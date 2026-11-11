package dspy4s.harness.guest

// A cell runs with capture checking, and the modes are capabilities: the bare type reads, and `T^` also writes (5.1).
import language.experimental.captureChecking

import caps.assumeSafe

/** A capability with a read-only view. The bare type can only read, and `T^` can also change something. */
@assumeSafe trait Cap extends caps.Stateful, caps.ExclusiveCapability

/** The files of the workspace. The bare `Files` can only read, and `Files^` can also write. */
@assumeSafe trait Files extends Cap:
  def read(path: String): String
  update def write(path: String, content: String): Unit

  /** Replaces the one place of `oldText` in the file with `newText`. A file where `oldText` occurs no time, or more
    * than one time, is an error, and the file does not change.
    *
    * A proposal for the review: section 5.1 names `edit` and gives no signature.
    */
  update def edit(path: String, oldText: String, newText: String): Unit

/** Commands in the workspace. A session has this capability, or it has none. */
@assumeSafe trait Shell extends caps.ExclusiveCapability:
  def exec(command: String)(using Files^): String

  /** Starts a job (6). With `report`, the end of the job sends a message to the session. */
  def spawn(command: String, report: Boolean = false)(using Files^): JobId
  def jobOutput(job: JobId): String

  /** Blocks until the job ends or the time is up. */
  def waitJob(job: JobId, maxSeconds: Int): JobStatus
  def stopJob(job: JobId): Unit

/** An LM call from a cell. A batch is one call, and the host runs its prompts at the same time (6). */
@assumeSafe trait Llm extends caps.ExclusiveCapability:
  def query(prompt: String): String
  def queryBatched(prompts: Vector[String]): Vector[String]

/** The network (5.2, 5.4). Only a mode with `Network^` gives it, and the proxy of the sandbox applies the host list.
  *
  * A proposal for the review: section 5.1 names `httpGet` and gives no signature or trait.
  */
@assumeSafe trait Network extends caps.ExclusiveCapability:
  /** The body of the answer to a GET request, as text. */
  def get(url: String): String

/** The session state that survives a restart (6). The bare `Store` can only load, and `Store^` can also store. */
@assumeSafe trait Store extends Cap:
  def keys: Vector[String]
  def load[A: Data](key: String): Option[A]
  update def store[A: Data](key: String, value: A): Unit

/** Other sessions (8.2). The bare `Sessions` can only look, and `Sessions^` can also start work and send messages
  * (5.2). The folder of the journals is closed to `Files`, so `zoom` and `observe` are the only ways to read another
  * session (5.4).
  */
@assumeSafe trait Sessions extends Cap:
  /** The cards of the sessions. The catalog gives the deterministic part (10.2). */
  def list(): Vector[SessionCard]

  /** A `Send` to that session. It returns after the receiver commits (8.6). */
  update def tell(session: SessionRef, message: String): Unit

  /** Starts a session in the background. Its report comes back as an `Input` that starts a turn (8.6). */
  update def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef

  /** A read-only `rlm` with the journal of that session as an input. It changes nothing in that session. */
  def observe(session: SessionRef, question: String)(using Llm^): String

  /** A part of the journal of a session. `ref` names the part, for example a cell. The form of `ref` is open. */
  def zoom(session: SessionRef, ref: String): String

/** A child inside the cell (5.1). A closed session has this capability, and it has only the bare `Sessions`. */
@assumeSafe trait Children extends Cap:
  update def rlm[A: Data](task: String, inputs: Map[String, String]): A
  update def rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])]): Vector[A]

/** What a cell gets from the host with no capability (5.1): the clock and the input values of the session. The
  * preamble of each mode binds it as a given, so a cell cannot make one. `now` is a guest API call, so the journal
  * records its value. An input is not an effect, because the journal already holds the value.
  */
@assumeSafe trait Turn:
  def now(): Long

  /** The `Json` of the input field `name`, or `None` for an unknown name. */
  def input(name: String): Option[zio.blocks.schema.json.Json]

  /** A part of the memory of this session (CODE_HARNESS_DESIGN.md, 7.1): the message of a reference of level 0, or
    * the lines of the two halves of a summary line, each with its reference. A session with no memory tree, or an
    * unknown reference, gives an error.
    */
  def zoom(ref: String): String

/** A command that runs after its cell ends (6). The id is plain data that holds the `EffectId` of its `spawn`, so it
  * stays valid in a later cell, and the store can hold it.
  */
