package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.History
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.ProgramCall
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

import scala.collection.mutable.ArrayBuffer

final case class PhTurn(question: String, history: History) derives Schema
final case class PhReply(answer: String) derives Schema

/** `ChainOfThought` and `DynamicPredict` give a conversation history to the adapter with no change of the value
  * (PORT_GAPS G-28). `PredictHistorySuite` covers `Predict`.
  */
class ProgramHistorySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Records each request and replies with one scripted text. */
  private final class RecordingLm(reply: String) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "recording"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  private def textOf(message: Message): String = message.text.getOrElse("")

  import MessageRole.{Assistant, System, User}

  test("ChainOfThought replays a History input, with the reasoning of a turn that has one") {
    val lm      = new RecordingLm("[[ ## reasoning ## ]]\n3 and 3 make 6.\n\n[[ ## answer ## ]]\n6\n\n[[ ## completed ## ]]")
    val program = ChainOfThought(Signature.derived[PhTurn, PhReply]("QA"))
    val history = History.empty :+
      DynamicValues.record("question" := "What is 1+1?", "reasoning" := "1 and 1 make 2.", "answer" := "2") :+
      DynamicValues.record("question" := "What is 2+2?", "answer" := "4")

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val output           = program(PhTurn("What is 3+3?", history)).map(_.output)
      assertEquals(output.map(o => (o.reasoning, o.answer)), Right(("3 and 3 make 6.", "6")))
    }

    val messages = lm.requests.head.messages
    assertEquals(messages.map(_.role), Vector(System, User, Assistant, User, Assistant, User))
    assert(textOf(messages(1)).contains("What is 1+1?"), textOf(messages(1)))
    assertEquals(
      textOf(messages(2)),
      "[[ ## reasoning ## ]]\n1 and 1 make 2.\n\n[[ ## answer ## ]]\n2\n\n[[ ## completed ## ]]\n"
    )
    // A turn with no reasoning shows the answer only.
    assertEquals(textOf(messages(4)), "[[ ## answer ## ]]\n4\n\n[[ ## completed ## ]]\n")
    val current = textOf(messages.last)
    assert(current.contains("What is 3+3?"), current)
    assert(!current.contains("history") && !current.contains("What is 1+1?"), current)
  }

  test("DynamicPredict replays a History value of the input record") {
    val lm      = new RecordingLm("[[ ## answer ## ]]\n6\n\n[[ ## completed ## ]]")
    val layout  = SignatureLayout.parse("question, history: History -> answer").toOption.get
    val history = History.empty :+ DynamicValues.record("question" := "What is 1+1?", "answer" := "2")

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val input            = DynamicValues.record("question" := "What is 3+3?", "history" := history)
      val result           = DynamicPredict(layout)(ProgramCall(input = input))
      assertEquals(result.map(p => lookupString(p.output, "answer")), Right("6"))
    }

    val messages = lm.requests.head.messages
    assertEquals(messages.map(_.role), Vector(System, User, Assistant, User))
    assert(textOf(messages(1)).contains("What is 1+1?"), textOf(messages(1)))
    assertEquals(textOf(messages(2)), "[[ ## answer ## ]]\n2\n\n[[ ## completed ## ]]\n")
    assert(!textOf(messages.last).contains("history"), textOf(messages.last))
  }
