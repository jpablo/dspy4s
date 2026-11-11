package dspy4s.harness.host

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.{Budget, HarnessLimits, PolicyInput, PolicyStep, Secrets, SubAnswer, SubQuery}
import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.{EffectId, Logged, SessionId}
import dspy4s.programs.strategies.Predict

import java.nio.file.Path
import scala.annotation.nowarn

/** The call that a probe answers: the `observe` effect `effect` of the session `caller` (8.7). */
final case class ProbeLink(caller: SessionId, effect: EffectId) derives CanEqual

/** A probe (CODE_HARNESS.md, 7.4; CODE_HARNESS_DESIGN.md, 8.2 and 8.7): the answer to a question about the journal of a
  * session. It changes nothing in that session.
  */
trait Probe:

  /** The answer to `question` about `journal`, the journal of `session`, for the call `link`. Each model call of the
    * probe takes one call from `budget`, and a spent budget gives an error.
    */
  def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
      RuntimeContext
  ): Either[String, String]

object Probe:

  /** A probe that gives an error: a host with no model. */
  val none: Probe = new Probe:
    def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
        RuntimeContext
    ): Either[String, String] = Left("this host has no probe")

/** The probe of a work runtime (8.7): a program call of a `Harness` with the leaves `policy` and `subQuery`. Its input
  * is the text of the journal (`journalText`) and the question, and its output is the text of `submit`. Its mode is
  * `mode`: only `Llm^`, so the code of the probe reads its input and asks the LM, and it can neither read files nor run
  * commands. The harness of the probe has a limit of model calls of the rest of `budget`, at most `limits.modelCalls`,
  * and after the call `budget` spends the calls that the probe made. `home` is the home of the secret paths, and
  * `secrets` the mask of its journal, which goes to `pathOf(root, link)`.
  */
@nowarn("msg=unused")
final class HarnessProbe(
    root    : Path,
    home    : Path,
    secrets : Secrets,
    limits  : HarnessLimits,
    policy  : Predict[PolicyInput, PolicyStep],
    subQuery: Predict[SubQuery, SubAnswer]
) extends Probe:

  def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
      RuntimeContext
  ): Either[String, String] = ???

object HarnessProbe:

  /** The mode of a probe: only `Llm^`. */
  val mode: Mode = Mode(Access.None, Access.None, Access.None, Access.Full, Access.None, Access.None, Access.None)

  /** The text of a journal for a probe: one line for each entry, with its `seq`, its source and its entry. The mask of
    * the host already holds the secrets of the journal.
    */
  def journalText(journal: Vector[Logged]): String = ???

  /** The file of the journal of the probe of `link`: `<root>/probes/<caller>.<cell>.<n>.db`, so `list` does not show
    * it, and its name links it to the `observe` call.
    */
  def pathOf(root: Path, link: ProbeLink): Path = ???
