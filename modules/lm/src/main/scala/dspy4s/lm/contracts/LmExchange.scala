package dspy4s.lm.contracts

import dspy4s.core.contracts.{ExchangeSinkRef, RuntimeContext}

/** One HTTP request of a model call, as it goes to the transport (docs/code-harness/OBSERVABILITY.md, 4.1).
  *
  * @param url
  *   the URL of the request
  * @param headers
  *   the headers of the request, with their values. They hold the API key: a sink masks it before it keeps a header.
  * @param body
  *   the exact text of the body
  * @param stream
  *   true for a stream request (`streamSse`), false for a plain request (`sendJson`)
  */
final case class ExchangeRequest(url: String, headers: Map[String, String], body: String, stream: Boolean)
    derives CanEqual

/** How an exchange ended. The values are facts of the transport, not of the reply. */
enum ExchangeEnd derives CanEqual:

  /** The reply came: the body of a plain reply, or each line of a stream to its end. */
  case Done

  /** The consumer closed the lines of a stream before their end. The OpenAI client closes the lines after the line
    * `data: [DONE]`, so a complete stream of the client ends with `Closed`, and its last line is `data: [DONE]`.
    */
  case Closed

  /** The transport failed: before the reply, or during the lines of a stream. `message` is the code and the message of
    * the error, or the class and the message of an exception.
    */
  case Failed(message: String)

/** The record of one exchange. The transport calls `replied` once when the status and the headers come, then `body` for
  * a plain reply or `line` for each line of a stream that the consumer reads, then `end` once. A failure before the
  * reply gives only `end`.
  *
  * The transport makes the calls of one record one at a time, but they can come from two threads: a consumer can close
  * a stream on another thread than the reads. After `end`, the record gets no call.
  */
trait ExchangeRecord:
  /** The status and the headers of the reply. */
  def replied(status: Int, headers: Map[String, String]): Unit

  /** The body of a plain reply. */
  def body(text: String): Unit

  /** One raw line of a stream, with its `data:` prefix, in the order of the reads. */
  def line(text: String): Unit

  /** The end of the exchange. */
  def end(how: ExchangeEnd): Unit

/** The record of the HTTP exchanges of the model calls (docs/code-harness/OBSERVABILITY.md, 4.1). A provider reads it
  * from the context of each model call (`RuntimeServices.exchangeSink`), and gives it each request and each reply.
  *
  * The transport calls `start` on the thread of the model call, before the request goes. So a sink can read the scope
  * of that thread, for example the predictor that runs (`RuntimeEnvironment.activePredictor`): `Module` sets the
  * predictor only in the scope of the thread, not in the context of the call.
  *
  * A sink must never change a call. When a call of the sink throws, the transport drops the exception, and the model
  * call goes on with no change.
  */
trait ExchangeSink extends ExchangeSinkRef:
  /** The record of a new exchange of `request`. */
  def start(request: ExchangeRequest)(using RuntimeContext): ExchangeRecord

object ExchangeSink:
  /** The sink of `context`, if it has one. */
  def of(context: RuntimeContext): Option[ExchangeSink] =
    context.exchangeSink match
      case Some(sink: ExchangeSink) => Some(sink)
      case _                        => None
