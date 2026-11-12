package dspy4s.harness

import dspy4s.core.contracts.{DspyError, DynamicValues, LmUsage, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.{Command, Entry, ModelChoice, Source}
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.json.Json

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import Models.*

/** The laws of the model options of a call to the policy (CLI_SCREEN.md, 14.4): the options of the model of the
  * session, as the reasoning effort, go to each call to the policy, and the progress gets the tokens of the call.
  */
class PolicyOptionsLaws extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val none = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)
  private val leaf = Signature.derived[PolicyInput, PolicyStep]("policy", Harness.policyInstructions)

  private def effort(level: String): Map[String, Json] = Map("reasoning_effort" -> Json.String(level))

  private def option(request: LmRequest, key: String): Option[DynamicValue] =
    DynamicValues.recordGet(request.options, key)

  private def text(value: String): Option[DynamicValue] = Some(DynamicValues.string(value))

  /** A model with no native reasoning whose reply is `reply`, with the usage `usage`. */
  private final class CountedLm(reply: String, usage: Option[LmUsage]) extends LanguageModel:
    override val id: String                                                                    = "counted"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = reply)), usage = usage))

  /** A progress that keeps the tokens that it gets. */
  private final class Tokens extends MemoryProgress:
    val seen: ConcurrentLinkedQueue[(Long, Long)]           = ConcurrentLinkedQueue()
    override def used(prompt: Long, completion: Long): Unit = seen.add((prompt, completion)): Unit

  /** `body` with new effects with `model`, on a journal of a session whose model has the options `options`, after one
    * `StepStarted`.
    */
  private def withEffects[A](
      model   : LanguageModel,
      options : Map[String, Json],
      progress: MemoryProgress = MemoryProgress.none,
      drafts  : Option[Drafts] = None
  )(body: (MemoryJournal, HarnessEffects) => A): A =
    val journal = Support.journal()
    Support.open(journal, Support.config().copy(model = ModelChoice("fake-model", options)))
    journal.commit(Entry.StepStarted(0)): Unit
    val zooms = ZoomSource()
    withModel(model) {
      val effects = HarnessEffects(
        "FAKE-TASK",
        Harness.apiReference(none),
        Predict(leaf, name = Some("policy")),
        Window,
        journal,
        () => ScalaRepl(none, Bindings(JournalTurn(journal, () => 0L, zooms))),
        Recorder(journal),
        SnapshotStore(),
        HarnessLimits(),
        () => Budget(5),
        None,
        PolicyRunner.Direct,
        zooms,
        progress,
        drafts
      )
      try body(journal, effects)
      finally effects.close()
    }

  // ── The options ──

  test("Options: a call to the policy sends the options of the model of the session") {
    val model = ThinkingLm("a native fake thought", thinking("FAKE-DONE", None))
    withEffects(model, effort("high") + ("fake_option" -> Json.String("FAKE-VALUE"))) { (_, effects) =>
      assert(effects.callPolicy(mustAnswer = false).isRight)
      val request = model.requests.head
      assertEquals(option(request, "reasoning_effort"), text("high"))
      assertEquals(option(request, "fake_option"), text("FAKE-VALUE"))
    }
  }

  test("Options: after a ModelChanged, the next call to the policy sends the new effort, in the same activation") {
    val model = ThinkingLm("a native fake thought", thinking("FAKE-ONE", None), thinking("FAKE-TWO", None))
    withEffects(model, effort("low")) { (journal, effects) =>
      assert(effects.callPolicy(mustAnswer = false).isRight)
      val changed = journal.run(Command.ChangeModel(ModelChoice("fake-model", effort("high"))), Source.User)
      assert(changed.isRight, changed)
      assert(effects.callPolicy(mustAnswer = false).isRight)
      assertEquals(model.requests.toVector.map(option(_, "reasoning_effort")), Vector(text("low"), text("high")))
    }
  }

  test("Options: with the effort none, the request sends none, the prompt has no reasoning field, and a step comes") {
    // The endpoint turns the reasoning off for the text none (CLI_SCREEN.md, 14.6). Only a null effort changes the
    // prompt, so the reply is in the form of a model with native reasoning, and it has no reasoning.
    val model = ThinkingLm("", thinking("FAKE-DONE", None))
    withEffects(model, effort("none")) { (_, effects) =>
      assertEquals(effects.callPolicy(mustAnswer = false), Right(PolicyReply("", "FAKE-DONE", None)))
      val request = model.requests.head
      assertEquals(option(request, "reasoning_effort"), text("none"))
      assert(!StreamLm.textOf(request).contains("[[ ## reasoning ## ]]"), StreamLm.textOf(request))
    }
  }

  // ── The tokens ──

  test("Tokens: callPolicy gives its progress the prompt and completion tokens of its model call") {
    // The footer of a client shows the context of the model (CLI_SCREEN.md, 14.4).
    val usage = LmUsage(totalTokens = 1_290, promptTokens = 1_234, completionTokens = 56)
    val seen  = Tokens()
    withEffects(CountedLm(policy("FAKE-DONE", None), Some(usage)), Map.empty, seen) { (_, effects) =>
      assert(effects.callPolicy(mustAnswer = false).isRight)
      assertEquals(seen.seen.asScala.toVector, Vector((1_234L, 56L)))
    }
  }

  test("Tokens: a call to the policy with no usage gives its progress no tokens") {
    val seen = Tokens()
    withEffects(CountedLm(policy("FAKE-DONE", None), None), Map.empty, seen) { (_, effects) =>
      assert(effects.callPolicy(mustAnswer = false).isRight)
      assertEquals(seen.seen.asScala.toVector, Vector.empty)
    }
  }

  test("Tokens: a call to the policy that streams gives its progress the tokens of the last chunk") {
    // The terminal client streams each call to the policy (CLI_SCREEN.md, 13).
    val usage = LmUsage(totalTokens = 2_010, promptTokens = 2_000, completionTokens = 10)
    val model = StreamLm(_ => Right(policy("FAKE-DONE", None)), usage = Some(usage))
    val seen  = Tokens()
    withEffects(model, Map.empty, seen, Some(Drafts())) { (_, effects) =>
      assert(effects.callPolicy(mustAnswer = false).isRight)
      assert(model.streams.get > 0, "the call did not stream")
      assertEquals(seen.seen.asScala.toVector, Vector((2_000L, 10L)))
    }
  }
