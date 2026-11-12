package dspy4s.harness.guest

// A cell runs with capture checking, and the modes are capabilities: the bare type reads, and `T^` also writes.
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
    */
  update def edit(path: String, oldText: String, newText: String): Unit

/** Commands in the workspace. A session has this capability, or it has none. */
@assumeSafe trait Shell extends caps.ExclusiveCapability:
  def exec(command: String)(using Files^): String

  /** Starts a job. With `report`, the end of the job sends a message to the session. */
  def spawn(command: String, report: Boolean = false)(using Files^): JobId
  def jobOutput(job: JobId): String

  /** Blocks until the job ends or the time is up. */
  def waitJob(job: JobId, maxSeconds: Int): JobStatus
  def stopJob(job: JobId): Unit

/** An LM call from a cell. A batch is one call, and the host runs its prompts at the same time. */
@assumeSafe trait Llm extends caps.ExclusiveCapability:
  def query(prompt: String): String
  def queryBatched(prompts: Vector[String]): Vector[String]

/** The network. Only a mode with `Network^` gives it, and the proxy of the sandbox applies the host list. */
@assumeSafe trait Network extends caps.ExclusiveCapability:
  /** The body of the answer to a GET request, as text. */
  def get(url: String): String

/** The session state that survives a restart. The bare `Store` can only load, and `Store^` can also store. */
@assumeSafe trait Store extends Cap:
  def keys: Vector[String]
  def load[A: Data](key: String): Option[A]
  update def store[A: Data](key: String, value: A): Unit

/** Other sessions. The bare `Sessions` can only look, and `Sessions^` can also start work and send messages. The
  * folder of the journals is closed to `Files`, so `zoom` and `observe` are the only ways to read another session.
  */
@assumeSafe trait Sessions extends Cap:
  /** The cards of the sessions of the host. */
  def list(): Vector[SessionCard]

  /** Sends `message` to that session. It returns after the session keeps the message. */
  update def tell(session: SessionRef, message: String): Unit

  /** Starts a session in the background. Its report comes back as a message that starts a turn. */
  update def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef

  /** A read-only `rlm` with the journal of that session as an input. It changes nothing in that session. */
  def observe(session: SessionRef, question: String)(using Llm^): String

  /** A part of the journal of a session. `ref` names the part, for example a cell. The form of `ref` is open. */
  def zoom(session: SessionRef, ref: String): String

/** A child inside the cell. A closed session has this capability, and it can only look at the other sessions. */
@assumeSafe trait Children extends Cap:
  update def rlm[A: Data](task: String, inputs: Map[String, String]): A
  update def rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])]): Vector[A]

/** What a cell gets from the host with no capability: the clock and the input values of the session. The
  * preamble of each mode binds it as a given, so a cell cannot make one. `now` is a guest API call, so the journal
  * records its value. An input is not an effect, because the journal already holds the value.
  */
@assumeSafe trait Turn:
  def now(): Long

  /** The `Json` of the input field `name`, or `None` for an unknown name. */
  def input(name: String): Option[zio.blocks.schema.json.Json]

  /** A part of the memory of this session: the message of a reference of level 0, or
    * the lines of the two halves of a summary line, each with its reference. A session with no memory tree, or an
    * unknown reference, gives an error.
    */
  def zoom(ref: String): String

/** A command that continues after its cell ends. The id is plain data, so it stays valid in a later cell, and the
  * store can hold it.
  */
@assumeSafe final case class JobId(cell: Long, n: Int) derives Data
@assumeSafe object JobId

/** What `waitJob` gives. */
@assumeSafe enum JobStatus derives Data:
  case Running
  case Exited(code: Int)
  case Stopped
  case Lost

@assumeSafe object JobStatus

/** A guard needs a person for this call. The handler throws it before the call runs. Safe mode rejects its
  * constructor, so a cell cannot make one.
  */
final case class NeedsApproval(op: String, argument: String)
    extends RuntimeException(s"$op($argument) needs an approval. End the turn and ask the person.")

