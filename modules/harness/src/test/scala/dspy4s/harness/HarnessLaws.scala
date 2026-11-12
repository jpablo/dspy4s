package dspy4s.harness

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.{Bindings, HeapGuard, ScalaRepl}
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite

import java.nio.file.{Files, LinkOption, Path}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

import Models.*

/** The laws of a harness for one task (CODE_HARNESS_DESIGN.md, 9), with a scripted model, a real REPL and the sandbox
  * of the host. The journal of each call goes to the test through `HarnessEnv.journals`.
  */
class HarnessLaws extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Runs a harness for the task of the tests in a new place, and gives the result and the journal of the call. */
  private def call(
      model         : LanguageModel,
      question      : String         = "What is 6 times 7?",
      steps         : Int            = 3,
      cellTime      : FiniteDuration = 30.seconds,
      maxObservation: Int            = 4_000,
      cellLimit     : Int            = 300,
      secrets       : Secrets        = Secrets.none
  )(check: (Place, Either[dspy4s.core.contracts.DspyError, Answer], Vector[Logged]) => Unit): Unit =
    val place   = Place()
    var journal = Vector.empty[Logged]
    try
      val env = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        secrets,
        journals = (_, log) => journal = log
      )
      val harness = Harness.forTask(
        signature,
        env,
        HarnessLimits(steps = steps, cellTimeout = cellTime, maxObservation = maxObservation, cellLimit = cellLimit)
      )
      val result = withModel(model)(harness(Question(question))).map(_.output)
      check(place, result, journal)
    finally place.close()

  private def ended(journal: Vector[Logged]): Vector[TurnOutcome] =
    journal.collect { case Logged(_, _, Entry.TurnEnded(outcome)) => outcome }

  test("Harness: a policy that submits gives the output through Data, and the journal is one turn") {
    val model = ScriptLm(policy("compute", Some("submit((6 * 7).toString)")))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")))
      assertEquals(ended(journal).size, 1)
      assert(Support.isTurn(journal.map(_.entry).dropWhile(_ != Entry.TurnStarted).drop(1)), journal.mkString("\n"))
    }
  }

  test("Harness: the output is in RawPrediction.values, where a metric reads it") {
    val model = ScriptLm(policy("compute", Some("submit(\"42\")")))
    val place = Place()
    try
      val env    = HarnessEnv(Some(place.root), place.home, HostList.empty, Backends.detect(), Secrets.none)
      val result = withModel(model)(Harness.forTask(signature, env)(Question("q")))
      assertEquals(
        result.map(p => DynamicValues.recordGet(p.raw.values, "answer")),
        Right(Some(DynamicValues.string("42")))
      )
    finally place.close()
  }

  test("Harness: the transcript of the finisher gives the outcome of each cell in words, with no Scala value") {
    val error = "-- [E172] Type Error: FAKE"
    val log   = Support.logOf(
      1000L,
      Entry.Acted("", "FAKE-LOOK", Some("println(1)")),
      Entry.Observed(error, CellOutcome.CompileError(Vector(error)), Map.empty)
    )
    val text = Harness.transcript(log, 10_000)
    assert(text.contains(CellOutcome.CompileError(Vector.empty).words), text)
    assert(text.contains(error), text)
    assert(!text.contains("CompileError"), text)
  }

  test("Harness: a reply with no code runs the finisher") {
    val model = ScriptLm(policy("It is 42.", None), answer("42"))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")))
      assertEquals(ended(journal), Vector(TurnOutcome.Replied("It is 42.")))
      assertEquals(model.requests.size, 2)
    }
  }

  test("Harness: a code field with the text null or None is no code, so the reply runs the finisher") {
    Vector("null", "None").foreach { empty =>
      RuntimeEnvironment.resetForTests()
      val model = ScriptLm(policy("It is 42.", Some(empty)), answer("42"))
      call(model) { (_, result, journal) =>
        assertEquals(result, Right(Answer("42")), empty)
        assertEquals(ended(journal), Vector(TurnOutcome.Replied("It is 42.")), empty)
      }
    }
  }

  test("Harness: after the heap guard closes the REPL, the turn stops and calls the policy no more") {
    val before = HeapGuard.pressure
    val spin   = "locally { var n = 0L; while true do n += 1 }"
    val model  = ScriptLm(policy("spin", Some(spin)), policy("again", Some("submit(\"x\")")), answer("x"))
    try
      // The pressure starts after the first policy call, so the guard stops the cell and not the preamble.
      HeapGuard.pressure = () => model.requests.nonEmpty
      call(model) { (_, result, journal) =>
        assert(result.isLeft, result.toString)
        assert(result.left.exists(_.message.contains("heap guard")), result.toString)
        assertEquals(model.requests.size, 1)
        assertEquals(ended(journal), Vector(TurnOutcome.Aborted), journal.mkString("\n"))
      }
    finally HeapGuard.pressure = before
  }

  test("Harness: when the REPL cannot start, the call gives a DspyError, and the journal ends the turn as Crashed") {
    val before = HeapGuard.pressure
    try
      // Under pressure from the start, the guard stops the preamble, so the REPL does not start.
      HeapGuard.pressure = () => true
      call(ScriptLm(policy("done", Some("submit(\"x\")")))) { (_, result, journal) =>
        assert(result.isLeft, result.toString)
        assertEquals(ended(journal), Vector(TurnOutcome.Crashed), journal.mkString("\n"))
        assert(Support.isTurn(journal.map(_.entry).dropWhile(_ != Entry.TurnStarted).drop(1)), journal.mkString("\n"))
      }
    finally HeapGuard.pressure = before
  }

  private def count(folder: Path): Long =
    val paths = Files.list(folder)
    try paths.count()
    finally paths.close()

  test("Harness: a workspace that cannot be copied gives a DspyError, and no part of the copy stays") {
    val place  = Place()
    val closed = place.root.resolve("closed.txt")
    try
      Files.writeString(closed, "FAKE-CLOSED"): Unit
      Files.setPosixFilePermissions(closed, java.util.Set.of()): Unit
      assume(!Files.isReadable(closed), "the test runs as a user that reads each file")
      intercept[java.io.IOException](Harness.forkWorkspace(Some(place.root), place.temp)): Unit
      assertEquals(count(place.temp), 0L)
      val env    = HarnessEnv(Some(place.root), place.home, HostList.empty, Backends.detect(), Secrets.none)
      val result = withModel(ScriptLm())(Harness.forTask(signature, env)(Question("q")))
      assert(result.isLeft, result.toString)
    finally place.close()
  }

  test("Harness: the copy of the workspace leaves out a named pipe, so the copy does not wait for a writer") {
    val place = Place()
    try
      val pipe = place.root.resolve("pipe")
      assume(Try(ProcessBuilder("mkfifo", pipe.toString).start().waitFor()).toOption.contains(0), "no mkfifo here")
      val copy = Future(Harness.forkWorkspace(Some(place.root), place.temp))(using ExecutionContext.global)
      val fork = Await.result(copy, 10.seconds)
      assertEquals(Files.readString(fork.resolve("a.txt")), "FAKE-A")
      assert(!Files.exists(fork.resolve("pipe"), LinkOption.NOFOLLOW_LINKS))
    finally place.close()
  }

  test("Harness: the task of the policy shows the preamble, so the model sees the type of submit") {
    val model = ScriptLm(policy("compute", Some("submit(\"42\")")))
    call(model) { (_, result, _) =>
      assertEquals(result, Right(Answer("42")))
      val prompt = model.requests.headOption.toVector.flatMap(_.messages).flatMap(_.text).mkString("\n")
      assert(prompt.contains("def submit(answer: String)"), prompt.take(3_000))
      assert(prompt.contains("val question: String"), prompt.take(3_000))
    }
  }

  private def reasoning(journal: Vector[Logged]): Vector[String] =
    journal.collect { case Logged(_, _, Entry.Acted(thought, _, _)) => thought }

  test("Harness: a model with native reasoning gets no reasoning field, and its reasoning goes into the Acted") {
    val model = ThinkingLm("a native fake thought", thinking("compute", Some("submit(\"42\")")))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")))
      assertEquals(reasoning(journal), Vector("a native fake thought"))
      val prompt = model.requests.headOption.toVector.flatMap(_.messages).flatMap(_.text).mkString("\n")
      assert(!prompt.contains("[[ ## reasoning ## ]]"), prompt.take(3_000))
    }
  }

  test("Harness: a model with no native reasoning writes the reasoning field, and it goes into the Acted") {
    val model = ScriptLm(policy("compute", Some("submit(\"42\")"), reasoning = "a written fake thought"))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")))
      assertEquals(reasoning(journal), Vector("a written fake thought"))
    }
  }

  /** The text of the user messages of the request `k` of `model`, where the observation of a step is. */
  private def prompt(model: ScriptLm, k: Int): String =
    model.requests.lift(k).toVector.flatMap(_.messages).flatMap(_.text).mkString("\n")

  test("Harness: the policy sees the hint of a compile error") {
    val model = ScriptLm(policy("keep", Some("val xs = Array(1, 2, 3)")), policy("done", Some("submit(\"x\")")))
    call(model) { (_, result, _) =>
      assertEquals(result, Right(Answer("x")))
      assert(prompt(model, 1).contains("Hint:"), prompt(model, 1).take(3_000))
    }
  }

  test("Harness: the policy sees that a cell stopped at its time limit") {
    val spin  = "locally { var n = 0L; while true do n += 1 }"
    val model = ScriptLm(policy("spin", Some(spin)), policy("done", Some("submit(\"x\")")))
    call(model, cellTime = 1.second) { (_, result, _) =>
      assertEquals(result, Right(Answer("x")))
      assert(prompt(model, 1).contains("stopped after"), prompt(model, 1).take(3_000))
    }
  }

  test(
    "Harness: after a cell that confuses the compiler, a new REPL runs the next cells, and the policy learns the lost names"
  ) {
    val model = ScriptLm(
      policy("keep", Some("store(\"k\", 5)\nval kept = 41")),
      policy("array", Some("val xs = Array(1, 2, 3)")),
      policy("sum", Some("submit((load[Int](\"k\").getOrElse(0) + Array(4, 5).map(_ + 1).sum).toString)"))
    )
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("16")), journal.mkString("\n"))
      val after = prompt(model, 2)
      assert(after.contains("started a new REPL"), after.take(3_000))
      assert(after.contains("gone: kept"), after.take(3_000))
    }
  }

  test(
    "Harness: a cell that breaks later Array chains with no error of its own leads to a new REPL at the next chain"
  ) {
    val model = ScriptLm(
      policy("define", Some("case class I(x: Int)\ndef f(i: I): Int = i.x")),
      policy("chain", Some("println(Array(4, 5).map(_ + 1).sum)")),
      policy("again", Some("submit(Array(4, 5).map(_ + 1).sum.toString)"))
    )
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("11")), journal.mkString("\n"))
      assert(prompt(model, 2).contains("started a new REPL"), prompt(model, 2).take(3_000))
    }
  }

  test("Harness: the hint of a compile error stays in the observation, also after the cut") {
    val model = ScriptLm(policy("keep", Some("val xs = Array(1, 2, 3)")), policy("done", Some("submit(\"x\")")))
    call(model, maxObservation = 200) { (_, result, _) =>
      assertEquals(result, Right(Answer("x")))
      assert(prompt(model, 1).contains("Hint:"), prompt(model, 1).take(3_000))
    }
  }

  test("Harness: a full REPL leads to a new REPL, and the policy learns the lost names") {
    val model = ScriptLm(
      policy("a", Some("val a = 1")),
      policy("b", Some("val b = 2")),
      policy("done", Some("submit(\"x\")"))
    )
    call(model, cellLimit = 2) { (_, result, journal) =>
      assertEquals(result, Right(Answer("x")), journal.mkString("\n"))
      assert(prompt(model, 2).contains("started a new REPL"), prompt(model, 2).take(3_000))
      assert(prompt(model, 2).contains("gone: a, b"), prompt(model, 2).take(3_000))
    }
  }

  private def observed(journal: Vector[Logged]): Vector[String] =
    journal.collect { case Logged(_, _, Entry.Observed(output, _, _)) => output }

  // No cell comes after the cell of the last step (4.3), so a new REPL is for nothing, and its note goes to the finisher.
  test("Harness: the cell of the last step starts no new REPL, so the finisher sees no note of a new REPL") {
    val model = ScriptLm(policy("keep", Some("val xs = Array(1, 2, 3)")), answer("x"))
    call(model, steps = 1) { (_, result, journal) =>
      assertEquals(result, Right(Answer("x")))
      assertEquals(ended(journal), Vector(TurnOutcome.Exhausted))
      assert(observed(journal).nonEmpty, journal.mkString("\n"))
      assert(!observed(journal).exists(_.contains("started a new REPL")), observed(journal).mkString("\n"))
      assert(!prompt(model, 1).contains("started a new REPL"), prompt(model, 1).take(3_000))
    }
  }

  // A cell that ended by `submit` ends the turn (5.1), so no cell comes after it.
  test("Harness: a cell that ended by submit starts no new REPL, also when the REPL is full") {
    val model = ScriptLm(policy("done", Some("submit(\"x\")")))
    call(model, cellLimit = 1) { (_, result, journal) =>
      assertEquals(result, Right(Answer("x")))
      assert(observed(journal).nonEmpty, journal.mkString("\n"))
      assert(!observed(journal).exists(_.contains("started a new REPL")), observed(journal).mkString("\n"))
    }
  }

  test("Harness: a compile error of another kind starts no new REPL, and the names stay") {
    val model = ScriptLm(
      policy("keep", Some("val kept = 41")),
      policy("typo", Some("println(noSuchName)")),
      policy("use", Some("submit(kept.toString)"))
    )
    call(model) { (_, result, _) =>
      assertEquals(result, Right(Answer("41")))
      assert(!prompt(model, 2).contains("started a new REPL"), prompt(model, 2).take(3_000))
    }
  }

  test("Harness: the step limit runs the finisher") {
    val model = ScriptLm(policy("start", Some("val x = 1")), answer("1"))
    call(model, steps = 1) { (_, result, journal) =>
      assertEquals(result, Right(Answer("1")))
      assertEquals(ended(journal), Vector(TurnOutcome.Exhausted))
    }
  }

  test("Harness: the call works in a copy of the workspace, and the workspace stays the same") {
    val code  = """write("a.txt", "after"); write("b.txt", "new"); submit(read("a.txt"))"""
    val model = ScriptLm(policy("change", Some(code)))
    call(model) { (place, result, _) =>
      assertEquals(result, Right(Answer("after")))
      assertEquals(Files.readString(place.root.resolve("a.txt")), "FAKE-A")
      assert(!Files.exists(place.root.resolve("b.txt")))
    }
  }

  test("Harness: exec runs in the sandbox, in the copy, and the copy is gone after the call") {
    assume(Backends.detect().isDefined, "no OS sandbox works on this host")
    val model = ScriptLm(policy("where", Some("""submit(exec("pwd").trim)""")))
    call(model) { (place, result, _) =>
      result match
        case Right(Answer(folder)) =>
          assert(!folder.startsWith(place.root.toString), folder)
          assert(!Files.exists(Path.of(folder)), folder)
        case other => fail(s"the call gave $other")
    }
  }

  test("Harness: the store keeps only the writes of a cell that completed") {
    val failing = """store("k", 1); throw IllegalStateException("fake failure")"""
    val reading = """submit(load[Int]("k").fold("none")(_.toString))"""
    val model   = ScriptLm(policy("store", Some(failing)), policy("read", Some(reading)))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("none")), journal.mkString("\n"))
    }
  }

  test("Harness: a failed model call gives a DspyError") {
    call(ScriptLm()) { (_, result, journal) =>
      assert(result.isLeft, result.toString)
      assert(ended(journal).exists {
        case TurnOutcome.Failed(_) => true
        case _                     => false
      })
    }
  }

  // The parallel executor interrupts the thread of a call at its timeout or at `maxErrors` (9).
  test("Harness: an interrupt of a program call gives its journal to env.journals, with a closed turn") {
    val gate  = Gate()
    val model = RequestLm(_ => Right(policy("compute", Some("submit(\"42\")"))), _ => gate.pass())
    Background.run(model, HarnessLimits(steps = 3), gate) { call =>
      assert(gate.awaitReached(), "the policy call did not start")
      call.thread.interrupt()
      assert(call.awaitEnd(), "the call did not end")
      call.all match
        case Vector(journal) =>
          assertEquals(ended(journal), Vector(TurnOutcome.Crashed), journal.mkString("\n"))
          assert(Support.isTurn(journal.map(_.entry).dropWhile(_ != Entry.TurnStarted).drop(1)), journal.mkString("\n"))
        case other => fail(s"env.journals got ${other.size} journals")
      assert(call.result.exists(_.isLeft), s"result ${call.result}, exception ${call.thrown}")
      assert(call.flagged, "the call did not set the interrupt flag of its thread again")
    }
  }

  test("Harness: after a fatal error of a call, env.journals still gets the journal") {
    val place   = Place()
    var journal = Vector.empty[Logged]
    try
      val env = HarnessEnv(
        Some(place.root),
        place.home,
        HostList.empty,
        Backends.detect(),
        Secrets.none,
        journals = (_, log) => journal = log
      )
      val model = RequestLm(_ => throw StackOverflowError("a fake fatal error"))
      // `intercept` does not catch a fatal error, so the test catches it.
      val fatal =
        try
          withModel(model)(Harness.forTask(signature, env)(Question("q"))): Unit
          false
        catch case _: StackOverflowError => true
      assert(fatal, "the fatal error did not go on to the caller")
      assert(journal.nonEmpty, "env.journals got no journal")
    finally place.close()
  }

  test("Harness: the journal of a call holds no known secret") {
    val secret = "fake-secret-9876"
    val model  = ScriptLm(policy("done", Some(s"""submit("$secret")""")))
    call(model, question = s"Repeat $secret", secrets = Secrets(Map("FAKE_KEY" -> secret))) { (_, _, journal) =>
      assert(journal.nonEmpty)
      assert(!journal.toString.contains(secret), journal.mkString("\n"))
    }
  }

  test("Harness: the REPL has only the givens of the task mode") {
    // Since phase 5 the task mode gives `Llm^` (8.1), so `llmQuery` compiles. The task mode still gives no `Network^`.
    val fetch = """httpGet("http://fake.test/page")"""
    val model = ScriptLm(policy("fetch", Some(fetch)), policy("give up", Some("""submit("x")""")))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("x")))
      assert(
        journal.exists {
          case Logged(_, _, Entry.Observed(_, CellOutcome.CompileError(_), _)) => true
          case _                                                               => false
        },
        journal.mkString("\n")
      )
    }
  }

  // ── The text of the code field (the live sessions of 2026-11-10) ──

  /** The code of each `Acted` of a journal. */
  private def actedCode(journal: Vector[Logged]): Vector[Option[String]] =
    journal.collect { case Logged(_, _, Entry.Acted(_, _, code)) => code }

  // A live model put each cell in a fence. The fence did not parse, so the session lost five steps to one habit.
  Vector(
    "a fence with the language" -> "```scala\nsubmit(\"42\")\n```",
    "a fence with no language"  -> "```\nsubmit(\"42\")\n```",
    "spaces around the fence"   -> "  ```scala\nsubmit(\"42\")\n```  \n"
  ).foreach { (name, code) =>
    test(s"Harness: a step whose code is in one ``` fence runs the code in the fence, and Acted has no fence: $name") {
      call(ScriptLm(policy("compute", Some(code)))) { (_, result, journal) =>
        assertEquals(result, Right(Answer("42")), journal.mkString("\n"))
        assertEquals(actedCode(journal), Vector(Some("submit(\"42\")")))
      }
    }
  }

  test("Harness: a fence inside the code is a part of the code, so it stays") {
    val code = "val mark = \"```\"\nsubmit(mark)"
    call(ScriptLm(policy("compute", Some(code)))) { (_, result, journal) =>
      assertEquals(result, Right(Answer("```")), journal.mkString("\n"))
      assertEquals(actedCode(journal), Vector(Some(code)))
    }
  }

  test("Harness: the history of the next step shows the code of a fenced step with no fence") {
    // The window put the fenced code of a failed step in the history, and the model copied it at the next step.
    val model =
      ScriptLm(policy("look", Some("```scala\nprintln(\"FAKE-SEEN\")\n```")), policy("done", Some("submit(\"x\")")))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("x")), journal.mkString("\n"))
      val second = model.requests.lift(1).getOrElse(fail("no second policy call"))
      val text   = second.messages.flatMap(_.text).mkString("\n")
      assert(text.contains("println(\"FAKE-SEEN\")"), text)
      // The task of a call shows the preamble in a fence, so the law looks only for the fence of the step.
      assert(!text.contains("```scala\nprintln"), text)
    }
  }

  // ── A step that does not follow the field format (the live session of 2026-11-11) ──

  /** The text and the code of each `Acted` of a journal. */
  private def acted(journal: Vector[Logged]): Vector[(String, Option[String])] =
    journal.collect { case Logged(_, _, Entry.Acted(_, text, code)) => (text, code) }

  // At the first step, a live model with native reasoning wrote a short line and then the code. 25 of 30 replies had no
  // text field. Each such reply ended the turn Failed("JSON parse failed and no fallback was applied").
  Vector(
    "a code field with no text field" ->
      "I'll take a look around the workspace first.\n\n[[ ## code ## ]]\nsubmit(\"42\")\n\n[[ ## completed ## ]]",
    "a fenced code field with no text field and no end marker" ->
      "I'll take a look around the workspace first.\n\n[[ ## code ## ]]\n```scala\nsubmit(\"42\")\n```",
    "one fence and no field markers" -> "I'll take a look around the workspace first.\n\n```scala\nsubmit(\"42\")\n```"
  ).foreach { (name, reply) =>
    test(s"Harness: a step with $name takes the text before the code as its text, with one model call") {
      val model = ThinkingLm("a native fake thought", reply)
      call(model) { (_, result, journal) =>
        assertEquals(result, Right(Answer("42")), journal.mkString("\n"))
        assertEquals(acted(journal), Vector(("I'll take a look around the workspace first.", Some("submit(\"42\")"))))
        assertEquals(model.requests.size, 1)
      }
    }
  }

  test("Harness: a step with no field markers and no fence is a text with no code, so the finisher runs") {
    val model = ThinkingLm("a native fake thought", "It is 42.", answer("42"))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")), journal.mkString("\n"))
      assertEquals(acted(journal), Vector(("It is 42.", None)))
      assertEquals(ended(journal), Vector(TurnOutcome.Replied("It is 42.")))
    }
  }

  // Only a ```scala fence is a cell. A fence of another language, or with no language, is a part of the text, so a reply
  // with an example of a shell command ends the turn and does not run the example as a cell.
  Vector(
    "bash"        -> "```bash\nsbt test\n```",
    "json"        -> "```json\n{\"answer\": 42}\n```",
    "no language" -> "```\nsubmit(\"42\")\n```"
  ).foreach { (name, fence) =>
    test(s"Harness: a step with no field markers and one fence of $name is a text with no code") {
      val reply = s"Run it like this:\n\n$fence"
      val model = ThinkingLm("a native fake thought", reply, answer("42"))
      call(model) { (_, result, journal) =>
        assertEquals(result, Right(Answer("42")), journal.mkString("\n"))
        assertEquals(acted(journal), Vector((reply, None)))
      }
    }
  }

  test("Harness: a step with no field markers runs its one scala fence, and a fence of another language stays text") {
    val bash  = "```bash\nls\n```"
    val reply = s"First the cell.\n\n```scala\nsubmit(\"42\")\n```\n\nThen in a shell:\n\n$bash"
    call(ThinkingLm("a native fake thought", reply)) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")), journal.mkString("\n"))
      assertEquals(acted(journal), Vector((s"First the cell.\n\nThen in a shell:\n\n$bash", Some("submit(\"42\")"))))
    }
  }

  test("Harness: a model with no native reasoning and a step with one fence and no field markers has no reasoning") {
    val model = ScriptLm("Let me compute it.\n\n```scala\nsubmit(\"42\")\n```")
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")), journal.mkString("\n"))
      assertEquals(reasoning(journal), Vector(""))
      assertEquals(acted(journal), Vector(("Let me compute it.", Some("submit(\"42\")"))))
    }
  }

  test("Harness: a step that fits no rule fails the turn with the start of the reply, so a person sees what it was") {
    // Two fences: the harness cannot know which one is the cell.
    val reply = "Here are two cells.\n\n```scala\nsubmit(\"1\")\n```\n\n```scala\nsubmit(\"2\")\n```"
    call(ThinkingLm("a native fake thought", reply, reply)) { (_, result, journal) =>
      assert(result.isLeft, result.toString)
      val failed = journal.collect { case Logged(_, _, Entry.TurnEnded(TurnOutcome.Failed(error))) => error }
      assertEquals(failed.size, 1, journal.mkString("\n"))
      assert(failed.head.contains("Here are two cells."), failed.head)
    }
  }

  test("Harness: the start of a reply in the error of a failed turn holds no known secret") {
    val secret = "fake-secret-9876"
    val reply  = s"Here is $secret.\n\n```scala\nval a = 1\n```\n\n```scala\nval b = 2\n```"
    val model  = ThinkingLm("a native fake thought", reply, reply)
    call(model, secrets = Secrets(Map("FAKE_KEY" -> secret))) { (_, result, journal) =>
      val failed = journal.collect { case Logged(_, _, Entry.TurnEnded(TurnOutcome.Failed(error))) => error }
      assert(failed.exists(_.contains(s"Here is ${Secrets.mark("FAKE_KEY")}.")), journal.mkString("\n"))
      assert(!journal.toString.contains(secret), journal.mkString("\n"))
      assert(!result.toString.contains(secret), result.toString)
    }
  }

  test("Harness: the start of a reply in the error of a failed turn cuts no surrogate pair in two") {
    // The JSON writer of a session file refuses a surrogate with no pair, and a failed write ends the session.
    val reply = ("a" * 199) + "😀 and more.\n\n```scala\nval a = 1\n```\n\n```scala\nval b = 2\n```"
    call(ThinkingLm("a native fake thought", reply, reply)) { (_, _, journal) =>
      val failed = journal.collect { case Logged(_, _, Entry.TurnEnded(TurnOutcome.Failed(error))) => error }
      assertEquals(failed.size, 1, journal.mkString("\n"))
      val lone = failed.head.indices.filter { i =>
        val c = failed.head.charAt(i)
        (c.isHighSurrogate && !failed.head.lift(i + 1).exists(_.isLowSurrogate)) ||
        (c.isLowSurrogate && !failed.head.lift(i - 1).exists(_.isHighSurrogate))
      }
      assertEquals(lone, Vector.empty, failed.head)
    }
  }

  test("Harness: callPolicy gives its progress the size of its projection and the budget") {
    // The footer of a client shows the size of the last view of the memory and its budget (10.6).
    val journal = Support.journal()
    Support.open(journal)
    journal.commit(Entry.StepStarted(0)): Unit
    val seen     = java.util.concurrent.ConcurrentLinkedQueue[(Int, Int)]()
    val progress = new MemoryProgress:
      override def projected(chars: Int, budget: Int): Unit = seen.add((chars, budget)): Unit
    val limits = HarnessLimits(budget = 12_345)
    val zooms  = ZoomSource()
    val none   = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)
    val leaf   = Signature.derived[PolicyInput, PolicyStep]("policy", Harness.policyInstructions)
    withModel(ScriptLm(policy("FAKE-DONE", None))) {
      val effects = HarnessEffects(
        "FAKE-TASK",
        Harness.apiReference(none),
        Predict(leaf, name = Some("policy")),
        Window,
        journal,
        () => ScalaRepl(none, Bindings(JournalTurn(journal, () => 0L, zooms))),
        Recorder(journal),
        SnapshotStore(),
        limits,
        () => Budget(5),
        None,
        PolicyRunner.Direct,
        zooms,
        progress
      )
      try
        assert(effects.callPolicy(mustAnswer = false).isRight)
        val size = Window.project(journal.log, (), limits.budget).size
        assert(size > 0)
        assertEquals(seen.asScala.toVector, Vector((size, 12_345)))
      finally effects.close()
    }
  }
