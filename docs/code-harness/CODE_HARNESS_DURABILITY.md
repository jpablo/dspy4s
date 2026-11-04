# Code harness: durability compared

Status: analysis, 2026-10-25. This document compares what survives a crash, a restart or a close in each
analyzed harness, and if the work continues. It adds to section 8 of [CODE_HARNESS.md](CODE_HARNESS.md), and it
gives facts to the analysis of execution and runtime models. The facts come from the source code of each project
on 2026-10-25.

## Summary

- Durability means that the state of an agent survives a crash, a restart or an eviction of the process. The
  strongest form also continues the work from the last committed point.
- The harnesses have five levels of durability: none, resume of the conversation, crash repair, continuation by
  a new turn, and durable execution (section 2).
- Only `pi-durable`, a separate package of pi, has durable execution. Its tasks continue from checkpoints, and its
  code-mode scripts replay from a journal.
- uniichat continues the work by itself after a restart, with a new turn. deepseek-harness repairs its log, and
  the interrupted turn ends. atc, the pi `coding-agent` and Codex can resume a conversation, but a crash loses
  the run in progress. tacit has no durability.
- No harness gives exactly-once effects. A tool call that a crash cuts has an unknown outcome. Each harness that
  records the call reports the unknown outcome to the model, and does not run the call again. Only `pi-durable`
  runs a call again, and only if the tool declares `replay: "safe"`.
- No harness keeps the state of a persistent REPL across a restart.

## 1. Terms

| Term | Meaning |
|---|---|
| Process crash | The process stops with no clean-up: a kill, an out-of-memory error, a bug. The files that the process wrote stay. |
| Host crash | The machine stops: power, kernel. Data that the operating system did not write to the disk is lost. Only `fsync` protects against this. |
| Intent record | A record in the log before an external effect. It is also called a write-ahead record. |
| Unknown outcome | An effect that has an intent record, but no outcome record. The effect possibly occurred. |
| At most once | After a crash, the harness does not do the effect again. The effect possibly never occurs. |
| At least once | After a crash, the harness does the effect again. The effect possibly occurs two times. |
| Idempotency key | An identifier that lets the receiver of an effect find a repeated request, and do the effect only once. |
| Checkpoint | A saved state from which a task continues. |
| Replay | A new run of deterministic code, in which recorded results take the place of the effects. |

## 2. Levels of durability

| Level | What survives a crash | What occurs after the crash | Harnesses |
|---|---|---|---|
| 0. None | Nothing | The session is gone. | tacit |
| 1. Resume of the conversation | The transcript, up to its last write | The run in progress is lost. The user can open the conversation again and continue it. | atc, pi `coding-agent`, Codex |
| 2. Crash repair | The transcript, and the log is made consistent | The interrupted turn ends as `interrupted`, with an error result for each call that has no result. | deepseek-harness |
| 3. Continuation by a new turn | The transcript and the list of the work that ran | The harness starts a new turn by itself, with a note about the restart. The model plans again from the log. | uniichat (Codex only after a managed restart of its daemon) |
| 4. Durable execution | The state of each task, with a checkpoint | Each task continues from its last checkpoint. Code replays from a journal. | `pi-durable` |

The levels are not a strict order. For example, uniichat continues the work, but it does not append closing
events to its log as deepseek-harness does.

## 3. The harnesses

### 3.1 `pi-durable` (`/Users/jpablo/GitHub/pi-mono/packages/durable`)

`@earendil-works/pi-durable` is a second agent harness of pi, next to the `coding-agent`. The README marks it as
experimental. Its normative specification is `docs/spec.md`. Its core rule:

> A Session atomically commits immutable entries, full task records, and Chord-tracked documents. Only committed
> state is observable.

