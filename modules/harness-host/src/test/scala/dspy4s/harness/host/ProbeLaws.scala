package dspy4s.harness.host

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{Budget, Harness, HarnessLimits, Models, Place, PolicyInput, PolicyStep, RequestLm, Secrets}
import dspy4s.harness.{SubAnswer, SubQuery}
import dspy4s.harness.Models.policy
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature

import java.nio.file.{Files, Path}

/** The laws of a probe (CODE_HARNESS.md, 7.4; CODE_HARNESS_DESIGN.md, 8.2 and 8.7): `observe` as a program call of a
  * harness on the journal of a session.
  */
class ProbeLaws extends munit.FunSuite:

  // A probe starts a real REPL.
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(3, "min")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val limits = HarnessLimits(steps = 3)
  private val link   = ProbeLink(Controller.id, EffectId(3L, 1))

  private def meta(source: Source): Meta = Meta(0L, source, Taint.Trusted, required = true)

  /** A short journal of a session: a message of the person and a reply. */
  private def journal: Vector[Logged] =
    Vector(
      Logged(0L, meta(Source.User), Entry.Input(RequestId("m1"), "the fake question of the person", Map.empty)),
      Logged(1L, meta(Source.Harness), Entry.TurnStarted),
      Logged(2L, meta(Source.Harness), Entry.TurnEnded(TurnOutcome.Replied("the fake reply")))
    )

  /** A probe at `root` with the leaves of a work runtime. */
  private def probeAt(root: Path, place: Place): HarnessProbe =
    val policyOf = Signature.derived[PolicyInput, PolicyStep]("policy", WorkRuntime.instructions)
    val subOf    = Signature.derived[SubQuery, SubAnswer]("subQuery", Harness.subQueryInstructions)
    HarnessProbe(
      root,
      place.home,
      Secrets.none,
      limits,
      Predict(policyOf, name = Some("policy")),
      Predict(subOf, name = Some("subQuery"))
    )

  test("Probe: journalText has one line for each entry, with its seq, its source and its text") {
    val lines = HarnessProbe.journalText(journal).linesIterator.toVector
    assertEquals(lines.size, journal.size)
    lines.zip(journal).foreach { (line, logged) =>
      assert(line.startsWith(s"${logged.seq} "), line)
      assert(line.contains(logged.meta.source.toString), line)
    }
    assert(lines.head.contains("the fake question of the person"), lines.head)
  }

  test("Probe: pathOf is in the folder probes of the root, and its name holds the caller and the effect") {
    val root = Path.of("/tmp/fake-root")
    assertEquals(HarnessProbe.pathOf(root, link), root.resolve("probes").resolve("controller.3.1.db"))
    assertNotEquals(HarnessProbe.pathOf(root, link), HarnessProbe.pathOf(root, link.copy(effect = EffectId(3L, 2))))
  }

  test("Probe: the mode of a probe has only Llm^") {
    assertEquals(HarnessProbe.mode.llm, dspy4s.harness.guest.Access.Full)
    assertEquals(
      HarnessProbe.mode.copy(llm = dspy4s.harness.guest.Access.None),
      dspy4s.harness.guest.Mode(
        dspy4s.harness.guest.Access.None,
        dspy4s.harness.guest.Access.None,
        dspy4s.harness.guest.Access.None,
        dspy4s.harness.guest.Access.None,
        dspy4s.harness.guest.Access.None,
        dspy4s.harness.guest.Access.None,
        dspy4s.harness.guest.Access.None
      )
    )
  }

  test("Probe: a probe answers with the value of submit, writes its journal, and spends its calls from the budget") {
    val place = Place()
    try withRoot { root =>
        val model  = RequestLm(_ => Right(policy("answer", Some("submit(\"the fake answer\")"))))
        val budget = Budget(5)
        val answer = Models.withModel(model) {
          probeAt(root, place).ask(id(1), journal, "what did the person ask?", link, budget)
        }
        assertEquals(answer, Right("the fake answer"))
        assert(Files.exists(HarnessProbe.pathOf(root, link)))
        assertEquals(budget.spent, model.requests.size)
        val asked = model.requests.peek().messages.flatMap(_.text).mkString("\n")
        assert(asked.contains("what did the person ask?") && asked.contains("the fake question of the person"), asked)
      }
    finally place.close()
  }

  test("Probe: a spent budget gives an error, and the probe makes no model call") {
    val place = Place()
    try withRoot { root =>
        val model  = RequestLm(_ => Right(policy("answer", Some("submit(\"x\")"))))
        val budget = Budget(1)
        budget.spend(): Unit
        val answer = Models.withModel(model)(probeAt(root, place).ask(id(1), journal, "why?", link, budget))
        assert(answer.isLeft, answer.toString)
        assertEquals(model.requests.size, 0)
      }
    finally place.close()
  }
