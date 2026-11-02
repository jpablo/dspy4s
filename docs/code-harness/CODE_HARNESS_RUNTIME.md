# Code harness: the runtimes compared

Status: analysis, 2026-10-25. This document adds to [CODE_HARNESS.md](CODE_HARNESS.md) and
[CODE_HARNESS_LOOPS.md](CODE_HARNESS_LOOPS.md). The loop document compares the agent loops. This document compares
how each project executes its work: threads, effects, cancellation, context, resources, streams, processes and
durability. The facts come from the source code of each project on 2026-10-25. A statement marked *(inference)* is
an interpretation of the code, not a fact that the code states. Section 5.5 was added on 2026-11-01.

## Scope

The scope was agreed on 2026-10-25:

- **The goal.** The construction of a program and the structure of its execution stay the same. Only the
  execution mechanism changes: the runtime that does the effects.
- **The runtimes.** Direct style on virtual threads, Scala `Future`, ZIO, Kyo, Cats Effect, Ox, and Pekko. For
  Pekko, the typed actors and also the other parts that can apply: Pekko Streams, Pekko Persistence, Pekko
  Cluster and Cluster Sharding.
- **Interop and native.** Both are in scope. Interop: a program runs inside a runtime and blocks a thread there.
  Native: a program uses the fibers, the interruption and the fiber-local values of the runtime.
- **"The same program".** This term is still open. It can mean one program value, with the runtime chosen when
  the program runs. It can also mean one source tree that compiles to each runtime, as kyo-compat does.
- **A new analysis.** The analysis starts from zero. The current runtime of dspy4s is not a constraint, because
  dspy4s has no users yet.

This first document compares seven projects: atc, tacit, pi, uniichat, deepseek-harness, Codex and kyo-ai. Later
documents will compare the Scala runtimes, and the dspy4s programs from one `Predict` up to the harness.

## Summary

- **Only kyo-ai keeps the program as a value.** A kyo-ai program has the type `A < LLM`. It is a description with
  no network in it, and one handler (`LLM.run`) adds `Async` and `Abort`. The other six projects do their effects
  at once: calls that block a thread (atc, tacit), Promises that start when they are made (pi, uniichat, deepseek-harness),
  or Rust futures that spawn when they are made (Codex).
- **The others separate at the edges.** Commands are data with a reply channel (Codex `Op`, uniichat `Ask` and
  `Job`). Services are contracts with providers that can change (Codex, deepseek-harness). The `durable` package
  of pi makes each task a versioned state machine, and a scheduler interprets its JSON checkpoint.
- **Each project builds its own cancellation.** The TypeScript projects use `AbortSignal`, Codex uses a tree of
  `CancellationToken`s, atc uses predicates, hooks in the HTTP client and the REPL stop flag, and kyo-ai uses
  fiber interruption. Rules that occur in several projects:
  - Cancellation waits until the cancelled work is quiet.
  - Cleanup runs outside cancellation.
  - A soft preemption (steering) is different from a hard cancellation.
- **Explicit context is the rule.** Ambient values are rare: one `ThreadLocal` in atc, one task-local in Codex,
  AsyncLocalStorage in deepseek-harness for attribution only. kyo-ai keeps its settings in the state of its
  handler.
- **Durability comes before effects and before events.**
  - Codex persists an event before it publishes it.
  - pi `durable` commits the intent of a tool call before the call.
  - deepseek-harness flushes the log before a model call and before a tool body.
  - uniichat answers an act only after a group commit.
  - Crash repair appends events (deepseek-harness, pi) and marks an uncertain effect as "unknown". It does not
    retry that effect automatically.
- **No project runs the same agent logic on two scheduling runtimes.** kyo-ai comes nearest by design, but its
  runtime is Kyo only. Kyo can put ZIO and Pekko on its own thread pool, but the program stays a Kyo program.
- **Pekko event sourcing fits only part of the harness** *(inference)*. The core actor loop fits with a small
  adapter. The log reads, the forks and the REPL replay do not fit, so the full harness needs other code (5.5).

## 1. The points of comparison

| Point | Question |
|---|---|
| Runtime base | Threads, an event loop, fibers, or an async runtime? |
| Effects | Are effects values, or do they run when they are made? Is any description of the work separate from its execution? |
| A turn | What runs where: the LM call, the tools, the code cell, the UI? |
| Concurrency | What runs in parallel, with which limits, and which state is shared? |
| Cancellation | How does a stop reach the LM stream, a tool, a cell, a process? Which timeouts exist? |
| Context | How do the session and call values go from step to step? |
| Resources | How are processes, REPLs and clients opened and closed? |
| Streams | How do tokens and events flow, and is there backpressure? |
| Process model | One process, several processes, remote execution? |
| Durability | What is written, when, and how does a restart recover? |
| Program and execution | Can the logic run with another model, another store, or another runtime? |
| Errors | How are failures shown and moved between layers? |

## 2. The projects

### 2.1 atc (`/Users/jpablo/GitHub/atc/app/src/atc`)

