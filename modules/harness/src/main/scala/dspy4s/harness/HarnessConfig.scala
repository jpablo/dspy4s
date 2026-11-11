package dspy4s.harness

import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.{Logged, SessionId}
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
  *   gets the id and the journal of each call at its end. The owner of a rollout is an optimizer, which reads the
  *   journal (8.6). It also gets the id and the journal of each child of `rlm` at the end of the child, before the
  *   journal of its parent (8.2). Each session of a call has its own id, and the task of a child holds the id of its
  *   parent in `Source.Parent` (phase 7). The children of `rlmBatched` end on several threads, so this function can run
  *   on several threads at the same time.
  */
final case class HarnessEnv(
    workspace: Option[Path],
    home     : Path,
    hosts    : HostList,
    backend  : Option[Backend],
    secrets  : Secrets,
    mode     : Mode                                = HarnessEnv.taskMode,
    journals : (SessionId, Vector[Logged]) => Unit = (_, _) => ()
)

object HarnessEnv:

  /** The mode of a program call: `Files^`, `Shell^`, `Llm^`, `Children^` and `Store^`, as 8.1 gives a rollout. Phase 4
    * had no `Llm^` and no `Children^`, and phase 5 adds them. The bare `Sessions` comes with the sessions of phase 9.
    */
  val taskMode: Mode = Mode(Access.Full, Access.Full, Access.None, Access.Full, Access.None, Access.Full, Access.Full)

/** The limits of a call, which an optimizer must not change (9). `budget` counts the characters of a projection. After
  * `cellLimit` cells, the REPL is full, and the harness starts a new REPL (6).
  *
  * @param modelCalls
  *   the model calls of one program call, for the whole tree (8.2): each policy call of the call and of each child, and
  *   each query. The finisher does not count.
  * @param depth
  *   the depth limit of `rlm` (8.2). The top call has the depth 0, and a child has the depth of its parent and 1. A
  *   REPL at the limit has no `Children^`.
  * @param batch
  *   the most queries or children that one batch call runs at the same time (6)
  * @param summaryCalls
  *   the calls of the leaves `compress` and `merge` of the memory tree in one turn (7.1), apart from `modelCalls`.
  *   After them, a node gets the line of its source, and a later turn builds it again.
  */
final case class HarnessLimits(
    steps         : Int            = 10,
    budget        : Int            = 40_000,
    cellTimeout   : FiniteDuration = 30.seconds,
    execTimeout   : Int            = 600,
    maxObservation: Int            = 4_000,
    cellLimit     : Int            = 300,
    modelCalls    : Int            = 60,
    depth         : Int            = 2,
    batch         : Int            = 4,
    summaryCalls  : Int            = 64
) derives CanEqual

/** The input of the finisher leaf (9): the task, and the steps of the call as text. */
final case class FinishInput(task: String, transcript: String) derives Schema