| Topic | What `pi-durable` does |
|---|---|
| Storage | Memory, SQLite or JSONL. One SQLite transaction is one commit. Node.js and Cloudflare Durable Objects are the hosts. |
| Write order | A change is visible only after its commit. The spec says: "All visible progress is durable. There is no volatile publication path." |
| Tasks | Each part of a run is a task: a state machine with phases and a checkpoint. `pi.generation` calls the model, and it owns one `pi.tool` task for each tool call. A phase runs again from its top after a restart. |
| Effects | The "effect sandwich" of spec section 5.2: commit the intent, do the effect, then commit the outcome. External effects never run inside a commit. |
| A tool call that a crash cuts | The intent was committed before `execute()`. The tool runs again only if it declares `replay: "safe"`. Else the model gets an `interrupted` error result, with the output committed so far. |
| A model request that a crash cuts | Partial output is committed at most every 100 ms. On recovery, the partial becomes an aborted assistant entry, and the same committed messages go to the model again, with the same model and options. |
| Input | A submission with the same `requestId` finds the existing submission, so a repeated input does not run two times. |
| Sleep | A task can end a phase asleep. The sleep is in storage, and it survives a restart. On Cloudflare, an alarm wakes the process. |
| Code mode | The script runs in a deterministic QuickJS sandbox: a virtual clock, seeded random numbers, and UTC. Each result goes into a journal before the script gets it. After a restart, the script runs again with the recorded results. A different path, a different sandbox fingerprint or code-mode version, or an expired `timeout_ms` ends the call with an error. |
| Crash loops | Each restart that cuts a task counts. At 5 restarts (`settings.restartLimit`), the task is "parked", and a host can retry it. |
| Disk | The JSONL backend has an `fsync` option that defaults to `false`. Without it, a commit survives a process crash, but a host crash can lose the last commits. |
| Versions | Each task definition has a version. A task whose definition is missing or cannot migrate waits as "blocked". |
| Tests | 38 test files reopen the storage after a simulated crash, in the same process. No test kills a process. |

The effect sandwich, as a sketch with the types of [CODE_HARNESS_LOOPS.md](CODE_HARNESS_LOOPS.md), section 3:

```scala
/** The record of one tool call in the log. */
enum CallRecord:
  case Intent(call: ToolCall)                      // committed before the effect
  case Outcome(call: ToolCall, result: ToolResult) // committed after the effect

/** After a reopen: the result of a call. An intent with no outcome is an effect with an unknown outcome. */
def recover(record: CallRecord, replaySafe: Boolean, run: ToolCall => ToolResult): ToolResult =
  record match
    case CallRecord.Outcome(_, result)         => result    // finished: never run it again
    case CallRecord.Intent(call) if replaySafe => run(call) // at least once
    case CallRecord.Intent(call)               =>           // at most once
      ToolResult(call.id, "interrupted: the outcome is unknown", isError = true)
```

The replay of a code-mode script, as a sketch:

```scala
/** One call of a script and the result that the script got. */
final case class JournalEntry(request: String, result: String)

final class Diverged(request: String) extends Exception(s"the script took another path at $request")

/** Runs a deterministic `script` again after a restart. The calls in the journal give their recorded results, and
  * no effect occurs. The first new call is a real effect, and its result goes into the journal before the script
  * gets it.
  */
def replay(
    script : (String => String) => String,
    journal: Vector[JournalEntry],
    call   : String => String,
    record : JournalEntry => Unit
): String =
  var next = 0
  script { request =>
    if next < journal.size then
      val entry = journal(next)
      next += 1
      if entry.request != request then throw Diverged(request)
      entry.result
    else
      val result = call(request)
      record(JournalEntry(request, result))
      result
  }
```

Both sketches compile with Scala 3.8.

### 3.2 uniichat (`/Users/jpablo/GitHub/uniichat`)

| Topic | What uniichat does |
|---|---|
| Storage | One JSONL file for each day in `main/` and `tree/`, binary index files, `view.json`, agent logs in `docs/agent/`, and a SQLite table of acts. |
| Write order | A user message goes into the log when it arrives, before the model gets it. The entries of a model step go into the log when the step ends. Stream deltas are never logged. A tool call stays in memory, and it goes into the log with its result. |
| Disk | Each write is a `writeSync` append, followed by a queued flush. `Disk.ts` says: "A process crash loses nothing; a machine's, about the last flush." |
| After a restart | The supervisor starts the worker again. `Chat.wake` opens each chat with work in `view.json`. `Chat.resume` (`Server.ts:969`) logs the note "The server restarted, cutting …; nothing was lost: go on.", starts a new turn on it, and sends the same text to each running agent. |
| A model request that a crash cuts | Lost. The steps that ended before stay in the log. |
| A tool call that a crash cuts | Not in the log. When the terminal connects again, it aborts its jobs. An effect that occurred on the device stays, with no record. |
| Subagents | Each subagent starts again from its own log. Its request in progress is lost. |
| Acts | An act is an action of a client, for example `say`, `import` or `stop`. An act that would change the chat again if done again is "logged (Chat.mark) before any effect off the log, or as the last page of what it logs" (`Server.ts:1194`). The act id prevents a second run. A tool job and its output (`out`) are not acts that the log records. |
| Repair | A line that a crash cut is skipped, and a newline is added. A line that is not JSON is skipped. |
| Tests | None kills a process in the middle of a turn. |

