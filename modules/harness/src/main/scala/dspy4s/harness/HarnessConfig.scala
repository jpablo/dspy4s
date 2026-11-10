package dspy4s.harness

import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.Logged
import dspy4s.harness.sandbox.{Backend, HostList}
import zio.blocks.schema.Schema

import java.nio.file.Path
import scala.concurrent.duration.*

/** What a harness needs that an optimizer must not change (CODE_HARNESS_DESIGN.md, 9).
  *
  * @param workspace
  *   the folder of the task. Each call works in a copy (9), and `None` gives an empty folder.
  * @param home
  *   the home folder, for the secret paths of the layout (5.4)
  * @param journals
  *   gets the journal of each call at its end. The owner of a rollout is an optimizer, which reads the journal (8.6).
  */
final case class HarnessEnv(
    workspace: Option[Path],
    home     : Path,
    hosts    : HostList,
    backend  : Option[Backend],
    secrets  : Secrets,
    mode     : Mode                   = HarnessEnv.taskMode,
    journals : Vector[Logged] => Unit = _ => ()
)

object HarnessEnv:

  /** The mode of phase 4: `Files^`, `Shell^` and `Store^`, and nothing else. Phase 5 adds `Llm^` and `Children^`, and
    * then a rollout has the mode of 8.1.
    */
  val taskMode: Mode = Mode(Access.Full, Access.Full, Access.None, Access.None, Access.None, Access.None, Access.Full)

/** The limits of a call, which an optimizer must not change (9). `budget` counts the characters of a projection. */
final case class HarnessLimits(
    steps         : Int            = 10,
    budget        : Int            = 40_000,
    cellTimeout   : FiniteDuration = 30.seconds,
    execTimeout   : Int            = 600,
    maxObservation: Int            = 4_000
) derives CanEqual

/** The input of the finisher leaf (9): the task, and the steps of the call as text. */
final case class FinishInput(task: String, transcript: String) derives Schema