| Point | atc |
|---|---|
| Runtime base | Platform daemon threads and `java.util.concurrent`. No effect library and no Scala `Future`. The build uses `-release:17`, so there are no virtual threads. |
| Effects | Calls that block a thread: `ChatModel.complete`, `ToolRunner.run`, and the guest `Interface`. Only the decisions of the loop are data (`Outcome`, `CompletionPolicy.Next`). The capabilities describe authority, not deferred work. |
| A turn | The main thread runs the turn. Each LM request runs on its own worker thread with the async stream of the SDK, and the main thread waits. A cell runs on its own worker when a timeout is set. Tool calls run one after another. |
| Concurrency | `parallel(tasks)` makes a pool with at most 8 threads. A nested `parallel` runs its tasks in order. One lock for the full JVM serializes all REPL evaluation, with `tryLock` and 10 s. There are no parallel tool calls and no sub-agents. |
| Cancellation | Ctrl-C makes the request check its `cancelled` predicate again. Then atc completes the request with `CancelledException`, interrupts the worker, and closes the transport and then the reader. Hooks in the HTTP client cancel a call that still waits for headers. The REPL gets the stop flag (a `ThreadDeath` at loop back-edges) and `Thread.interrupt`. A process gets its descendants killed, 2 s, then a forced kill. |
| Timeouts | A cell has 300 s of execution time. The clock stops during user prompts, commands and LM calls from the cell. |
| Context | Explicit parameters and by-name values. Each capability carries the id of its permission scope, so the scope travels with a value. One `ThreadLocal` (the request scope) and some volatile globals. |
| Resources | `try`/`finally` and `Using.resource`. Processes end when their `request*` scope ends. A closed REPL is left to the garbage collector. |
| Streams | SDK callbacks write tokens to the TUI on the callback thread. There is no queue and no backpressure. Process output goes to bounded buffers of 8 MiB. |
| Process model | One JVM, the REPL in the same JVM, processes for `exec`, and `claude -p` as a subprocess. |
| Durability | A session snapshot after each turn (a temporary file, then a move). The turn in progress is lost in a crash. |
| Program and execution | Test doubles and traits: `Agent.runTurn(runner: ToolRunner, ...)`, `ScriptedModel`, `EchoModel`. There is no interpreter split. |
| Errors | Exceptions for failures (`CancelledException`), result types for outcomes (`TurnOutcome`, `ExecutionResult`). `NonFatal` lets the stop signals pass. |

Ideas for dspy4s:

- A timeout clock that stops while the cell waits for the user or for an LM call.
- Cancellation that reaches the HTTP transport, also before the response headers arrive.
- Two levels of REPL stop: the stop flag for loops, and `Thread.interrupt` for calls that wait.

### 2.2 tacit (`/Users/jpablo/GitHub/tacit`)

| Point | tacit |
|---|---|
| Runtime base | One main thread runs a stdio JSON-RPC loop. A worker thread exists only for a cell with a timeout. No executors and no futures. |
| Effects | Calls that block a thread. The MCP layer composes `Either` values. |
| A turn | There is no agent loop: the MCP client is the agent. Each request runs on the main thread. A stateless call makes a new compiler and class loader each time. |
| Concurrency | None. A long cell blocks each other request, also `ping`. A `synchronized` lock lets only one evaluation run at a time. |
| Cancellation | The server ignores `notifications/cancelled`. On a timeout, it interrupts the worker and keeps the earlier REPL state. It never sets the REPL stop flag, so a CPU loop in model code does not stop, and the abandoned worker keeps the lock. `exec` stops its direct child after 30 s. |
| Context | An explicit `given Context(config, recorder)`. The library configuration is a static value in the class loader of each REPL, set by reflection. So in effect the class loader is the context of a session. |
| Resources | REPLs are never closed. A deleted session only leaves the map. |
| Streams | None. Each request gives one result. |
| Process model | One JVM, an MCP server over stdio, and REPLs with separate class loaders. |
| Durability | Sessions live in memory only. `CodeRecorder` writes an audit log. |
| Program and execution | Tests call `McpServer.handleRequest` directly, and `createFS` can give a `VirtualFileSystem`. |
| Errors | JSON-RPC errors for protocol faults, and `ExecutionResult(success, output, error)` for cells. An uncaught exception in model code counts as `success = true`, and a test asserts this. |

Ideas for dspy4s:

- Evaluate a cell, then adopt its new REPL state only if it completes.
- A static configuration for each class loader, which model code cannot see.

### 2.3 pi (`/Users/jpablo/GitHub/pi-mono/packages`)

