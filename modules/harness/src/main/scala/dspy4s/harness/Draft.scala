package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.{LanguageModel, StreamSink, StreamingLanguageModel}
import dspy4s.streaming.StreamSession

/** The text that the model writes now in one model call of the policy, for a client (CLI_SCREEN.md, 13): the native
  * reasoning, and the reply as it came, with no parse. A draft is not state: it never goes into the journal, and a
  * crash loses it.
  */
final case class Draft(reasoning: String, reply: String) derives CanEqual:

  /** The reasoning of the step so far: the native reasoning, else the section of `reasoning` of the reply. */
  def thought: String = if reasoning.strip.nonEmpty then reasoning else PolicyAdapter.reasoningSoFar(reply)

  /** The text of the step so far, with the rules of the chat format and of `PolicyAdapter.values`:
    *
    *   - The reply ends at the marker `[[ ## completed ## ]]`.
    *   - A last line with no line end waits when it can still become a marker or a fence: its first character that is
    *     not white space is `[` or a backquote.
    *   - With a marker: the section of `text`, else the reply before the first marker. A section of another field, for
    *     example `code`, does not show.
    *   - With no marker: the reply, with no ```` ```scala ```` fence. A fence that does not end hides the rest of the
    *     reply. The parts around a fence come with an empty line between them.
    *
    * Each text has no white space at its two ends. For a whole reply that the policy parses, the result is the text of
    * the step.
    */
  def text: String = PolicyAdapter.textSoFar(reply)

object Draft:

  /** The draft of a model call that has no token yet. */
  val empty: Draft = Draft("", "")

/** The drafts of the calls to the policy of one activation (CLI_SCREEN.md, 13). `HarnessEffects.callPolicy` writes
  * them, and a client reads `current` with no wait for the call.
  *
  * Each call to the policy has a number from `begin`, and `end` closes it. A model call of the call starts a new draft
  * (`restart`), and its tokens go to it (`add`). A number that is not the number of the open call changes nothing. So a
  * late token of a call that a stop cut shows nowhere, also when its thread goes on.
  */
final class Drafts:

  private var number  = 0L
  private var open    = false
  private var started = false
  private val thought = StringBuilder()
  private val written = StringBuilder()

  /** A new call to the policy starts. It gives the number of the call. No draft shows until its first model call. */
  def begin(): Long =
    synchronized {
      number += 1
      open = true
      started = false
      clear()
      number
    }

  /** A model call of the call `call` starts: an empty draft, in place of the draft of an earlier model call. */
  def restart(call: Long): Unit =
    synchronized {
      if live(call) then
        started = true
        clear()
    }

  /** Adds `reasoning` and `reply` to the draft of the call `call`. */
  def add(call: Long, reasoning: String, reply: String): Unit =
    synchronized {
      if live(call) && started then
        thought.append(reasoning): Unit
        written.append(reply): Unit
    }

  /** The call `call` ended. Its draft goes away. */
  def end(call: Long): Unit =
    synchronized {
      if live(call) then
        open = false
        started = false
        clear()
    }

  /** The draft of the open call, or none when no call is open or the open call has no model call yet. */
  def current: Option[Draft] = synchronized(Option.when(open && started)(Draft(thought.toString, written.toString)))

  /** True when `call` is the call that is open now. */
  private def live(call: Long): Boolean = open && call == number

  private def clear(): Unit =
    thought.clear()
    written.clear()

/** The stream of the call `call` to the policy (CLI_SCREEN.md, 13). Each model call of the policy gets a preview
  * session of the streaming module, so the tokens go to `drafts` and do not count as output that a person saw. The
  * native reasoning goes to the reasoning of the draft, and the text of the reply goes to its reply. A model that does
  * not stream stays as it is, and its draft stays empty.
  */
private[harness] final class PolicyStream(drafts: Drafts, call: Long) extends StreamSink:

  override def wrap(model: LanguageModel): LanguageModel =
    model match
      case streaming: StreamingLanguageModel =>
        drafts.restart(call)
        StreamSession.preview { token =>
          if token.fieldName.isEmpty then drafts.add(call, "", token.chunk) else drafts.add(call, token.chunk, "")
        }.wrap(streaming)
      case other => other

private[harness] object PolicyStream:

  /** `context` with the stream of the call `call` to the policy. */
  def around(context: RuntimeContext, drafts: Drafts, call: Long): RuntimeContext =
    context.withServices(context.services.copy(streamSink = Some(PolicyStream(drafts, call))))
