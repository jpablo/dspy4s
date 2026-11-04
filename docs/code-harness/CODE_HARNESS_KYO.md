# Code harness: what Kyo can give

Status: analysis, 2026-11-01. This document says which parts of Kyo the dspy4s harness can reuse. It adds to
[CODE_HARNESS_RUNTIME.md](CODE_HARNESS_RUNTIME.md) and to section 8 of [CODE_HARNESS.md](CODE_HARNESS.md). The
recommendation in section 7 is not a decision.

## Summary

- Kyo can give three parts now: the session host (`kyo-core`, `kyo-actor`), a journal backend (`kyo-sql-sqlite`), and
  an early MCP server (`kyo-mcp`).
- Other modules can come later: `kyo-pod`, `kyo-browser` and `kyo-lsp`. `kyo-tasty` must first read the TASTy version
  of the build.
- The turn, the REPL, `Predict` and the OS sandbox stay dspy4s code. `kyo-flow` does not fit the turn, `kyo-ai` stays
  a design reference, and `kyo-compat` has no interrupt.
- A small session host on `kyo-actor` ran 2,000 sessions with a stop at a random time, and it broke no rule of section
  4.2 of CODE_HARNESS.md. This check found two problems, which section 4.1 describes. One problem was an error in that
  section 4.2.
- One commit to SQLite, from a plain thread through an actor and back, took about 140 µs. So the two commits of each
  guest API call are small next to a cell compile. This number has no full flush of the disk cache. With `PRAGMA fullfsync`, a separate check measured 5 to 9 ms. The design uses it only for some commits
  ([CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), choice 17).
- Kyo RC7 needs JDK 25. dspy4s compiles on JDK 25, and all its tests pass there.
- Kyo is still a release candidate. So the recommendation keeps Kyo types out of the contracts of the harness.

## 1. Scope and method

The source is `main` at `432aac1f6` (2026-10-11), which was the upstream head on 2026-11-01. The latest release is
`v1.0.0-RC7` (2026-09-28), and `main` is 177 commits ahead of it. The checks use RC7. The line references are for
`main`, and they are relative to `/Users/jpablo/GitHub/kyo`.

For each harness need, the analysis gives one of three answers: covers it, covers part of it, or does not cover it.

These modules were read: `kyo-core`, `kyo-actor`, `kyo-direct`, `kyo-scheduler`, `kyo-compat`, `kyo-zio`, `kyo-sql`,
`kyo-sql-sqlite`, `kyo-sql-doltlite`, `kyo-system`, `kyo-system-doltfs`, `kyo-stm`, `kyo-flow`, `kyo-pod`, `kyo-http`,
`kyo-browser`, `kyo-ai`, `kyo-mcp`, `kyo-jsonrpc`, `kyo-lsp`, `kyo-compiler`, `kyo-tasty`, `kyo-doctest`, and the
boundary of `kyo-schema`.

These modules were not read: `kyo-slack`, `kyo-discord`, `kyo-telegram`, `kyo-teams`, `kyo-whatsapp`, `kyo-email`,
`kyo-ui` and `kyo-stats-otlp`. The first six can possibly give channels for the controller, and `kyo-ui` can possibly
give a web client *(inference)*.

The code of the checks is in `tmp/kyo-check/`, and it is not committed.

## 2. What ran

The checks used Kyo RC7, Scala `3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY`, JDK 25.0.5, and macOS on 14 cores. Each
number comes from one run, so it gives only the order of size.

