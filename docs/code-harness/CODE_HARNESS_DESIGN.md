# Code harness: the design

Status: design, 2026-11-01. This document is the current design of the dspy4s harness. It takes the place of section 4
of [CODE_HARNESS.md](CODE_HARNESS.md), which keeps the analysis and the history. Nothing here exists in the code of
dspy4s yet. The sketches of the kernel and of the guest API compile and ran in checks (section 12). Section 14 lists
the choices that are new in this document.

## Summary

The harness is a coding agent that acts only by writing Scala. A session is one journal, and four pure functions
decide everything about it. Small interpreters do the effects. The compiler, the handlers and an OS sandbox limit the
authority of a session. Each LM call is a `Predict` leaf, so the dspy4s optimizers can tune the full harness.

The controller, a work session, a child and an optimizer rollout are the same thing: a session with a different
configuration. So the design has one kernel.

## 1. The seven ideas

| # | Idea | Why |
|---|---|---|
| 1 | **Code is the only action.** The model writes Scala against a typed API, and the source of that API is its reference. | One cell can do the work of many tool calls. Its values stay in the REPL, outside the LM context. |
| 2 | **A session is one journal.** Only committed state is observable. | A live session, a reopened session and a test have one model. |
| 3 | **The kernel is pure.** `evolve`, `next`, `decide` and `repair` hold all the decisions, and threads only interpret them. | The laws can run with no thread, no model and no REPL. |
| 4 | **Authority has three levels.** A mode for the compiler, a guard for each call, and a sandbox for each process. | Each level stops what the level above it cannot see. |
| 5 | **The context is a projection.** A strategy changes only what the model sees, and the journal keeps all. | Compaction loses nothing, and the model can get each detail back. |
| 6 | **All work is a session.** A session can start other sessions, up to a limit that its capability holds. | Delegation needs no second mechanism. |
| 7 | **Each LM call is a leaf.** The policy, the summaries, the goal check and the reviewer are `Predict` leaves. | The optimizers see the full harness. |

## 2. What each project gives

| Project | What this design takes | What it leaves |
|---|---|---|
| atc | One action language, the API source as the reference, capabilities as `using` parameters, compile errors as observations, safe mode | The lock for the full JVM, compaction that loses detail |
| pi | The session as an append-only list, one file for each session, the context as a pure projection, `store` and `load` kept in the session | JSON tools as the action language |
| `pi-durable` | "Only committed state is observable", an intent and an outcome for each effect, request ids, version checks | Each task as a state machine, a deterministic JavaScript sandbox |
| uniichat | The memory tree, `zoom`, a cheaper model for summaries, the goal check, a report as a message | JSON tools, one chat for all work |
| deepseek-harness | Crash repair by append, the mark for optional entries, a sandbox that fails closed, recorded model streams, tests that kill a process | Agent teams with a task graph, the opt-in REPL that is never durable |
| Codex | Commit on success for the store, soft and hard stops as two commands, world-state diffs, taint, an LLM reviewer, best-of-N, a SQLite view that the session files can rebuild | Four levels of loops, the yield protocol, the message board |
| tacit | Results that show that capability-safe Scala works for agents, a timed-out cell that keeps the earlier state, an early MCP server | A validator as a boundary |
| dspy4s `RLM` | Inputs as REPL values, `llmQuery`, recursion that returns a typed value | The Python interpreter |
| dspy4s | `Predict`, `History`, `Module`, the optimizers | `AgentLoop.run` for this loop (`next` holds the limit) |
| Kyo | The claim token as a fence | `kyo-actor` for the first session host and `kyo-sql-sqlite` for the journal (choice 7), `kyo-mcp` for the MCP server (choice 10), `kyo-flow` for the turn, `kyo-ai` for `Predict`, `kyo-compat` for pluggable programs |

The analysis of each project is in [CODE_HARNESS.md](CODE_HARNESS.md) (sections 2 and 9),
[CODE_HARNESS_LOOPS.md](CODE_HARNESS_LOOPS.md), [CODE_HARNESS_RUNTIME.md](CODE_HARNESS_RUNTIME.md),
[CODE_HARNESS_DURABILITY.md](CODE_HARNESS_DURABILITY.md) and [CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md).

## 3. The words

These words settle section 4 of [CODE_HARNESS_CONCEPTS.md](CODE_HARNESS_CONCEPTS.md). Each word has one meaning. The
older documents keep their words: [CODE_HARNESS.md](CODE_HARNESS.md) and the Phase 0 report call the protections of
Phase 0 "guards".

| Word | Meaning | Do not use it for |
|---|---|---|
| Session | A durable conversation: an id and a journal | The live objects |
| Activation | The live part of a session in one process: the REPL, the threads, the mailbox | |
| Host | The harness process that runs the REPL and the handlers: host code, as against guest code | A machine, and a network host of the host list |
| Journal | The append-only list of the entries of a session | `History`, the transcript, the trace |
| Writer | The one thread of a session that takes messages from its mailbox and commits to its journal | The worker, which runs the turn |
| Feed | The view of one client: the committed entries of a session, in a bounded queue | The journal |
| Entry | One fact in a journal | An event for clients, which is a published event |
| Input | An `Input` entry: a message that enters a session | The values of a program call, which are its input fields |
| Commit | One atomic write of entries to a journal | A git commit, which is always "git commit" |
| Catalog | The derived list of the sessions, with one row for each session | The journal |
| Turn | From `TurnStarted` to `TurnEnded` | One model reply |
| Step | One policy call and what it starts | The result of `AgentLoop` |
| Cell | One unit of code that the REPL runs | |
| Effect | One guest API call | A tool call |
| Job | A command that runs after its cell ends | |
| Policy | The `Predict` leaf that writes the next cell | Guard rules, a sandbox profile |
| Guard | The rules of the `guarded` handler. Each says `Allow`, `Ask` or `Deny` (5.5). | The five protections of Phase 0 |
| Mode | The capabilities of a session, and their views | Code mode, a sandbox mode |
| Scope | The place of the work of a session: a directory, a worktree or a scratch directory | The range of a graph query, which is a `GraphRange` |
| Task | The work that a session gets at its start | A row of a task set, which is a rollout task |
| Goal | A condition that the work must meet | |
| Preamble | The text that a new REPL compiles before its first cell: the types, `submit` and the input fields of a session | A prompt |
| Pending call | A rule in `pending`: a call that waits for the answer of a person (5.5) | A plain question in the text of the model |
| Grant | The answer of a person to a pending call, or a permission in advance: a guard rule and a lifetime | A change of mode |
| Report | The message that a session sends to the session that owns it, after a turn | The value of `rlm` |
| Steer | A message that enters between two steps (`Send` in a turn) | |
| Abort | The hard stop of a turn | `Thread.interrupt`, which is the only meaning of "interrupt" |
| Fork | Say which one: a journal fork, a workspace fork or a session fork | |
| Replay | A new run of cells with recorded results | Recovery, which is a fold |

## 4. The kernel

The kernel is pure data and pure functions. It has no thread, no REPL and no LM call. `tmp/design-check/` holds the
code in this section.

### 4.1 The journal

```scala
final case class Logged(seq: Long, meta: Meta, entry: Entry) derives Schema

// The data that the model never sees. `required` tells a reader what to do with an entry that it cannot decode.
final case class Meta(atMillis: Long, source: Source, taint: Taint, required: Boolean) derives Schema

enum Entry derives Schema:
  case Activated(versions: Versions, config: SessionConfig)    // a start, a reopen, or the first entry after a crash
  case Input(request: RequestId, text: String, values: Map[String, Json]) // `meta.source` is the sender
  case TurnStarted
  case StepStarted(step: Int)                                  // the intent of the LM call
  case Acted(reasoning: String, text: String, code: Option[String]) // the LM outcome, and the intent of the cell
  case EffectStarted(id: EffectId, op: String, args: Json)     // the intent of a guest API call
  case EffectDone(id: EffectId, result: EffectResult)          // the outcome of the guest API call
  case Observed(output: String, outcome: CellOutcome, writes: Map[String, Json]) // the outcome of the cell
  case StopRequested(reason: String)
  case JobsStopRequested(which: JobSelector, reason: String)   // a stop of jobs, apart from the turn (10.3)
  case Granted(request: RequestId, rule: GuardRule, lifetime: GrantLifetime) // a person widened the guard (5.5)
  case ScopeRecorded(head: String, clean: Boolean)             // the git head of the scope, at the end of each turn
  case TurnEnded(outcome: TurnOutcome)
  case Ended(outcome: SessionOutcome, reason: String)

final case class EffectId(cell: Long, n: Int)                  // `cell` is the `seq` of the `Acted` entry

enum EffectResult derives Schema:
  case Value(value: Json, rendered: String)                    // the value for a replay, and the text for a reader
  case Error(message: String)
  case NeedsApproval(rule: GuardRule)                          // a pending call (5.5)
  case Unknown                                                 // a stop or a crash cut the call

enum TurnOutcome derives Schema:
  case Replied(text: String)
  case Submitted(value: Json)
  case Exhausted, Aborted, Crashed
  case Failed(error: String)
```

The entries of one turn form a small language. `Input`, `StopRequested`, `JobsStopRequested`, `Granted` and
`ScopeRecorded` can occur at any place:

```text
Turn  ::= TurnStarted Step* TurnEnded
Step  ::= StepStarted [ Acted [ Cell ] ]
Cell  ::= ( EffectStarted | EffectDone )* Observed
```

Five rules about the journal:

- **The commit.** `commit` returns after the write reaches the backend, so an intent is in the journal before its
  effect starts. A stream delta to a client is not state: a client can show it, and a crash can lose it.
- **One journal.** One commit writes to one journal. A commit across sessions needs all sessions in one database.
  Without it, each backend of `Journal` stays possible: a file on a local disk, a Pekko journal or a server database.
  Section 8.6 says how a message goes from one journal to another.
- **The flush.** A normal commit survives a crash of the process, and a power loss can remove it. A commit with an
  entry that `needsFlush` also reaches the disk before `commit` returns. A flush covers all earlier commits, so after
  a power loss the journal is a prefix. A read that a power loss removes does no harm, because nothing outside
  changed.
- **Unknown entries.** A reader that cannot decode an entry stops a rebuild if `required` is true, and it skips the
  entry if `required` is false.
- **The values.** A value in an entry is `Json`, which has only the six JSON cases. `Data` gives each value its form
  (5.1), and `parse(print(j)) == j` for that form. So a state from the journal equals the live state. The JSON codec
  of `DynamicValue` does not have this law (12).

| Effect | Intent | Outcome |
|---|---|---|
| The LM call of the policy | `StepStarted` | `Acted` |
| A cell | `Acted` with code | `Observed` |
| A guest API call, with `llmQuery` and `rlm(...)` | `EffectStarted` | `EffectDone` |

```scala
// The operations that change nothing outside the session. Each other operation flushes, so a new one is safe.
val readOnlyOps = Set("read", "keys", "load", "sessions", "jobOutput", "waitJob", "now", "zoom", "observe", "llmQuery",
  "llmQueryBatched")

def needsFlush(entry: Entry): Boolean = entry match
  case Entry.EffectStarted(_, op, _)             => !readOnlyOps(op)
  case Entry.Input(_, _, _) | Entry.Activated(_, _) => true   // a sender has an answer, or a parent has a child
  case _                                         => false
```

### 4.2 The state is a fold

```scala
enum Position:
  case NoTurn
  case BeforeStep(step: Int)
  case InPolicy(step: Int)
  case InCell(step: Int, cell: Long, code: String)
  case Answered(text: String)
  case Finished(value: Json)

final case class SessionState(
    config  : SessionConfig,
    nextSeq : Long,
    position: Position,
    stopping: Boolean,
    closed  : Boolean,
    waiting : Int,                       // inputs that no step has seen, since the last stop
    open    : Map[EffectId, OpenCall],   // effects with an intent and no outcome
    store   : Map[String, Json],
    inputs  : Map[String, Json],         // the values of the input fields, apart from the store (5.1)
    requests: Set[RequestId],
    grants  : Set[ActiveGrant],
    pending : Set[GuardRule],            // the pending calls, which wait for a person (5.5)
    lastTurn: Long,                      // the `seq` of the last `TurnStarted`
    jobs    : Map[EffectId, JobRecord]   // each `spawn` that has an intent
)

def evolve(s: SessionState, logged: Logged): SessionState   // total and pure
```

`evolve` is the only place where state changes. `SessionState` is `journal.foldLeft(initial)(evolve)`, so a fold of a
prefix continues to the same state: the journal is a free monoid, and the fold is a homomorphism from it to the
functions on the state.

