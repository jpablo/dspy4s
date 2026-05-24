package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.XMLAdapter
import dspy4s.programs.ChainOfThought
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.ToolFunction
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.contracts.Prediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.Predict
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class StreamListenerSuite extends FunSuite:

  /** Scripted streaming LM that just plays back a fixed sequence of LmChunks. */
  private final class ScriptedLm(chunks: Vector[LmChunk]) extends StreamingLanguageModel:
    override val id: String = "scripted"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = chunks.map(_.text).mkString))))
    override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
      chunks.iterator

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private def collectStream(events: ClosableIterator[StreamEvent]): Vector[StreamEvent] =
    val buf = ArrayBuffer.empty[StreamEvent]
    while events.hasNext do buf += events.next()
    buf.toVector

  private type ClosableIterator[A] = dspy4s.core.contracts.ClosableIterator[A]

  test("chat-adapter listener routes streamed text to the targeted field") {
    val chunks = Vector(
      LmChunk(text = "[[ ## reasoning ## ]]\n"),
      LmChunk(text = "think step "),
      LmChunk(text = "by step\n"),
      LmChunk(text = "[[ ## answer ## ]]\n42\n"),
      LmChunk(text = "[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("question -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener(signatureFieldName = "answer"))
      )(Map("question" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      // Only the "answer" field should be in the token stream.
      assert(tokens.nonEmpty, "expected at least one TokenEvent for the listened field")
      assertEquals(tokens.map(_.fieldName).toSet, Set("answer"))
      assertEquals(tokens.map(_.chunk).mkString, "42")
      assertEquals(tokens.last.isLastChunk, true)
    }
  }

  test("multiple listeners each collect tokens for their declared field") {
    val chunks = Vector(
      LmChunk(text = "[[ ## reasoning ## ]]\nthinking"),
      LmChunk(text = "\n[[ ## answer ## ]]\nok\n[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("q -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(
          StreamListener("reasoning"),
          StreamListener("answer")
        )
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      val grouped = tokens.groupMapReduce(_.fieldName)(_.chunk)(_ + _)
      assertEquals(grouped.get("reasoning"), Some("thinking"))
      assertEquals(grouped.get("answer"), Some("ok"))
    }
  }

  test("listener subscribed to a single field filters out the others") {
    val chunks = Vector(
      LmChunk(
        text = "[[ ## reasoning ## ]]\nthinking\n[[ ## answer ## ]]\nok\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("q -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.fieldName).toSet, Set("answer"))
      assertEquals(tokens.map(_.chunk).mkString, "ok")
    }
  }

  test("when no listeners are provided, all field chunks are emitted") {
    val chunks = Vector(
      LmChunk(
        text = "[[ ## reasoning ## ]]\nr\n[[ ## answer ## ]]\na\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("q -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector.empty
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.fieldName).toSet, Set("reasoning", "answer"))
    }
  }

  test("json-adapter listener routes streamed text per output field") {
    val chunks = Vector(
      LmChunk(text = """{"reasoning": "think","""),
      LmChunk(text = """ "answer": "42"}""", finishReason = Some("stop"))
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("q -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> JSONAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.fieldName).toSet, Set("answer"))
      assertEquals(tokens.map(_.chunk).mkString, "42")
    }
  }

  test("xml-adapter listener routes streamed text per output field") {
    val chunks = Vector(
      LmChunk(text = "<outputs><reasoning>think</reasoning>"),
      LmChunk(text = "<answer>42</answer></outputs>", finishReason = Some("stop"))
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("q -> reasoning, answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> XMLAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.fieldName).toSet, Set("answer"))
      assertEquals(tokens.map(_.chunk).mkString, "42")
    }
  }

  test("ChainOfThought: listener receives the augmented signature's fields") {
    val chunks = Vector(
      LmChunk(
        text = "[[ ## reasoning ## ]]\nwalked through it\n[[ ## answer ## ]]\n42\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val baseSignature = SignatureDsl.parse("q -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val program = ChainOfThought(baseSignature = baseSignature)
      val stream = Streamify.streamify(
        program = program,
        streamListeners = Vector(
          StreamListener("reasoning"),
          StreamListener("answer")
        )
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      val grouped = tokens.groupMapReduce(_.fieldName)(_.chunk)(_ + _)
      assertEquals(grouped.get("reasoning"), Some("walked through it"))
      assertEquals(grouped.get("answer"), Some("42"))
      // predictName is the innermost active Predict's name (matches Python
      // DSPy parity). ChainOfThought delegates to a default-named inner Predict.
      assertEquals(tokens.map(_.predictName).toSet, Set("predict"))
    }
  }

  test("ChainOfThought: listener filtering by predictName works against the inner Predict's name") {
    val chunks = Vector(
      LmChunk(
        text = "[[ ## reasoning ## ]]\nr\n[[ ## answer ## ]]\na\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val baseSignature = SignatureDsl.parse("q -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = ChainOfThought(baseSignature = baseSignature),
        streamListeners = Vector(
          StreamListener("answer", predictName = Some("predict"))
        )
      )(Map("q" -> "x"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.chunk).mkString, "a")
    }
  }

  test("ReAct: routes streamed tokens through the inner predict's signature") {
    // Single-iteration scenario: the model emits all three signature fields
    // with non-empty values, so ReAct's `hasAnswer` returns true after the
    // first call and we exercise the signature/predictName resolution path
    // through the ReAct wrapper.
    val chunks = Vector(
      LmChunk(
        text = "[[ ## answer ## ]]\n42\n[[ ## tool_name ## ]]\nnoop\n[[ ## tool_args ## ]]\n{}\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val noopTool = new ToolFunction:
      override val name: String = "noop"
      override def invoke(args: Map[String, Any])(using RuntimeContext) =
        Right("ok")

    val signature = SignatureDsl.parse("q -> answer, tool_name, tool_args").toOption.get
    val innerPredict = Predict(signature = signature)

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val react = ReAct(module = innerPredict, tools = Vector(noopTool), maxIterations = 2)
      val stream = Streamify.streamify(
        program = react,
        streamListeners = Vector(StreamListener("answer"))
      )(Map("q" -> "x"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.chunk).mkString, "42")
      // predictName is the innermost active Predict's name. ReAct's inner
      // module is a default-named Predict, so tokens surface as "predict".
      assertEquals(tokens.map(_.predictName).toSet, Set("predict"))
    }
  }

  test("multi-Predict composite: per-LM-call routing picks each Predict's own signature") {
    // Mirrors Python's tests/streaming/test_streaming.py::test_stream_listener_chat_adapter:
    // a user-defined Module composing two Predicts with different signatures.
    // Each LM call must use the active Predict's signature to parse fields,
    // and TokenEvents must carry that Predict's user-given name.
    val perCallOutputs = Vector(
      "[[ ## answer ## ]]\nparis\n[[ ## completed ## ]]",
      "[[ ## judgement ## ]]\nconfident\n[[ ## completed ## ]]"
    )
    val callIdx = new java.util.concurrent.atomic.AtomicInteger(0)
    val multiCallLm = new StreamingLanguageModel:
      override val id: String = "multi-scripted"
      override val mode: LmMode = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        val idx = callIdx.getAndIncrement() % perCallOutputs.size
        Right(LmResponse(outputs = Vector(LmOutput(text = perCallOutputs(idx)))))
      override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
        val idx = callIdx.getAndIncrement() % perCallOutputs.size
        Iterator(LmChunk(text = perCallOutputs(idx), finishReason = Some("stop")))

    val sig1 = SignatureDsl.parse("question -> answer").toOption.get
    val sig2 = SignatureDsl.parse("question, answer -> judgement").toOption.get

    val composite = new PredictProgram:
      override val moduleName: String = "my_program"
      private val predict1 = Predict(signature = sig1, name = Some("predict1"))
      private val predict2 = Predict(signature = sig2, name = Some("predict2"))
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, Prediction] =
        for
          answer    <- predict1.run(input)
          judgement <- predict2.run(input.copy(
                          inputs = input.inputs.updated("answer", answer.values("answer"))
                        ))
        yield judgement

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> multiCallLm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = composite,
        streamListeners = Vector(
          StreamListener("answer"),
          StreamListener("judgement")
        )
      )(Map("question" -> "what is the capital of france"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      // Token grouping per Predict.
      val perPredict = tokens.groupMap(_.predictName)(t => t.fieldName -> t.chunk)
      // Predict1 streamed the `answer` field.
      assertEquals(
        perPredict.get("predict1").map(_.toMap),
        Some(Map("answer" -> "paris"))
      )
      // Predict2 streamed the `judgement` field — under its own different signature.
      assertEquals(
        perPredict.get("predict2").map(_.toMap),
        Some(Map("judgement" -> "confident"))
      )
    }
  }

  test("listener with non-matching predictName is filtered out") {
    val chunks = Vector(
      LmChunk(text = "[[ ## answer ## ]]\n1\n[[ ## completed ## ]]", finishReason = Some("stop"))
    )
    val lm = new ScriptedLm(chunks)
    val signature = SignatureDsl.parse("q -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      SettingsData(
        Map(
          SettingKeys.languageModel.name -> lm,
          SettingKeys.adapter.name -> ChatAdapter()
        )
      )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = Predict(signature = signature),
        streamListeners = Vector(StreamListener("answer", predictName = Some("other-predict")))
      )(Map("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens, Vector.empty[TokenEvent])
    }
  }