### 3.3 deepseek-harness (`/Users/jpablo/GitHub/deepseek-harness/packages`)

| Topic | What deepseek-harness does |
|---|---|
| Storage | A JSONL log of typed events, in checksummed Zstd frames by default, under `~/.dsh/sessions/`. |
| Write order | Listeners, among them the UI, see an event before it is durable, because the writer buffers events for up to 200 ms. The checkpoint policy flushes the log before each model request, before each top-level tool, and at each `agent/pre-step`. |
| Disk | `fsync` for each append batch. |
| Intent record | `tool/call` goes into the log before the tool runs, and the log is flushed before the tool body runs. The README calls this "durable execution intent, not exactly-once effects", and it suggests `exec.callId` as an idempotency key for tools. |
| Resume | `--resume` or `--session-id`. The resume reads the full log and appends closing events. Then a new session starts from the log. |
| A tool call that a crash cuts | The resume appends an error result `TOOL_OUTCOME_UNKNOWN` ("Do not retry blindly"), or `TOOL_NOT_STARTED`. The call never runs again by itself. |
| A model request that a crash cuts | Lost. The turn ends as `turn/end{interrupted}`. |
| Inbox | Durable, through `agent/inbox/spliced` events. A restored message does not wake the driver. |
| Lost after a restart | The memory of the PTC runtime, local jobs, and the armed state of a goal. |
| Repair | The semantic repair never removes an event. It appends error results, an open `step/end`, and `turn/end{interrupted}`. The writer removes the bytes after the last valid record of the open turn before its first new append. Damage before a later `turn/end` is corruption, and an unknown required event refuses the full log. |
| Tests | `session-checkpoint-policy/tests/crash-recovery.e2e.ts` kills a process with SIGKILL during a model request and during a tool call. |

### 3.4 Codex (`/Users/jpablo/GitHub/codex/codex-rs`)

| Topic | What Codex does |
|---|---|
| Storage | A rollout JSONL file for each thread, and SQLite databases. The SQLite history is "a rebuildable view" of the JSONL. |
| Write order | An item goes into the rollout before Codex delivers its event to the clients. A tool call goes into the rollout before Codex dispatches it. Stream deltas are not written. |
| Disk | `flush()` after each line, with no `fsync`. |
| Resume | `codex resume` rebuilds the history from the newest compaction and the items after it. |
| A tool call that a crash cuts | The call is in the rollout with no output. When Codex makes the next prompt, it adds the output `"aborted"` in memory. Nothing goes into the log. |
| A model request that a crash cuts | Lost. The turn has `TurnStarted` and no end event. |
| Input | Steer input is only in memory, and a crash loses it. Messages in the SQLite queue survive. |
| Code mode | The `store` values are in memory, and they commit only when a cell completes. A crash loses them. |
| Managed restart | Only after a planned shutdown of the daemon, Codex appends `TurnAborted(Interrupted)` and starts a new turn with the message "The server restarted and interrupted the previous turn. …" |
| Repair | A missing last newline is added, and lines that do not parse are skipped. |
| Tests | Resume and managed-restart tests exist. None sends SIGKILL in the middle of a turn. |

### 3.5 atc (`/Users/jpablo/GitHub/atc/app/src/atc`)

| Topic | What atc does |
|---|---|
| Storage | One JSON file for each working directory, at `~/.atc/sessions/<sha256(cwd)>.json`: the history, the notes, the task and the to-dos. |
| Write order | After each turn, so the user sees the output first. The comment in `App.scala` says: "The session is saved after every turn, so a closed terminal or a signal loses at most the turn in progress." |
| Disk | A temporary file and an atomic move, with no `fsync`. |
| Resume | At start, atc offers "Resume last session", and `/resume` loads a file. The REPL is new, and the message says "No tool calls were replayed". A note tells the model that each `val` and `def` is gone. |
| A crash in a turn | The full turn is lost: its user message, the model request, the tool calls, the queued input and the REPL state. Nothing records a `run_scala` call. |
| Repair | None. The decoder refuses a file with malformed JSON or an unanswered tool call. |
| Tests | Round trips of the session file. No crash test. |