| Point | pi |
|---|---|
| Runtime base | One Node or Bun event loop. Shell tools run in child processes. Each codemode run gets a new worker thread with a QuickJS-WASM VM. `pi-env` runs tools in a remote Rust daemon over ssh. |
| Effects | Promises that start at once, and an `EventStream` (a push queue with a final `result()`). The `durable` package is the exception: a `TaskDefinition` is a versioned state machine with phases, and its checkpoint is JSON data that a scheduler interprets. |
| A turn | The LM call and the tools run on the main loop. Bash runs in a child process. Codemode runs in a worker that posts its tool calls back to the host. |
| Concurrency | Tool calls are prepared one at a time and then run together (`Promise.all`, with no limit). The results keep the source order. A tool can ask for sequential mode. At most 2 codemode workers. Edits of one file go through a mutex made of a promise chain. |
| Cancellation | One `AbortController` for each run. Its signal reaches the fetch, the hooks and each tool. Bash kills the process group. Codemode stops a worker with an interrupt flag in a `SharedArrayBuffer` and `terminate()`. In `durable`, a cancelled wait stops only the wait, never the work, and cleanup runs with the signal removed (`withoutAbortSignal`). |
| Context | Explicit: closures and a `signal` parameter. `durable` passes an immutable `Context { abortSignal, telemetry, value(key) }` to each async call, as in Go. No AsyncLocalStorage. |
| Resources | One worker for each codemode run, closed in `finally`. In `durable`, each invocation owns its watches and timers, and `close()` returns the tasks that run to `pending`. |
| Streams | An `AssistantMessageEvent` union with a shared partial message. The agent awaits each listener in turn, and RPC mode waits for stdout to drain. So a slow consumer slows the loop. |
| Process model | One CLI process with interactive, print and RPC modes. `durable` runs on Node or on a Cloudflare Durable Object. With `onWake`, the host sets the alarms. |
| Durability | The session JSONL at each `message_end`. In `durable`: atomic commit batches; a task left `running` becomes `pending` with `restarts + 1`; a tool commits its intent before it runs; a rerun happens only if the tool says `replay: "safe"`, else the call ends as interrupted. Durable codemode replays a journal with a virtual clock and a seeded random source. |
| Program and execution | `StreamFn` injection, a fake provider, `MemoryStorage`, conformance suites. In `durable`, a phase must commit progress, or the task faults. So each phase is a unit that can restart from its top. |
| Errors | A failure is a stream event with a stop reason, never a thrown exception. Tool failures are `isError` results. `durable` separates storage errors, invalid reads and user faults. |

Ideas for dspy4s:

- A task as a versioned state machine with a JSON checkpoint, interpreted by any scheduler.
- Intent committed before an effect, and a `replay` policy (safe or unsafe) for each tool.
- Deterministic replay of a code run: a virtual clock, a seed, ordered delivery of results, a journal written
  before each delivery.
- "Cancelling a wait only cancels that wait, never the work."

### 2.4 uniichat (`/Users/jpablo/GitHub/uniichat`)

| Point | uniichat |
|---|---|
| Runtime base | Bun, as one server process or as a supervisor with workers. No worker threads. Shells run on devices: the terminals of the user. |
| Effects | Promises and callback sinks. A call is `{send, stop, done}`. The request (`Ask`) and the device job (`Job`) are data, and `Wire` is a record of request and parse functions for each vendor. |
| A turn | A raw `fetch` with an idle timer. Tools go through MCP over local HTTP. Device jobs go to a terminal over its WebSocket. |
| Concurrency | One turn at a time for each chat. Tools run in order. Subagents run detached. Up to 64 summary builds at a time for each side of a chat. Token buckets for each user and each network. One leader for each prompt-cache prefix. |
| Cancellation | A stop aborts the call, and the step returns at once. A paid request is read to its end without the signal of the user, so that its cost is known, and the process waits for such reads before it exits. A tool job ends with "Stopped.", and the terminal kills its process group. |
| Context | Closures and explicit parameters. The secret of a chat is in the path of its MCP URL. |
| Resources | A chat loads when it is needed, and it is let go when it is idle and nobody views it. Each chat has an LRU of open files. |
| Streams | Deltas and entries go to each WebSocket. No backpressure: a socket that overflows is closed, and its client syncs again. Each client act has an id, and the client sends it again until the server acknowledges it. |
| Process model | Users are sharded over workers by a hash. A deploy hands each chat to a new worker. Calls stay in the process that started them, and their events go to the new process (`call.move` on a sink). |
| Durability | Append-only JSONL with index slots, and a group commit (`syncfs` or `fsync`). An act gets its answer only after its flush, and acts are deduplicated by id. After a restart, the server reopens the chats that had work, and it tells the model that the server restarted. |
| Program and execution | Data that another part interprets (`Wire`, `Job`), and the `Sink` indirection. Tests patch `fetch` and the timers globally. |
| Errors | Values: `End {ok, text, left, stop}`, `Step.stop`, `Out {text, fail}`. |

Ideas for dspy4s:

- A call that runs, with a consumer that can change (`move`), so that a call stays where it started while its
  events go elsewhere.
- A region that cancellation does not cut, for work that must end (the paid read, a commit).
- Idempotency keys at the boundary of each effect, with a group commit.

### 2.5 deepseek-harness (`/Users/jpablo/GitHub/deepseek-harness`)

