package dspy4s.harness

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmChunk, LmMode, LmOutput, LmRequest, LmResponse, StreamingLanguageModel}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** The input of the task of the tests. */
final case class Question(question: String) derives Schema

/** The output of the task of the tests. */
final case class Answer(answer: String) derives Schema

/** A model that gives its replies in order. With no reply left, the call fails. */
final class ScriptLm(replies: String*) extends LanguageModel:
  override val id: String              = "scripted"
  override val mode: LmMode            = LmMode.Chat
  val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    requests += request: Unit
    replies
      .lift(requests.size - 1)
      .toRight(RuntimeError("scripted", "the script has no more replies"))
      .map(text => LmResponse(outputs = Vector(LmOutput(text = text))))

/** A model with native reasoning: each reply has the reasoning `thought`, apart from its text. */
final class ThinkingLm(thought: String, replies: String*) extends LanguageModel:
  override val id: String                 = "thinking"
  override val mode: LmMode               = LmMode.Chat
  override def supportsReasoning: Boolean = true
  val requests: ArrayBuffer[LmRequest]    = ArrayBuffer.empty

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    requests += request: Unit
    replies
      .lift(requests.size - 1)
      .toRight(RuntimeError("thinking", "the script has no more replies"))
      .map(text => LmResponse(outputs = Vector(LmOutput(text = text, reasoning = thought))))

/** A model whose reply is a function of the text of its request, not of the order of the calls. So calls at the same
  * time get the right replies (6). `pause` gives the milliseconds that a call waits before its reply, and `most` counts
  * the most calls that ran at the same time. A `Left` reply is a failed call.
  */
final class RequestLm(reply: String => Either[String, String], pause: String => Long = _ => 0L) extends LanguageModel:
  override val id: String                        = "request"
  override val mode: LmMode                      = LmMode.Chat
  val requests: ConcurrentLinkedQueue[LmRequest] = ConcurrentLinkedQueue()
  val most: AtomicInteger                        = AtomicInteger(0)
  private val running                            = AtomicInteger(0)

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    requests.add(request): Unit
    most.accumulateAndGet(running.incrementAndGet(), math.max): Unit
    try
      val text = request.messages.flatMap(_.text).mkString("\n")
      Thread.sleep(pause(text))
      reply(text).left.map(RuntimeError("request", _)).map(t => LmResponse(outputs = Vector(LmOutput(text = t))))
    finally running.decrementAndGet(): Unit

/** A model that streams (CLI_SCREEN.md, 13). The reply of a call is `reply` of the text of its request, as in
  * `RequestLm`. A stream gives the native reasoning `thought` first in one chunk, if any, then the reply in parts of
  * `size` characters, and the last part has the finish reason. `gate(i)` runs before the chunk `i` of a stream, from 0,
  * so a law can see the state after each chunk or hold a stream. A `call` gives the whole reply, and `calls` counts the
  * calls and the streams.
  */
final class StreamLm(
    reply  : String => Either[String, String],
    thought: String      = "",
    size   : Int         = 7,
    gate   : Int => Unit = _ => ()
) extends StreamingLanguageModel:
  override val id: String                 = "streaming"
  override val mode: LmMode               = LmMode.Chat
  override def supportsReasoning: Boolean = thought.nonEmpty
  val calls: AtomicInteger                = AtomicInteger(0)
  val streams: AtomicInteger              = AtomicInteger(0)

  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    calls.incrementAndGet(): Unit
    reply(StreamLm.textOf(request)).left.map(RuntimeError("streaming", _))
      .map(text => LmResponse(outputs = Vector(LmOutput(text = text, reasoning = thought))))

  override def stream(request: LmRequest)(using RuntimeContext): Iterator[LmChunk] =
    streams.incrementAndGet(): Unit
    reply(StreamLm.textOf(request)) match
      case Left(error) => Iterator.single(LmChunk.failure(RuntimeError("streaming", error)))
      case Right(text) =>
        val parts  = text.grouped(size).toVector
        val last   = parts.size - 1
        val chunks = Option.when(thought.nonEmpty)(LmChunk(reasoning = thought)).toVector ++
          (if parts.isEmpty then Vector(LmChunk(finishReason = Some("stop")))
           else
             parts.zipWithIndex.map((part, i) => LmChunk(text = part, finishReason = Option.when(i == last)("stop"))))
        chunks.iterator.zipWithIndex.map { (chunk, i) =>
          gate(i)
          chunk
        }

object StreamLm:

  /** The text of the messages of `request`. */
  def textOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

/** The helpers of the tests that call a model. */
object Models:

  /** The signature of the task of the tests. */
  val signature: Signature[Question, Answer] = Signature.derived[Question, Answer]("qa", "Answer the question.")

  /** A reply of the policy leaf in the format of the chat adapter, with the reasoning field of a model with no native
    * reasoning. A reply with no code has no code field.
    */
  def policy(text: String, code: Option[String], reasoning: String = "a fake thought"): String =
    val field = code.fold("")(c => s"[[ ## code ## ]]\n$c\n\n")
    s"[[ ## reasoning ## ]]\n$reasoning\n\n[[ ## text ## ]]\n$text\n\n$field[[ ## completed ## ]]"

  /** A reply of the policy leaf of a model with native reasoning: the prompt has no reasoning field (9). */
  def thinking(text: String, code: Option[String]): String =
    val field = code.fold("")(c => s"[[ ## code ## ]]\n$c\n\n")
    s"[[ ## text ## ]]\n$text\n\n$field[[ ## completed ## ]]"

  /** A reply of the finisher leaf for `Answer`. */
  def answer(value: String): String = s"[[ ## answer ## ]]\n$value\n\n[[ ## completed ## ]]"

  /** Runs `body` with `model` and the chat adapter. */
  def withModel[A](model: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }
