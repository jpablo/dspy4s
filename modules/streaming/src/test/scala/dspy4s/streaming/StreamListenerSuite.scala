package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.XMLAdapter
import dspy4s.programs.ChainOfThought
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.ToolFunction
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.DynamicPredict
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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector(StreamListener(signatureFieldName = "answer"))
      )(rec("question" -> "x"))

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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector(
          StreamListener("reasoning"),
          StreamListener("answer")
        )
      )(rec("q" -> "x"))

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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(rec("q" -> "x"))

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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector.empty
      )(rec("q" -> "x"))

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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(JSONAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(rec("q" -> "x"))

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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(XMLAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector(StreamListener("answer"))
      )(rec("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.fieldName).toSet, Set("answer"))
      assertEquals(tokens.map(_.chunk).mkString, "42")
    }
  }

  test("reasoning-augmented DynamicPredict: listener receives the augmented signature's fields") {
    val chunks = Vector(
      LmChunk(
        text = "[[ ## reasoning ## ]]\nwalked through it\n[[ ## answer ## ]]\n42\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val baseSignature = SignatureDsl.parse("q -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val program = DynamicPredict(ChainOfThought.augmentLayout(baseSignature).toOption.get)
      val stream = Streamify.streamify(
        program = program,
        streamListeners = Vector(
          StreamListener("reasoning"),
          StreamListener("answer")
        )
      )(rec("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      val grouped = tokens.groupMapReduce(_.fieldName)(_.chunk)(_ + _)
      assertEquals(grouped.get("reasoning"), Some("walked through it"))
      assertEquals(grouped.get("answer"), Some("42"))
      // predictName is the active DynamicPredict's name.
      assertEquals(tokens.map(_.predictName).toSet, Set("predict"))
    }
  }

  test("reasoning-augmented DynamicPredict: listener filtering by predictName works") {
    val chunks = Vector(
      LmChunk(
        text = "[[ ## reasoning ## ]]\nr\n[[ ## answer ## ]]\na\n[[ ## completed ## ]]",
        finishReason = Some("stop")
      )
    )
    val lm = new ScriptedLm(chunks)
    val baseSignature = SignatureDsl.parse("q -> answer").toOption.get

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val program = DynamicPredict(ChainOfThought.augmentLayout(baseSignature).toOption.get)
      val stream = Streamify.streamify(
        program = program,
        streamListeners = Vector(
          StreamListener("answer", predictName = Some("predict"))
        )
      )(rec("q" -> "x"))

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
    val innerPredict = DynamicPredict(layout = signature)

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val react = ReAct(module = innerPredict, tools = Vector(noopTool), maxIterations = 2)
      val stream = Streamify.streamify(
        program = react,
        streamListeners = Vector(StreamListener("answer"))
      )(rec("q" -> "x"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.chunk).mkString, "42")
      // predictName is the innermost active DynamicPredict's name. ReAct's inner
      // module is a default-named DynamicPredict, so tokens surface as "predict".
      assertEquals(tokens.map(_.predictName).toSet, Set("predict"))
    }
  }

  test("multi-DynamicPredict composite: per-LM-call routing picks each DynamicPredict's own signature") {
    // Mirrors Python's tests/streaming/test_streaming.py::test_stream_listener_chat_adapter:
    // a user-defined Module composing two Predicts with different signatures.
    // Each LM call must use the active DynamicPredict's signature to parse fields,
    // and TokenEvents must carry that DynamicPredict's user-given name.
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
      private val predict1 = DynamicPredict(layout = sig1, name = Some("predict1"))
      private val predict2 = DynamicPredict(layout = sig2, name = Some("predict2"))
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        for
          answer    <- predict1.run(input)
          judgement <- predict2.run(input.copy(
                          inputs = dspy4s.core.contracts.DynamicValues.recordUpdated(
                            input.inputs,
                            "answer",
                            answer.get("answer").getOrElse(zio.blocks.schema.DynamicValue.Null)
                          )
                        ))
        yield judgement

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(multiCallLm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = composite,
        streamListeners = Vector(
          StreamListener("answer"),
          StreamListener("judgement")
        )
      )(rec("question" -> "what is the capital of france"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      // Token grouping per DynamicPredict.
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
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = signature),
        streamListeners = Vector(StreamListener("answer", predictName = Some("other-predict"))),
        warningSink = _ => () // suppress the expected validation warning
      )(rec("q" -> "x"))

      val events = collectStream(stream)
      val tokens = events.collect { case e: TokenEvent => e }
      assertEquals(tokens, Vector.empty[TokenEvent])
    }
  }

  test("allowReuse=false: listener fires for first LM call and is silent on subsequent ones") {
    val perCallOutputs = Vector(
      "[[ ## answer ## ]]\nparis\n[[ ## completed ## ]]",
      "[[ ## answer ## ]]\nlondon\n[[ ## completed ## ]]"
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

    // Both Predicts share the same signature; they both output the "answer"
    // field. Without allowReuse=false the listener would emit chunks from
    // both LM calls; with allowReuse=false only the first call's chunks
    // should appear.
    val sig = SignatureDsl.parse("question -> answer").toOption.get
    val composite = new PredictProgram:
      override val moduleName: String = "my_program"
      private val predict1 = DynamicPredict(layout = sig, name = Some("predict1"))
      private val predict2 = DynamicPredict(layout = sig, name = Some("predict2"))
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        for
          _      <- predict1.run(input)
          second <- predict2.run(input)
        yield second

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(multiCallLm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = composite,
        streamListeners = Vector(StreamListener("answer", allowReuse = false)),
        warningSink = _ => ()
      )(rec("question" -> "x"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      // Only the first LM call's chunks for "answer" should appear; the
      // second LM call (predict2) also outputs to "answer" but the listener
      // has already fired.
      assertEquals(tokens.map(_.predictName).toSet, Set("predict1"))
      assertEquals(tokens.map(_.chunk).mkString, "paris")
    }
  }

  test("allowReuse=true (default): listener keeps firing across multiple LM calls") {
    // Same composite + scripted LM as the test above, but the default-on
    // allowReuse means BOTH calls' answer chunks should fire.
    val perCallOutputs = Vector(
      "[[ ## answer ## ]]\nparis\n[[ ## completed ## ]]",
      "[[ ## answer ## ]]\nlondon\n[[ ## completed ## ]]"
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

    val sig = SignatureDsl.parse("question -> answer").toOption.get
    val composite = new PredictProgram:
      override val moduleName: String = "my_program"
      private val p1 = DynamicPredict(layout = sig, name = Some("p1"))
      private val p2 = DynamicPredict(layout = sig, name = Some("p2"))
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        for
          _ <- p1.run(input)
          out <- p2.run(input)
        yield out

    RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(multiCallLm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = composite,
        streamListeners = Vector(StreamListener("answer"))
      )(rec("question" -> "x"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assertEquals(tokens.map(_.predictName).toSet, Set("p1", "p2"))
    }
  }

  test("validation: listener for an unknown field emits a warning") {
    val sig = SignatureDsl.parse("q -> answer").toOption.get
    val warnings = scala.collection.mutable.ArrayBuffer.empty[String]
    val sink: String => Unit = warnings.append

    val lm = new ScriptedLm(Vector(
      LmChunk(text = "[[ ## answer ## ]]\nok\n[[ ## completed ## ]]", finishReason = Some("stop"))
    ))
    val _ = RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = sig),
        streamListeners = Vector(
          StreamListener("nonexistent_field"),
          StreamListener("answer") // valid; should not warn
        ),
        warningSink = sink
      )(rec("q" -> "x"))
      collectStream(stream)
    }
    assertEquals(warnings.size, 1, s"warnings: ${warnings.mkString("; ")}")
    assert(warnings.head.contains("nonexistent_field"), warnings.head)
    assert(warnings.head.contains("never fire"), warnings.head)
  }

  test("validation: listener with unknown predictName emits a warning") {
    val sig = SignatureDsl.parse("q -> answer").toOption.get
    val warnings = scala.collection.mutable.ArrayBuffer.empty[String]
    val sink: String => Unit = warnings.append

    val lm = new ScriptedLm(Vector(
      LmChunk(text = "[[ ## answer ## ]]\nok\n[[ ## completed ## ]]", finishReason = Some("stop"))
    ))
    val _ = RuntimeEnvironment.withSettings(
      RuntimeContext(
          lm = Some(lm),
          adapter = Some(ChatAdapter())
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val stream = Streamify.streamify(
        program = DynamicPredict(layout = sig),
        streamListeners = Vector(StreamListener("answer", predictName = Some("nonexistent_predict"))),
        warningSink = sink
      )(rec("q" -> "x"))
      collectStream(stream)
    }
    assertEquals(warnings.size, 1)
    assert(warnings.head.contains("nonexistent_predict"), warnings.head)
  }

  test("validation: opaque user composite skips validation (no warnings)") {
    val warnings = scala.collection.mutable.ArrayBuffer.empty[String]
    val sink: String => Unit = warnings.append

    val opaqueProgram = new PredictProgram:
      override val moduleName: String = "opaque"
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        Right(dspy4s.core.contracts.DynamicPrediction(values = rec("answer" -> "x")))

    given RuntimeContext = RuntimeEnvironment.current
    // We don't actually invoke the stream — validation runs eagerly when
    // streamify is called, before any inputs flow.
    val _ = Streamify.streamify(
      program = opaqueProgram,
      streamListeners = Vector(
        StreamListener("anything_at_all"),
        StreamListener("answer", predictName = Some("anyone"))
      ),
      warningSink = sink
    )
    assertEquals(warnings, scala.collection.mutable.ArrayBuffer.empty[String])
  }