### 3.6 pi `coding-agent` (`/Users/jpablo/GitHub/pi-mono/packages/coding-agent`)

| Topic | What the pi `coding-agent` does |
|---|---|
| Storage | One JSONL file for each session, under `~/.pi/agent/sessions/`: a header, then entries with `id` and `parentId`. |
| Write order | At `message_end`, after the listeners. A user message is in the file before the model request. The assistant message with the tool calls is in the file before the tools run. A tool result goes into the file after the tool ends. |
| Disk | `appendFileSync`, with no `fsync`. |
| Resume | `--continue`, `--resume` or `--session`. Nothing starts a run again on load. |
| A tool call that a crash cuts | The call is in the file with no result. When pi makes the next request, it adds the result "No result provided" with `isError: true`, in memory only. The tool does not run again. |
| A model request that a crash cuts | Lost. Only the final assistant message goes into the file. |
| Input | Steer and follow-up messages are only in memory. |
| Code mode | A new sandbox for each call. Each script that succeeds appends its `store` values to the session, so a resume keeps them. |
| Repair | Malformed lines are skipped, and a newline is added after an unterminated last line. |
| Tests | Tests of malformed and unterminated files, and of tool calls with no result. No crash test. |

### 3.7 tacit (`/Users/jpablo/GitHub/tacit`)

| Topic | What tacit does |
|---|---|
| Storage | Nothing by default. With `--record <dir>`, two text files for each cell, after the cell ends. No code reads them back. |
| Resume | None. The sessions are in a `TrieMap` in memory. After a restart, an old session id gives `Session not found`. |
| A crash in a cell | The cell and its REPL state are lost, with no record. |
| Tests | No crash or restart test. |

## 4. Side by side

| Topic | `pi-durable` | uniichat | deepseek-harness | Codex | atc | pi `coding-agent` | tacit |
|---|---|---|---|---|---|---|---|
| Level (section 2) | 4 | 3 | 2 | 1 (3 after a managed restart) | 1 | 1 | 0 |
| Durable before it is shown | Yes | Yes, except stream deltas | No (200 ms buffer), but flushed before each effect | Yes, except stream deltas | No | No | Not applicable |
| `fsync` | SQLite transaction; JSONL optional, off by default | No (a queued flush) | Yes, for each batch | No | No | No | Not applicable |
| Intent record before a tool runs | Yes | No | Yes, flushed | Yes (the call item) | No | Yes (the assistant message) | No |
| A tool call that a crash cuts | `interrupted`, or a new run if `replay: "safe"` | Not recorded; jobs aborted | `TOOL_OUTCOME_UNKNOWN`, appended | `"aborted"`, in memory | Lost | "No result provided", in memory | Lost |
| A model request that a crash cuts | Sent again | Lost; a new turn starts | Lost; the turn ends | Lost | Lost | Lost | Not applicable |
| Queued input | Durable | Logged; the queue is in memory | Durable | Steer input lost; the SQLite queue survives | Lost | Lost | Lost |
| Code or REPL state | The script replays | No code mode | PTC memory lost | The store is lost | A new REPL | The store is in the session | Lost |
| Repeated input | `requestId` | Act ids | Inbox message ids | None | None | None | None |
| A torn last line | Removed | Skipped | Removed | Skipped | Atomic move | Skipped | Not applicable |
| A crash test that kills a process | No (simulated in the same process) | No | Yes (SIGKILL) | No | No | No | No |

## 5. What the comparison shows

### 5.1 No exactly-once effects

A harness cannot make an effect occur exactly once. If a crash occurs during an effect, the outcome is unknown,
and the harness must choose:

- **At most once.** Report the unknown outcome to the model, and do not run the call again. deepseek-harness, Codex,
  the pi `coding-agent`, and `pi-durable` by default choose this.
- **At least once.** Run the call again. `pi-durable` does this only for a tool that declares `replay: "safe"`. Its
  README says that such a tool "still needs its own idempotency".

An idempotency key moves the problem to the receiver of the effect. deepseek-harness suggests `exec.callId` for
this. uniichat makes its client acts idempotent with act ids.