/** The functions that a cell calls. Each one takes its capability as a given, so the mode limits what compiles. */
@assumeSafe object api:
  /** Prints `value` to the output of the cell. The next step sees this output. A long output is cut, so print only
    * what the next step needs.
    */
  def println(value: Any): Unit = scala.Console.println(value)

  /** Milliseconds since the epoch. A guest API call, so the journal records its value. */
  def now()(using t: Turn): Long = t.now()

  /** The value of an input field of this session. It needs no capability, because it reads only what the
    * session got. A value that does not fit the type throws a `DataError` with its path, and the cell fails.
    */
  def input[A](name: String)(using t: Turn, d: Data[A]): A =
    t.input(name) match
      case Some(value) => d.decode(value, name).fold(error => throw error, identity)
      case None        => throw IllegalArgumentException(s"this session has no input with the name $name")

  /** The text of the file at `path`, in UTF-8. A path is relative to the folder of the session, or an absolute path in
    * it. A missing file, a path outside the folder, and a closed file such as `.env` give an error.
    */
  def read(path: String)(using f: Files): String                                  = f.read(path)

  /** Writes `content` to the file at `path`, in UTF-8. The text replaces the whole file, and the call makes the folders
    * that the path needs. A path outside the folder, or in a read-only part such as `.git`, gives an error.
    */
  def write(path: String, content: String)(using f: Files^): Unit                 = f.write(path, content)

  /** Replaces the one place of `oldText` in the file at `path` with `newText`, for a small change of a large file. No
    * place, or more than one place, of `oldText` gives an error, and the file does not change.
    */
  def edit(path: String, oldText: String, newText: String)(using f: Files^): Unit = f.edit(path, oldText, newText)

  /** The body of the answer to a GET request to `url`, as text. The host list of the session can refuse the host. */
  def httpGet(url: String)(using n: Network^): String                              = n.get(url)

  /** Runs `command` with `/bin/sh -c` in the folder of the session, waits for its end, and gives its standard output
    * and its standard error as one text. The command gets no standard input. A last line tells when the exit code is
    * not 0, when the output was too long and was cut, or when the command ran too long and stopped. Use `spawn` for a
    * long command.
    */
  def exec(command: String)(using s: Shell^, f: Files^): String                    = s.exec(command)

  /** Starts `command` as a job, and gives its id at once. The job continues after the cell ends. With `report`, the end
    * of the job sends a message to the session. Use the id with `jobOutput`, `waitJob` and `stopJob`.
    */
  def spawn(command: String, report: Boolean = false)(using s: Shell^, f: Files^): JobId = s.spawn(command, report)

  /** The output of `job` until now: its standard output and its standard error as one text. A very long output keeps
    * only its last part.
    */
  def jobOutput(job: JobId)(using s: Shell^): String                               = s.jobOutput(job)

  /** Waits until `job` ends or `maxSeconds` pass, and gives its status. The status is `Running` when the time ends
    * first.
    */
  def waitJob(job: JobId, maxSeconds: Int)(using s: Shell^): JobStatus             = s.waitJob(job, maxSeconds)

  /** Stops `job`. A job that this call stops gets the status `Stopped`. */
  def stopJob(job: JobId)(using s: Shell^): Unit                                   = s.stopJob(job)

  /** The answer of a model to `prompt`. The model sees only the prompt and nothing of this session, so put each fact
    * that it needs into the prompt. Each query uses one model call of the budget.
    */
  def llmQuery(prompt: String)(using l: Llm^): String                              = l.query(prompt)

  /** The answers to `prompts`, in the order of the prompts. The host runs some queries at the same time, and each query
    * uses one model call of the budget. When a query fails, the call fails, and the error gives its index.
    */
  def llmQueryBatched(prompts: Vector[String])(using l: Llm^): Vector[String]      = l.queryBatched(prompts)

  /** The keys of the store of this session, in order. */
  def keys()(using s: Store): Vector[String]                                       = s.keys

  /** The value of `key` in the store, or `None` when the store has no such key. A value that does not fit the type `A`
    * gives an error.
    */
  def load[A: Data](key: String)(using s: Store): Option[A]                        = s.load[A](key)

  /** Keeps `value` under `key` in the store of this session, which survives a restart. The value goes into the store
    * only when the cell completes, so a failed cell changes nothing in the store.
    */
  def store[A: Data](key: String, value: A)(using s: Store^): Unit                 = s.store(key, value)

  /** The cards of the sessions of the host. */
  def sessions()(using s: Sessions): Vector[SessionCard]                           = s.list()

  /** Sends `message` to `session`. The call returns after the session keeps the message. */
  def tell(session: SessionRef, message: String)(using s: Sessions^): Unit         = s.tell(session, message)

  /** The answer to `question` about `session`. A read-only child reads the journal of that session, and it changes
    * nothing there.
    */
  def observe(session: SessionRef, question: String)(using s: Sessions, l: Llm^): String = s.observe(session, question)

  /** A part of the journal of `session`. `ref` names the part, for example a cell. */
  def zoom(session: SessionRef, ref: String)(using s: Sessions): String            = s.zoom(session, ref)

  /** A part of the memory of this session. `ref` is a reference of the memory, for example `3.5`: the message of
    * a reference `0.i`, or the two lines under a summary line. It needs no capability, because it reads only this
    * session.
    */
  def zoom(ref: String)(using t: Turn): String = t.zoom(ref)

  /** Starts a session in the background with `goal` in `scope` and the context strategy `strategy`. The report of the
    * session comes back as a message that starts a turn.
    */
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
