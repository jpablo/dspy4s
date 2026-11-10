package dspy4s.harness

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.kernel.*
import dspy4s.harness.sandbox.{Backends, HostList}
import munit.FunSuite

import java.nio.file.{Files, Path}

import Models.*

/** The laws of a harness for one task (CODE_HARNESS_DESIGN.md, 9), with a scripted model, a real REPL and the sandbox
  * of the host. The journal of each call goes to the test through `HarnessEnv.journals`.
  */
class HarnessLaws extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Runs a harness for the task of the tests in a new place, and gives the result and the journal of the call. */
  private def call(
      model   : ScriptLm,
      question: String  = "What is 6 times 7?",
      steps   : Int     = 3,
      secrets : Secrets = Secrets.none
  )(check: (Place, Either[dspy4s.core.contracts.DspyError, Answer], Vector[Logged]) => Unit): Unit =
    val place   = Place()
    var journal = Vector.empty[Logged]
    try
      val env =
        HarnessEnv(Some(place.root), place.home, HostList.empty, Backends.detect(), secrets, journals = journal = _)
      val harness = Harness.forTask(signature, env, HarnessLimits(steps = steps))
      val result  = withModel(model)(harness(Question(question))).map(_.output)
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

  test("Harness: a reply with no code runs the finisher") {
    val model = ScriptLm(policy("It is 42.", None), answer("42"))
    call(model) { (_, result, journal) =>
      assertEquals(result, Right(Answer("42")))
      assertEquals(ended(journal), Vector(TurnOutcome.Replied("It is 42.")))
      assertEquals(model.requests.size, 2)
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

  test("Harness: a failed model call gives a DspyError") {
    call(ScriptLm()) { (_, result, journal) =>
      assert(result.isLeft, result.toString)
      assert(ended(journal).exists {
        case TurnOutcome.Failed(_) => true
        case _                     => false
      })
    }
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
    val model = ScriptLm(policy("ask", Some("""llmQuery("hi")""")), policy("give up", Some("""submit("x")""")))
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