@assumeSafe final case class JobId(cell: Long, n: Int) derives Data
@assumeSafe object JobId

/** What `waitJob` gives (6). */
@assumeSafe enum JobStatus derives Data:
  case Running
  case Exited(code: Int)
  case Stopped
  case Lost

@assumeSafe object JobStatus

/** A guard needs a person for this call (5.5). The handler throws it before the call runs. Safe mode rejects its
  * constructor, so a cell cannot make one.
  */
final case class NeedsApproval(op: String, argument: String)
    extends RuntimeException(s"$op($argument) needs an approval. End the turn and ask the person.")

/** The functions that a cell calls (5.1, 8.2). Each one takes its capability as a given, so the mode limits what
  * compiles.
  */
@assumeSafe object api:
  def println(value: Any): Unit = scala.Console.println(value)

  /** Milliseconds since the epoch. A guest API call, so the journal records its value (5.1). */
  def now()(using t: Turn): Long = t.now()

  /** The value of an input field of this session (5.1). It needs no capability, because it reads only what the
    * session got. A value that does not fit the type throws a `DataError` with its path, and the cell fails.
    */
  def input[A](name: String)(using t: Turn, d: Data[A]): A =
    t.input(name) match
      case Some(value) => d.decode(value, name).fold(error => throw error, identity)
      case None        => throw IllegalArgumentException(s"this session has no input with the name $name")

  def read(path: String)(using f: Files): String                                  = f.read(path)
  def write(path: String, content: String)(using f: Files^): Unit                 = f.write(path, content)
  def edit(path: String, oldText: String, newText: String)(using f: Files^): Unit = f.edit(path, oldText, newText)
  def httpGet(url: String)(using n: Network^): String                              = n.get(url)
  def exec(command: String)(using s: Shell^, f: Files^): String                    = s.exec(command)
  def spawn(command: String, report: Boolean = false)(using s: Shell^, f: Files^): JobId = s.spawn(command, report)
  def jobOutput(job: JobId)(using s: Shell^): String                               = s.jobOutput(job)
  def waitJob(job: JobId, maxSeconds: Int)(using s: Shell^): JobStatus             = s.waitJob(job, maxSeconds)
  def stopJob(job: JobId)(using s: Shell^): Unit                                   = s.stopJob(job)
  def llmQuery(prompt: String)(using l: Llm^): String                              = l.query(prompt)
  def llmQueryBatched(prompts: Vector[String])(using l: Llm^): Vector[String]      = l.queryBatched(prompts)
  def keys()(using s: Store): Vector[String]                                       = s.keys
  def load[A: Data](key: String)(using s: Store): Option[A]                        = s.load[A](key)
  def store[A: Data](key: String, value: A)(using s: Store^): Unit                 = s.store(key, value)
  def sessions()(using s: Sessions): Vector[SessionCard]                           = s.list()
  def tell(session: SessionRef, message: String)(using s: Sessions^): Unit         = s.tell(session, message)
  def observe(session: SessionRef, question: String)(using s: Sessions, l: Llm^): String = s.observe(session, question)
  def zoom(session: SessionRef, ref: String)(using s: Sessions): String            = s.zoom(session, ref)

  /** A part of the memory of this session (7.1). `ref` is a reference of the memory, for example `3.5`: the message of
    * a reference `0.i`, or the two lines under a summary line. It needs no capability, because it reads only this
    * session.
    */
  def zoom(ref: String)(using t: Turn): String = t.zoom(ref)

  def start(goal: String, scope: Scope, strategy: StrategyName)(using s: Sessions^): SessionRef =
    s.start(goal, scope, strategy)

  /** Runs a child with `task`, and gives the value of its `submit`. Each input is a `String` val of the child. The
    * prompt of the child shows only the start of an input, so give a long text as an input, not in the task.
    */
  def rlm[A: Data](task: String, inputs: Map[String, String] = Map.empty)(using c: Children^): A =
    c.rlm[A](task, inputs)

  /** Runs one child for each task and its inputs, some at the same time, and gives the values in the order of the
    * tasks. The children share the files of this session, so give each child its own file to write. Example:
    * `rlmBatched[Int](parts.map(part => ("Count the errors in text.", Map("text" -> part))))`.
    */
  def rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])])(using c: Children^): Vector[A] =
    c.rlmBatched[A](tasks)