### 5.2 The order of the write and the show

`pi-durable`, Codex and uniichat write a change before a client sees it. Codex and uniichat show stream deltas that
they never write. The pi `coding-agent`, atc and deepseek-harness show a change first. Then a crash can remove
something that the user already saw, and the history after a restart is different. deepseek-harness limits this
risk: it flushes before each effect, so an effect never runs before its intent is on the disk.

### 5.3 A consistent log for the provider

A tool call with no result breaks the request format of the providers. The harnesses fix this in three ways:

- Append a closing record to the log (deepseek-harness).
- Add a result in memory at each request (Codex: `"aborted"`; the pi `coding-agent`: "No result provided").
- Write the error result as a normal outcome of the task (`pi-durable`: `interrupted`).

### 5.4 Who continues the work

- A task scheduler continues each task from its checkpoint (`pi-durable`). This needs tasks that are written as
  state machines.
- A new turn with a note continues the work (uniichat, and Codex after a managed restart). This costs little: the
  model reads the log and plans again. Codex tells the model to check the current state before it repeats actions.
- In the other harnesses, the user starts the work again.

### 5.5 The state of code

Only `pi-durable` makes running code durable, with replay from a journal. Replay needs three things: a
deterministic sandbox (a virtual clock and seeded random numbers), a check of the path (the same calls with the same
arguments), and a check of the versions (the sandbox fingerprint and the code-mode version). With a fresh cell for
each call (pi, Codex, deepseek-harness), the store is the only state, and pi keeps it in the session. With a
persistent REPL (atc, tacit), a restart loses all of the state.

### 5.6 Process crash and host crash

Only deepseek-harness calls `fsync` for each batch. `pi-durable` gets the same protection from SQLite, or from its
JSONL backend with `fsync: true`. The other harnesses survive a process crash, but a host crash can lose their last
writes.

### 5.7 Crash loops

Only `pi-durable` counts the restarts that cut a task, and parks the task after 5. Without such a count, work that
crashes its process each time runs again after each restart.

### 5.8 Tests

Only deepseek-harness kills a process in a test. `pi-durable` simulates a crash in the same process, in many tests.

## 6. Consequences for the dspy4s harness

Sections 8.2 to 8.4 of CODE_HARNESS.md already aim at level 4 for cells: `EffectStarted` and `EffectDone` events
around each guest API call, and a replay of the cells. `pi-durable` shows that this design works, and it adds
these rules:

1. **At most once by default.** After a crash, an effect with no `EffectDone` gives an error result with an unknown
   outcome. Only an operation that declares itself safe runs again, for example a read.
2. **Deterministic replay.** A replay of a Scala cell needs a virtual clock and seeded random numbers. The `Clock`
   and `Random` handlers of Phase 2 give these. Replay also needs a check of the path (the same guest calls with
   the same arguments), and a check of the versions (the compiler and the guest API, as the table of 8.4 says).
3. **The LM call is an effect.** Record the intent and the outcome of each `Predict` call. After a crash, send the
   same request again. A repeated LM call has no external effect, but it costs money.
4. **Write before show.** The actor of a session publishes an event only after the journal write (8.1).
5. **Durable input.** Give each input and each `tell` a request id, so a repeated input runs only once.
6. **A first version.** Before full replay exists, a new turn with a note can continue the work, as in uniichat
   and Codex.
7. **Crash loops.** Count the restarts of each session, and park the session after a limit.
8. **A disk policy.** Decide where `fsync` is necessary. At least, flush before each effect, as deepseek-harness
   does.
9. **Crash tests.** Kill a process with SIGKILL during a model request and during a guest API call, as the
   deepseek-harness tests do.

Section 4.2 of CODE_HARNESS.md now has rules 3, 4 and 5, and the first part of rule 1. The other rules are still
open.

An earlier question: can safe mode keep model code away from the system clock and random numbers? A check on the 3.10
nightly says no. Safe mode rejects `System.currentTimeMillis`, `System.nanoTime`, `java.util.Random` and
`UUID.randomUUID`. It allows `java.time.Instant.now()`, `java.time.Clock`, `scala.util.Random`, `Math.random()` and
`Object.hashCode`. So the type system does not make a cell deterministic. Open question 6 of
[CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), section 14, asks which check closes them for a later replay.