Each other view of a journal is also a fold, for example the note of lost names (6), the ledger, the usage totals and
the deterministic part of a `SessionCard`. Folds compose, so several views need one pass:

```scala
final case class Fold[-E, S, +A](initial: S, step: (S, E) => S, done: S => A):
  def apply(entries: Iterable[E]): A = done(entries.foldLeft(initial)(step))
  def map[B](f: A => B): Fold[E, S, B] = Fold(initial, step, s => f(done(s)))
  def zip[F <: E, T, B](that: Fold[F, T, B]): Fold[F, (S, T), (A, B)] =
    Fold(
      (initial, that.initial),
      { case ((s, t), e) => (step(s, e), that.step(t, e)) },
      { case (s, t) => (done(s), that.done(t)) }
    )
```

### 4.3 The next piece of work is an unfold

`next` reads the state and says what comes next. So a session is an unfold of work and a fold of results.

```scala
enum Next:
  case Wait                                    // no open turn, or an effect is in progress
  case CallPolicy(step: Int, mustAnswer: Boolean)
  case RunCell(cell: Long, code: String)
  case End(outcome: TurnOutcome)

def next(s: SessionState): Next =
  if s.position == Position.NoTurn then Next.Wait
  else if s.stopping then Next.End(TurnOutcome.Aborted)
  else
    val limit = s.config.steps
    s.position match
      case Position.BeforeStep(i) if i < limit => Next.CallPolicy(i, mustAnswer = false)
      case Position.BeforeStep(i) if i == limit && s.config.kind == TurnKind.Chat => Next.CallPolicy(i, mustAnswer = true)
      case Position.BeforeStep(_)              => Next.End(TurnOutcome.Exhausted)
      case Position.InCell(i, _, _) if i >= limit => Next.End(TurnOutcome.Exhausted)
      case Position.InCell(_, cell, code)      => Next.RunCell(cell, code)
      case Position.Answered(text)             => Next.End(TurnOutcome.Replied(text))
      case Position.Finished(value)            => Next.End(TurnOutcome.Submitted(value))
      case Position.InPolicy(_) | Position.NoTurn => Next.Wait
```

```mermaid
stateDiagram-v2
  [*] --> NoTurn
  NoTurn --> BeforeStep: TurnStarted
  BeforeStep --> InPolicy: StepStarted
  InPolicy --> InCell: Acted with code
  InPolicy --> Answered: Acted with no code
  InCell --> BeforeStep: Observed
  InCell --> Finished: Observed with submit
  Answered --> NoTurn: TurnEnded(Replied)
  Finished --> NoTurn: TurnEnded(Submitted)
  BeforeStep --> NoTurn: TurnEnded(Exhausted)
```

A stop can end a turn from each of these states. The two stop rules are one rule in `next`:

| Event | Chat turn | Program call |
|---|---|---|
| A cell calls `submit(...)` | `Submitted` | The value is the output. |
| A reply with no cell | `Replied`, and the text is the answer | `Replied`, and the `finisher` leaf makes the output |
| The step limit | One more policy call with a note to answer. The harness runs no cell from it. | `Exhausted`, and the `finisher` leaf makes the output |

### 4.4 The repair

```scala
def closing(s: SessionState): Vector[Entry] =
  val effects = s.open.keys.toVector.sortBy(id => (id.cell, id.n)).map(Entry.EffectDone(_, EffectResult.Unknown))
  s.position match
    case Position.InCell(_, _, _) => effects :+ Entry.Observed("", CellOutcome.Failed("the cell did not complete"), Map.empty)
    case _                        => effects

def repair(s: SessionState): Vector[Entry] =
  if s.position == Position.NoTurn then Vector.empty else closing(s) :+ Entry.TurnEnded(TurnOutcome.Crashed)
```

A stop and a crash close a turn with the same entries. The old entries never change. Nothing runs an effect with an
unknown outcome again automatically.

### 4.5 The guard

`decide` says which entries a command can commit. It keeps each journal inside the language of 4.1.

```scala
enum Command:
  case Activate(versions: Versions, config: SessionConfig)
  case Send(request: RequestId, text: String, values: Map[String, Json])
  case Commit(entries: Vector[Entry])
  case Abort(reason: String)
  case StopJobs(which: JobSelector, reason: String)
  case Grant(request: RequestId, rule: GuardRule, lifetime: GrantLifetime)
  case Close(outcome: SessionOutcome, reason: String)

def decide(command: Command, s: SessionState): Either[Rejection, Vector[Entry]]
```

| Command | What it commits |
|---|---|
| `Activate` | `repair(s)`, then `Activated`. If an input waits, also `TurnStarted`. |
| `Send` with a known request id | Nothing |
| `Send` to a session that has ended | Nothing. The command gets the rejection `SessionClosed`. |
| `Send` with no open turn | `Input` and `TurnStarted` |
| `Send` in a turn | `Input`. The projection shows it at the next `StepStarted`. |
| `Commit` | The entries, if each one is valid at that position (the rules below). |
| `Abort` in a turn | `StopRequested`, which also clears the inputs that wait. It changes no job. |
| `StopJobs` | `JobsStopRequested`. It needs no open turn, and it changes no turn. |
| `Grant` with a known request id | Nothing |
| `Grant` to a session that has ended | Nothing. The command gets the rejection `SessionClosed`. |
| `Grant` that answers a pending call | `Granted` and an `Input` from the harness. With no open turn, also `TurnStarted`. |
| `Grant` with no pending call | `Granted`. It starts no turn. |
| `Close` | `Ended`, only with no open turn |

The rules for `Commit`:

- After `StopRequested`, only outcomes can commit: `EffectDone`, `Observed` and `TurnEnded`.
- `EffectStarted` needs an `EffectId` of the current cell. So a late message from an old cell cannot enter a later
  cell.
- A late `EffectDone` of a closed effect adds nothing, because an interrupt does not wait for a commit in progress.
- If an input waits when `TurnEnded` commits, `TurnStarted` commits with it.

Two questions have one answer here. An input that no step saw gets a turn, also after a crash. An input from before a
stop starts no turn, and the next turn shows it.

### 4.6 The worker

The worker interprets `next`. It holds no decision and no state of its own:

```scala
trait TurnEffects:
  def callPolicy(state: SessionState, mustAnswer: Boolean): Either[String, PolicyReply]
  def runCell(cell: Long, code: String): CellResult

trait Journal:
  def state: SessionState
  def run(command: Command): Either[Rejection, SessionState]
  final def commit(entries: Entry*): Either[Rejection, SessionState] = run(Command.Commit(entries.toVector))

@tailrec def drive(journal: Journal, effects: TurnEffects): SessionState =
  val state = journal.state
  next(state) match
    case Next.Wait => state
    case Next.CallPolicy(step, mustAnswer) =>
      journal.commit(Entry.StepStarted(step)).foreach { started =>
        val _ = effects.callPolicy(started, mustAnswer) match
          case Right(reply) => journal.commit(Entry.Acted(reply.reasoning, reply.text, reply.code))
          case Left(error)  => journal.commit(Entry.TurnEnded(TurnOutcome.Failed(error)))
      }
      drive(journal, effects)
    case Next.RunCell(cell, code) =>
      val result = effects.runCell(cell, code)
      val open = closing(journal.state).filter(_.isInstanceOf[Entry.EffectDone])
      val _ = journal.commit((open :+ Entry.Observed(result.output, result.outcome, result.writes))*)
      drive(journal, effects)
    case Next.End(outcome) =>
      val _ = journal.commit((closing(journal.state) :+ Entry.TurnEnded(outcome))*)
      drive(journal, effects)
```

A test gives `TurnEffects` as a script. The harness gives a `Predict` leaf and a REPL. A journal can be a value in
memory or a writer with SQLite behind it, and `drive` stays the same.

An example journal: a user asks a question, the model runs one cell with one guest API call, and then it answers.

```text
Activated  Input  TurnStarted
  StepStarted(0)  Acted(code)  EffectStarted  EffectDone  Observed
  StepStarted(1)  Acted(no code)
TurnEnded(Replied)  ScopeRecorded
```

The same journal, with a stop during the guest API call:

```text
Activated  Input  TurnStarted
  StepStarted(0)  Acted(code)  EffectStarted  StopRequested  EffectDone(Unknown)  Observed(Failed)
TurnEnded(Aborted)
```

### 4.7 The laws of the kernel

| Law | Statement |
|---|---|
| Fold | `SessionState` is the fold of the journal. |
| Homomorphism | `state(a ++ b)` is the fold of `b` from `state(a)`. |
| Language | Each journal is a word of the language of 4.1. |
| Commit | A reader sees an entry only after its commit. |
| Flush | After a power loss, the journal is a prefix that ends at the last flush. Each effect that changed the world has its intent in it, and so does each `Input` for which a sender got an answer. |
| Effect | An intent commits before its effect, and the outcome commits after the effect. Each intent has exactly one outcome after a repair. |
| Stop | After `StopRequested`, `next` starts no new work, and `decide` accepts no new intent. |
| One journal | One commit writes to one journal. |
| Input | Two `Send` commands with one request id give one `Input`. |
| Report | After the report pass of 8.6, each `TurnEnded` that needs a report has exactly one `Input` in the journal of an open owner. |
| Rest | With no open turn, no input waits. |
| Grant | A `Once` grant covers one call. A `Turn` grant ends at the next `TurnEnded`. No grant stays after `Ended`, and a journal fork starts with no grants. A repeat of a `Grant` commits nothing. |
| Pending call | A pending call has no active grant that covers it. `TurnStarted` and `Ended` empty `pending`, and a journal fork starts with no pending calls. A session with no person never gets a pending call. |
| Guard | `verdict` is a homomorphism from `Guard` to `Verdict`. A grant changes only `Ask`, and more grants never make a result stronger. |
| Job | An `Abort` changes no job. A stop covers only the jobs with an intent before it. After `Abort` and then `StopJobs(OfLastTurn)`, no job of that turn starts or runs. `Ended` stops all jobs, and a journal fork starts with no jobs. |
| Repair | `repair` closes each open part, and `repair(state after repair)` is empty. |
| Store | The store holds only the writes of cells that completed. |
| Names | The note after a restart lists the names of the cells that completed, and no name of a failed cell. |
| Secret | No known secret value is in a committed entry, and a second mask changes nothing. |
| Data | `decode(encode(v)) == Right(v)` for each value with a `Data`. An `encode` never throws. |
| Text | `parse(print(encode(v))) == encode(v)`. So a commit changes no value. |
| Form | Only `Data` reads the form of `Data`. The host reads the value of `submit` with `Data[O]`. |
| Shape | A type that a preamble defines from `shape` reads each value of the first type, and it writes the same `Json`. |
| Inputs | `inputs` is the fold of the `values` of the `Input` entries. A repeat adds nothing, a fork keeps them, and the store does not hold them. |
| Log | No part of the harness changes or removes an entry. |

## 5. Authority

### 5.1 The guest API

The guest API is one trait for each effect. The bare type is the read-only view, and `T^` is the full view. Each
definition that a cell can use has `@assumeSafe`, because safe mode rejects the other definitions.

```scala
@assumeSafe trait Cap extends caps.Stateful, caps.ExclusiveCapability

@assumeSafe trait Files extends Cap:
  def read(path: String): String
  update def write(path: String, content: String): Unit

@assumeSafe trait Shell extends caps.ExclusiveCapability:
  def exec(command: String)(using Files^): String  def spawn(command: String, report: Boolean = false)(using Files^): JobId
  def jobOutput(job: JobId): String
  def waitJob(job: JobId, maxSeconds: Int): JobStatus
  def stopJob(job: JobId): Unit

@assumeSafe trait Store extends Cap:
  def keys: Vector[String]
  def load[A: Data](key: String): Option[A]
  update def store[A: Data](key: String, value: A): Unit

@assumeSafe trait Sessions extends Cap:
  def list(): Vector[String]  update def tell(session: String, message: String): Unit

@assumeSafe trait Children extends Cap:  update def rlm[A: Data](task: String, inputs: Map[String, String]): A
  update def rlmBatched[A: Data](tasks: Vector[String]): Vector[A]

@assumeSafe object api:
  def println(value: Any): Unit = scala.Console.println(value)
  def read(path: String)(using f: Files): String = f.read(path)
  def write(path: String, content: String)(using f: Files^): Unit = f.write(path, content)
  def exec(command: String)(using s: Shell^, f: Files^): String = s.exec(command)
  def load[A: Data](key: String)(using s: Store): Option[A] = s.load[A](key)
  def store[A: Data](key: String, value: A)(using s: Store^): Unit = s.store(key, value)
  def now(): Long                                              // milliseconds since the epoch
  def input[A: Data](name: String): A                          // the value of an input field of this session
  // and the other functions
```

The sketch shows the shape. The full API also has `edit`, `httpGet`, `llmQuery`, `llmQueryBatched`, `rlm`, `start`,
`observe` and `zoom`. `submit` comes from the preamble of the session. The source text of these traits, with a
notation header, is the API reference in the prompt. A search function and the reference on demand can come later, for
a large API.

**`Data` is the type of a value that a later cell, another session or the host reads back with a type.** Text has no
`Data`: `println`, the printed result of a cell, `write`, `exec` and `tell` take text. Safe code cannot name the
`Schema` of zio-blocks: `store("k", 1)` with `A: Schema` gives "Cannot refer to value int in object Schema from safe
code". A `Schema.derived` inside an inline of the guest API also fails: in a capture-checked cell, the macro crashes
the compiler with `RecursionOverflow`. So the guest API owns a small type class, which derives from `Mirror`:

```scala
@assumeSafe trait Data[A]:
  def encode(value: A): Json
  def decode(value: Json, path: String): Either[DataError, A]
  def shape: DataShape                             // what a value looks like, for the preamble of a new REPL

enum DataShape:                                    // a record and a union are lazy, because a type can hold itself
  case Plain(name: String)
  case Applied(name: String, args: Vector[DataShape])
  case Record(name: String, fields: () => Vector[(String, DataShape)])
  case Union(name: String, cases: () => Vector[(String, DataShape)])

@assumeSafe trait DataAuto:
  inline given auto[A](using m: Mirror.Of[A]): Data[A] = Data.derived[A]

@assumeSafe object Data extends DataAuto:
  given Data[Int] = ...                            // and the other numbers, String, Char, Boolean, Unit, Instant, Duration
  given [A: Data]: Data[Vector[A]] = ...           // and List, Seq, IndexedSeq, Set
  given [K: Data, V: Data]: Data[Map[K, V]] = ...
  given [H: Data, T <: Tuple: Data]: Data[H *: T] = ...
  given [A: Data]: Data[Option[A]] = ...           // and Some, None, Either, Left, Right
  inline def derived[A](using m: Mirror.Of[A]): Data[A] = // from the names and the elements of `m`
```

A case class or an enum needs no other code. A cell can write `case class Point(x: Int, y: String)`, and then
`store("p", Point(1, "a"))`. A recursive type needs one more line, because the inline given does not end on it: `case
class Dir(name: String, kids: Vector[Dir])`, and then `given Data[Dir] = Data.derived`.

| Value | Its `Json` form |
|---|---|
| A number | A number. NaN, the infinities and -0.0 are strings. A `BigInt` or a `BigDecimal` with more than 34 digits is a string, because the reader rounds to 34 digits. |
| `Char`, `Instant`, `Duration` | A string |
| `Vector`, `List`, `Seq`, `IndexedSeq`, `Set`, a tuple | An array |
| A case class | An object with one field for each parameter |
| An enum, a sealed trait, `Option`, `Either` | An object with one field, the name of the case: `{"Failed":{"reason":"x"}}` |
| `Map[String, V]` | An object |
| Another `Map` | An array of pairs |

The rules of `Data`:

- A capability, a function, `Any`, a plain class, an `Array`, an `Iterator`, a `LazyList` and a mutable collection
  have no `Data`. The compile error names the type and says what can leave a cell. A `var` in a case class does not
  pass the capture checker, so a value has no cycle.
- `load` gives `None` for a key with no value. A value that does not fit the type throws a `DataError` with its path,
  and the cell fails: `at a.y: expected a field of P, found {x}`. `load` ignores an extra field.
- The journal holds no type. `load` reads a value with the type of the cell that calls it, so a later cell can define
  the type again.
- A cell can also write `derives Data`, but the reference does not show it. The capture checker rejects `derives Data`
  in a cell that also has a `def` or a lambda with a capability. The second try of section 6 covers this case.

**Where a typed value crosses.** Both ends use `Data`:

| Place | From and to | The reader |
|---|---|---|
| `store`, `load` | A cell to a later cell | `Data` in the later cell |
| `submit` in a program call | A cell to the host | `Data[O]` in the host |
| `submit` in a child | A child to its parent | `Data[A]` in the parent |
| The input fields of a program call | The host to a cell | `Data` in the cell, through `input` |
| The `inputs` of `rlm` | A parent to a child | `input[String]` in the child |

**Only `Data` reads the form of `Data`.** `Data` and `Schema` write different JSON, and each fails on the text of the
other:

| Value | `Data` writes | `Schema` writes |
|---|---|---|
| `Some("n")` | `{"Some":"n"}` | `"n"` |
| `None` | `{"None":{}}` | No field |
| An enum case with no fields | `{"Fix":{}}` | `"Fix"` |
| `Map(1 -> "a")` | `[[1,"a"]]` | `{"1":"a"}` |
| `Right(1)` | `{"Right":1}` | `{"Right":{"value":1}}` |
| `NaN` | `"NaN"` | The encode throws |

So `Harness[I: Data, O: Data]` reads the value of `submit` with `Data[O]` (9), and the compiler derives it.
`Schema[O]` stays for the LM side, so the `finisher` leaf gives an `O` as before. This takes the place of the decode
in F3. `Data` is a second codec next to `Schema`, only at the boundary of a cell. A field with a hand-written `Schema`
needs a hand-written `Data`, and the compile error names the type.

**The preamble.** A new REPL compiles a text before its first cell. The harness writes this text from `shape`, because
the REPL of a child has none of the types of its parent:

```scala
// the cell of the parent
case class Point(x: Int, note: Option[String])
val p = rlm[Point]("find the point", Map("doc" -> text))

// the preamble of the child: the types, one given for each type, `submit`, and one `val` for each input
case class Point(x: Int, note: Option[String])
given Data[Point] = Data.derived
def submit(x: Int, note: Option[String]): Nothing = ...
val doc: String = input[String]("doc")
```

- `SessionConfig.preamble` holds the text (8.1), and the journal holds no `DataShape`. A new REPL after a crash or a
  reopen compiles the same text. The text holds no value, so it holds no secret.
- The API reference in the prompt also shows the preamble, so the model sees the types and `submit`.
- A program makes its preamble from `Data[O].shape` and `Data[I].shape` when it is built. A child gets its preamble
  from `Data[A].shape` when `rlm` is called. A session with no output type has `submit(value: String)`.
- A record gives `submit` one parameter for each field (F3). Another type gives `submit(value: T)`.
- The JSON of a value does not hold the name of a type. So the preamble can write `Box[Int]` as a class with an `Int`
  field, and a sealed trait as an enum, with the same form.
- Three things have no preamble: two types with one name and two forms, a sealed trait inside a sealed trait, and an
  input with the name of a guest API function. A program fails at its build, and `rlm` fails with a plain error.

**The values of the input fields.** The input fields of a program call and the `inputs` of `rlm` are in the `values`
of the `Input` entry. `SessionState.inputs` is their fold, apart from the store, so a `store` cannot change an input.
`input[A](name)` reads this fold. It needs no capability, because it reads only what the session got. It is not an
effect, because the journal already holds the value. The `inputs` of `rlm` stay `Map[String, String]` in the first
version. A typed value in `inputs` can come later by the same route.

**A child that gives no value.** `rlm[A]` gets its value only from `submit`. The `finisher` leaf makes an `O` and not
an `A`, so it cannot help. A child that ends with `Replied` or `Exhausted` makes the call fail, and the error holds
the last text of the child. `rlmBatched` fails if one child fails, and the error names the task.

**`now()` gives the time.** Safe mode rejects `System.currentTimeMillis` and `System.nanoTime`, and models write them.
`now()` is a guest API call like the others, so `recorded` stores its value. It closes nothing: safe mode allows
`java.time.Instant.now()`, `scala.util.Random` and `Math.random()`, so a cell is not deterministic by its type. Choice
9 leaves this for a later replay.

### 5.2 Modes

A mode is a set of givens that the compiler checks. As a value, a mode is an element of a bounded meet-semilattice: a
product of chains `None < Read < Full`.

```scala
enum Access:
  case None, Read, Full

final case class Mode(files: Access, shell: Access, network: Access, llm: Access, sessions: Access, children: Access, store: Access):
  def <=(that: Mode): Boolean
  def meet(that: Mode): Mode
```

| Mode | Givens in the preamble |
|---|---|
| Full | `files: Files^`, `shell: Shell^`, `net: Network^`, `llm: Llm^`, `peers: Sessions^`, `children: Children^`, `state: Store^` |
| Local | `files: Files^`, `shell: Shell^`, `llm: Llm^`, `peers: Sessions^`, `children: Children^`, `state: Store^` |
| Read-only | `files: Files`, `llm: Llm^`, `peers: Sessions`, `state: Store^` |
| Controller | `files: Files`, `llm: Llm^`, `peers: Sessions^`, `children: Children^`, `state: Store^` |
| A child, a rollout | The givens of its base mode, with the bare `peers: Sessions` and with `children: Children^` |

Each mode has `Store^`, because the store changes only the state of the session. A change of mode needs a new REPL,
because the preamble changes, so it is a new activation.

**The ceiling law.** A `Sessions^` or a `Children^` capability holds a ceiling. The mode of a new session is at most
that ceiling. For a work session, the ceiling is its own mode, so a child never has more authority than its parent.
For the controller, the ceiling comes from the configuration of the user. So the controller can start a session with
`Files^`, but it cannot write a file itself.

**The closed law.** A child and a rollout have the mode `base.meet(Mode.closed)`, where `Mode.closed` is `Mode.full`
with `sessions = Read`. They keep `rlm`, with its depth limit, and the compiler rejects `start` and `tell`. So nothing
that they start can outlive them, and an optimizer run stays inside its own fork.

### 5.3 Handlers

The value behind each given is a handler. Handler transformers form a monoid under composition, for example
`recorded(guarded(Files.overlay(base), guard))`.

| Handler | Purpose |
|---|---|
| `Files.local(root)`, `Files.overlay(base)`, `Files.memory(snapshot)` | Real files, copy on write for one rollout, or files in memory for tests |
| `Shell.sandboxed(root, profile)`, `Shell.denied` | Commands in an OS sandbox, or no commands. If no sandbox works, `exec` fails. |
| `Store.snapshot(state)` | A snapshot for one cell and a write buffer. `CellResult.writes` holds the buffer. |
| `recorded(h)` | Commits `EffectStarted` before each call and `EffectDone` after it |
| `replayed(journal)` | Answers each call from a journal |
| `guarded(h, guard)` | Calls `guardCheck` before each call (5.5). A deny rule wins. |
| `reviewed(h, leaf)` | Asks a `Predict` leaf about a call that the rules do not settle. It fails closed. |

Laws:

- `guarded` is a monoid homomorphism: `guarded(allowAll)(h)` behaves as `h`, and `guarded(p)(guarded(q)(h))` behaves
  as `guarded(p and q)(h)`.
- For the calls in a journal, `replayed(journal)` gives the same results as the handler from which `recorded` wrote
  it.

### 5.4 The sandbox

Capture checking stops at the process boundary. A command that a session can start can run code that the session
wrote. So each command runs in an OS sandbox, with a profile from the capabilities at the call. F7 and F8 of
[CODE_HARNESS.md](CODE_HARNESS.md) have the threat model and the backends.

| Capabilities at the `exec` call | Profile of the child process |
|---|---|
| No `Shell` | No child process |
| `Shell^` and `Files^` | Writes only under the scope and a private temporary directory. No network. |
| `Shell^`, `Files^` and `Network^` | The same, plus the network through a proxy that applies the host list |
| Each mode | Secrets and classified paths are not readable. Other paths outside the scope are read-only. |

Law: `profile` is monotone in the mode, and the authority of a child process is at most the authority of the
capabilities at its `exec` call.

The folder of the journals is closed to each profile and to `Files`. So `zoom` and `observe`, through `Sessions`, are
the only ways to read another session.

Seatbelt on macOS and bubblewrap on Linux come first. `kyo-pod` is a possible later backend for rollouts.

### 5.5 Approvals: a cell never waits for a person

Each rule of a session says `Allow`, `Ask` or `Deny` for the calls that it matches. The strongest verdict wins, and a
call that no rule matches is allowed. A grant changes only `Ask` to `Allow`.

