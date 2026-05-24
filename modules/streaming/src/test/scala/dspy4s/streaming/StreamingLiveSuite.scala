package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.providers.OpenAiClient
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.programs.Predict
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.streaming.contracts.StreamEvent
import dspy4s.streaming.contracts.StreamListener
import dspy4s.streaming.contracts.TokenEvent
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** Live integration tests that hit a real OpenAI-compatible endpoint. These
  * are dspy4s ports of the `@pytest.mark.llm_call`-marked tests from Python
  * DSPy's `tests/streaming/test_streaming.py`. They exercise the full
  * streaming pipeline — adapter formatting, real SSE chunks, per-field
  * routing, listener filtering — against an actual model.
  *
  * Gating mirrors [[dspy4s.lm.providers.OpenAiLiveSuite]]:
  *   - `OPENAI_API_KEY` must be set (env or system property)
  *   - `OPENAI_LIVE_ENABLED=true` must opt in
  *
  * Without both, every test calls `assume(...)` and reports as skipped, so
  * `sbt test` passes without credentials.
  *
  * Cost: each test pins `max_tokens` low and `temperature=0`, so a full run
  * is roughly $0.001 against `gpt-4o-mini`.
  */
class StreamingLiveSuite extends FunSuite:

  private val apiKey: Option[String] =
    sys.env.get("OPENAI_API_KEY").orElse(sys.props.get("OPENAI_API_KEY")).filter(_.nonEmpty)
  private val baseUrl: Option[String] =
    sys.env.get("OPENAI_BASE_URL").orElse(sys.props.get("OPENAI_BASE_URL")).filter(_.nonEmpty)
  private val model: String =
    sys.env.getOrElse("OPENAI_LIVE_MODEL", sys.props.getOrElse("OPENAI_LIVE_MODEL", "gpt-4o-mini"))

  private def hasOptIn: Boolean =
    val env = sys.env.getOrElse("OPENAI_LIVE_ENABLED", "")
    val prop = sys.props.getOrElse("OPENAI_LIVE_ENABLED", "")
    (env.nonEmpty && env != "0" && env != "false") ||
    (prop.nonEmpty && prop != "0" && prop != "false")

  private def requireLive(): Unit =
    assume(
      apiKey.isDefined && hasOptIn,
      "Live streaming tests require OPENAI_API_KEY *and* OPENAI_LIVE_ENABLED=true"
    )

  private def buildLm(): OpenAiLanguageModel =
    val client = OpenAiClient(apiKey = apiKey.get, baseUrl = baseUrl.getOrElse(OpenAiClient.defaultBaseUrl))
    OpenAiLanguageModel(model = model, client = client)

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private def collectStream(events: dspy4s.core.contracts.ClosableIterator[StreamEvent]): Vector[StreamEvent] =
    val buf = ArrayBuffer.empty[StreamEvent]
    while events.hasNext do buf += events.next()
    buf.toVector

  /** Shared MyProgram scaffold used by both the Chat and JSON adapter ports.
    * Mirrors Python's `class MyProgram(dspy.Module)` in
    * tests/streaming/test_streaming.py. */
  private def buildComposite(): PredictProgram =
    val sig1 = SignatureDsl.parse("question -> answer").toOption.get
    val sig2 = SignatureDsl.parse("question, answer -> judgement").toOption.get
    new PredictProgram:
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

  /** Direct dspy4s port of Python DSPy's
    * `tests/streaming/test_streaming.py::test_stream_listener_chat_adapter`.
    *
    * Composes two `Predict`s with different signatures:
    *   - predict1: `question -> answer`
    *   - predict2: `question, answer -> judgement`
    *
    * Streams with two listeners (one per output field) and asserts that the
    * first chunk comes from `predict1`/`answer` and the second from
    * `predict2`/`judgement`. The Python test uses the same first/second
    * assertion pattern with a tolerance for the trailing chunk.
    */
  test("live: stream_listener_chat_adapter parity (two Predicts, two listeners)") {
    requireLive()
    val lm = buildLm()
    val composite = buildComposite()

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
        program = composite,
        streamListeners = Vector(
          StreamListener("answer"),
          StreamListener("judgement")
        ),
        includeFinalPrediction = false
      )(Map("question" -> "why did a chicken cross the kitchen?"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assert(tokens.nonEmpty, "expected at least one TokenEvent from a live stream")

      // First chunk: from predict1, field "answer".
      assertEquals(tokens.head.predictName, "predict1", s"first token: ${tokens.head}")
      assertEquals(tokens.head.fieldName, "answer", s"first token: ${tokens.head}")

      // Last chunk: from predict2, field "judgement".
      // Behavioral delta from Python parity: the Python test checks tokens[-2]
      // because its ChatAdapter `[[ ## completed ## ]]` marker sometimes adds a
      // trailing flush chunk; dspy4s's ChatAdapter has no completion marker,
      // so the field's final content IS the last token — checking `.last` is
      // equivalent to Python's `[-2]` for our framing.
      assertEquals(tokens.last.predictName, "predict2", s"last token: ${tokens.last}")
      assertEquals(tokens.last.fieldName, "judgement", s"last token: ${tokens.last}")
    }
  }

  /** Direct dspy4s port of Python DSPy's
    * `tests/streaming/test_streaming.py::test_stream_listener_json_adapter`.
    *
    * Same MyProgram, same listeners, but with [[JSONAdapter]] instead of
    * [[ChatAdapter]]. Asserts the first chunk comes from `predict1`/`answer`
    * and the last from `predict2`/`judgement` — the JSON adapter has no
    * completion-marker quirk so Python uses `[-1]` directly here. */
  test("live: stream_listener_json_adapter parity (two Predicts, two listeners)") {
    requireLive()
    val lm = buildLm()
    val composite = buildComposite()

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
        program = composite,
        streamListeners = Vector(
          StreamListener("answer"),
          StreamListener("judgement")
        ),
        includeFinalPrediction = false
      )(Map("question" -> "why did a chicken cross the kitchen?"))

      val tokens = collectStream(stream).collect { case e: TokenEvent => e }
      assert(tokens.nonEmpty, "expected at least one TokenEvent from a live stream")

      assertEquals(tokens.head.predictName, "predict1", s"first token: ${tokens.head}")
      assertEquals(tokens.head.fieldName, "answer", s"first token: ${tokens.head}")
      // Behavioral delta from Python parity: Python asserts
      // `all_chunks[0].is_last_chunk is False` because its JSON state machine
      // emits per-token slices. dspy4s's JsonStreamingState emits one chunk
      // per value at the close-of-value boundary (see STREAMING_POSTPONED.md
      // v1.3 design note), so the first chunk IS the last chunk for that
      // field — `isLastChunk` is `true` here. Skipping the is_last_chunk
      // assertion preserves Python's intent (verify routing) while matching
      // our coarser-grained emission discipline.

      assertEquals(tokens.last.predictName, "predict2", s"last token: ${tokens.last}")
      assertEquals(tokens.last.fieldName, "judgement", s"last token: ${tokens.last}")
    }
  }