| Point | deepseek-harness |
|---|---|
| Runtime base | One Node event loop, and child processes for PTC programs, shells, terminals and LSP. A Cordis fiber is the runtime instance of one plugin, with the states PENDING, LOADING, ACTIVE, UNLOADING, DISPOSED and FAILED. It is not a thread. |
| Effects | `ctx.effect(execute)` runs at once and returns a disposer. An effect is a disposer, an iterable of disposers, a promise of one, or an async iterable. So registration and execution are one act. Separation exists at other levels: plugins are values, the plugin tree is YAML data, an LM stream is a lazy async iterable, and PTC does `resolve`, then `run`. |
| A turn | The agent driver has three phases (`idle`, `maintenance`, `running`), each with its own `AbortController`. The tool pipeline has three stages: prepare (policy, approval, guards), dispatch, and complete. A PTC program runs in a new Node child, with RPC over a pipe. |
| Concurrency | Exclusive calls are barriers, and parallel calls use a bounded pool. Only the bodies overlap, and results commit in model order. At most 10 parallel PTC sub-calls. At most 8 active subagents: admission rejects, it does not queue. |
| Cancellation | `AbortSignal` only. A started tool body is never abandoned: it becomes quiet before its outcome becomes `ABORTED`. Two rules: "Dispose must reach quiescence", and "timing does not carry correctness". A timeout is a wrapper around `tools/execute` that joins the signals. |
| Context | The Cordis `ctx`, a proxy with `extend`, `isolate` and `intercept`. A service that is called through `ctx` binds its side effects to the lifetime of the caller. AsyncLocalStorage only for the attribution of the initiator. |
| Resources | Each effect belongs to a fiber. Inside one effect, the disposers run in reverse order. The effects of a fiber dispose concurrently. When a service goes away, its dependents unload first, and they load again when it comes back. |
| Streams | A lazy `AsyncIterable` of chunks, live frames to the UI, and a session log with a 200 ms write window. Producers are never blocked. An inbound stream fails when its inbox is full. |
| Process model | One process is one plugin tree. The host and the browser each have a tree. An SDK over stdio JSON-RPC, and ACP. SSH moves the execution world (files, processes, sandbox), and the harness stays on the host. |
| Durability | `append` is best effort, and `flush` is the barrier. A flush comes before each model stream, before each top-level tool body, and at each step, and a failed flush stops the effect. Crash repair appends end events with an unknown outcome. Projections are folds with cached checkpoints. |
| Program and execution | Definition, Provider and Consumer roles. An executor for each scope (native or PTC). `dsh-llm-replay` replaces the model with recorded streams. The swap is of providers with lifetimes, not of the scheduling runtime. |
| Errors | Result fields, never rejections. Failure kinds that do not overlap. "One value states one fact." |

Ideas for dspy4s:

- Lifetimes that a dependency graph owns: when a provider goes away, its dependents unload, and they load again
  when it comes back.
- A service binds its effects to the lifetime of the caller, with no ambient value.
- Wrappers around a lazy stream, so that a checkpoint can come before the first side effect.
- A scheduler with ordered policy stages, in which only the bodies overlap.

### 2.6 Codex (`/Users/jpablo/GitHub/codex/codex-rs`)

| Point | Codex |
|---|---|
| Runtime base | One multi-thread tokio runtime, `spawn_blocking` for file work, one OS thread for each V8 isolate, and child processes for shells, the code-mode host, the exec server, hooks and MCP servers. |
| Effects | Ordinary Rust futures. Commands are data with a reply channel (`Op` with a `oneshot`), and the actors have message types. Tool dispatch starts when its future is made, because it spawns at once. |
| A turn | One submission loop for each thread. The turn runs in a spawned task, so the loop can take interrupts and steering. Two spawned stages sit between the socket and the turn loop, with bounded channels of 1600. Tool calls are futures in a `FuturesOrdered`, and results are recorded in model order. |
| Concurrency | A read/write gate for each model request: tools that are safe in parallel take the read lock, the others the write lock. No count limit for tool calls. Limits: 64 exec processes, 128 code-mode cells, 8 memory jobs. Sub-agents are sessions in the same process, with mailboxes. |
| Cancellation | A tree of `CancellationToken`s: turn, task, request, item, tool call. A separate soft token for steering. A turn abort cancels, waits 100 ms, aborts the task, runs its cleanup hook, flushes the log, and only then emits `TurnAborted`. A process gets TERM to its group, 50 ms, then KILL. V8 is stopped from outside its thread. |
| Context | Explicit `Arc<Session>`, `TurnContext` and `StepContext` parameters. One task-local, which each spawn must carry. Trace context crosses processes as `traceparent`. |
| Resources | Cleanup at drop: `AbortOnDropHandle`, `kill_on_drop`, and lock files for one writer. An idle thread unloads after 30 min with no subscriber. A read guard travels in each submission, so an unload cannot race a delivery. |
| Streams | `send_event` persists to the rollout, then delivers. Bounded inputs (512 submissions, 1600 stream events, 256 log writes), an unbounded core event queue, and a bounded writer for each connection, which disconnects a slow client. Code-mode cells yield, and the model calls `wait`. |
| Process model | The app-server runs in process for the TUI and `exec`. A daemon listens on a Unix socket. The exec server runs over WebSocket or an encrypted relay. The code-mode host is a child process, with a session lease. |
| Durability | JSONL rollouts with flush barriers (each turn end, before `TurnAborted`, before a suspension), SQLite projections, a file lock for one writer, and a daemon snapshot for restarts. |
| Program and execution | Ports and adapters: the thread store, the model provider, the HTTP transport, the exec backend, the code-mode session. The same `Op`/`EventMsg` core runs in process, behind the daemon, or with remote executors. But every implementation uses tokio. |
| Errors | `CodexErr` with retry classes and backoff. Tool errors are outputs that the model sees. Errors cross processes as protocol values. |