A call with `Ask` and no grant does not run. The cell gets a `NeedsApproval` error, and `EffectDone` holds the rule of
that call as `EffectResult.NeedsApproval`. The rule stays in `pending`, and it is a pending call. A client reads it
from the journal and asks the person, so a `catch` in the cell cannot hide it. The model ends its turn and asks. The
person answers through a client, which sends `Grant(request, rule, lifetime)`. The new turn makes the call again.

```scala
enum ArgMatch derives Schema:
  case Exact(value: Json)                                      // only these arguments
  case Prefix(field: String, text: String)                     // a text argument that starts with `text`
  case AnyArgs                                                 // each call of `op`

final case class GuardRule(op: String, args: ArgMatch) derives Schema:
  def matches(callOp: String, callArgs: Json): Boolean         // pure and total
  def covers(call: GuardRule): Boolean                         // a grant of this rule allows that pending call

enum Verdict derives Schema:                                   // a chain, so `join` is a semilattice
  case Allow, Ask, Deny
  def join(that: Verdict): Verdict                             // the stronger verdict

final case class VerdictRule(rule: GuardRule, verdict: Verdict) derives Schema

final case class Guard(rules: Vector[VerdictRule]) derives Schema: // `and` is a monoid, and `allowAll` is its identity
  def verdict(op: String, args: Json): Verdict                 // the join of the rules that match, or `Allow`
  def and(that: Guard): Guard = Guard(rules ++ that.rules)

enum GrantLifetime derives Schema:
  case Once, Turn, Session

final case class ActiveGrant(rule: GuardRule, lifetime: GrantLifetime) derives Schema

enum GuardResult:
  case Run
  case NeedsPerson(rule: GuardRule)
  case Refused(reason: String)

// The handler `guarded` calls this after the commit of `EffectStarted`.
def guardCheck(s: SessionState, op: String, args: Json): GuardResult =
  val granted = s.grants.exists(_.rule.matches(op, args))
  s.config.guard.verdict(op, args) match
    case Verdict.Allow                           => GuardResult.Run
    case Verdict.Deny                            => GuardResult.Refused("a rule denies this call")
    case Verdict.Ask if granted                  => GuardResult.Run
    case Verdict.Ask if s.config.owner.hasPerson => GuardResult.NeedsPerson(GuardRule(op, ArgMatch.Exact(args)))
    case Verdict.Ask                             => GuardResult.Refused("no person can answer in this session")

def forked(prefix: Vector[Logged]): SessionState =
  SessionState.of(prefix).copy(grants = Set.empty, pending = Set.empty, jobs = Map.empty)

// Three examples. `args` gives the `Json` of the arguments of the call.
Grant(request, GuardRule("exec", ArgMatch.Exact(args("git push origin main"))), GrantLifetime.Once)
Grant(request, GuardRule("write", ArgMatch.AnyArgs), GrantLifetime.Turn)
Grant(request, GuardRule("exec", ArgMatch.Prefix("command", "git push")), GrantLifetime.Session)
```

| Lifetime | Use | `evolve` removes the grant at |
|---|---|---|
| `Once`, the default | One call | The `EffectDone` of the first call that the rule matches |
| `Turn` | A loop of calls | The next `TurnEnded` |
| `Session` | Work that the person trusts | `Ended` |

`Ended` removes all grants, so a reopened session asks again. The lifetime is in the grant, because only the person
knows how much they trust that call.

**The answer.** A `Grant` that answers a pending call commits `Granted`, an `Input` and, with no open turn,
`TurnStarted` in one commit. So the client sends one command. The harness writes the text of the `Input`, and
`meta.source` is `Source.Harness`, so an owner gets a report of that turn (8.6). A repeat with the same request id
commits nothing, so one OK gives one grant. To refuse, a person sends a plain message. A new turn empties `pending`,
and the model asks again if it still needs the call.

**A session with no person.** `hasPerson` is true for an owner that is the user or a session. It is false for a cell
and an optimizer. So in a child and a rollout, `Ask` gives a plain error, and `pending` stays empty. The model of a
child can try another way. If it cannot finish, `rlm` fails in the parent, and the parent can make the call with its
own approval.

**The first rules.** `SessionConfig.guard` holds the rules, so a change of rules is a new `Activated`, as a change of
mode is. The default has one rule, only in a mode with `Network^`: `exec` with the prefix `git push` gets `Ask`. In
local mode the sandbox already stops a push. A rule on the text of a command is only a help, because `sh -c` goes
around it. The sandbox is the real limit (5.4), and a grant cannot open what the sandbox closes.

**The cost.** One approval costs two more LM calls: one to ask and one to continue. A cell with no `catch` also loses
its values, and a `catch` of `NeedsApproval` keeps them (12). `Turn` and `Session` grants make repeats rare. A short
wait in the handler can come later with no kernel change, because `Granted` can already commit during a cell.

- No thread waits for days, so a restart loses nothing.
- Only a client of the user can send `Grant`. The model and the controller cannot.
- A grant widens the guard rules. It does not change the mode, and a deny rule still wins.
- A cell cannot make a `NeedsApproval` error, because safe mode rejects its constructor (12).
- A power loss can remove a `Granted` with no `Input`. The next call then asks again, so this does no harm.
- The `NeedsApproval` error holds the narrowest rule, with `Exact`. A client can offer a wider rule.
- `guarded` reads the state after the commit of `EffectStarted`, so a `Once` grant must stay until the outcome. A
  crash still uses up the grant, because `closing` commits `EffectDone(Unknown)`. The call then needs a new approval.
- The calls of one cell run one at a time, so the next call sees the removal. A later version with parallel guest
  calls must change this rule.
- A `Once` grant that no call uses stays until `Ended`.
- `Exact` compares `Json` values, so a grant matches its call before and after a restart.
- A grant belongs to one session. A child or a session from `start` does not get it, and `forked` drops all grants of
  a journal fork. So a rollout never gets an approval that a person gave for other work.

### 5.6 Secrets and taint

**A secret never enters a cell.** So a secret never enters the journal, a derivation or a prompt. Four rules hold:

- A handler adds a secret to a request (`secretHeaders`), and a cell never sees the value.
- Each mode keeps the secret paths unreadable (5.4), for example `~/.ssh`, `~/.aws` and `.env`.
- A command from `exec` or `spawn` gets only an allowlist of environment variables: `PATH`, `HOME`, `SHELL`, `TMPDIR`,
  `TMP`, `TEMP`, `LANG`, `LC_ALL`, `LC_CTYPE`, `LOGNAME`, `USER`, `TERM`, and the names in `SessionConfig.env`. A name
  that contains `KEY`, `SECRET`, `TOKEN`, `PASSWORD`, `PASSWD`, `CREDENTIAL` or `AUTH` never goes through, also if the
  session adds it. The harness process holds the keys of the LM provider, and Java and Codex pass all variables by
  default.
- The writer masks each known secret value in each string of an entry before the commit, as `«secret:NAME»`. The known
  values are the values behind `secretHeaders` and the values of the secret-like variables of the harness process. A
  longer value goes first, so a value inside another value is not cut. This rule is a second guard: it does not find a
  value that a command cuts across two lines or encodes.

- `Meta.taint` marks each entry from external content. The model does not see this mark. The reviewer and the graph
  read it.

**`Classified` comes later.** atc and tacit have a type `Classified[A]`: code can send its value to an allowed place,
but it cannot print or read the value. The first version has no such type, because no call gives a classified value. A
later `Classified` must be a plain class with no `Data`, so the compiler keeps it out of `store`, `submit` and the
`values` of an `Input`. Two points are open for it. A trusted LM for the classified parts needs the clear value, and
the journal does not hold it. A function that `Classified.map` runs can leak a bit through an exception or its time.

## 6. The REPL

- **One REPL for each activation.** `ReplDriver` runs in the same JVM, with capture checking and safe mode. The five
  protections of Phase 0 stay ([CODE_HARNESS.md](CODE_HARNESS.md), section 11, calls them guards).
- **One cell at a time, on its own thread,** with `Console.withOut` and the stop flag.
- **A cell commits only if it completes.** After `CompileError`, `Failed` or `TimedOut`, a later cell sees only the
  definitions from before the cell, and the store writes are gone.
- **One call at a time in a cell.** Safe mode rejects `Thread`, `Future`, `Executors` and `CompletableFuture`, so
  model code cannot start concurrent work. The guest API has two batch calls for this need: `llmQueryBatched` and
  `rlmBatched`. Each is one guest API call with one result, and the host runs its work at the same time, with a limit.
- **The clock of a cell.** The timeout counts only the time of guest code, as in atc. It stops during a guest API
  call, and each call has its own limit: `timeoutSeconds` for `exec`, `maxSeconds` for `waitJob`, and the budget and
  the depth limit for `rlm`.
- **A second try in two parts.** The capture checker can reject a cell that defines a type and uses a capability. The
  error says "is not included in the allowed capture set of the enclosing object". The same code compiles when the
  type definitions come first. So after this error, the host parses the cell, and it compiles the type definitions and
  then the rest. The host compiles all other cells as written, because a class can use a `val` of its own cell.
- **A failed second part.** The host adds the first part to `invalidObjectIndexes`, and it keeps the `objectIndex` of
  the REPL. If a later cell uses that index again, the REPL throws `NoClassDefFoundError` from a class that did not
  initialize.
- **A compiler crash.** `ReplDriver.run` can throw, for example `RecursionOverflow`. The host catches this at the
  cell, or the process stops.
- **Error hints.** The host adds a hint to the compile errors that models cannot read:

| The error | The cause | The hint |
|---|---|---|
| "Maximal number of successive inlines (32) exceeded" | A recursive type with no given | `given Data[T] = Data.derived` |
| "is not included in the allowed capture set" after the second try | A capability in a place that must be pure | The name of the capability |
| "Found: (x$1: (String, Vector[String]{val prefix1: ..." | A `Vector` in a pair, for example `groupBy(f).map { case (k, v) => ... }` | `.view.mapValues`, `groupMapReduce` or `.toList` |
| "Found: ((Array[(String)?]^{any.rd}))?^{x1.rd}" | `case Array(x, y)` inside `collect` | `.toList`, and then `case List(x, y)` |

The session has two kinds of state between cells:

| State | What it holds | After a restart |
|---|---|---|
| The store | `Data` values under a key, as `Json`. It is a fold of the journal. | It comes back from the journal, and `load` reads it with the type of the cell. |
| The REPL state | Definitions and live objects. It is a cache. | A new REPL starts, and the model gets a note with the names that it lost. |

**Long work is a job.** A build or a test run can take minutes, and a cell must stay short. `spawn(command)` starts a
job and gives a `JobId`, which is plain data that holds the `EffectId` of the `spawn`. So it stays valid in a later
cell, and the store can hold it. The session owns each job: an `Abort` does not stop a job, and `StopJobs` and the end
of the session do (10.3). After a crash of the host, the process of a job is gone in the first version, and `status`
reports the job as lost. This takes the place of the yield protocol of Codex.

Two calls make a job useful. `waitJob(job, maxSeconds)` blocks until the job ends or the time is up. Safe mode rejects
`Thread.sleep`, and a model that polls pays for each step and uses up its step limit. `spawn(command, report = true)`
also sends a message when the job ends: a `Send` with `Source.Harness` and a request id from the `JobId`. So a repeat
gives one `Input`, and the message can start a turn. The default is no message. A job that a stop ended sends no
message. A cell has a timeout, and no second cell runs at the same time.

**Replay comes later.** Replay of cells ([CODE_HARNESS.md](CODE_HARNESS.md), 8.4) can rebuild the definitions, because
the journal already holds each typed result and the versions. The first version has no replay. After a restart, a new
REPL compiles the preamble (5.1), the store comes back, and the model gets a note of the names that it lost.

**The note of lost names.** After a crash the old REPL is gone, so the note comes from a fold of the journal. For each
cell that completed, the parser of the compiler finds the top-level names: `val`, `lazy val`, `def`, `class`, `trait`,
`object`, `enum`, `type`, the names in a pattern, extension methods and imports. A later cell that defines a name
again moves the name to that cell. A cell that failed defines nothing, as in the REPL. The note lists each name with
its cell, and the model can read that cell with `zoom` and run it again. The parser needs no state of the old REPL, so
the note is the same after a crash, a reopen and a change of mode.

**One restart for each cause.** A crash, a reopen, a change of mode, an unclean host (protection 3 of Phase 0) and the
cell limit (protection 5, about 300 cells) all start a new REPL in the same way: the preamble, the store and the note.
Replay would not suit the cell limit in any version. The limit exists because the memory of a REPL grows with its
cells, and a replay of 300 cells makes the same 300 cells again.

