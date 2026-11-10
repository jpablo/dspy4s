package dspy4s.harness

import dspy4s.harness.guest.{Data, Files, Store, Turn}
import dspy4s.harness.repl.Bindings
import dspy4s.harness.sandbox.Profile
import zio.blocks.schema.json.Json

import java.nio.file.Path
import scala.annotation.nowarn

/** The gate of the guest API calls of a cell: `recorded(h)` of CODE_HARNESS_DESIGN.md, 5.3. It commits `EffectStarted`
  * before each call and `EffectDone` after it, with the value as `Json` for a replay and as text for a reader (4.1). A
  * call that the journal refuses does not run, for example after a stop (4.7, Stop), and the cell gets an exception.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class Recorder(journal: Journal):

  /** Starts the calls of the cell whose `Acted` has the `seq` `cell`. Its calls get the ids `EffectId(cell, 0)`,
    * `EffectId(cell, 1)` and so on.
    */
  def startCell(cell: Long): Unit = ???

  /** One call: the intent, the call, and the outcome. A call that throws gets `EffectResult.Error`, and the exception
    * goes on to the cell.
    */
  def call[A](op: String, args: Json)(run: => A)(using Data[A]): A = ???

/** `Files.local(root)` of 5.3: the real files under `root`, with the rules of `profile` (5.4). A write is only under a
  * writable root and not in a read-only part, such as `.git`. A hidden path cannot be read or written, and nor can a
  * file with a secret name of `names` that exists, as for a command. A new file with a secret name can be written, and
  * after that it is hidden. A path outside `root` is an error, also through a symbolic link.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class LocalFiles(root: Path, profile: Profile, names: Set[String]) extends Files:
  def read(path : String): String                                 = ???
  def write(path: String, content: String): Unit                  = ???
  def edit(path : String, oldText: String, newText: String): Unit = ???

/** `Store.snapshot(state)` of 5.3: the store of the session at the start of a cell, and a buffer of its writes.
  * `CellResult.writes` holds the buffer, so the store changes only when the cell completes (4.7, Store). The REPL binds
  * one handler for its life, so `begin` takes the snapshot of each cell.
  */
final class SnapshotStore extends Store:
  def begin(base        : Map[String, Json]): Unit = ???
  def writes: Map[String, Json]                    = ???
  def keys: Vector[String]                         = ???
  def load[A: Data](key : String): Option[A]       = ???
  def store[A: Data](key: String, value: A): Unit  = ???

/** The `Turn` of a session (5.1): the time of `clock`, and the values of the input fields from `SessionState.inputs`.
  * `recorded` records each `now`, so a replay gets the same time.
  */
@nowarn("msg=unused") // the skeleton: the proof uses the parameters
final class JournalTurn(journal: Journal, clock: () => Long) extends Turn:
  def now(): Long                       = ???
  def input(name: String): Option[Json] = ???

/** `recorded(h)` of 5.3 for each handler of `bindings`: each call of the guest API goes through `recorder`. `input`
  * reads the journal, so it is not an effect.
  */
def recorded(bindings: Bindings, recorder: Recorder): Bindings = ???