Ideas for dspy4s:

- Cancellation in stages: a cooperative token, a short grace period, a hard abort, a cleanup hook, a flush, and
  then the event.
- Two kinds of cancellation: a soft preemption (steer, yield) and a hard abort.
- Persist before publish, with one writer.
- A guard inside each message, so that an unload cannot race a delivery.

### 2.7 kyo-ai (`/Users/jpablo/GitHub/kyo/kyo-ai`)

| Point | kyo-ai |
|---|---|
| Runtime base | The Kyo scheduler: a work-stealing pool with adaptive concurrency. A fiber is a promise and a scheduled task, and `KyoApp` runs a program. `kyo-scheduler-zio` and `kyo-scheduler-pekko` put ZIO and Pekko on the Kyo pool. Kyo does not run on their pools. |
| Effects | `A < S` values. kyo-ai defines the effect `LLM` (a GADT of operations) and one handler, `LLM.run`, which adds `Async & Abort[AIGenException]`. A test checks at compile time that `LLM` never implies `Async` (`NotGiven[LLM <:< Async]`). So a program is a value until a runner executes it. |
| A turn | The handler runs the generation loop: it makes the result tool, calls the provider, runs the tools in order, and decodes the result. Two HTTP wires (OpenAI style and Anthropic) on kyo-http, and two CLI harnesses: Claude Code calls back through a local MCP server, and Codex through its `dynamicTools`. |
| Concurrency | `Isolate` decides how the conversation state crosses a fork: copy in, merge with no duplicates, keep the settings of the parent. `Async.foreach` and `race` work on `< LLM`. A `Meter` limits the rate. An agent is an actor: a bounded mailbox and one fiber, inside one `LLM.run`. |
| Cancellation | Fiber interruption. One deadline for each model call covers its retries; when it expires, a sleep fiber interrupts the call. Interruption reaches the HTTP connect. The code admits that an abandoned stream connection can stay open. Processes are registered with a `Scope` and killed when it closes. |
| Context | The settings live in the state of the handler (get, set, run, restore), not in `Local` or `Env`. |
| Resources | `Scope` finalizers. A fiber started with `Fiber.init` is interrupted and awaited when its scope closes. |
| Streams | Kyo `Stream` over `Emit`. A producer fiber fills a `Channel` of 1024. A turn joins the conversation only after its stream is fully consumed. |
| Process model | One process, CLI harnesses as subprocesses, no distribution. |
| Durability | None. The conversation can be serialized, but sessions and agents live in memory. |
| Program and execution | The model backend can change through `Config`. The runtime is Kyo only, with bridges to ZIO and `Future`. No public fake model: tests run a real local HTTP server. |
| Errors | `AIException` is a `Throwable`. Typed `Abort` rows, and retry by error class after classification. |

Ideas for dspy4s:

- A domain effect with one boundary: the program has no `Async`, and only the handler adds it. A compile-time
  test keeps it so.
- Explicit rules for state across a fork (capture, run, restore).
- One reliability wrapper for each call: a deadline that covers the retries, the retry by error class, and the
  rate limit.

## 3. Side by side

| Project | Runtime base | Effects as values | Cancellation | Context |
|---|---|---|---|---|
| atc | JDK platform threads | No | Predicates, hooks in the HTTP client, REPL stop flag, interrupt | Parameters and one `ThreadLocal` |
| tacit | One thread, and a worker for a timeout | No | Interrupt at a timeout only; no stop flag | `given Context`, statics in each class loader |
| pi | Event loop, workers, processes | Only the task definitions of `durable` | `AbortSignal`; a wait is not the work | Explicit; a `Context` value in `durable` |
| uniichat | Event loop, processes | Requests and jobs are data | `AbortSignal`; a paid read finishes | Closures |
| deepseek-harness | Event loop, processes, Cordis fibers | No: registration runs | `AbortSignal`; wait until quiet | The Cordis `ctx` |
| Codex | tokio | Commands are data | Token tree, soft and hard | `Arc` parameters and one task-local |
| kyo-ai | Kyo fibers | Yes: `A < LLM` | Fiber interruption | State of the handler |