**The early MCP server.** In phase 2, a small module `harness-mcp` offers the REPL and the guest API to another agent,
for example Claude Code, before the harness has its own loop. So real models can try the guest API, `NeedsApproval`,
the preamble and the note of lost names before phase 4. The server uses the MCP Java SDK, which dspy4s already has in
its `mcp` module, and it runs on JDK 21. It has two tools: `run_cell(code)` gives the output of one cell, and a second
tool gives the API reference. Two rules hold:

- Only the transport writes to the stdout of the process, because stdout carries the protocol. At its start the server
  sends `System.out` to stderr, and a cell writes to its own buffer. In the check of section 12, one late print of a
  library broke each later call of a server with no such rule.
- Until the sandbox of phase 3 exists, the server starts only the read-only mode of 5.2, with no `Shell^` and no
  `Network^`. The other agent has its own shell, so the server only tests the guest API.

## 7. The context

A strategy derives data from the journal and makes the policy input. It never changes the journal.

```scala
trait Derivation[D]:
  def empty: D
  def update(journal: Vector[Logged], d: D)(using RuntimeContext): Either[DspyError, D]  // can call Predict leaves

trait ContextStrategy[D] extends Derivation[D]:
  def project(journal: Vector[Logged], d: D, budget: Int): PolicyInput                   // pure
  def guestApi: GuestApi                                                                 // for example `zoom`

final case class PolicyInput(task: String, api: String, status: String, observation: String, history: History)
final case class PolicyStep(text: String, code: Option[String])
```

| Strategy | Derived data | Projection | Use |
|---|---|---|---|
| Window | None | The last entries that fit | A rollout, a child |
| Prefix summary | Summary entries | The last summary, then the entries after it | A work session |
| Memory tree | The nodes of a binary tree of summaries | A cut through the tree, with `zoom` in the guest API | The controller, a long work session |

- **Cheap steps first.** A strategy cuts long observations with no LM call, and it adds a note that says how to read
  the remainder. A summary that does not shrink is rejected.
- **The status section.** `status` holds the names in the REPL, the keys of the store, the scope, and the jobs with
  their states: running, exited, stopped or lost. It is typed, and it renders only its changes, so the prompt prefix
  stays the same.
- **Laws.** `project` depends only on the journal and the derived data. Two updates give the same structure as one
  update on the full journal. The memory tree has the laws Coverage, Zoom, Prefix, Immutability, Order, Size and Roots
  ([CODE_HARNESS.md](CODE_HARNESS.md), 6.2).
- **Derived data has a journal of its own.** An LM makes it, so a new derivation costs money and gives a different
  result.

## 8. Sessions

### 8.1 One kernel, four configurations

```scala
final case class SessionConfig(
    kind    : TurnKind,          // Chat or Program
    steps   : Int,
    mode    : Mode,
    ceiling : Mode,              // the most that a session started from here can get
    strategy: StrategyName,
    scope   : Scope,
    owner   : Owner,             // the user, an optimizer, a session (`start`) or a cell (`rlm`)
    guard   : Guard,             // the rules of 5.5
    preamble: String,            // the types, `submit` and the input fields of this session (5.1)
    model   : ModelChoice,
    goal    : Option[String],
    env     : Set[String]        // more names of variables for a command (5.6)
)
```

`Activated` holds this value, so a journal keeps it for good, and it must hold no secret (5.6). The entry before an
`Activated` says why the session came alive: nothing for a start, `Ended` for a reopen, and `TurnEnded(Crashed)` for a
return after a crash in a turn. A crash with no open turn and a change of mode look alike, apart from the new
configuration. Neither one cut any work.

| Session | Mode | Strategy | Scope | Lifetime | Started by |
|---|---|---|---|---|---|
| Controller | Controller | Memory tree | The home of the user | Never ends | The harness, one for each user |
| Work session | Full or local | The choice of the user | A directory, a worktree or a scratch directory | Until the user closes it | The user or `start` |
| Child | At most the mode of the parent, and closed | Window | The scope of the parent, or a fork | Until `submit` | `rlm(...)` |
| Rollout | Local, and closed | Window | A workspace fork | One task | An optimizer |

### 8.2 The operations

| Function | Needs | Meaning |
|---|---|---|
| `rlm[A: Data](task, inputs): A` | `Children^` | Runs a child inside the cell and gives its value. It is one effect of the parent, and the child has its own journal. A child that ends with no `submit` makes the call fail (5.1). |
| `rlmBatched[A: Data](tasks): Vector[A]` | `Children^` | Runs several children at the same time inside the cell. It is one effect of the parent, and each child has its own journal. |
| `start(goal, scope, strategy): SessionRef` | `Sessions^` | Starts a session in the background. Its report comes back as an `Input` with `Source.Child`, and this starts a turn (8.6). |
| `tell(session, message)` | `Sessions^` | `Send` to that session, with `Source.Controller` or `Source.Child`. It returns after the receiver commits (8.6). |
| `observe(session, question): String` | `Sessions`, `Llm^` | A read-only `rlm` with the journal of that session as an input. It changes nothing in that session. |
| `zoom(session, ref): String` | `Sessions` | Reads a part of a journal. |
| `list(): Vector[SessionCard]` | `Sessions` | The cards of the sessions. The catalog gives the deterministic part (10.2). |

`rlm` shares the budget of the parent and has a depth limit. `start` has no budget of its parent, and the new session
knows only its goal: it has no view of the conversation of its parent. There is no third form of subagent. The
`spawn(command)` of `Shell` is a job (10.3), and it stays.

### 8.3 What the controller sees

Information goes from the sessions to the controller. A session reads the graphs only on demand, and nothing goes into
its context automatically. The decisions of [CODE_HARNESS.md](CODE_HARNESS.md), 7.10 stay.

| Layer | What it is | LM |
|---|---|---|
| Ledger | A fold of the effects of a session: what it read and what it made | No |
| Graph | Facts and links with time. An LM extracts them at the end of each turn, and they are a derivation. The ontology is a value, and it is pluggable. | Yes |
| Probes | `observe(session, question)` | Yes |

The controller journal gets an entry only when a layer changes in a way that matters. Sections 7.5 to 7.8 of
[CODE_HARNESS.md](CODE_HARNESS.md) have the ontology, the time queries and `Graph`, with `GraphRange` as the name of
`GraphScope`.

### 8.4 The end of a session

The end is an entry, and the journal stays. A pure function makes a release plan from the state of the scope:
`releasePlan(scope, outcome): Vector[ReleaseItem]`. A reversible item runs automatically, and an item that stays
irreversible needs a `Grant`. Section 7.9 of [CODE_HARNESS.md](CODE_HARNESS.md) has the plans and their laws.

### 8.5 The goal

A goal stays in the configuration, but the permission to continue by itself is live, and a person must give it again
after a restart. After a turn, the `goal` leaf answers "is the goal met now?". On `false`, the harness sends a message
with `Source.Harness`, and this starts a turn. A goal is "blocked" after three turns with the same blocker. The check
does not run while `pending` has a pending call, so a session that waits for a person uses no LM calls.

### 8.6 Messages between sessions

One commit writes to one journal (4.1). So a message between two sessions is two commits, and a crash can occur
between them. A second send is safe, because `Send` with a known request id commits nothing (4.5).

| Operation | The order of the commits | After a crash between them |
|---|---|---|
| `tell` | The sender commits `EffectStarted`, the receiver commits `Input`, and the sender commits `EffectDone`. | The effect gets `Unknown`. Nothing sends the message again automatically, and the model sees the outcome. |
| `start` | The parent commits `EffectStarted`, the new session commits `Activated` and its task in one commit, and the parent commits `EffectDone`. | The effect gets `Unknown`. The new session can exist, and its reports still reach the parent. |
| `rlm` | The parent commits `EffectStarted`, the child runs until `submit`, and the parent commits `EffectDone`. | The effect gets `Unknown`, as in scenario 6 of section 11. |
| A report | The session commits `TurnEnded`, and its owner commits `Input`. | The report pass sends the report again. |

The handler of `tell` waits for the commit of the receiver through `Journal.run`. So `EffectDone` with a value means
that the `Input` is in the journal of the receiver.

A report is a pure function of the journal of the session. So a second send has the same request id and the same text:

```scala
// One `Send` for each `TurnEnded` of a turn that saw an `Input` from the owner or from the harness.
// A stop that clears an `Input` counts as a step that saw it.
// The request id holds the session and the `seq` of that entry.
def reports(session: SessionId, journal: Vector[Logged]): Vector[Command.Send]

def missing(session: SessionId, journal: Vector[Logged], owner: SessionState): Vector[Command.Send] =
  reports(session, journal).filterNot(send => owner.requests(send.request))
```

A turn that a message of the harness started has a report: the message at the end of a job, a goal message and the
message after a grant. So an owner gets the result of work that a person approved. A person can also type into a
session that has an owner. That turn has no report, and the ledger of 8.3 shows the work (open question 8 of section
14). A child of `rlm` and a rollout have no report. The owner of a child is a cell, which gets the value through the
effect. The owner of a rollout is an optimizer, which reads the journal.

**The report pass.** The host runs `missing`, in the order of `seq`, for each session that has a session as its owner.
It does this at its own start, and again when an owner opens. A session with an open turn first gets `Activate`, so
the repair commits `TurnEnded(Crashed)`, and the report says so. An owner that has ended rejects `Send` with
`SessionClosed`, and the pass sends the report when that owner opens.

## 9. Programs and optimization

`Harness` is a dspy4s `Module`, so the current optimizers can tune it:

```scala
final case class Harness[I: Data, O: Data](
    signature: Signature[I, O],
    policy   : Predict[PolicyInput, PolicyStep],
    finisher : Predict[FinishInput, O],
    subQuery : Predict[Query, Answer],
    goal     : Predict[GoalInput, GoalVerdict],
    strategy : Strategy,               // its leaves join the leaves of the harness
    env      : HarnessEnv,             // handlers, workspace, REPL factory, mode; not optimizable
    limits   : HarnessLimits           // not optimizable
) extends Module[I, O]
```

`forward` makes a journal in memory and a REPL in a workspace fork. It sends an `Input` with the values of the input
fields (5.1), runs `drive`, and reads the last `TurnEnded`. `Submitted` gives the output through `Data[O]`. `Replied`
and `Exhausted` run the `finisher` leaf. This path needs no writer and no thread other than the cell thread.

| Leaf | Where | What it decides |
|---|---|---|
| `policy` | Each step | The next cell or reply |
| `finisher` | The end of a program call | The output, from the journal |
| `subQuery` | `llmQuery` | The answer to a question from code |
| `goal` | After a turn | If the goal is met |
| `compactor`, or `compress` and `merge` | A strategy | What a summary keeps |
| `extract`, `card` | The controller layers | The facts and the card of a session |
| `reviewer` | `reviewed` | If a call is safe |

- **What the optimizers tune.** The work rules in the policy instructions. The API reference is an input field, so
  GEPA cannot change it.
- **The thought.** The policy has `text` and `code`. A `reasoning` field is an option of the leaf, and it is on only
  for a model with no native reasoning. `Acted.reasoning` holds either one.
- **Metrics.** A `FeedbackMetric` runs the tests in the fork of the rollout. A memory metric asks questions about old
  messages. A graph metric compares the graph with probes. Critical-path time can be a second score.
- **Rollout tasks.** The first source is the history of this repository: a commit that makes a test pass gives a
  rollout task and a metric.
- **Tests with no model.** A script of `TurnEffects`, and later recorded model streams.

## 10. The runtime

### 10.1 The layers

A runtime enters in two places. The middle stays the same in each option, because the model writes direct-style code
and a cell needs a thread.

```text
Session host: mailbox, writer, worker start and stop, feeds     <- pluggable; first version on a plain thread
Kernel: evolve, next, decide, repair                            pure, one implementation
Worker, cell, REPL: drive on plain threads                      one implementation
Guest API handlers: LM, files, shell, network                   <- pluggable; each handler can use Kyo inside
```

```scala
trait SessionHost:
  def open(id: SessionId, config: SessionConfig): Either[DspyError, SessionRef]

trait SessionRef:
  def send(command: Command): Either[DspyError, Unit]   // into the mailbox; it does not wait for the commit
  def state: SessionState                               // the committed state
  def subscribe(capacity: Int): Feed                    // only committed entries

trait Feed:
  def poll(): Option[Either[Behind, Logged]]            // `Behind`: this client lost entries (10.2)
```

Each of the kernel functions, `drive`, `Journal` and `SessionHost` is free of the types of a runtime. So a Kyo, ZIO or
Pekko host stays possible.

### 10.2 The first session host

