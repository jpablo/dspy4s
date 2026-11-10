package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.guest.Access
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.LanguageModel
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import Models.*

/** The laws of RLM in the harness (CODE_HARNESS_DESIGN.md, 5.1, 6, 8.1, 8.2 and 9): `llmQuery`, `llmQueryBatched`,
  * `rlm`, `rlmBatched`, the shared budget and the depth limit. Each call has a real REPL for each session and the
  * sandbox of the host. The journal of each session goes to the test through `HarnessEnv.journals`: the top session has
  * the owner `Optimizer`, and a child has the owner `Cell`.
  */
class RlmLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Runs a harness for the task of the tests in a new place, and gives the result and the journal of each session. */
  private def call(model: LanguageModel, limits: HarnessLimits = HarnessLimits(steps = 3))(
      check: (Place, Either[DspyError, Answer], Vector[Vector[Logged]]) => Unit
  ): Unit =
    val place    = Place()
    val journals = ConcurrentLinkedQueue[Vector[Logged]]()
    try
      val env = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.none,
        journals = log => journals.add(log): Unit
      )
      val result = withModel(model)(Harness.forTask(signature, env, limits)(Question("What is 6 times 7?")))
      check(place, result.map(_.output), journals.asScala.toVector)
    finally place.close()

  private def owner(log: Vector[Logged]): Option[Owner] =
    log.collectFirst { case Logged(_, _, Entry.Activated(_, config)) => config.owner }

  /** The journal of the top session. */
  private def top(journals: Vector[Vector[Logged]]): Vector[Logged] =
    journals.filter(owner(_).contains(Owner.Optimizer)) match
      case Vector(log) => log
      case other       => fail(s"the call has ${other.size} top journals")

  /** The journals of the children, in the order of their ends. */
  private def kids(journals: Vector[Vector[Logged]]): Vector[Vector[Logged]] =
    journals.filter(owner(_).contains(Owner.Cell))

  private def ops(log: Vector[Logged]): Vector[String] =
    log.collect { case Logged(_, _, Entry.EffectStarted(_, op, _)) => op }

  private def ended(log: Vector[Logged]): Vector[TurnOutcome] =
    log.collect { case Logged(_, _, Entry.TurnEnded(outcome)) => outcome }

  private def cells(log: Vector[Logged]): Vector[(String, CellOutcome)] =
    log.collect { case Logged(_, _, Entry.Observed(output, outcome, _)) => (output, outcome) }

  private def failedWith(log: Vector[Logged], text: String): Boolean =
    cells(log).exists {
      case (output, CellOutcome.Failed(_)) => output.contains(text)
      case _                               => false
    }

  private def inputs(log: Vector[Logged]): Vector[Map[String, Json]] =
    log.collect { case Logged(_, _, Entry.Input(_, _, values)) => values }

  private def text(model: ScriptLm, k: Int): String =
    model.requests.lift(k).toVector.flatMap(_.messages).flatMap(_.text).mkString("\n")

  /** The number of the prompt `Q<n>` of a request of the subQuery leaf. */
  private val Prompted = raw"\[\[ ## prompt ## \]\]\s*Q(\d+)".r

  private def prompted(request: String): Option[Int] = Prompted.findFirstMatchIn(request).map(_.group(1).toInt)

  /** The number of the task `FAKE-TASK-<n>` of a request of the policy of a child. */
  private val ChildTask = raw"\[\[ ## task ## \]\]\s*FAKE-TASK-(\d+)".r

  private def childTask(request: String): Option[Int] = ChildTask.findFirstMatchIn(request).map(_.group(1).toInt)

  // ── llmQuery and llmQueryBatched ──

  test("LLM: llmQuery gives the answer of the subQuery leaf, and the journal of the parent has an llmQuery effect") {
    val model = ScriptLm(policy("ask", Some("""submit(llmQuery("FAKE-QUESTION"))""")), answer("FAKE-ANSWER"))
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("FAKE-ANSWER")))
      val log = top(journals)
      assertEquals(ops(log), Vector("llmQuery"))
      val args = log.collectFirst { case Logged(_, _, Entry.EffectStarted(_, "llmQuery", a)) => a.print }
      assert(args.exists(_.contains("FAKE-QUESTION")), args.toString)
      val value = log.collectFirst { case Logged(_, _, Entry.EffectDone(_, EffectResult.Value(_, shown))) => shown }
      assertEquals(value, Some("FAKE-ANSWER"))
      assert(text(model, 1).contains(Harness.subQueryInstructions), text(model, 1).take(2_000))
      assert(text(model, 1).contains("FAKE-QUESTION"), text(model, 1).take(2_000))
    }
  }

  test("LLM: the trace of a query on the thread of the cell reaches the trace of the program call") {
    val model = ScriptLm(policy("ask", Some("""submit(llmQuery("FAKE-QUESTION"))""")), answer("FAKE-ANSWER"))
    val place = Place()
    try
      val env    = HarnessEnv(Some(place.root), place.home, HostList.empty, Backends.detect(), Secrets.none)
      val traced = withModel(model) {
        val result = Harness.forTask(signature, env)(Question("q"))
        (result.map(_.output), RuntimeEnvironment.current.trace)
      }
      assertEquals(traced._1, Right(Answer("FAKE-ANSWER")))
      assert(traced._2.exists(e => DynamicValues.recordGet(e.inputs, "prompt").isDefined), traced._2.mkString("\n"))
    finally place.close()
  }

  test(
    "LLM: llmQueryBatched keeps the order of the prompts, with at most HarnessLimits.batch queries at the same time"
  ) {
    val code = """submit(llmQueryBatched(Vector("Q1", "Q2", "Q3", "Q4", "Q5", "Q6")).mkString(","))"""
    // The first prompt waits the longest, so the queries end in another order than the prompts.
    val model = RequestLm(
      request => Right(prompted(request).fold(policy("ask", Some(code)))(n => answer(s"A$n"))),
      request => prompted(request).fold(0L)(n => (7 - n) * 60L)
    )
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("A1,A2,A3,A4,A5,A6")))
      assertEquals(ops(top(journals)), Vector("llmQueryBatched"))
      assert(model.most.get <= HarnessLimits().batch, s"${model.most.get} calls ran at the same time")
    }
  }

  test("LLM: llmQueryBatched fails when one query fails, with the index of that prompt in the error") {
    val code  = """submit(llmQueryBatched(Vector("Q1", "Q2", "Q3")).mkString(","))"""
    val model = RequestLm { request =>
      prompted(request) match
        case Some(3)                                  => Left("a fake failure")
        case Some(n)                                  => Right(answer(s"A$n"))
        case None if request.contains("Step 2 of 3.") => Right(policy("give up", Some("""submit("none")""")))
        case None                                     => Right(policy("ask", Some(code)))
    }
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("none")))
      assert(failedWith(top(journals), "the prompt 2 failed"), cells(top(journals)).mkString("\n"))
    }
  }

  // ── The shared budget ──

  test("Budget: the policy calls, the queries and the children share one budget") {
    // The step of the parent, the step of the child and the query of the child are three model calls.
    def script =
      ScriptLm(
        policy("delegate", Some("""submit(rlm[String]("FAKE-CHILD-TASK"))""")),
        policy("ask", Some("""submit(llmQuery("FAKE-QUESTION"))""")),
        answer("FAKE-ANSWER")
      )
    val enough = script
    call(enough, HarnessLimits(steps = 3, modelCalls = 3)) { (_, result, _) =>
      assertEquals(result, Right(Answer("FAKE-ANSWER")))
      assertEquals(enough.requests.size, 3)
    }
    RuntimeEnvironment.resetForTests()
    val short = script
    call(short, HarnessLimits(steps = 3, modelCalls = 2)) { (_, result, journals) =>
      assert(result.left.exists(_.message.contains("budget")), result.toString)
      assertEquals(short.requests.size, 2)
      val child = kids(journals).headOption.getOrElse(fail("no child journal"))
      assert(failedWith(child, "spent"), cells(child).mkString("\n"))
      assert(ended(child).exists {
        case TurnOutcome.Failed(error) => error.contains("budget")
        case _                         => false
      })
      assert(failedWith(top(journals), "budget"), cells(top(journals)).mkString("\n"))
    }
  }

  test(
    "Budget: a query with a spent budget fails its cell, and a policy call with a spent budget ends the turn Failed"
  ) {
    val model = ScriptLm(policy("ask", Some("""submit(llmQuery("FAKE-QUESTION"))""")), answer("FAKE-ANSWER"))
    call(model, HarnessLimits(steps = 3, modelCalls = 1)) { (_, result, journals) =>
      assert(result.isLeft, result.toString)
      assertEquals(model.requests.size, 1)
      val log = top(journals)
      assert(failedWith(log, "spent"), cells(log).mkString("\n"))
      assert(ended(log).exists {
        case TurnOutcome.Failed(error) => error.contains("spent")
        case _                         => false
      })
    }
  }

  // ── rlm ──

  test(
    "RLM: rlm gives the typed value of the submit of a child, also for a record, and the child has its own journal"
  ) {
    val parent = """case class Point(x: Int, note: String)
                   |val p = rlm[Point]("FAKE-POINT-TASK")
                   |submit(s"${p.x}-${p.note}")""".stripMargin
    val model = ScriptLm(policy("delegate", Some(parent)), policy("give", Some("""submit(3, "n")""")))
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("3-n")), journals.mkString("\n"))
      assertEquals(journals.size, 2)
      val child = kids(journals).headOption.getOrElse(fail("no child journal"))
      assertEquals(ops(top(journals)), Vector("rlm"))
      assertEquals(ops(child), Vector.empty)
      assert(ended(child).exists {
        case TurnOutcome.Submitted(_) => true
        case _                        => false
      })
      assert(text(model, 1).contains("FAKE-POINT-TASK"), text(model, 1).take(3_000))
      assert(text(model, 1).contains("def submit(x: Int, note: String)"), text(model, 1).take(3_000))
    }
  }

  test("RLM: the inputs of rlm are String vals of the child, and the Input entry of the child holds them") {
    val parent = """submit(rlm[String]("FAKE-READ-TASK", Map("doc" -> "fake doc")))"""
    val model  = ScriptLm(policy("delegate", Some(parent)), policy("read", Some("submit(doc.toUpperCase)")))
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("FAKE DOC")), journals.mkString("\n"))
      val child = kids(journals).headOption.getOrElse(fail("no child journal"))
      assertEquals(inputs(child), Vector(Map("doc" -> Json.String("fake doc"))))
      assert(text(model, 1).contains("val doc: String"), text(model, 1).take(3_000))
    }
  }

  test("RLM: a child that replies with no code makes rlm fail in the cell of the parent, with the text of the child") {
    val model = ScriptLm(
      policy("delegate", Some("""submit(rlm[String]("FAKE-CHILD-TASK"))""")),
      policy("FAKE-CHILD-TEXT: I give no value.", None),
      policy("give up", Some("""submit("none")"""))
    )
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("none")), journals.mkString("\n"))
      val child = kids(journals).headOption.getOrElse(fail("no child journal"))
      assertEquals(ended(child), Vector(TurnOutcome.Replied("FAKE-CHILD-TEXT: I give no value.")))
      assert(failedWith(top(journals), "FAKE-CHILD-TEXT"), cells(top(journals)).mkString("\n"))
    }
  }

  test("RLM: a child below the depth limit can call rlm, and a child at the depth limit cannot compile rlm") {
    // The default limit is 2: the top session has the depth 0, its child 1, and the child of that child 2.
    val model = ScriptLm(
      policy("delegate", Some("""submit(rlm[String]("FAKE-CHILD-TASK"))""")),
      policy("delegate again", Some("""submit(rlm[String]("FAKE-GRANDCHILD-TASK"))""")),
      policy("deeper", Some("""rlm[String]("FAKE-TOO-DEEP")""")),
      policy("give", Some("""submit("leaf")"""))
    )
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("leaf")), journals.mkString("\n"))
      assertEquals(kids(journals).size, 2)
      // The child of the child ends first, so its journal comes first.
      val deepest = kids(journals).headOption.getOrElse(fail("no child journal"))
      assert(
        cells(deepest).headOption.exists {
          case (_, CellOutcome.CompileError(_)) => true
          case _                                => false
        },
        cells(deepest).mkString("\n")
      )
      assertEquals(kids(journals).map(ops), Vector(Vector.empty, Vector("rlm")))
    }
  }

  property("RLM: a mode has Children^ only below the depth limit, and the other capabilities stay") {
    val env = HarnessEnv(None, Path.of("/tmp"), HostList.empty, None, Secrets.none)
    forAllNoShrink(Gen.choose(0, 5), Gen.choose(0, 5)) { (depth, limit) =>
      val mode = Harness.forTask(signature, env, HarnessLimits(depth = limit)).modeAt(depth)
      assertEquals(mode.children, if depth < limit then Access.Full else Access.None, s"depth $depth, limit $limit")
      assertEquals(mode.copy(children = Access.Full), HarnessEnv.taskMode)
    }
  }

  test("RLM: a child works in the copy of the parent, so the parent reads a file that the child writes") {
    val parent = """val w = rlm[String]("FAKE-WRITE-TASK")
                   |submit(read("child.txt") + "/" + w)""".stripMargin
    val child = """write("child.txt", "FAKE-CHILD-FILE")
                   |submit("ok")""".stripMargin
    val model = ScriptLm(policy("delegate", Some(parent)), policy("write", Some(child)))
    call(model) { (place, result, journals) =>
      assertEquals(result, Right(Answer("FAKE-CHILD-FILE/ok")), journals.mkString("\n"))
      assert(!Files.exists(place.root.resolve("child.txt")))
      assertEquals(ops(top(journals)), Vector("rlm", "read"))
      assertEquals(kids(journals).map(ops), Vector(Vector("write")))
    }
  }

  // ── rlmBatched ──

  test(
    "RLM: rlmBatched gives the values in the order of the tasks, with at most HarnessLimits.batch children at once"
  ) {
    val tasks = (1 to 5).map(n => s"\"FAKE-TASK-$n\"").mkString(", ")
    val code  = s"""submit(rlmBatched[String](Vector($tasks)).mkString(","))"""
    // The first child waits the longest, so the children end in another order than the tasks.
    val model = RequestLm(
      request =>
        Right(childTask(request).fold(policy("delegate", Some(code)))(n => policy("give", Some(s"""submit("v$n")""")))),
      request => childTask(request).fold(0L)(n => (6 - n) * 80L)
    )
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("v1,v2,v3,v4,v5")), journals.mkString("\n"))
      assertEquals(ops(top(journals)), Vector("rlmBatched"))
      assertEquals(kids(journals).size, 5)
      assert(model.most.get <= HarnessLimits().batch, s"${model.most.get} calls ran at the same time")
    }
  }

  test("RLM: rlmBatched fails when one child fails, with that task in the error") {
    val code  = """println(rlmBatched[String](Vector("FAKE-TASK-1", "FAKE-TASK-2", "FAKE-TASK-3")).mkString(","))"""
    val model = RequestLm { request =>
      childTask(request) match
        case Some(2)                                  => Right(policy("FAKE-NO-VALUE", None))
        case Some(n)                                  => Right(policy("give", Some(s"""submit("v$n")""")))
        case None if request.contains("Step 2 of 3.") => Right(policy("give up", Some("""submit("none")""")))
        case None                                     => Right(policy("delegate", Some(code)))
    }
    call(model) { (_, result, journals) =>
      assertEquals(result, Right(Answer("none")), journals.mkString("\n"))
      assert(failedWith(top(journals), "\"FAKE-TASK-2\", failed"), cells(top(journals)).mkString("\n"))
    }
  }