| Project | Resources | Streams and backpressure | Process model | Durability |
|---|---|---|---|---|
| atc | `try`/`finally`, scope ids | Callbacks to the TUI; none | One JVM | Snapshot after each turn |
| tacit | Never closed | None | One JVM, MCP over stdio | None |
| pi | `finally`; owned watches in `durable` | Await each listener; drain stdout | CLI; Durable Objects; remote tools | Intent before effect; replay policy |
| uniichat | Load on demand, let go when idle | Close the slow socket | Workers; calls stay where they started | Group commit; idempotent acts |
| deepseek-harness | Effects owned by fibers | Producers never blocked; inbox limit | Plugin trees; SSH moves execution | Flush barriers; append closers |
| Codex | Cleanup at drop; leases | Bounded inputs; disconnect slow clients | Daemon; remote exec; code-mode host | Persist before publish |
| kyo-ai | `Scope` | `Channel` of 1024 | One process | None |

## 4. What the comparison shows

### 4.1 Effects as values are rare

- Only kyo-ai keeps a program as a value with its effects in the type. Its runtime is Kyo only.
- The other projects separate at the edges: commands as data (Codex, uniichat), providers behind contracts
  (Codex, deepseek-harness), and a task as a state machine with a data checkpoint (pi `durable`).
- These edge separations let a project change a model, a store, a process backend or an execution host. They do
  not let it change the scheduling runtime.

### 4.2 Cancellation is a design of its own

- **Two kinds.** Codex keeps a soft preemption (steering, yield) apart from a hard abort. A soft preemption lets
  the program decide what to do. A hard abort stops the work.
- **Quiet before the outcome.** deepseek-harness waits until a cancelled body is quiet before it reports
  `ABORTED`. Codex waits a short grace period before it aborts a task.
- **Cleanup outside cancellation.** pi `durable` runs cleanup with the signal removed. uniichat reads a paid
  stream to its end after a stop. Both are regions that cancellation does not cut.
- **The full path.** A stop must reach the HTTP transport (atc hooks, kyo-http interruption), the REPL (a stop
  flag for loops and an interrupt for calls that wait), and the processes (the process group: TERM, a grace
  period, then KILL).

### 4.3 Explicit context wins

- Each project passes the session and the turn as values. Ambient values are few, and each one has a narrow
  purpose: a trace context, the attribution of an initiator, or a request scope.
- Codex must carry its one task-local into each spawn by hand. deepseek-harness avoids ambient state by design: a
  service binds its effects to the caller.

### 4.4 Lifetimes have owners

- deepseek-harness ties each effect to a fiber, and a lost dependency unloads its dependents.
- Codex ties cleanup to drop, and a lease ties a code-mode session to its stream.
- kyo-ai ties fibers and processes to a `Scope`.
- atc and tacit have no general owner: atc uses `finally` and scope ids, and tacit closes nothing.

### 4.5 Durability comes first

- An event is persisted before it is published (Codex).
- An intent is committed before its effect (pi `durable`), and a flush comes before each side effect
  (deepseek-harness).
- After a crash, an effect with no recorded end has an unknown outcome, and nobody retries it automatically. pi
  reruns it only when the tool says that a rerun is safe.
- Deterministic replay of a code run (pi durable codemode) needs a virtual clock, a seed, ordered delivery and a
  journal written before each delivery.

### 4.6 Execution moves, sessions stay

- deepseek-harness moves the execution world over SSH, Codex runs exec on a remote server, and pi runs tools on
  a remote daemon. The session and the model calls stay on the host.
- uniichat moves a chat to a new process, but each call that runs stays in the process that started it.

### 4.7 Ordered results from parallel work

- Codex and deepseek-harness run tool bodies in parallel, but they record the results in model order.
- A tool declares whether it is safe in parallel. Codex uses a read/write gate; deepseek-harness uses barriers and
  a bounded pool.
- kyo-ai adds rules for state across a fork, so parallel branches can share a conversation.

## 5. Consequences for dspy4s

### 5.1 For the harness design

The design of [CODE_HARNESS.md](CODE_HARNESS.md) agrees with most of these findings. These points need attention.
On 2026-11-01, section 4.2 of that document took the first four points. The last two points are still open:

- **Two kinds of cancellation.** A `tell` to a turn that runs (7.3) is a soft preemption. A stop is a hard abort.
  The decider of 8.5 needs both as separate commands.
- **Cancellation and finalizers.** A hard abort must reach the LM transport, the REPL (stop flag and
  interrupt) and the process groups. It must wait until they are quiet before the decider records the outcome.
- **Regions that cancellation does not cut.** A journal commit, and the end of a cell event (8.3), must complete
  after a stop.
- **Barriers before effects.** The `recorded` handler (F2) writes `EffectStarted` before the effect, and the
  journal is flushed at that point. This is the rule of pi `durable` and deepseek-harness.
- **A replay policy for each tool.** The idea of pi (`replay: "safe"` or `"unsafe"`) fits the guest API. An
  operation that is not safe to repeat gets the outcome "unknown" after a crash.