- **A thread and a queue.** One virtual thread for each session takes messages from a bounded mailbox, and it is the
  one writer of its journal. A worker commit puts a message with a `CompletableFuture` and waits for it. An interrupt
  of a cell thread reaches only its own wait, so the commit stands.
- **`sqlite-jdbc` for the journal.** One file for each session, at `<root>/sessions/<id>.db`. The writer holds one
  connection for the full activation, and no pool replaces it. One row for each entry, with a primary key on the
  session and `seq`. The primary key rejects a stale writer of that file. The `session` column stays in each file, so
  a copy of the rows moves a session to another file or to a server database. A JSONL file stays possible behind
  `Journal`.
- **A catalog for `list` and search.** `<root>/catalog.db` has one row for each session: the id, the owner, the parent
  of a fork, the status and the last `seq`. This row is the deterministic part of a `SessionCard`, so it is a `Fold`
  of the journal. The writer updates the row after a turn ends or when the status changes. After a crash a row can
  lag, so the host lists the folder at its start and folds each journal that differs from its row. A removal of the
  catalog file loses nothing.
- **The flush on SQLite.** Each file uses WAL, `synchronous=NORMAL` and `fullfsync=ON`. For a commit that `needsFlush`
  (4.1), the writer sets `synchronous=FULL` and then sets `NORMAL` again. `synchronous` belongs to a connection, so
  the writer holds one connection. On macOS `fullfsync` uses `F_FULLFSYNC`. On other systems it does nothing, and
  `FULL` uses a plain `fsync`.
- **The end of a session.** The writer sets a flag and then fails each message in the mailbox. A caller checks the
  flag after its put, so a message after the last drain also fails. So a call after the end gets an error, and it does
  not wait for ever.
- **A failed write.** It ends this session only. Each caller that waits gets the error, and the other sessions go on.
- **Feeds.** The writer publishes an entry only after its commit, and it never waits for a client. If the queue of a
  client is full, the writer empties it and marks the client as `Behind`. The client subscribes again, reads the
  journal from its last `seq`, and skips each entry with a `seq` that it has. So it misses nothing.
- **Timers.** A stop starts a timer for the grace period of 10.3. The timer only puts a message in the mailbox, so the
  writer stays the one place that commits.
- **The JDK.** The host needs no Kyo and no JDK 25. The checks of section 12 ran on JDK 21 and on JDK 25, and dspy4s
  stays on JDK 21 (choice 8).

One file for each session makes a session one item on disk. A person can remove or archive it as one file. A corrupt
file, a stuck transaction or a large WAL stops only one session, and two terminals need no coordination. The graph of
a session can stay in its file.

Throughput does not decide this, because an LM step takes seconds. The wait time does. The lock of SQLite has no fair
order. In the check of section 12, a writer on one shared file waited for up to 2.7 s. A shared file would need one
writer for all sessions, and that works only inside one process.

The costs: `list` needs the catalog, and a report needs the report pass of 8.6. The parent of a journal fork must stay
until each fork has copied its prefix. 10,000 rollouts also make 10,000 files of at least 16 KB. The primary key holds
the session, so a later version can put the rollouts of one optimizer run into one file. Phase 6 shows if this is
necessary.

[CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md) has the measurements and the limits.

### 10.3 The path of a hard stop

1. `Abort` arrives as a message, and the writer commits `StopRequested`.
2. The host sets the stop flag and interrupts the cell thread. The worker thread gets no interrupt, so its commits
   complete.
3. The host stops the LM transport and the process groups of the running `exec` calls: TERM, a grace period, then
   KILL.
4. The cell ends, and the worker commits the closing entries and `TurnEnded(Aborted)`.
5. If the cell does not end after a grace period, the host marks the process as unclean (protection 3 of Phase 0). The
   writer then commits the closing entries. A later commit from that worker gets a rejection.

A stop never cuts a commit, because the commit runs in the writer.

**The stop of a job.** An `Abort` stops no job, because a job must be able to outlive a turn, for example `sbt ~test`.
`StopJobs` stops jobs, and it also works with no open turn:

```scala
enum JobSelector derives Schema:
  case One(job: EffectId)
  case OfLastTurn                   // the open turn, or the last turn if no turn is open
  case All

final case class JobRecord(turn: Long, stopRequested: Boolean) derives Schema
```

1. The writer commits `JobsStopRequested`, and `evolve` marks the jobs that the selector covers.
2. The host copies the marks to its job table. It stops the process groups of these jobs: TERM, a grace period, then
   KILL.
3. A `spawn` in flight finds the mark in the job table. It does not start its process, and it returns an error.

The list of jobs is a fold of the journal: `EffectStarted` with the op `spawn` adds a job. So a stop also covers a
`spawn` that has an intent and no process yet. It also covers a `spawn` that a stop cut, whose `EffectDone` is
`Unknown`. The job table holds only the live part: the process groups, the output and the marks. `stopJob(job)` in a
cell is `StopJobs(One(job))`, and `Ended` marks all jobs.

A client has two controls:

- **Stop** sends `Abort`. If a job of that turn still runs, the client says so and offers to stop it.
- **Stop all** sends `Abort`, then `StopJobs(OfLastTurn)`. After `StopRequested`, `decide` accepts no new intent, so
  no job of that turn can start later.

**A crash of the host.** A process can outlive its host. So each job runs under a small wrapper that holds a pipe to
the host. When the pipe closes, the wrapper stops the process group. After a restart the job table is empty, and
`status` reports a job with no mark and no row as lost.

### 10.4 Distribution

Distribution comes later. "Sessions in a cluster" means three different things:

| Meaning | What it needs | Sharding? |
|---|---|---|
| Rollouts on many machines | A job queue. A failed rollout runs again. | No |
| A session on one machine, and its tools on another | A remote handler behind `Files` and `Shell` | No |
| Many sessions on many machines, addressed by id | One writer for each session, and routing | Yes |

Only a multi-user server needs the third meaning. An idle session with a clean scope has its state in the journal and
in a git commit, so it can reopen on another machine. A session in a turn cannot move.

With files, this move is a copy of one file. The host closes the file first, and SQLite then moves the WAL into it. A
copy has no fence, because each machine then has its own file. So the first machine must stop all writes to that
session. The files stay on a local disk, because WAL needs one machine.

The third meaning needs one database that all machines can reach, behind `Journal`, with the same table. The primary
key of the journal is the fence there. JDBC also reaches a server database, but no check has run one.

### 10.5 The modules

| Module | Contents | Depends on |
|---|---|---|
| `harness-guest` | The guest API traits, `Data`. The source text is the API reference. | zio-blocks only |
| `harness-kernel` | `Entry`, `SessionState`, `evolve`, `next`, `decide`, `repair`, `reports`, `Fold`, `Mode`, `ContextStrategy`, the memory tree | zio-blocks only |
| `harness-repl` | `ScalaRepl`, the protections of Phase 0 | `harness-guest`, `scala3-repl` |
| `harness-mcp` | The early MCP server of section 6 | `harness-repl`, the MCP Java SDK |
| `harness-sandbox` | Seatbelt, bubblewrap, the profiles | `harness-kernel` |
| `harness` | `Harness`, `drive`, the handlers, the leaves | The modules above, dspy4s `programs` |
| `harness-host` | The session host, the SQLite journal, the catalog, the report pass | `harness`, `sqlite-jdbc` |
| `harness-cli` | The terminal client | `harness-host` |

## 11. The kinds of state, and the questions that test them

[CODE_HARNESS_CONCEPTS.md](CODE_HARNESS_CONCEPTS.md) lists eight kinds of state. Each kind has one model:

| Kind | Model |
|---|---|
| Control | `Position`, `stopping` and `closed`: a fold of the journal |
| Record | The journal |
| Data | `SessionConfig` in `Activated`, and the store: a fold of the journal |
| Derived | A `Fold` if it is pure. A `Derivation` with a journal of its own if an LM makes it. |
| Live | The activation. Nothing outside it depends on it, and a restart makes a new one. |
| World | A handler. `ScopeRecorded` records the head, and a turn reads the world again. |
| Remote | Not modeled. The prompt cache is a benefit of a stable prefix, and a provider handle is stored as data. |
| Transient | A published event or a stream delta. It is never state. |

The answers to the scenarios of [CODE_HARNESS_CONCEPTS.md](CODE_HARNESS_CONCEPTS.md), section 5:

| # | Scenario | Answer |
|---|---|---|
| 1 | A message during a 10-minute cell | The cell does not see it and does not stop. The `Input` commits at once, and the policy reads it at the next step. |
| 2 | `sbt ~test` with `spawn`, and the turn ends | The session owns the job. It stays until `stopJob`, `StopJobs` or the end of the session. An `Abort` does not stop it. |
| 3 | A session closes and opens the next day on another machine | It is the same session: one id and one journal, which moves as one file (10.4). A new activation and a new REPL start, and the store comes back. The scope comes from `ScopeRecorded` if it was clean. |
| 4 | `val x`, then the next line throws | A later cell gets `Not found: x`, and it still sees the earlier definitions. |
| 5 | A rollout forks a session at entry `k` | A journal fork is a pointer to the parent and `k`. The workspace fork starts at the last clean `ScopeRecorded` at or before `k`. The REPL is new, and the store is the fold up to `k`. The fork has no grants. No process comes along, and the prompt prefix stays the same. |
| 6 | `rlm(...)`, and the parent crashes during the call | Both views hold: it is an effect of the parent, and a session of its own. The effect gets `Unknown`. The child gets `Crashed` at its next activation, and nothing runs it again. |
| 7 | A reply with text and no code | In a chat turn, it ends the turn. In a program call, the `finisher` leaf makes the output. |
| 8 | An approval that comes two days later | A cell never waits for a person (5.5). The answer is a `Grant` and a new turn. |
| 9 | A compaction between two steps | It belongs to no unit. The derivation runs in the background, and a step reads what is built. An `Abort` does not cut it. |
| 10 | A cell yields after 10 s | A cell does not yield. Long work is a job, so no new step runs during a cell. |

## 12. What ran

The checks used Scala `3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY` and JDK 25.0.5, and the plain session host, Phase 0
and the start probe also ran on JDK 21.0.11. The code is in `tmp/design-check/`, `tmp/kyo-check/` and
`tmp/plain-host/`, and it is not committed.

