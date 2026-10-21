package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.ReplCodeInterpreter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.strategies.{ChainOfThought, Predict, RLM, ReAct, ReActV2}
import dspy4s.signatures.Signature
import dspy4s.streaming.contracts.PredictionEvent
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

import scala.collection.mutable.ArrayBuffer

private final case class StreamableInput(question: String) derives Schema
private final case class StreamableOutput(answer: String) derives Schema

class StreamableSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val qa = Signature.derived[StreamableInput, StreamableOutput]("StreamableAgent")

  /** A model that streams `text` as one chunk for each call. */
  private final class OneReplyLm(text: String) extends StreamingLanguageModel:
    override val id: String                                                                    = "one-reply"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      Iterator(LmChunk(text = text, finishReason = Some("stop")))

  /** A REPL that gives the `SUBMIT` payload `payload` for each code. */
  private final class SubmitRepl(payload: String) extends ReplCodeInterpreter:
    override def execute(code: String): Either[DspyError, CodeResult]                                       = execute(code, Map.empty)
    override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
      Right(CodeResult("", "", 0, finalOutput = Some(payload)))
    override def close(): Unit = ()

  /** The events of one `streamify` run of `program` with a chat adapter and a model that replies `reply`. */
  private def streamRun[P: Streamable](
      program : P,
      reply   : String,
      listener: StreamListener,
      warnings: ArrayBuffer[String]
  ): Vector[StreamEvent] =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(OneReplyLm(reply)), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream           = Streamify.streamify(
        program,
        streamListeners = Vector(listener),
        warningSink = warnings.append
      )(rec("question" := "x"))
      val events = ArrayBuffer.empty[StreamEvent]
      while stream.hasNext do events += stream.next()
      events.toVector
    }

  test("Streamable exposes the two stable executable predictor signatures of ReAct") {
    val program = ReAct(
      baseSignature = Signature.derived[StreamableInput, StreamableOutput]("StreamableReAct"),
      tools = Vector.empty
    )
    val known = summon[Streamable[ReAct[StreamableInput, StreamableOutput]]]
      .knownSignatures(program)

    assertEquals(
      known,
      Vector(
        program.reactPredict.moduleName     -> program.reactPredict.signature.layout,
        program.extractorPredict.moduleName -> program.extractorPredict.signature.layout
      )
    )
    assertEquals(known.map(_._1), Vector("react", "react_extract"))
    assertEquals(known(1)._2.outputFields.head.name, "reasoning")
  }

  test("Streamable derives Predict and ChainOfThought signatures from the shared runner") {
    val signature = Signature.derived[StreamableInput, StreamableOutput]("StreamableTyped")
    val predict   = Predict(signature)
    val cot       = ChainOfThought(signature)

    val predictKnown = summon[Streamable[Predict[StreamableInput, StreamableOutput]]]
      .knownSignatures(predict)
    val cotKnown = summon[Streamable[ChainOfThought[StreamableInput, StreamableOutput]]]
      .knownSignatures(cot)

    assertEquals(predictKnown, Vector(predict.moduleName -> signature.layout))
    assertEquals(cotKnown.map(_._1), Vector("predict"))
    assertEquals(cotKnown.head._2.outputFields.head.name, "reasoning")
  }

  test("Streamable exposes the action and extract predictor signatures of RLM") {
    val program = RLM(qa, interpreterFactory = Some((_, _) => SubmitRepl("""{"answer": "42"}""")))
    val known   = summon[Streamable[RLM[StreamableInput, StreamableOutput]]].knownSignatures(program)

    assertEquals(
      known,
      Vector(
        program.actionPredict.moduleName  -> program.actionPredict.signature.layout,
        program.extractPredict.moduleName -> program.extractPredict.signature.layout
      )
    )
    assertEquals(known.map(_._1), Vector("rlm_action", "rlm_extract"))
    assertEquals(known.head._2.outputFields.map(_.name), Vector("reasoning", "code"))
    assertEquals(known(1)._2.outputFields.map(_.name), Vector("answer"))
  }

  test("streamify runs RLM and streams the fields of its action predictor") {
    val program  = RLM(qa, interpreterFactory = Some((_, _) => SubmitRepl("""{"answer": "42"}""")))
    val reply    = "[[ ## reasoning ## ]]\nI know it.\n\n[[ ## code ## ]]\nSUBMIT(answer='42')\n\n[[ ## completed ## ]]"
    val warnings = ArrayBuffer.empty[String]
    val events   = streamRun(program, reply, StreamListener("reasoning"), warnings)

    val tokens = events.collect { case e: TokenEvent => e }
    assertEquals(warnings.toVector, Vector.empty[String])
    assertEquals(tokens.map(_.chunk).mkString, "I know it.")
    assertEquals(tokens.map(_.predictName).toSet, Set("rlm_action"))
    assertEquals(
      events.collect { case e: PredictionEvent => lookupString(e.prediction.values, "answer") },
      Vector("42")
    )
  }

  test("Streamable exposes the step predictor signature of ReActV2") {
    val program = ReActV2(qa, Vector.empty)
    val known   = summon[Streamable[ReActV2[StreamableInput, StreamableOutput]]].knownSignatures(program)

    assertEquals(known, Vector(program.reactPredict.moduleName -> program.reactPredict.layout))
    assertEquals(known.map(_._1), Vector("react"))
    assertEquals(known.head._2.outputFields.map(_.name), Vector("next_thought", "tool_calls"))
  }

  test("streamify runs ReActV2 and streams the fields of its step predictor") {
    val program = ReActV2(qa, Vector.empty)
    val reply   = "[[ ## next_thought ## ]]\nI can answer now.\n\n[[ ## tool_calls ## ]]\n" +
      """{"tool_calls": [{"name": "submit", "args": {"answer": "42"}}]}""" + "\n\n[[ ## completed ## ]]"
    val warnings = ArrayBuffer.empty[String]
    val events   = streamRun(program, reply, StreamListener("next_thought"), warnings)

    val tokens = events.collect { case e: TokenEvent => e }
    assertEquals(warnings.toVector, Vector.empty[String])
    assertEquals(tokens.map(_.chunk).mkString, "I can answer now.")
    assertEquals(tokens.map(_.predictName).toSet, Set("react"))
    assertEquals(
      events.collect { case e: PredictionEvent => lookupString(e.prediction.values, "answer") },
      Vector("42")
    )
  }