- **Parallel calls with ordered results.** A `parallel` in the guest API can run bodies at the same time and
  record their events in a fixed order (Codex, deepseek-harness).

### 5.2 For "the same program on several runtimes"

The seven projects give two kinds of evidence:

- **kyo-ai shows that a program can be a value with a small domain effect and one handler.** The handler is the
  only place where concurrency and failure enter.
- **The other projects show which effects a harness needs from its runtime.** It needs: fork and join, hard and
  soft cancellation, finalizers, a region that cancellation does not cut, bounded queues, timers, and one
  context value at most.

*(inference)* A candidate shape for dspy4s therefore has two layers:

1. A small domain algebra for the work of a program: LM calls, tool calls, cells, time, random values, and the
   store. Programs are values over this algebra, and handlers interpret it.
2. A small set of scheduling operations that each runtime supplies: fork, join, race, interrupt, finalizers,
   uncancelable regions, queues, timers, and a local value.

The next document compares the Scala runtimes on the second layer.

### 5.3 A first map from the mechanisms to the Scala runtimes

This table is an *(inference)* from the reports. The next document must check each cell.
[CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md) checked the Kyo column on 2026-11-01.

| Mechanism | ZIO | Kyo | Cats Effect | Ox | Pekko |
|---|---|---|---|---|---|
| Spawn with cleanup at drop | `forkScoped`, `Scope` | `Fiber.init`, `Scope` | `Resource`, `background` | `fork` in `supervised` | child actor |
| Hard cancellation | interruption | `Fiber.interrupt` | `cancel` | scope interruption | stop message, `KillSwitch` (Streams) |
| Soft preemption | `Promise` or `Ref` signal | `Promise`, `Channel` | `Deferred`, `SignallingRef` | `Channel` | a message |
| Region that cancellation does not cut | `uninterruptible` | `Async.uninterruptible`, which forks the body, and the caller does not wait | `uncancelable` | code outside the scope | actor finishes its message |
| Bounded queue | `Queue.bounded` | `Channel` | `Queue.bounded` | `Channel.buffered` | bounded mailbox, `Source.queue` |
| Broadcast to many views | `Hub` | `Hub` | fs2 `Topic` | by hand | `BroadcastHub` (Streams) |
| One local value | `FiberRef` | `Local` | `IOLocal` | `ForkLocal` | message metadata |
| Retry with backoff | `Schedule` | `Retry` and `Schedule` | cats-retry | `retry` | `RestartSource` (Streams) |
| Actor with a journal | `Queue` and a loop | `Actor` (kyo-actor), with no journal | `Queue` and a stream | `Actor` | Pekko Persistence, `EventSourcedBehavior` |
| Distribution | none in the core | `kyo-aeron` topics | none in the core | none | Cluster, Cluster Sharding |

### 5.4 Questions for the next step

1. Is the two-layer shape of 5.2 the frame for the comparison of the Scala runtimes?
2. Which program levels does the next document cover first: one `Predict`, a composition, an optimizer, or the
   harness?
3. For Pekko: do Pekko Streams (backpressure, `KillSwitch`, `BroadcastHub`) and Cluster Sharding get a full
   section, or only the parts that the harness needs? Section 5.5 gives a first answer for Pekko Persistence and
   Cluster Sharding.

### 5.5 The fit of Pekko event sourcing

The question: is the event sourcing of Pekko Persistence a good match for the actions of the harness, and mainly
for its core actor loop? The scale has four answers, and the second answer has two forms:

| Answer | Test |
|---|---|
| 1. Natural | The concepts map one to one, and the harness uses a Pekko guarantee as it is. |
| 2. Vertical | A harness concept is a composition of Pekko concepts, and the Pekko guarantees still hold. |
| 2. Horizontal | The concepts translate, but the adapter must fill a gap in meaning. |
| 3. Contortion | The adapter must bypass or duplicate a Pekko guarantee. A different tool is then a better fit. |
| 4. No fit | The code of the harness is about the same with or without Pekko. |

The answer changes with the aspect of the harness. The core actor loop alone is answer 2, mostly vertical. The full
harness is answer 3: it can fit, but `EventSourcedBehavior` is not the right core. These answers, and the answers
in the next table, are *(inference)*.