| Check | Result |
|---|---|
| The kernel under random schedules, with no threads | 200,000 runs with 318,283 crashes, 223,402 stops, 70,885 late messages, 684,647 grants, 55,191 calls under a `Once` grant, 65,382 jobs and 19,289 `spawn` calls that a stop refused. The calls go through `guardCheck`: 50,606 gave a pending call, 11,733 grants answered a pending call, and 189,989 grants were repeats from a client. Each crash read the journal through the JSON codec of `Logged`: 7,785,588 entries, with no change. No run broke a checked law of 4.7. A second phase of 50,000 runs had 132,952 power losses, which cut the journal at the last flush, and no run broke a law. The 485,904 values of inputs stayed a fold of the journal. The law One journal did not run. For the law Report, only `reports` ran: 618,665 reports match the turns that owe one. |
| The check of that check | Thirty-six mutants. Fifteen for the kernel: keep the waiting inputs after a stop (78,443 runs fail), accept an effect of any cell (8,584), keep a late outcome (17,704), remove a `Once` grant at `EffectStarted` (45,405), keep a `Once` grant after its call (21,636), keep a `Turn` grant after its turn (117,250), keep a grant after `Ended` (48,318), let an `Abort` stop jobs (50,090), keep the jobs after `Ended` (11,205), let `OfLastTurn` reach other turns (15,957), take the list of jobs only from the live table (9,548), no flush at all (43,719 runs of the second phase fail), a flush at the outcome in place of the intent (205), no flush for `Input` (32,374), no flush for `Activated` (13,946). Nine for approvals: nothing in `pending` (33,116), keep a pending call after a new turn (17,465), no check of a repeated `Grant` (98,346), no `Input` after an answer (14,913), reports only for the owner (136,829), a pending call in a session with no person (33,430), a grant above a deny rule (14,216), the first rule wins (39,446), and a goal check while a pending call waits. Only the fixed flows find the last one: 6 turns in place of 1. Six for the values: a wrong field in a preamble (17 problems), a preamble with no givens (2), a host that reads with `Schema` (1), no values in the state (138,589 runs), values in the store (138,589), and a fork with no values (138,589). Six for the plain session host: no drain at the end, no answer after a failed write, no timer, a writer that waits for a client, a silent drop, and a publish before the commit. Each one fails a fixed flow of the host. |
| `drive` with scripts | A chat answer, `submit`, the step limit of both kinds, a failed cell, a failed LM call, a stop during a cell, and a crash during a cell give the expected journals. |
| The fixed flows of an approval | A session from `start` asks, the person approves, and the owner gets two reports, the last with the result. A repeat of the `Grant` commits nothing, also after the call. A typed message gives no report. A session with a goal uses one turn while it waits. A child and a rollout get a plain error. `Verdict` is a semilattice, `verdict` is a homomorphism from `Guard`, and a grant never widens a deny rule. |
| A denied call in a real REPL with safe mode, with a stub of the handler | With no `catch`, the cell stops, and a later cell does not see its `val`. With a `catch` of `NeedsApproval` or `Exception`, or with `scala.util.Try`, the cell continues and keeps its values. Safe mode rejects `NeedsApproval(...)` in a cell. |
| `Mode` | The semilattice laws hold over 81 modes. A meet with `Mode.closed` never gives `Sessions^`, and it keeps `Children^`. |
| Modes as givens in a real REPL, with capture checking and safe mode | Read-only mode: `read` compiles, and `write`, `exec`, `spawn`, `stopJob` and `tell` do not. Controller: `tell` compiles, and `write`, `exec` and `stopJob` do not. Local: all compile. Read-only also rejects `rlm` and `start`. Controller: `rlm` and `start` compile. Closed: `rlm`, `write` and `exec` compile, and `start` and `tell` do not. `rlmBatched` follows `rlm`, `waitJob` needs `Shell^`, and `llmQueryBatched` compiles in all four modes. |
| Safe mode in the same REPL | It rejects `asInstanceOf`, the name of the host, `caps.unsafe`, `@assumeSafe`, `Thread`, `Future`, `CompletableFuture`, `Executors`, `System.exit`, `System.currentTimeMillis`, `System.nanoTime`, `System.getenv`, `sys.env`, `java.io.File`, `java.nio.file.Files`, `scala.io.Source`, `Runtime`, `Predef.println`, `java.util.Random`, `UUID.randomUUID` and the `Schema` of zio-blocks. It allows `java.time.Instant.now()`, `java.time.Clock`, `scala.util.Random`, `Math.random()` and `Object.hashCode`. |
| The JSON codec of `DynamicValue` | 19 of 30 kinds of value change or throw. `Long(5)` comes back as `Int(5)`, `Double(2.5)` as a `BigDecimal`, a `Variant` and a `Map` as a `Record`. NaN, the infinities and a record as a map key throw at the encode. A `GuardRule` with `Exact` matches its call before the codec, and it does not match after the codec. |
| The lost names from the journal, with the parser only | 51 cells: 21 that cover patterns, givens, extension methods, imports, enums, types, a name that a later cell defines again, a compile error and a failure at run time, and 30 from a model. The parser read the 44 cells that completed in 9 ms. It found each name that the old REPL completes and a new REPL does not, apart from the names that the REPL makes: `res0`, the wrapper objects and the names of anonymous givens. It also found two types that the completion of the REPL does not list. The names of the 7 failed cells were absent from the old REPL too. |
| The environment of a command and the mask of secrets, with fake values | With all variables, `env` showed 3 of 3 secret variables, and with the allowlist of 5.6 it showed 0. A normal command ran, and a secret name that a session adds did not go through. The mask hid a key that a command printed from a file, and a key in JSON. On 100,000 random texts, no known value stayed, and a second mask changed nothing. A key cut across two lines and a key in base64 stayed. |
| The early MCP server on the MCP Java SDK 2.1.0, with a real REPL, on JDK 21 | 35 calls of `run_cell` with prints, definitions, a compile error, an exception, and a library that prints to stdout during a cell and 50 ms after it. With stdout as the wire and no rule: 6 calls worked, and after the late print each later call timed out. With the rule of section 6: 35 of 35 worked, with a median of 14 ms for each call. |
| A model value with the name of a given of the preamble | `val files`, `val state`, `val shell`, `val llm`, `val peers` and `val children` did not hide the capability from a later cell. |
| `Data` with `decode`, in a real REPL | Enums, sealed traits, recursion, mutual recursion, generic types, tuples, named tuples and maps come back equal through the journal text. The compiler rejects capabilities, functions, `Any`, plain classes, iterators, lazy lists and mutable buffers. |
| The laws of `Data` on random values | 300,000 values, with 0 failures. The first run found that the reader rounds a number to 34 digits. |
| The forms of `Data` and `Schema` | Of 13 kinds of value, 7 differ, and each reader fails on the text of the other. A plain record, an enum case with fields, a `Map` with string keys, a `Set`, an `Instant` and a tuple have one form. |
| A child from a `shape`, in two real REPLs with capture checking and safe mode | 15 kinds of value: records, `Option`, enums, nested types, recursion, mutual recursion, generic types, reserved words, maps, tuples, named tuples, `Either`, `Set`, `Instant` and sealed traits. 13 go from the parent to the child and back, and they come back equal. 2 give the plain error of 5.1. |
| A program call in a real REPL | The host has `In` and `Out`, and the REPL has only the preamble. The cell sees the input fields as typed values, a wrong argument is a compile error, and `Data[Out]` reads the value. `Schema[Out]` fails on the same text. A new REPL with the same text gives the same output. |
| Cells that models wrote, from 30 tasks | Haiku and Sonnet did not know the design, and each wrote the 30 cells twice. A local model wrote 8. See the table after this one. |
| `Schema.derived` inside an inline of the guest API | In a capture-checked cell, the compiler crashes with `RecursionOverflow`, and the exception stops the process. |
| `parallelMap(items)(f)` on real threads, in a real REPL with the stop flag | The work runs at the same time: 8 tasks of 200 ms took 389 ms, and 16 took 591 ms with a limit of 8. The results keep their order, a failed task waits for the others, and a stop ended a loop and a blocked call in a task, with no task left. It has three problems. A task that uses a `val` of its own cell hangs until the stop, and so does a `def` of the cell that holds the call. A thread dump shows the cause: the cell runs in the class initializer of its wrapper object and waits for the tasks, and each task waits on the class initialization monitor of that object. The capture checker accepts a local `var` that tasks share, and 8 tasks gave 840,409 of 1,600,000 updates. It also accepts two tasks that write with one `Files^`. |
| Batch calls and job calls in the same REPL | `llmQueryBatched`, `rlmBatched`, `waitJob` and `spawn(..., report = true)` compile, and a batch has no hang with a definition of its cell. Their handlers are stubs. |
| The second try, with the parser of the compiler | After a failed second part, a later cell cannot use the class, as a term or as a type, and it can define the name again. A class that needs a `val` of its cell compiles only with no split, so the host does not split each cell. |
| A session host on `kyo-actor` with threads | 2,000 sessions with a stop at a random time: no violation ([CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md), 4.1). |
| A commit through `kyo-actor` to SQLite | About 140 µs from a plain thread and back, with no full flush of the disk cache. |
| A plain session host against a session host on `kyo-actor` and `kyo-sql-sqlite`, each with one SQLite file for each session, on JDK 25 | Both: 2,000 sessions with a stop at a random time and the disk equal to the live state, and SIGKILL in 20 rounds with no lost answer and no corrupt file. A normal commit: 49 µs against 355 µs. A commit with a flush: 4.2 ms against 5.1 ms. 2,000 open sessions: heap +15 MB against +35 MB. The two hosts have 80 and 75 lines, with no feeds and no timer ([CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md), section 9). |
| The pool of the Kyo driver | It replaces an idle connection, by default after 10 minutes, and `fullfsync` goes from 1 to 0 with no error. With an idle time of 2 s, a commit with a flush went from 7.5 ms to 2.0 ms. |
| The plain session host of 10.2 on JDK 21 and on JDK 25 | 2,000 sessions one after the other and 256 at the same time: no problem, and the disk equals the live state. SIGKILL in 20 rounds: 35,736 and 61,326 commits with an answer, none lost, and no corrupt file. A normal commit: 59 µs and 66 µs. A commit with a flush: 4.0 ms and 4.4 ms. With the feeds, the timer and the rules for the end, the host has about 145 lines. |
| The plain session host with fixed flows | A call after the close gets an error. A failed write gives the error to the caller, and another session goes on. A cell that ignores a stop ends at the timer, and later commits from its worker get a rejection. A client that reads too slowly does not stop the writer, and it gets 20,005 entries once and in order. A client saw 200 of 200 entries only after their commit. |
| The two file layouts of 10.2, with Python's SQLite 3.49.1 and not `sqlite-jdbc` | 64 writers: 5,300 commits per second and a longest wait of 45 ms with one file for each session, and 3,500 and 2.7 s with one shared file. Section 2 of [CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md) has all the numbers. |
| `PRAGMA fullfsync` in the same check | One writer: 5 to 9 ms for each commit, against 75 µs without it. |
| A flush for only some commits, with the same SQLite and one writer | A commit that flushes: a median of 4.4 ms. A normal commit: a median of 48 µs, and 266 µs right after a flush. A cell of 100 `write` calls spends 0.54 s in commits, and a cell of 100 `read` calls spends 0.03 s. |
| SIGKILL of a writer with normal commits, with the same SQLite | 50 rounds with 29,016 commits that got an answer: none lost, and no corrupt file. |
| dspy4s on JDK 25 | A compile from no classes passes, and 2,172 tests have no failures (6 skipped). |
| The Phase 0 experiment on JDK 21.0.11 and on JDK 25.0.5 | The same 43 checks pass on both, and the same 3 known problems fail. The first REPL in a JVM: 1,048 ms and 1,026 ms. The heap after 600 cells: 349 MiB and 348 MiB. |
| A new JVM up to the end of the first harness cell, with the preamble of 5.2 | JDK 21: 2.51 s. JDK 25: 2.51 s. JDK 25 with an AOT cache from one training run: 1.69 s, and the cache file has 64 MB. Each number is a median of 5 runs. |
| `sqlite-jdbc` on JDK 25 | It prints a warning about native access, and the warning names the flag `--enable-native-access=ALL-UNNAMED`. JDK 21 prints no warning. |

The cells that models wrote. A cell counts if it compiled, so the table does not say that the cell does its task. The
last column has this `Data` and the second try of section 6:

| Model and reference | The earlier `Data` | This `Data` |
|---|---|---|
| Sonnet, the earlier reference | 4 of 30 | 27 of 30 |
| Haiku, the earlier reference | 9 of 30 | 25 of 30 |
| A local model, the earlier reference | 0 of 8 | 5 of 8 |
| Sonnet, the reference of 5.1 | Not run | 29 of 30 |
| Haiku, the reference of 5.1 | Not run | 30 of 30 |

None of the 128 cells had a `Data` error with this `Data`, and the 96 values that reached `store` came back equal.
Twelve other cells failed. Seven used `System.currentTimeMillis` or `nanoTime`, three had model errors or a cut, and
two have the forms in the hints of section 6. With the reference of 5.1, no cell used `System` or `derives Data`, and
each recursive type had its given. A cell took a median of 265 ms and at most 813 ms, with no baseline for comparison.

These checks found errors in the earlier design. They are fixed here:

- A stop before the first step started a new turn.
- On Kyo, an interrupted `ask` ended the session actor.
- `store[A: Schema]` cannot compile in safe mode.
- `DynamicValue` in an entry changed values at a commit, and a grant with `Exact` did not match after a restart.
- A NaN or a record as a map key threw at the encode. So a value of a model could stop a commit.
- The `Data` of the earlier design had no sum types and no `decode`, and it rejected most cells that models wrote.
- A job had no way to wait and no message at its end. The timeout of a cell had no rule for a long guest API call.
- The design had dropped `llmQueryBatched` from the guest API with no decision, and RLM needs it.
- A reading of 5.5 found more errors. The journal held a pending call only as text. A goal asked the person again. A
  child could never get an answer. A repeat of a `Grant` could allow a second call. An owner missed the result of an
  approved turn. The rules had no verdict and no default.
- A reading of 5.1 found more errors. The host read the value of `submit` with `Schema`, which cannot read the form of
  `Data`. A child could not know the type that its parent wants. The design had no route for typed input fields.
- A reading of choice 11 found that a command got the full environment of the harness, with its API keys. Choice 11
  also covered a type that the first version does not have.
- A reading of choice 10 found that the plan put the early MCP server under "Later". It also found that `kyo-mcp`
  would bring in Kyo and JDK 25.
- A reading of choice 9 found two more errors. The note of lost names had no source after a crash. Guards 3 and 5 of
  Phase 0 still used replay.
- A reading of 10.2 found that the pool of the Kyo driver can drop the flush settings of choice 17 with no error. The
  design had also chosen Kyo with no plain session host to compare.

What did not run:

- The kernel with a real REPL and a real LM.
- The AOT cache with a real CLI, on Linux, and after a change of the class path.
- A hard stop that reaches the LM transport and a process group.
- The OS sandbox.
- A real power loss. The kernel check only cuts the journal at the last flush, and the SQLite check only measures the
  time. That a flush reaches the disk and covers the earlier commits comes from the SQLite documentation.
- The flush on Linux, and a `tell` across two journals with a power loss.
- A process kill of the real host during a commit.
- A real model that reads the note of lost names.
- A real `exec` handler with the allowlist, the mask in the real writer, and a key that a model builds again from
  parts.
- A real agent that uses the early MCP server, its tool for the API reference, and the server with the kernel and a
  journal.
- `zoom` on the journal of its own session.
- The length of a note for a long session.
- `load` after a real restart, in a new REPL. The check has one REPL and text from the same process.
- `rlm`, `start`, `observe` and `zoom`. The check has a stub of `rlm`, so its cells only compile.
- `now()` through `recorded`. The check calls the clock directly.
- A real `waitJob`, a real batch, a message at the end of a job, and the clock rule of a cell.
- The kernel with several open calls in one cell, because the test of `parallel` stopped at the REPL.
- The plain session host with a real REPL, a real LM and a real client. Its cells in the checks are plain threads.
- The idle time of 10 minutes of the Kyo pool. The check used 2 s, and the 10 minutes comes from the Kyo source.
- The catalog, `missing` and the report pass.
- A real job: `spawn`, `stopJob`, the job table, the process groups and the wrapper. The kernel check has the job
  table as a small value, with no process.
- `guarded` as a real handler, and a client that sends `Grant`. The kernel check has only `guardCheck`, with no
  handler behind it.
- What real models do after `NeedsApproval`. No test shows that a model ends its turn and asks.
- A denied call in a real child of `rlm`.
- How `submit` ends a cell. The checks have `submit` as a normal call, and F3 says `Nothing` (open question 9).
- A model that reads a preamble, a real child with an LM, and a child that ends with no value.
- The values of a real `Send`. In the REPL checks they come from a system property.
- The full `SessionConfig` of 8.1. The kernel check has only `kind`, `steps`, `guard` and `owner`.
- Any metric or optimizer run.

## 13. What the design leaves out

| Left out | Reason |
|---|---|
| `spawn` for subagents | `rlm` and `start` cover both uses. |
| The yield protocol | Jobs cover long work. |
| Replay in the first version | The store, the preamble and the note are enough for a restart, and replay does not suit the cell limit (6). The journal keeps the option. |
| Fresh cells | A persistent REPL lets the model build on its values. The store covers what must survive. |
| Effects as values for guest code | Models write direct-style code with fewer errors, and handlers give record, replay and guard. |
| `A < S` or `F[_]` as the program type of the harness | A cell needs a thread in each case. The question for `Predict` stays open (RUNTIME 5.4). |
| Agent teams, shared boards | The controller and `tell` cover them. |
| A merge of graphs across sessions | The first version has one graph for each session. |
| Pekko and Cluster Sharding | The session host contract keeps the option (RUNTIME 5.5). |
| `Classified` in the first version | No call gives a classified value, and the rules of 5.6 keep secrets out of cells. |
| `kyo-mcp` for the early MCP server | It needs Kyo and JDK 25 (choices 7 and 8). The MCP Java SDK is already in dspy4s, and it runs on JDK 21 (12). |
| A move to JDK 25 in the first version | It gives only a faster start of a new process, and the CLI comes in phase 7 (12). |
| Kyo for the first session host | The plain session host has the same safety and about the same code, with no pool, no bridge and no release candidate (12). The contracts keep Kyo possible. |
| A cell that waits for a person | A wait of two days holds a thread, and a restart loses it. The cost of the other way is in 5.5. |
| `parallel` in the first version | The hang and the lost updates of section 12. Batch calls cover the main need. |
| Code that outlives its cell | A cell commits only if it completes. A job or `start` covers this need. |
| `Data` as a wrapper around `Schema` | The macro crashes the compiler in a capture-checked cell (12). |
| `Data` that writes the form of `Schema` | `Schema` throws on NaN and on a record as a map key, so a value from a model could stop a commit (12). |
| `rlm` that gives only text, or only the output type of the program | A parent cell then parses text, and the compiler cannot check it. |
| A `finisher` leaf for a child | It needs a `Schema[A]`, and a type from a cell has none. |
| `DynamicValue` in an entry | Its JSON codec changes most kinds of value (12). |

## 14. The choices

The decisions of [CODE_HARNESS.md](CODE_HARNESS.md), 7.10 and 8.7 stay, except decision 1 of 8.7, as item 7 says.
These choices are new in this document, and all of them are confirmed:

| # | Choice | Answers |
|---|---|---|
| 1 | Confirmed on 2026-11-03. The kernel has four pure functions, and `drive` takes the place of `AgentLoop.run` in the harness. RLM stays on `AgentLoop` for now. | |
| 2 | Confirmed on 2026-11-04. `Activated` takes the place of `Started` and `Reopened`, and it holds the configuration. `Child` and `Submitted` are not entries. | |
| 3 | Confirmed on 2026-11-04. All work is a session. `Sessions` and `Children` take the place of `Agents`, and each holds a ceiling. `spawn` for subagents goes away. `rlm` needs `Children^`, and `start` and `tell` need `Sessions^`. A child and a rollout have only `Children^` (5.2). | Question 5 of section 11 |
| 4 | Confirmed on 2026-11-04. Long work is a job. There is no yield protocol. A job has `waitJob` and an optional message at its end, and the clock of a cell stops during a guest API call (6). | Question 2 of 9.4 |
| 5 | Confirmed on 2026-11-04. A cell never waits for a person. A rule says `Allow`, `Ask` or `Deny`. A call with `Ask` fails with `NeedsApproval`, and the journal holds the pending call. A `Grant` with a request id answers it and starts a turn (5.5). A child and a rollout cannot ask. A goal waits for the answer (8.5), and the turn after a grant has a report (8.6). | |
| 6 | Confirmed on 2026-11-05. `Data` is the type of a value that a later cell, another session or the host reads back with a type. Only `Data` reads its form. `Data` has `shape`, and the preamble of a session holds its types and `submit`. An `Input` holds the values of a program call. A child with no `submit` makes `rlm` fail (5.1). | |
| 7 | Confirmed on 2026-11-05. The session host is one plain thread and one mailbox for each session, and the journal is on `sqlite-jdbc`, behind contracts. The turn stays on plain threads. Decision 1 of 8.7 stays, and a Kyo host stays possible (10.2). | Question 2 of 8.7 |
| 8 | Confirmed on 2026-11-05. dspy4s stays on JDK 21 in the first version. The CLI of phase 7 can run on JDK 25 with an AOT cache, and that decision comes with the CLI (12). | |
| 9 | Confirmed on 2026-11-05. Replay is not in the first version. After each restart, a new REPL gets the preamble and the store, and the model gets a note of the lost names, which the parser finds in the journal. The cell limit and an unclean host restart in the same way (6). | Question 1 of 8.7, open question 6 |
| 10 | Confirmed on 2026-11-05. The early MCP server uses the MCP Java SDK, and it comes in phase 2 as `harness-mcp`. Only the transport writes to stdout. Until the sandbox of phase 3, the server starts only the read-only mode (6). | Question 4 of 9.4 |
| 11 | Confirmed on 2026-11-05. A secret never enters a cell: `secretHeaders`, unreadable secret paths, an allowlist of environment variables for a command, and a mask of the known values at each commit (5.6). `Classified` comes later. | Question 5 of 9.4 |
| 12 | Confirmed on 2026-11-05. The words of section 3, with the rows Host, Input, Guard and Pending call. The protections of Phase 0, a machine, the input fields of a program call and the items of a release plan have their own words. | Section 4 of the concept inventory |
| 13 | Confirmed on 2026-11-03. One commit writes to one journal (4.1). The journal has one SQLite file for each session, with a catalog that a fold can rebuild (10.2). The report pass sends again a report that a crash cut (8.6). | Open question 1 below |
| 14 | Confirmed on 2026-11-03. Each `Grant` holds a lifetime: `Once` (the default), `Turn` or `Session`. `GuardRule` is data with a pure `matches`. A journal fork has no grants (5.5). | Open question 2 below |
| 15 | Confirmed on 2026-11-03. An `Abort` does not stop jobs. `StopJobs` does, also with no open turn, and a client can send both with one key. The list of jobs is a fold of the journal (10.3). After a crash, a job is lost in the first version. | Open question 3 below |
| 16 | Confirmed on 2026-11-03. `Data` derives from `Mirror.Of`, and a guest does not write `derives Data` (5.1). A value in an entry is `Json` (4.1). The guest API has `now()`. After one capture error, the host compiles the type definitions of a cell first (6). | Open question 4 below |
| 17 | Confirmed on 2026-11-03. A commit does a full flush for the intent of a call that can change something outside the session, for each `Input` and for each `Activated` (4.1, 10.2). A power loss can remove the other commits, and a process crash cannot. | Open question 5 below |
| 18 | Confirmed on 2026-11-04. A cell runs one call at a time. `llmQueryBatched` and `rlmBatched` are one call each, and the host runs their work at the same time. `parallel` is not in the first version (12). | |

Open questions:

1. Closed by choice 13: one SQLite file for each session.
2. Closed by choice 14: each grant holds its lifetime.
3. Closed by choice 15: an `Abort` does not stop jobs, and `StopJobs` does.
4. Closed by choice 16: `Mirror` is enough, with `Json` in the entries.
5. Closed by choice 17: a commit does a full flush only where a power loss can do harm.
6. Closed by choice 9: the first version needs no check of the clock and random numbers. A later replay stops at the
   first divergence ([CODE_HARNESS.md](CODE_HARNESS.md), 8.4).
7. What must change before `parallel` can come? A task must be able to use a `val` of its own cell. A shared `var`
   needs a rule, and the `Once` rule of 5.5 must change.
8. How does an owner get the result when a person answers a plain question by typing? That turn has no report (8.6),
   so the owner can wait for a result that it never gets. Choice 5 fixes this only for an answer through `Grant`.
9. How does `submit` end a cell? F3 says `Nothing`, so a throw is the plain way. A cell with a `catch` of `Throwable`
   can stop that throw, and a test must show what the host does then.

## 15. The plan

Each phase ends with something that runs.

| Phase | Work | Result |
|---|---|---|
| 1. Kernel | `harness-kernel`, with `pending`, `inputs`, `Guard` and `guardCheck`, the laws as ScalaCheck properties and the mutants of section 12. No REPL and no LM. | The laws of 4.7 run in CI. |
| 2. Guest API and REPL | `harness-guest`, `harness-repl`, the modes, the protections of Phase 0, the laws of `Data` as properties, `shape` and the preamble, the note of lost names, the second try of a cell, the catch around `ReplDriver.run`, the clock of a cell, the error hints, negative tests for modes, and `harness-mcp`. | Model code runs against the guest API in each mode, and another agent can run cells through MCP. |
| 3. Sandbox | `harness-sandbox`: Seatbelt and bubblewrap, profiles from modes, the allowlist of environment variables, and escape tests. | `exec` runs only in a sandbox. |
| 4. One task | `Harness`, `drive`, the window strategy, `OptimizableStructure`, `ProgramRunner`, the mask of secrets. | A harness for one task that the optimizers accept. |
| 5. RLM | `llmQuery`, `llmQueryBatched`, `rlm` and `rlmBatched` behind `Children^`, a child from a `shape`, the shared budget, the depth limit. | RLM in the harness. |
| 6. Optimization | A metric from tests, a small task set, one GEPA run. | A first measured improvement, or a reason for its absence. |
| 7. The session host | `harness-host`: the writer, feeds, timers, one SQLite file for each session, the catalog, the path of a hard stop, `StopJobs`, `waitJob`, the message of a job and the job table, crash tests with SIGKILL that also check that no job process stays. `guarded`, and the client side of `Grant`. `harness-cli`, its JDK and an AOT cache (choice 8). | A coding agent for daily use, one session at a time. |
| 8. Memory | The memory tree and its metric. | Long sessions that lose no detail. |
| 9. The controller | `Sessions`, the report pass with a crash test, the ledger, the graph, probes, release plans. | One controller above all sessions. |
| Later | Replay, the reviewer, jobs across a restart, `parallel` if batch calls are not enough, a container backend, LSP as a guest API, prompt-cache marks. | |

Phase 6 comes before the session host on purpose. The optimizers are what makes this harness different from the
others, so the design must meet them early.
