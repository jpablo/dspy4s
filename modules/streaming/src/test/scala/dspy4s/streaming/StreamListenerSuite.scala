package dspy4s.streaming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.StreamingLanguageModel
import dspy4s.programs.Predict
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
      LmChunk(text = "reasoning: "),
      LmChunk(text = "think step "),
      LmChunk(text = "by step\n"),
      LmChunk(text = "answer: 42"),
      LmChunk(finishReason = Some("stop"))
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
      LmChunk(text = "reasoning: thinking"),
      LmChunk(text = "\nanswer: ok", finishReason = Some("stop"))
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
      LmChunk(text = "reasoning: thinking\nanswer: ok", finishReason = Some("stop"))
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
      LmChunk(text = "reasoning: r\nanswer: a", finishReason = Some("stop"))
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

  test("listener with non-matching predictName is filtered out") {
    val chunks = Vector(LmChunk(text = "answer: 1", finishReason = Some("stop")))
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