| Check | Result |
|---|---|
| `kyo-core`, `kyo-actor`, `kyo-sql-sqlite` and `kyo-system` on the nightly | They compile and run. `kyo-mcp` compiles and its classes load, but no server ran. |
| A Kyo program inside a REPL cell (`ReplDriver`) | Pass |
| A plain thread runs a Kyo program and waits (`KyoApp.Unsafe.runAndBlock`) | 9 µs for each warm call with a pure body |
| `Thread.interrupt` on the thread that waits | It returns in 7 ms with `Failure(InterruptedException)`. The finalizer of the program ran. |
| Fiber interrupt, body in `Thread.sleep` | The thread gets the interrupt, and the fiber result comes in 17 ms. |
| Fiber interrupt, body in a tight loop | The body does not stop, and no result came in 30 s. |
| 64 fibers in `Thread.sleep` | A new fiber starts at once, and a `sleep` of 10 ms ends in 11 ms. |
| `scala.Console.withOut` into a fiber | It does not cross. The output goes to the standard output of the process. |
| `ThreadLocal` into a fiber | It does not cross. |
| Round trip from a plain thread to an actor | 36 µs |
| One-row SQLite commit, WAL, `synchronous=FULL` | 133 µs |
| Plain thread to actor to SQLite and back | 138 µs |
| A stale writer (the same session and sequence number) | Rejected with `[23505]` |
| A two-entry commit with a stale second entry | The first entry rolls back. |
| A read of 4,000 rows of 500 bytes | 135 ms |
| The session host of section 4.1, 2,000 sessions | No violation |
| dspy4s on JDK 25: a compile from no classes, then all tests | 2,172 tests, 0 failures, 6 skipped |

The SQLite numbers do not show durability after a host crash. On macOS, `fsync` does not force the data through the
disk cache, and the check did not set `PRAGMA fullfsync`.

A later check compared two layouts of the journal: one file for each session, and one shared file. The script is
`tmp/kyo-check/sqlite_layouts.py`. It used Python's SQLite 3.49.1 on the same Mac, and not the Kyo driver, which vendors
SQLite 3.53.4. The settings were WAL and `synchronous=FULL`, with `BEGIN IMMEDIATE` and two rows of 1 KB in each commit.
Each writer had its own connection. Python threads add delay when there are many writers, so compare only the
approximate sizes. Each row is one run.

| Measurement | One file for each session | One shared file |
|---|---|---|
| 1 writer: median commit | 75 µs | 74 µs |
| 1 writer: commits per second | 10,500 | 7,700 |
| 8 writers: commits per second | 14,400 | 9,000 |
| 8 writers: longest wait | 22 ms | 465 ms |
| 64 writers: commits per second | 5,300 | 3,500 |
| 64 writers: longest wait | 45 ms | 2.7 s |
| 256 writers: commits per second | 4,200 | 2,600 |
| 256 writers: longest wait | 377 ms | 4.0 s |
| `fullfsync`, 1 writer: median commit | 9.4 ms | 4.9 ms |
| `fullfsync`, 8 writers: commits per second | 224 | 200 |
| `fullfsync`, 8 writers: longest wait | 110 ms | 2.1 s |
| Start a new session: median | 1 ms | 0.1 ms |
| Disk size of a session with two rows | 16 KB | 9 KB |
| List 2,000 sessions | 687 ms | 2.4 ms |
| Remove 1,000 sessions | 47 ms | 558 ms, then a `VACUUM` of 50 ms on 18 MB |

With one writer the two layouts do the same work, and they still differ by 30%. So the differences in throughput mean
little. The longest wait differs by ten times or more, because the lock of SQLite has no fair order.

## 3. The fit for each part of the harness

| Part | Module | Verdict | Main limit |
|---|---|---|---|
| Session host: mailbox, one writer, timers, broadcast | `kyo-core`, `kyo-actor` | Reuse now | A stop must stay a message, and each reply needs a guard (4.1) |
| Journal backend | `kyo-sql-sqlite` | Reuse now | A native library through FFI, and one writer for each database file (4.2) |
| Early MCP server (question 4 of 9.4) | `kyo-mcp` | Reuse now | No server ran in the checks (4.3) |
| Container sandbox (F8) | `kyo-pod` | Later | It needs Docker or Podman, one `exec` cannot be stopped, and it has no host list (4.5) |
| Browser guest API | `kyo-browser` | Later | It starts Chrome with `--no-sandbox` (4.7) |
| LSP guest API | `kyo-lsp` | Later | It sends no workspace root, and it cannot start a server process (4.7) |
| `variables`, the names of each cell | `kyo-tasty` | Blocked | It reads TASTy 28.9, and the nightly writes 28.11 (4.8) |
| `Shell`, `Files` | `kyo-system` | Small gain | No process group, grace period or output limit (4.4) |
| LM transport | `kyo-http` | Small gain | HTTP/1.1 only, and a hard stop works only on the callback path (4.6) |
| The turn | `kyo-flow` | No fit | It writes no intent, it records no effect in a body, and a crash runs the body again (4.9) |
| `Predict`, LM calls | `kyo-ai` | Design reference | It builds its own prompt, and it has no REPL or session log (4.10) |
| The REPL | `kyo-compiler` | No fit | It only type-checks, with no state and no run (4.8) |
| Pluggable everywhere | `kyo-compat` | No fit | It has no interrupt and no uncancelable region (4.11) |
| Workspace fork (F6) | `kyo-system-doltfs` | No fit | A shell command cannot see files in a database (4.12) |

