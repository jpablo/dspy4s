package dspy4s.harness

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.*
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.json.Json

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

import Support.*

/** The laws of the goal leaf and of the check in the worker (CODE_HARNESS_DESIGN.md, 8.5), with scripts of
  * `TurnEffects` and a journal in memory.
  */
class GoalLeafLaws extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val goal = "The fake build passes"
  private val none = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)

  /** A model that keeps each request and gives `reply`. */
  private final class KeptLm(reply: String) extends LanguageModel:
    val requests: ConcurrentLinkedQueue[LmRequest]                                             = ConcurrentLinkedQueue()
    override val id: String                                                                    = "kept"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests.add(request): Unit
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  /** The effects of a harness on `journal`, with the goal leaf when `leaf` is true. */
  private def effectsOf(journal: MemoryJournal, leaf: Boolean)(using RuntimeContext): HarnessEffects =
    val zooms = ZoomSource()
    HarnessEffects(
      _ => "FAKE-TASK",
      Harness.apiReference(none),
      Predict(Signature.derived[PolicyInput, PolicyStep]("policy", Harness.policyInstructions), name = Some("policy")),
      Window,
      journal,
      () => ScalaRepl(none, Bindings(JournalTurn(journal, () => 0L, zooms))),
      Recorder(journal),
      SnapshotStore(),
      HarnessLimits(),
      () => Budget(5),
      None,
      goalLeaf = Option.when(leaf)(Predict(GoalLeaf.signature, name = Some("goal")))
    )

  /** A work session of the person with a goal, a loop of `limit` from the person, and its first message. */
  private def looping(limit: Int): MemoryJournal =
    val j = journal()
    j.run(
      Command.Activate(versions, config(kind = TurnKind.Chat).copy(owner = Owner.User, goal = Some(goal))),
      Source.Harness
    ): Unit
    j.run(Command.StartLoop(limit), Source.User): Unit
    j.run(Command.Send(RequestId("task"), "Make the fake build pass", Map.empty), Source.User): Unit
    j

  /** Effects that reply to each step with no code, and give the checks of `checks` in order. */
  private final class Checks(checks: Int => Either[String, GoalCheck]) extends TurnEffects:
    val asked: ArrayBuffer[(String, String)]                                         = ArrayBuffer.empty
    def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply]                 = answer("working on it")
    def runCell(cell         : Long, code: String): CellResult                       = done
    override def checkGoal(goal: String, blocker: String): Either[String, GoalCheck] =
      asked += (goal -> blocker): Unit
      checks(asked.size)

  private def entries(j: Journal): Vector[Entry] = j.log.map(_.entry)

  private def personMessages(j: Journal): Int =
    j.log.count(l =>
      l.meta.source == Source.User &&
        (l.entry match
          case Entry.Input(_, _, _) => true
          case _                    => false)
    )

  test("Goal leaf: a loop of more than 8 turns with no message of the person reaches its goal") {
    val j      = looping(50)
    val effect = Checks(n => Right(GoalCheck(met = n >= 12, s"check $n", "", same = false)))
    drive(j, effect): Unit
    val turns = entries(j).count(_ == Entry.TurnStarted)
    assertEquals(turns, 12)
    assertEquals(personMessages(j), 1)
    assertEquals(j.state.loopEnd, Some(LoopEnd.Met("check 12")))
    assertEquals(j.state.loop, None)
    assertEquals(effect.asked.toVector.map(_._1).distinct, Vector(goal))
    // Each goal message comes from the harness and gives the reason of its check.
    val goalMessages = j.log.collect {
      case Logged(_, meta, Entry.Input(id, text, _)) if id.value.startsWith("goal:") => (meta.source, text)
    }
    assertEquals(goalMessages.size, 11)
    assert(
      goalMessages.forall((source, text) => source == Source.Harness && text.contains("check")),
      goalMessages.toString
    )
  }

  test("Goal leaf: drive gives the leaf the blocker of the last check") {
    val j      = looping(50)
    val effect = Checks(n => Right(GoalCheck(met = n >= 3, "not yet", s"blocker $n", same = false)))
    drive(j, effect): Unit
    assertEquals(effect.asked.toVector.map(_._2), Vector("", "blocker 1", "blocker 2"))
  }

  test("Goal leaf: a failed check gives not met with the error, and three failed checks end the loop as blocked") {
    val j      = looping(50)
    val effect = Checks(_ => Left("FAKE-DOWN"))
    drive(j, effect): Unit
    assertEquals(j.state.loopEnd, Some(LoopEnd.Blocked(GoalLeaf.FailedBlocker)))
    val checks = j.log.collect { case Logged(_, _, Entry.GoalChecked(check)) => check }
    assertEquals(checks.size, 3)
    assert(checks.forall(c => !c.met && c.reason.contains("FAKE-DOWN")), checks.toString)
    assertEquals(checks.map(_.same), Vector(false, true, true))
  }

  test("Goal leaf: a check that throws counts as a failed check, and effects with no leaf fail each check") {
    val j      = looping(50)
    val thrown = new TurnEffects:
      def callPolicy(mustAnswer: Boolean): Either[String, PolicyReply]                 = answer("working")
      def runCell(cell         : Long, code: String): CellResult                       = done
      override def checkGoal(goal: String, blocker: String): Either[String, GoalCheck] =
        throw IllegalStateException("FAKE-THROW")
    drive(j, thrown): Unit
    assertEquals(j.state.loopEnd, Some(LoopEnd.Blocked(GoalLeaf.FailedBlocker)))
    val plain = looping(50)
    drive(plain, Script(Vector.fill(5)(answer("working")))): Unit
    assertEquals(plain.state.loopEnd, Some(LoopEnd.Blocked(GoalLeaf.FailedBlocker)))
  }

  test("Goal leaf: at the limit the loop ends, and no turn starts after it") {
    val j = looping(3)
    drive(j, Checks(_ => Right(GoalCheck(met = false, "not yet", "", same = false)))): Unit
    assertEquals(j.state.loopEnd, Some(LoopEnd.Limit))
    // The first turn of the person and three turns of the loop.
    assertEquals(entries(j).count(_ == Entry.TurnStarted), 4)
  }

  // ── turnText ──

  private def at(seq: Int, source: Source, entry: Entry): Logged =
    Logged(seq.toLong, Meta(0L, source, Taint.Trusted, true), entry)

  private def twoTurns: Vector[Logged] =
    Vector(
      at(0, Source.Harness, Entry.Activated(versions, config())),
      at(1, Source.User, Entry.Input(RequestId("a"), "FAKE-FIRST-MESSAGE", Map.empty)),
      at(2, Source.User, Entry.TurnStarted),
      at(3, Source.Harness, Entry.StepStarted(0)),
      at(4, Source.Policy, Entry.Acted("", "FAKE-FIRST-STEP", None)),
      at(5, Source.Harness, Entry.TurnEnded(TurnOutcome.Replied("FAKE-FIRST-STEP"))),
      at(6, Source.Harness, Entry.GoalChecked(GoalCheck(met = false, "FAKE-CHECK", "", same = false))),
      at(7, Source.Harness, Entry.Input(RequestId("goal:6"), "FAKE-GOAL-MESSAGE", Map.empty)),
      at(8, Source.Harness, Entry.TurnStarted),
      at(9, Source.Harness, Entry.StepStarted(0)),
      at(10, Source.Policy, Entry.Acted("", "FAKE-SECOND-STEP", Some("FAKE-CODE"))),
      at(11, Source.Harness, Entry.Observed("FAKE-OUTPUT", CellOutcome.Ok, Map.empty)),
      at(12, Source.Harness, Entry.StepStarted(1)),
      at(13, Source.Policy, Entry.Acted("", "", Some("submit(v)"))),
      at(14, Source.Harness, Entry.Observed("", CellOutcome.Submitted(Json.String("FAKE-VALUE")), Map.empty)),
      at(15, Source.Harness, Entry.TurnEnded(TurnOutcome.Submitted(Json.String("FAKE-VALUE"))))
    )

  test("Goal leaf: turnText gives the messages, the steps, the cells and the outcome of the last turn only") {
    val text = turnText(twoTurns)
    for part <- Vector("FAKE-GOAL-MESSAGE", "FAKE-SECOND-STEP", "FAKE-CODE", "FAKE-OUTPUT", "submit(v)", "FAKE-VALUE")
    do
      assert(text.contains(part), s"$part is not in:\n$text")
    for part <- Vector("FAKE-FIRST-MESSAGE", "FAKE-FIRST-STEP", "FAKE-CHECK") do
      assert(!text.contains(part), s"$part is in:\n$text")
    assert(text.indexOf("FAKE-CODE") < text.indexOf("FAKE-OUTPUT"), text)
    assert(text.contains("The turn ended with submit: FAKE-VALUE"), text)
  }

  test("Goal leaf: turnText keeps the end of a long turn, up to its budget") {
    val long = twoTurns.updated(
      11,
      at(11, Source.Harness, Entry.Observed("x" * 50_000 + "FAKE-TAIL", CellOutcome.Ok, Map.empty))
    )
    val text = turnText(long, max = 2_000)
    assert(text.length <= 2_100, text.length.toString)
    assert(text.contains("FAKE-VALUE"), text)
    assert(!text.contains("FAKE-GOAL-MESSAGE"), text)
    assert(text.startsWith("[The start of the turn is cut.]"), text.take(80))
    assertEquals(turnText(twoTurns, max = 1_000_000), turnText(twoTurns))
  }

  test("Goal leaf: turnText of a log with no turn gives its messages, and the failed answer names the error") {
    val waiting = twoTurns.take(2)
    assert(turnText(waiting).contains("FAKE-FIRST-MESSAGE"), turnText(waiting))
    assertEquals(GoalLeaf.failed("FAKE", "").same, false)
    assertEquals(GoalLeaf.failed("FAKE", GoalLeaf.FailedBlocker).same, true)
    assertEquals(GoalAnswer(met = true, "r", "", same = false).check, GoalCheck(met = true, "r", "", same = false))
  }

  test("Goal leaf: the effects of a harness ask the leaf with the goal, the last turn and the blocker") {
    val reply = "[[ ## met ## ]]\ntrue\n\n[[ ## reason ## ]]\nFAKE-REASON\n\n[[ ## blocker ## ]]\nFAKE-NEW\n\n" +
      "[[ ## same ## ]]\nfalse\n\n[[ ## completed ## ]]"
    val model = KeptLm(reply)
    val j     = journal()
    open(j, config(kind = TurnKind.Chat), "FAKE-MESSAGE")
    j.commit(
      Entry.StepStarted(0),
      Entry.Acted("", "FAKE-STEP-TEXT", None),
      Entry.TurnEnded(TurnOutcome.Replied("x"))
    ): Unit
    Models.withModel(model) {
      val effects = effectsOf(j, leaf = true)
      try
        assertEquals(
          effects.checkGoal("FAKE-GOAL", "FAKE-BLOCKER"),
          Right(GoalCheck(met = true, "FAKE-REASON", "FAKE-NEW", same = false))
        )
        val asked = StreamLm.textOf(model.requests.asScala.head)
        for part <- Vector("FAKE-GOAL", "FAKE-BLOCKER", "FAKE-MESSAGE", "FAKE-STEP-TEXT", GoalLeaf.fields("met")) do
          assert(asked.contains(part), s"$part is not in:\n$asked")
        val plain = effectsOf(j, leaf = false)
        try assert(plain.checkGoal("FAKE-GOAL", "").isLeft)
        finally plain.close()
      finally effects.close()
    }
  }