| Harness aspect | Answer | Why |
|---|---|---|
| Session state machine (the decider of [CODE_HARNESS.md](CODE_HARNESS.md), 8.5) | 1. Natural | `decide` and `evolve` are the command handler and the event handler. Each session id has one writer. |
| Mailbox, steering, unload of an idle session | 1. Natural | This is the actor model, and it needs no persistence. |
| Step loop: policy, cell, observation | 2. Vertical | This is the standard pattern: write, `pipeToSelf`, write. A step is a phase in the state, and `RecoveryCompleted` starts the next phase. |
| Effect protocol for each guest API call | 2. Vertical | The sketch below shows it. Each call costs two journal writes and one wait. The actor stashes commands during a write, up to `stash-capacity = 4096`, and the default `stash-overflow-strategy` is `"drop"`. A worker whose `Start` is dropped gets only a timeout. |
| The log as the input of each step | 2. Horizontal | A Pekko entity reads its journal only at recovery, and other readers use a query side. The policy needs the full log at each step. So `State` must hold the log, and a snapshot saves little. |
| LM derivations (memory tree, graph) | 2. Horizontal | A projection handler can run again. Here each run costs money and gives different text, so the outputs must be events of a second entity. |
| Commit across sessions | 2. Horizontal | A Pekko write is atomic for one persistence id only (`AtomicWrite` has one `persistenceId`), so a parent and a child need a saga. `pi-durable` commits entries, tasks and documents in one transaction. |
| Fork of a session | 3. Contortion | A persistence id is one linear stream. A fork needs a full copy, or a parent pointer that custom recovery code follows. That code duplicates the Pekko recovery. |
| REPL replay, and a cell that continues after a crash | 4. No fit | The event handler must be pure, and it also runs after each write. A replay compiles and runs code, and it makes live objects. So the replay must run outside, and Pekko gives only the event list. |

An LM output that becomes an event is not a mismatch. A command can carry an external result, so the step loop is
a normal composition of Pekko concepts.

The effect protocol as a small harness DSL on Pekko concepts. This sketch leaves out the imports, and it covers
only the vertical part of the table:

```scala
object SessionEntity:
  /** Commands arrive in the mailbox. The journal does not store them. */
  enum Command:
    case Submit(text: String)
    case Start(id: Long, op: String, replyTo: ActorRef[Long]) // from the `recorded` handler, before an effect
    case Finish(id: Long, result: String)                     // from the `recorded` handler, after the effect

  /** Events are the facts in the journal. */
  enum Event:
    case InputAccepted(text: String)
    case EffectStarted(id: Long, op: String)
    case EffectDone(id: Long, result: String)

  /** The state is a fold of the events. `open` holds the effects that have no outcome. */
  final case class State(inputs: Vector[String], open: Map[Long, String])

  def apply(sessionId: String): Behavior[Command] =
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(sessionId),
        emptyState = State(Vector.empty, Map.empty),
        commandHandler = (_, command) =>
          command match
            case Command.Submit(text)           => Effect.persist(Event.InputAccepted(text))
            case Command.Start(id, op, replyTo) => // the worker waits for this reply before it does the effect
              Effect.persist(Event.EffectStarted(id, op)).thenReply(replyTo)(_ => id)
            case Command.Finish(id, result)     => Effect.persist(Event.EffectDone(id, result)),
        eventHandler = (state, event) =>
          event match
            case Event.InputAccepted(text)   => state.copy(inputs = state.inputs :+ text)
            case Event.EffectStarted(id, op) => state.copy(open = state.open + (id -> op))
            case Event.EffectDone(id, _)     => state.copy(open = state.open - id)
      ).receiveSignal { case (state, RecoveryCompleted) =>
        // A start with no done: the outcome is unknown. Record that, and do not run the effect again.
        state.open.keys.foreach(id => context.self ! Command.Finish(id, "interrupted: the outcome is unknown"))
      }
    }
```

**The two main strengths of Pekko do not meet in this design.** This follows from section 8.6 of CODE_HARNESS.md:

- A work session needs durability. But its worktree and its REPL are on one machine, so Cluster Sharding cannot
  move it.
- An optimizer rollout can use many machines. But it is short, and a failed rollout can run again, so it needs no
  recovery *(inference)*.

So `EventSourcedBehavior` with Cluster Sharding has no clear use in this design. Also, the parts that fit best are
the small parts, and Pekko gives no help with the hardest parts.

**The recommendation.** This is not a decision yet.

- Keep the log, the effect protocol and the replay as the code of the harness, with the decider above them. This
  agrees with decision 1 of CODE_HARNESS.md, 8.7.
- If the harness uses Pekko for other reasons, use plain typed actors, and use Cluster Sharding only for rollouts.
- If the harness uses Pekko Persistence later, the log can go behind a journal plugin. The plugin API has four
  methods: `asyncWriteMessages`, `asyncDeleteMessagesTo`, `asyncReplayMessages` and `asyncReadHighestSequenceNr`.

**What would change this answer:**

- Fresh cells with an explicit store in place of the persistent REPL. The replay row goes away, and the store is
  plain data in the fold. Section 4.2 of CODE_HARNESS.md now has the store next to the persistent REPL, so replay
  is optional, and the row applies only to that option.
- No forks of a session log. The contortion row goes away. With this change and the one above, the full harness
  moves to answer 2.
- Sessions that are not tied to one machine, for example with remote devices (CODE_HARNESS.md, 8.6). Cluster
  Sharding then has a real use for work sessions.

**How far this is checked.** The sketch compiles with Scala 3.8.2 and `pekko-persistence-typed` 1.1.3 and 1.7.1. The
two stash settings, the plugin API and `AtomicWrite` come from the 1.7.1 jars. No other row was built, and the cost
of the effect path was not measured.