## 4. The modules

### 4.1 `kyo-core` and `kyo-actor`: the session host

Facts:

- **Dependencies.** `kyo-core` and `kyo-actor` do not need `kyo-schema` (`build.sbt:1785`, `build.sbt:2127`). So the
  codec rule of dspy4s does not apply to them.
- **Mailbox.** It is bounded (`kyo-actor/shared/src/main/scala/kyo/Actor.scala:746`), and `send` suspends when it is
  full (`:757`). It has no drop rule, so the stash problem of RUNTIME 5.5 does not occur here.
- **Stop of an actor.** `close` finishes the current message and returns the queued messages
  (`kyo-actor/README.md:641`). Supervision is handler composition, with no strategy API (`kyo-actor/README.md:643`).
- **Blocked work.** A worker with two flat CPU samples counts as blocked
  (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/BlockingMonitor.scala:263`). The pool then grows
  (`Scheduler.scala:498`), up to 100 workers for each core (`Flags.scala:7`).
- **Interrupt.** For an interrupted fiber on a blocked worker, the monitor calls `Thread.interrupt`
  (`BlockingMonitor.scala:274`). A tight loop does not count as blocked, and code that never polls is never preempted
  (`kyo-kernel/jvm-native/src/main/scala/kyo/kernel/internal/Safepoint.scala:49`). So the REPL stop flag is still
  necessary.
- **A region that a stop does not cut.** `Async.uninterruptible` forks the body
  (`kyo-core/shared/src/main/scala/kyo/Async.scala:135`). The body completes, but the caller stops at once
  (`kyo-core/shared/src/test/scala/kyo/AsyncTest.scala:486`). So this does not give "quiet before the outcome". A
  message to an actor gives it: the actor finishes the message.
- **A custom cancel action.** There is no API for one. The pattern is `Sync.ensure(stop)(wait)`, as
  `fromCompletableFuture` does (`kyo-core/jvm-native/src/main/scala/kyo/internal/AsyncPlatformSpecific.scala:37`).
- **Broadcast.** `Hub` has one buffer for each listener, but one full listener can stop the publishers
  (`kyo-core/shared/src/main/scala/kyo/Hub.scala:16`). A slow client view needs its own rule.

The small session host uses the phases and the commands of section 4.2 of CODE_HARNESS.md. The turn and the cell run
on plain threads. In the check, the journal was a log in memory. This sketch leaves out the types:

```scala
val host = Actor.run(Actor.receiveLoop[Command](initial) { case (command, s) =>
  val decided = decide(command, s)                     // pure (8.5)
  val next    = decided.fold(_ => s, _.foldLeft(s)(evolve))
  val entries = decided.getOrElse(Vector.empty)
  journal.write(entries)                               // the commit comes first
    .andThen(Sync.defer(startOrStopWork(entries)))     // only then an effect can see the entries
    .andThen(command match
      // A caller that an interrupt cut no longer waits, and its reply subject is closed. The commit still stands.
      case Command.Commit(_, replyTo) => Abort.run[Closed](replyTo.send(decided.map(_ => next))).unit
      case _                          => Kyo.unit)
    .andThen(Loop.continue(next))
})

// The worker and the cell are plain threads, and a commit is one call across the bridge.
def commit(entries: Vector[Entry]): Either[String, SessionState] =
  KyoApp.Unsafe.runAndBlock(30.seconds)(host.ask(Command.Commit(entries, _))).getOrThrow

// The hard stop: the flag for loops in guest code, and an interrupt for calls that wait.
def stopWork(): Unit =
  stopFlag.set(true)
  Option(cellThread.get()).foreach(_.interrupt())
```

Only the cell thread gets the interrupt. The worker thread does not, so its commits complete. After a cell ends, the
worker reads the state and closes each open effect with `EffectResult.Unknown`.

The stress check ran 2,000 sessions, each with a stop at a random time. A stop reached 1,671 sessions, the stop cut
272 cells, and the worker closed 14 effects as unknown. No session broke these rules:

- No intent commits after `StopRequested`.
- Each `EffectStarted` has exactly one `EffectDone`.
- Each `TurnStarted` has one `TurnEnded`.

The check found two problems before it passed:

1. **A stop before the first step started a new turn.** The `Input` still counted as an input that waits, so
   `TurnEnded` also committed `TurnStarted`. The user stopped the work, and the work started again. This was an error
   in section 4.2 of CODE_HARNESS.md. Now `StopRequested` clears the inputs that wait: the next turn shows them, but
   they do not start it.
2. **An interrupted `ask` ended the actor.** The interrupt closes the reply subject of the `ask`. A plain
   `replyTo.send` then fails in the handler, and that failure ends the actor. So each reply needs
   `Abort.run[Closed](...)`.

An interrupt also does not wait for the fiber of the `ask`, so a late `EffectDone` can arrive after the worker closed
the effect. The decider drops an outcome of a closed effect.

### 4.2 `kyo-sql` and `kyo-sql-sqlite`: the journal

This form ran in the checks:

```scala
DB.transaction(sql"INSERT INTO log (session, seq, entry) VALUES ('s1', $seq, $entry)".execute)
```

With a primary key on the session and the sequence number, this covers `Journal.commit`:

- `DB.transaction` (`kyo-sql/shared/src/main/scala/kyo/DB.scala:153`) opens `BEGIN IMMEDIATE`
  (`kyo-sql-sqlite-driver/shared/src/main/scala/kyo/internal/sqlite/SqliteConnection.scala:144`). So several entries
  are one atomic write.
- A stale writer gets `23505` (`SqliteErrors.scala:58`), so the primary key works as a fence.
- The file uses WAL (`kyo-sql-sqlite/README.md:157`). The default `synchronous` was 2 (`FULL`) in the check.

Limits:

- The driver loads a native library through `java.lang.foreign`. JDK 25 prints a warning, and the JVM needs
  `--enable-native-access=ALL-UNNAMED` in a future release.
- An interrupt of a write rolls back the whole transaction (`kyo-sql-sqlite/README.md:488`). So the commit must run in
  the actor, and a stop must stay a message.
- SQLite has one writer for the full file (`kyo-sql-sqlite/README.md:464`), and its lock has no fair order. In the
  check of section 2, a writer on one shared file waited for up to 2.7 s. So the design uses one file for each session
  ([CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), 10.2).
- `DB.run` opens one pool for each URL (`kyo-sql-sqlite/README.md:108`), with ten connections by default
  (`kyo-sql-sqlite/README.md:125`). So each session needs a smaller pool. No check has run this.
- `kyo-sql` depends on `kyo-schema-json`, but rows use `SqlSchema`. An entry can be a JSON string from zio-blocks, so
  the codec rule of dspy4s still holds.

A JSONL file also stays possible behind the same contract, as in pi and deepseek-harness.

### 4.3 `kyo-mcp`

- It is a client and a server (`kyo-mcp/README.md:37`), with the protocol versions `2025-06-18` and `2025-11-25`
  (`kyo-mcp/shared/src/main/scala/kyo/McpConfig.scala:83`, `:89`).
- The transports are stdio, a Unix socket, memory and WebSocket. The module has no Streamable HTTP.
- It does not need `kyo-ai`. `toolRaw` gives a tool with dynamic JSON (`McpHandler.scala:540`), so the boundary with
  zio-blocks is small *(inference)*.
- `stdioWith` diverts only the Kyo `Console` (`kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala:108`). A
  guest `System.out` can corrupt the wire *(inference)*.

It covers the early MCP server on stdio. dspy4s already has `McpTools` on the Java SDK, so the client side adds
little.

### 4.4 `kyo-system`

- `Command` and `Process` start, stream and stop one process. `destroy` is SIGTERM to one PID, and `destroyForcibly`
  is SIGKILL (`kyo-system/jvm-native/src/main/scala/kyo/internal/ProcessPlatformSpecific.scala:63`).
- A scope close sends SIGKILL with no grace period (`kyo-system/shared/src/main/scala/kyo/Command.scala:75`).
- It has no process group, no TERM then KILL sequence, and no output limit. F7 and RUNTIME 5.1 need all three.
- `Path` tracks reads and writes in the effect type (`PathRead`,
  `kyo-system/shared/src/main/scala/kyo/Path.scala:61`). It has no path rules and no sandbox.
- `FileSystem` is an abstraction with a conformance suite. But some abstract members are `private[kyo]`
  (`FileSystem.scala:147`), so an overlay or memory backend outside package `kyo` needs a change in Kyo *(inference)*.

So `Shell.sandboxed` stays dspy4s code. For `Files.local`, the gain over `java.nio` is small.

### 4.5 `kyo-pod`

- It gives containers, `exec`, logs, networks, read-only binds, tmpfs, `NetworkMode.None` and limits.
- It needs Docker or Podman (`kyo-pod/README.md:30`), and a Linux VM on macOS. The check did not measure the start
  cost.
- A scope close stops the container: SIGTERM, a wait, then a kill. An `exec` has no kill call, so the command
  continues until the container stops *(inference)*.
- A runtime can ignore some limits (`kyo-pod/README.md:328`). This conflicts with the fail-closed rule of F8.

It covers no network, and it covers part of the path rules. It does not cover a host list. It is a possible second
backend for F8, next to Seatbelt and bubblewrap.

### 4.6 `kyo-http`

- The client is HTTP/1.1 only. On the JVM it needs no native library.
- It has SSE helpers for GET only (`kyo-http/shared/src/main/scala/kyo/HttpClient.scala:680`). A POST with SSE needs a
  route and `sendWith`.
- A hard stop closes the socket only on the `sendWith` callback path. On a returned stream, the socket stays open
  through a silent pause *(inference)*.

The current LM clients of dspy4s stay. This module helps only if the LM layer moves to Kyo.

### 4.7 `kyo-browser` and `kyo-lsp`

`kyo-browser` drives Chrome over CDP. It starts Chrome with `--no-sandbox`
(`kyo-browser/shared/src/main/scala/kyo/internal/BrowserLauncher.scala:271`), so it needs a container for untrusted
pages. It can attach to a Chrome in a container. It fits an optional guest API.

`kyo-lsp` is a client and a server, with typed `definition`, `references` and `hover`. It does not reach metals yet:

- `initialize` sends no workspace root (`kyo-lsp/shared/src/main/scala/kyo/internal/lsp/LspClientEngine.scala:26`).
- It has no transport that starts a server process.
- No test uses a real server (`kyo-lsp/README.md:634`).

No analyzed harness gives LSP as a guest API, and deepseek-harness has it as a JSON tool. It is a new idea for dspy4s:
the compiler data of a project as typed values in a cell.

### 4.8 `kyo-tasty`, `kyo-compiler` and `kyo-doctest`

`kyo-tasty` reads `.tasty` and `.class` files with its own reader. It could give the names and the types for
`PolicyInput.variables`, and the names that each cell defines. It is blocked now: it supports TASTy 28.9
(`kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1098`), and the nightly writes 28.11.

`kyo-compiler` wraps the presentation compiler (`kyo-compiler/jvm/src/main/scala/kyo/internal/LocalBackend.scala:3`).
It only type-checks, it keeps no state, and it does not run code. So it solves no problem of Phase 0. `Pool.init` also
starts a native Aeron driver (`CompilerPool.scala:196`).

`kyo-doctest` is not a library for this use, but it has two ideas for section 6.

### 4.9 `kyo-flow`

A flow is a static plan of named nodes. Each attempt reads the recorded fields
(`kyo-flow/shared/src/main/scala/kyo/FlowEngine.scala:848`) and skips the recorded nodes.

- `loopOn` can express the dynamic loop of a turn, with one LM call or cell for each iteration *(inference)*. But its
  events come after the body (`Flow.scala:1595`), so no intent comes before an effect. A crash runs the body again,
  and this conflicts with rule 1 of [CODE_HARNESS_DURABILITY.md](CODE_HARNESS_DURABILITY.md), section 6.
- The engine does not record the effects inside a body, so it cannot give the cell events of 8.3.
- A timeout abandons the attempt, and the body can continue (`kyo-flow/README.md:166`).
- The release has only a memory store. A durable store is your own code (`kyo-flow/README.md:795`).
- `loopOn` state needs `kyo.Schema`.

This table uses the scale of RUNTIME 5.5. The ratings are a judgment:

| Harness aspect | Answer | Why |
|---|---|---|
| Session state machine | 4. No fit | It has no command or evolve hook. |
| Mailbox, steering, unload | 3. Contortion | A signal is once for each declared name. |
| Step loop | 2. Horizontal | A cell needs its own intent record. |
| Effect protocol | 4. No fit | The body is opaque. |
| The log as the input of each step | 2. Horizontal | The state must hold the log, so the writes grow with the square of its size. |
| LM derivations | 2. Vertical | A recorded output does not run again. |
| Commit across sessions | 2. Horizontal | A transition covers one execution. |
| Fork of a session | 3. Contortion | It needs a copy of the rows. |
| REPL replay | 4. No fit | Replay here only skips nodes. |

Its claim lease can possibly serve the first meaning of a cluster, optimizer rollouts on many machines, because a
rollout can run again *(inference)*.

### 4.10 `kyo-ai`

- **Parts.** The `LLM` effect, `Tool`, an actor `Agent`, `Isolate`, streams, eleven providers, and wires to `claude
  -p` and `codex app-server`.
- **Absent.** A REPL, a session log, prompt-cache marks and cost. `Compactor` exists only on an unmerged branch.
- **As a transport below `LanguageModel`.** It covers part of this. The public path always adds a result tool
  (`kyo-ai/shared/src/main/scala/kyo/LLM.scala:582`), and the OpenAI wire always sends `tool_choice`
  (`OpenAICompletion.scala:291`). The wire keeps one choice (`:114`) and drops the reasoning text (`:134`). The dspy4s
  adapters need `response_format` and open options.
- **In place of `Predict`.** This does not fit. `kyo-ai` builds its own prompt (`LLM.scala:662`), so the optimizers
  would lose the leaf.

`kyo-ai` also has `Decider`, with calibrated probabilities (`kyo-ai/shared/src/main/scala/kyo/Decider.scala`). It
looks close to the decision types of dspy4s (D6). This document did not compare them.

### 4.11 `kyo-compat`

The backends are Kyo, ZIO, Future, Ox and Twitter Future. Pekko is not a backend. The primitives have fork, join,
race, finalizers, queues, timers and a local value. They do not expose cancellation (`kyo-compat/README.md:257`), and
they have no uncancelable region. So `kyo-compat` cannot express `Abort`, and it does not meet the second layer of
RUNTIME 5.2.

### 4.12 `kyo-system-doltfs` and `kyo-stm`

`kyo-system-doltfs` stores each file as a row in a Dolt database, with branches and commits. Only `Path` code sees the
tree. A shell command, a test runner or git cannot see it, so it does not cover F6 for a coding agent *(inference)*.

`kyo-stm` works in memory, and a body can run again, so I/O stays outside. It can possibly serve a session registry,
but it does not cover the decider *(inference)*.

## 5. The costs of reuse

- **JDK 25.** The Kyo build states that `-release 25` is required (`build.sbt:120`), and the RC7 class files have
  version 69. The Kyo README still says JDK 21+ (`README.md:256`). dspy4s pins JDK 21 in `.tool-versions` and in CI.
- **Release state.** The release notes describe RC7 as stabilization work on the way to 1.0, and the API still
  changes. Several modules turn off MiMa (`build.sbt:819`, `:1254`, `:3306`). `kyo-sql` first appeared in RC6, and
  `kyo-flow` in RC2.
- **The compiler.** Kyo builds on Scala 3.9.0, and dspy4s builds on a 3.10 nightly. The modules in section 2 work. A
  later nightly can break Kyo inline code.
- **The bridge.** `KyoApp.Unsafe.runAndBlock` closes its `Scope` at each call, and its doc comment limits it to
  application entry points (`KyoApp.scala:88`). A warm resource needs a scope that lives longer.
- **Values tied to a thread.** `RuntimeContext` and `Console.withOut` do not cross into a fiber. So `Predict` and
  guest code stay on their threads. A guest `parallel` cannot run its bodies on Kyo workers unless it binds the output
  again.
- **Style.** Host code becomes `A < S`. `kyo-direct` keeps it close to direct style, but it rejects `var`, `try` and
  `synchronized` (`kyo-direct/README.md:5`).

## 6. Ideas to copy

- **A claim with a generation token.** A write needs the token and an unexpired claim
  (`kyo-flow/shared/src/main/scala/kyo/FlowStore.scala:277`). This gives the fence for a session on another host.
- **State across a fork.** The `Isolate` rules of `kyo-ai` (RUNTIME 2.7).
- **Negative tests for modes.** `kyo-doctest` has `expect=fails-compile`. A test can check that a cell does not
  compile in a stricter mode.
- **A fresh driver.** `kyo-doctest` makes a fresh compiler driver after a "denotation invalid" error when it reuses a
  driver with macros. This is a risk to test in a long REPL *(inference)*.
- **A deadline for each call.** One deadline covers the retries of a model call, and `Retry-After` stays under that
  deadline (`kyo-ai/shared/src/main/scala/kyo/ai/completion/Completion.scala:230`).

## 7. The recommendation

The two-boundary shape stays:

```text
Session host: mailbox, worker start and stop, event broadcast   <- pluggable; first version on kyo-actor
Decider and commit rule                                         pure, one implementation
Turn loop, cell, REPL                                           one thread in each option
Guest API handlers: LM, files, shell, network                   <- pluggable; each handler can use Kyo inside
```

1. **Keep the turn on its own threads.** `RuntimeContext` and `Console.withOut` do not cross into a fiber, and a tight
   loop needs the REPL stop flag in any case.
2. **Build the session host on `kyo-actor`.** It has a bounded mailbox, `ask`, finalizers, and a scheduler that
   tolerates blocked work. This changes decision 1 of 8.7, but only for the host.
3. **Put `Journal` on `kyo-sql-sqlite`, with one file for each session.** It gives the atomic write and the fence with
   little code. The contract also takes a JSONL file.
4. **Use `kyo-mcp` for the early MCP server,** if question 4 of 9.4 gets a yes.
5. **Keep Kyo types out of `Entry`, `Journal`, the guest API and `Module`.** Then an API change in Kyo stays in the
   adapters. A ZIO or Pekko host also stays possible.
6. **Move dspy4s to JDK 25.** Then run the guards of Phase 0 again on it.

This does not make `A < S` the program type of dspy4s. That is the separate question of RUNTIME 5.4.

## 8. What is not tested

- A session host with a real REPL. The check used plain threads and a flag in place of the REPL instrumentation.
- A session host with SQLite as its journal under the stress check. The stress check used a log in memory.
- The Kyo driver with one file for each session. The check of the layouts used Python's SQLite.
- A hard stop that reaches the LM transport and a process group.
- The guards of Phase 0 on JDK 25. Only one simple REPL cell ran there.
- A real `kyo-mcp` server, and `kyo-pod`, `kyo-browser` and `kyo-lsp` on the nightly.
- Durability after a host crash, and a process kill during a commit.
- A comparison with ZIO or Ox at the same depth.
