# Code harness: analysis and design proposals

Status: proposal, 2026-10-19. Sections 7 and 8 were added on 2026-10-21, the Phase 0 results (section 11) on
2026-10-22, and sections 2.4 to 2.6 and section 9 on 2026-10-24. On 2026-10-24 the document was also put in a new
order around one recommended design (section 4), and F7 and F8 were added. On 2026-11-01 the kernel in 4.2 got one
journal with a commit rule, a store, both stop rules, two stop commands, and metadata on each entry. Nothing in this
document exists in the code of dspy4s yet.

## Summary

- A new module, `harness`, gives a coding agent. The model acts only through Scala code that it writes, and
  an embedded Scala REPL runs that code.
- atc shows how to do code mode in Scala: one action language, a typed API whose source text is the API
  reference, and compiler errors as observations.
- pi shows a small kernel: the session is an append-only log, and the LM context is a pure projection of
  the log.
- uniichat shows a memory that loses no detail: one log that never ends, a binary tree of summaries over the
  log, and a view that is a cut through the tree. The agent zooms into any line to get the detail back.
- dspy4s has most of the parts: `Predict` leaves, `AgentLoop`, the interpreter contract, `History`, RLM, and the
  optimizers. It has no Scala interpreter. Also, RLM sub-calls write no trace entry.
- dspy4s builds on the same 3.10 nightly as atc, so the guest API uses capture checking (F2). A mode is a set of
  givens, and the compiler checks it. A handler decides what each call does.
- The capability checks stop at the process boundary, and a permitted command can do anything that the user can
  do. So each command runs in an OS sandbox, with a policy from the capabilities in scope (F7, F8).
- [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md) is the current design. It distills this document and the other
  analysis documents.
- Section 4 shows the design before that distillation. A session is a journal, a scope, a mode, a context strategy,
  and a REPL. Its state is a fold of the journal, and only committed state is observable. A pure decider checks each
  commit, and a `Predict` leaf makes each LM step. Section 5 keeps a short history of the three earlier designs (A,
  B and C) that led to it.
- Each work session has a pluggable context strategy: a window, a prefix summary, or the memory tree of
  section 6. One controller conversation that never ends uses the memory tree, starts the sessions, and sees
  each session through a ledger, a knowledge graph with time and a pluggable ontology, and probes (section 7).
- Section 8 describes the runtime. A session is an actor, its log is an event journal, and each guest API call
  in a cell is an event. The store of 4.2 survives a restart, and a replay of the journal can also rebuild the
  REPL definitions. The first runtime uses direct style, and distribution comes later.
- [CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md) says which parts of Kyo the harness can reuse: the session host, a journal
  backend, and an early MCP server.
- The Phase 0 experiment (section 11) recommends a REPL in the same JVM, with five guards. Two of the guards
  start a new REPL. The store keeps the saved values across that change, and replay also keeps the definitions.
- deepseek-harness, Codex and tacit confirm the main choices (section 9). tacit reports that agents that write
  capability-safe Scala match the tool-call baselines. The three harnesses also challenge three choices: REPL
  state with no explicit store, cells that stop at a timeout, and a journal with only rendered results. Section
  4.2 now answers the first choice and the third choice.

## 1. Goal

The `harness` module gives a coding agent with these properties:

- **Code mode only.** The model acts only through Scala code that it writes. A Scala REPL runs the code.
- **Reuse.** The harness uses the current modules: signatures, `Predict`, the interpreter contract, `History`, and
  the optimizers. Its loop is `drive`, in place of `AgentLoop` ([CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md),
  choice 1).
- **Optimizable.** BootstrapFewShot, COPRO, MIPROv2 and GEPA can tune the harness as they tune any other program.
- **RLM in the first version.** Large inputs stay in the REPL. Code can call the LM, and code can start a child
  agent.
- **Functional design.** The state is a value. The effects go through handler values. The important parts have
  laws.

Section 2 analyzes six reference harnesses and the dspy4s components. Section 3 gives the foundation of the
design. Section 4 shows the recommended design, and section 5 tells how it came from three earlier designs.
Section 6 describes the memory tree. Section 7 describes the work sessions and the controller, and section 8
describes the runtime of a session. Section 9 lists the findings from three of the reference harnesses:
deepseek-harness, Codex and tacit. Section 10 describes the optimization. Section 11 gives the phases, the
Phase 0 results, and the open questions. Section 12 lists the alternatives.

## 2. Analysis

### 2.1 atc (`/Users/jpablo/GitHub/atc`)

atc is a terminal coding agent. The model has one native tool, `run_scala`. All other operations are Scala
functions inside the REPL.

| Topic | What atc does |
|---|---|
| Action language | One tool, `run_scala(code)`. A call to another tool name gives the error "Only run_scala is available". |
| REPL | The standard `dotty.tools.repl.ReplDriver`, in the same JVM. The REPL state persists between snippets until a reset. |
| Timeout | `-Xrepl-interrupt-instrumentation:true` stops a loop at a timeout. The output from before the timeout goes back to the model. |
| Tool API | One trait, `Interface`, with about 60 methods: files, search, edit, `exec`, HTTP, `parallel`, to-dos, `chat`. The full source text of the trait, with its doc comments, is the API reference in the system prompt. |
| Capabilities | Each method with an effect takes a capability as a `using` parameter. The preamble of the REPL binds the capabilities. Capture checking and safe mode make a forbidden operation a compile error. |
| Observation | Compiler diagnostics, REPL echoes and printed output, in order. A hint for each frequent error. A long output loses its middle part. |
| End of a turn | A model reply with no tool call. |
| LM calls from model code | `chat(message)`: one completion, with no tools and no history. atc has no sub-agents and no recursion. |
| Context | It drops old exchanges at user boundaries. At 80 % of the window, an LM call compacts the history. |
| Style | Direct-style, imperative Scala 3. Mutable shell classes (`Agent`, `Conversation`, `ReplSession`) contain pure cores (`CompletionPolicy`, `ContextManager.prepare`). |
| Compiler | A 3.10 nightly, because capture checking and safe mode need it. |

Ideas to use:

- One action language: Scala code. The tools are functions, not JSON schemas.
- The source text of the API trait is the API reference for the model.
- Compiler errors are observations. The model learns the limits of its mode from the type errors.
- The REPL state is part of the agent memory.
- A trait for tests (`ToolRunner`): the loop runs against a scripted model and a recorded tool runner, with no REPL.

Ideas to change:

- atc swaps `System.out` under a lock for the full JVM, and it installs the host in one global slot. Then
  parallel sessions wait for each other. The optimizers need many parallel rollouts.
- `chat()` is one completion. atc has no RLM.
- Capture checking needs the nightly compiler. Since 2026-10-21, dspy4s builds on the same nightly as atc
  (`3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY`), so the harness can use capture checking too.

### 2.2 pi (`/Users/jpablo/GitHub/pi-mono`)

pi is a minimal coding agent in TypeScript. Its core has three packages: `ai` (the providers and one message
model), `agent` (the loop), and `coding-agent` (the tools, the sessions and the prompt).

| Topic | What pi does |
|---|---|
| Tools | Four by default: `read`, `bash`, `edit`, `write`. |
| Prompt | About 30 lines, plus the project files (`AGENTS.md`, `CLAUDE.md`) and a list of skills. |
| Loop | Free functions over a context and a record of hooks: `prepareRequest`, `finishTurn`, `beforeToolCall`, `afterToolCall`, and the queues for steering and follow-up messages. The loop emits a stream of events. |
| Session | An append-only JSONL file. The entries make a tree by parent id. A branch or a fork moves a leaf pointer. |
| Context | A pure projection of the entries on the path from the root to the leaf. A compaction entry replaces a prefix in the projection. The file keeps the original entries. |
| Output limits | 2000 lines or 50 KB. The cut text tells the model how to continue, for example `Use offset=K`. |
| Code mode | The `codemode` package runs model JavaScript in QuickJS. Code can call the injected tools. It can also call a classifier model (`models.classify()`) or an image model (`models.generateImages()`), but not a chat model. The nested tool calls do not go into the LM context. |
| Left out on purpose | Sub-agents, plan mode, permission prompts, to-do tools, background shells. |
| Style | Data first: all messages, events and entries are plain JSON values. But the loop mutates the context, and the session class composes hooks by a change of mutable fields. |

Ideas to use:

- A small kernel: a log, a projection, a step, and a few hooks.
- The log is the state. A pure function makes the LM context from the log.
- A compaction is an entry, not a deletion.
- A small tool set. Other features go into extensions.
- Output limits that tell the model the next step.

Ideas to change:

- pi composes hooks by a change of mutable fields. In dspy4s, a hook is a value, and hooks compose with laws.
- pi has no sub-agents. RLM needs recursion in the first version.

### 2.3 uniichat (`/Users/jpablo/GitHub/uniichat`)

uniichat is an agent with one chat that never ends. There is no "new chat" command. A server owns the chat,
and each browser tab or terminal is a view of it. The code is TypeScript on Bun. The design is in the header
comment of each file, mainly `Memory.ts`, `Server.ts`, `Agent.ts`, `Tools.ts` and `prompt.txt`.

| Topic | What uniichat does |
|---|---|
| Log | Each message is one JSON line in a file for each day (`main/`). The kinds are `user`, `unii` (replies), `tool` (calls), `echo` (results), `work` (agent reports), `ai` (imported chats) and `note`. The log is never cut. |
| Memory tree | A binary tree over the message indices. Node `(0, i)` is message `i` in at most 512 bytes. Node `(l, i)` merges the nodes `(l-1, 2i)` and `(l-1, 2i+1)`. There is one root for each set bit of the message count. A source of 512 bytes or less is its own node, with no LM call. |
| Compactor | A cheaper model writes each node in the background, up to 64 at a time, in message order. It gets the same system prompt as a turn, so it uses the same prompt cache. Its task asks for one line of at most 512 bytes, with an order of value: the user words, the effects that last, the findings, and the tool steps last. A line that is too long goes back with a note. |
| View | A list of nodes that covers the full chat, oldest first: a cut through the tree. Each new message adds a level-0 line at the end. Above 128 KB, a batch merges the adjacent pairs whose parent is built, the oldest first, down to half the size. Between batches the view changes only at its end, so the prompt cache holds most of the input. |
| Zoom | The tool `zoom(id, n)` opens line `id+n` into its two halves. `zoom(id, 1)` gives the full message. `zoom("Name")` gives the log of an agent. The prompt makes zoom the only way to read the memory. |
| Turn | The input is the view up to the first message of the turn, then the open devices, the agents at work, and the new messages in full. A native tool loop runs inside the turn. A message that the user sends during the turn goes in between two tool calls. The turn ends at a step with no tool call and no new message. |
| Subagents | `spawn(tasks)` starts one subagent for each task in the background, and it returns their names at once. Each subagent gets the view, its task, and its own flat log. Its report comes back as a `work` message, which starts a new turn. `tell(name, message)` sends a message to an agent that runs or waits. A subagent cannot spawn. |
| Goal | `/goal` sets a goal. After each turn, a call with no tools answers "yes" or "no" for the goal. On "no", the harness sends "keep working" as a user message. |
| Tools | JSON tools over MCP: `zoom`, `date`, `spawn`, `tell`, `computer`, and `shell`, `read`, `write`, `edit` on a named device (a computer with the `unii` terminal open). It is not code mode. |
| Acts | An act is an action of a client, for example `say`, `import` or `stop`. An act that would change the chat again if done again goes into the log before any effect off the log. After a crash, the server reads the log and does not do a recorded act again. A tool job and its output are not recorded acts. |
| Style | Namespaces of free functions over plain records. The records are mutable (`Memory` holds maps and counters), but the files are append-only. |

Ideas to use:

- One log that is never cut. A compaction deletes nothing: it adds summary nodes.
- A summary tree with a fixed shape. The tree fixes which lines each merge combines, so the LM merge does not need to be
  associative.
- The view is a cut through the tree. Old parts of the chat have less detail, and new parts have more.
- `zoom` gives back any detail. The prompt loses detail, but the store does not.
- Between batches, the view changes only at its end, so the prompt cache stays valid.
- A cheaper model builds the summaries in the background, before a turn needs them.
- Subagents run in the background and report as messages.
- A goal check after each turn.

Ideas to change:

- The tools are JSON tools. In dspy4s, `zoom` becomes a guest function, and code can walk the tree.
- The compaction prompt is fixed text. In dspy4s, the compactor is a `Predict` leaf, so the optimizers can
  tune it.
- The memory record is mutable. In dspy4s, the memory is a persistent value, so a rollout can fork it at
  almost no cost.

### 2.4 deepseek-harness (`/Users/jpablo/GitHub/deepseek-harness`)

deepseek-harness (`dsh`) is an agent harness in TypeScript from DeepSeek AI. Every feature is a plugin on the
Cordis framework. The English docs under `docs/` describe each subsystem in detail.

| Topic | What deepseek-harness does |
|---|---|
| Plugins | A plugin registers services and effects in a context. Each registration is reversible, and its lifetime is the lifetime of its context. A plugin waits until the services that it needs exist, and it unloads when one of them goes away. |
| Session log | Typed events, for example `turn/start`, `tool/call`, `assistant/message` and `compaction/summary`. An event is required unless it is marked `ignorable`. A reader that finds an unknown required event refuses to rebuild the session. |
| Surface | The model sees a projection of the log (the surface). Each surface event appends or replaces a range, and it must cite each event that it hides (`sourceEventSeqs`). |
| Projections | Pure folds: `init`, `apply`, an optional `view`, and a version. A snapshot is a consistent cut with an `asOfSeq` mark. The docs say: "Projection is the only read and compute path". |
| Requests | "Model-visible means logged". The system prompt is node 0 of the surface, and the tools and the configuration are a logged header. Dynamic context is a user-role snapshot, appended only when it changes. |
| Crash repair | The writer appends end events (`turn/end{interrupted}`), and an error result for each tool call with no result. It never removes a whole event, but it removes a torn or invalid last row before its first new append. An interrupted tool call has an unknown outcome, and the harness never retries it automatically. |
| Code mode | The PTC executor. Only `run_code` is callable, and the other tools are a generated TypeScript SDK, sorted by name. Each program runs in a new Node process in a sandbox. A persistent REPL is opt-in, and it is never durable. |
| Typed tool output | Each tool declares an output schema and a pure `render`. Code gets the typed value, and the model gets the rendered text. Only the rendered text is stored, so "replay reproduces presentation but cannot reconstruct canonical intermediate values". |
| Compaction | First, cuts of large tool outputs with no LM call. Then a summary, only if the context is still too large. A summary that is not smaller than its source is rejected. The summary call sends the same prefix byte for byte, so it uses the prompt cache. |
| Subagents | Providers (spawn, fork, Codex, Claude Code, ACP) return data, never a capability. A subagent result has its own message kind, separate from an agent message. Agent teams share a task graph with advisory write scopes. |
| Inbox | `send(message, target: next-turn \| next-step, wake)`, with the presets follow-up, steer and inject. Each message has a kind (who wrote it) and a form (`instructions \| catalog \| snapshot \| notice \| relay \| recall`). |
| Goals | The armed state of a goal is local to the process and is never stored, so a human must arm it again after a resume. A goal is "blocked" only after three rounds with the same blocker. |
| Safety | A tool pipeline with guards that can only deny, approval that fails closed, and an optional LLM reviewer that trusts only text from the user. |
| Tests | Recorded session logs replay as model streams (`dsh-llm-replay`), so tests need no API key. |

Ideas to use:

- The surface: an append-only log in which an event can replace a range and must cite what it hides. The
  memory-tree view is this kind of surface.
- An unknown required event stops a rebuild. This protects the journal when its schema changes.
- Crash repair by append, and the rule "unknown outcome, never an automatic retry".
- Projections as pure folds with a version and a cut mark. This is our `Derivation` (7.2).
- Cheap compaction steps first, and a check that a summary shrinks.
- The inbox algebra, and the message kind and form, for `tell` (7.3).
- Goals that a human must arm again after a restart.

Ideas to change:

- Store the typed value of each tool result too. Without it, replay cannot rebuild the values that code saw.
- deepseek-harness records the nested calls of a program under the checkpoint of the outer call. The cell
  events of 8.3 record each nested call.

### 2.5 Codex (`/Users/jpablo/GitHub/codex`)

Codex is the coding agent of OpenAI, in Rust (`codex-rs/`). It has a code mode, a typed protocol between the
agent and its clients, and many safety components.

| Topic | What Codex does |
|---|---|
| Code mode | JavaScript in an embedded V8, with a new isolate for each cell. `ToolMode::CodeModeOnly` hides all tools except `exec` and `wait`. Code calls `tools.<name>(args)`, and each tool appears as a generated TypeScript declaration, with a size limit. Code finds deferred tools with `tools.tool_search({query})`. |
| Cell state | Codex removed its persistent JavaScript REPL (`js_repl` has `Stage::Removed`). State goes from cell to cell only through `store(key, value)` and `load(key)`. A cell sees a snapshot of the store, and its writes are committed only if the cell completes (`commit_completion`). |
| Long cells | `exec` returns after `yield_time_ms` (10 s by default) with a cell id and the output so far, and the cell continues to run. The model calls `wait(cell_id)` later. Inside a cell, the tool call ids are deterministic (`tool-1`, `tool-2`, and so on). |
| Nested calls | Each call from code goes through the same tool runtime as a direct call, tagged with `ToolCallSource::CodeMode { cell_id, runtime_tool_call_id }`. So approval, the sandbox, hooks and the reviewer apply to it. |
| Protocol | A submission queue and an event queue. A thread has one serial loop and a mailbox, so it is an actor. Turn input has modes: `StartOrSteer`, `StartIfIdle`, `ContinueIfIdle(expectedTurn)` and `Steer(expectedTurn)`. The result is `Started`, `Steered` or `NotSubmitted(reason)`. |
| Rollouts | JSONL logs. A fork points into the immutable log of its parent (`HistoryPosition`). A revert writes a new file and moves one pointer. Each item has an envelope with metadata that the model never sees. |
| World state | Context sections (permissions, environment, AGENTS.md, tools), each with a snapshot type and `render_diff(previous)`. Later turns send only the changes, and the snapshots go into the log as merge patches. |
| Compaction | Speculative compaction: a background summary of a prefix, installed only if the history generation did not change. A token-budget mode opens fresh windows with no summary. It gives the model tools to read its earlier windows, and a private store for notes. |
| Memories | Phase 1 extracts memories from each rollout ("Write task history, not a user profile"). Phase 2 consolidates them in a git repository, driven by the diff since the last good state. Each citation in an answer increases a use count, which decides the rank and the decay of each memory. A thread that read external content is "polluted" and stays out of memory. |
| Multi-agent | Agent paths (`/root/...`), a mailbox with `QueueOnly` or `TriggerTurn` delivery, a graph of spawn edges, roles that can only reduce authority, and a message board that never wakes a finished agent. An idle child thread unloads, and it loads again when necessary. |
| Probes | `thread/sample` gives one model answer on a temporary fork that cannot run tools and that shares the prompt cache. |
| File edits | `apply_patch`: file operations and `@@` context anchors with no line numbers, a fuzzy match in four passes, the full diff before any write, and a report of a partial application. |
| Safety | The guardian: an LLM reviewer with a fixed JSON output, a circuit breaker, and a one-token classifier that decides when the full review runs. Starlark command rules with `match` and `not_match` examples that the loader checks. A network proxy that puts the real secrets in place of dummy credentials. |
| Turn time | The critical-path time of each turn, split into inference, review, user wait, tools and overhead. |

Ideas to use:

- `store`/`load` with commit-on-success, next to the persistent REPL state (9.2).
- The yield protocol: a cell can run longer than one model step (9.2).
- Deterministic effect ids inside a cell, as replay keys (9.2).
- Typed world-state sections that render only their changes.
- Fresh windows with history tools as a context strategy, and speculative compaction for the prefix-summary
  strategy.
- Typed turn input with expected turn ids, for `tell` (7.3).
- A probe on a fork with no tools, for `observe` (7.4).
- The two-phase memory pipeline, citation counts, and taint, for the graph of the controller (7.5).
- The guardian as a `Predict` leaf, policy rules with examples, and dummy credentials.

Ideas to change:

- Codex shows its tools as TypeScript declarations. In our harness, they are Scala signatures with Scaladoc.
- Codex removed its persistent REPL. Phase 0 shows that a persistent Scala REPL works, so our harness keeps it
  and adds an explicit store (9.2).

### 2.6 tacit (`/Users/jpablo/GitHub/tacit`)

tacit (Tracked Agent Capabilities In Types) is the research project that atc came from. Its core is the same as
the core of atc: a Scala 3 REPL, capture checking in safe mode, and one typed `Interface`. tacit is not an agent
loop. It is an MCP server that other agents use.

| Topic | What tacit does |
|---|---|
| Results | The README reports the results of its paper. On τ²-bench and SWE-bench Lite, agents that write capability-safe Scala "match or slightly exceed" the tool-call baselines, with gpt-oss-120b, MiniMax M2.5 and DeepSeek V3.2. With secrets in `Classified[String]`, the type system blocked each injection and each malicious task in all 131 trials. |
| Deployment | An MCP server. The README tells the user to disable the built-in tools of the host agent (`--disallowedTools "Bash,Read,Write,Edit,WebFetch"`), so the host agent works in code mode only. |
| Execution | Two kinds. `execute_scala` runs a cell in a fresh REPL. `create_repl_session` and `execute_in_session` keep the state. The model chooses. |
| API reference | The tool `show_interface` gives the API reference on demand ("Call this first"), because an MCP server cannot set the system prompt. |
| Timeout | `-Xrepl-interrupt-instrumentation:local` instruments only the classes that the REPL defines. On a timeout, tacit interrupts the worker thread, and the session keeps its earlier REPL state: "the abandoned statement has no observable effect". But tacit never sets the stop flag (`setStopFlag` occurs nowhere in its source), so the instrumentation never stops a loop. Only blocking calls react to the interrupt. |
| `Classified` | `secretHeaders` send a classified value to an allowlisted host, and code never sees the value. `httpPostClassified` sends a classified body and gives back a classified response. `secureOutput` writes each `println`, with the classified values unwrapped, to a file that only the user can read. `classifiedWrite = false` makes the classified paths read-only, because `Classified` protects confidentiality, not integrity. |
| Files | `VirtualFileSystem` is an in-memory file system for tests. `allowedRoots` fails closed, and symlinks are resolved before each containment check. A command must be in two allowlists: the configuration and the scope. |
| Validator tests | `CodeValidatorEvasionSuite` tests ways around the regex validator: names split across lines, interpolation, character literals, `sys.process`. |

The README lists sub-agents among the things that code can use, but the current `Interface` has only `chat`.

Ideas to use:

- The results are the best evidence so far for code mode only, in Scala.
- An MCP server for our REPL and guest API, so that another agent can test them before our own loop
  exists.
- A fresh-cell mode next to the persistent REPL, for probes and `rlm(...)` children.
- The REPL state changes only if a cell completes.
- The recipe for a capture-checked guest API: a capability class with a private constructor that extends
  `caps.SharedCapability`, a `request*` method that takes a block `Cap^ ?=> T`, and operations that take the
  capability as a `using` parameter. atc uses a newer model of the nightly: `caps.ExclusiveCapability`, and
  `caps.Stateful` with read-only views and `update def`. F2 uses the model of atc, and the `request*` blocks of
  tacit stay an option for a narrower scope inside a cell.
- `secretHeaders`, the secure output file, and `classifiedWrite`.

Ideas to change:

- tacit holds a lock on output for the full process. Phase 0 removed that lock (F1).
- tacit never sets the stop flag, so a CPU loop in model code does not stop at a timeout, and the abandoned
  worker keeps its lock. Also, with the `local` mode, a loop inside library or JDK code cannot stop. F1 keeps
  the `true` mode, with the class resources hidden, and it sets the stop flag on a timeout, as atc does.

### 2.7 The harnesses side by side

| Topic | atc | pi | uniichat |
|---|---|---|---|
| Action language | Scala code (one tool) | JSON tools (four) | JSON tools (MCP) |
| State | A message list and a live REPL | A tree of session entries | One log that never ends |
| LM context | Old exchanges dropped, or a summary of the prefix | A projection; a compaction replaces a prefix | A cut through a summary tree |
| Detail after compaction | Lost | Lost in the prompt, kept in the file | Kept: `zoom` gives it back |
| Sub-agents | None | None (an example extension) | Background, one level, report as a message |
| LM calls from model code | `chat()`, one completion | In code mode, a classifier model or an image model, but no chat model | None |
| Prompt cache | A stable system prompt | System messages in the middle of the conversation | The view changes only at its end; block marks |

The row "LM calls from model code" shows if a REPL cell can call an LM and keep the answer in a variable, not in
the LM context.

| Topic | deepseek-harness | Codex | tacit |
|---|---|---|---|
| Action language | JSON tools, or one `run_code` tool (PTC) | JSON tools, or code mode (`exec` and `wait`) | Scala code through MCP tools |
| State | A typed event log with a surface | JSONL rollouts and SQLite projections | The REPL state of each session |
| LM context | A projection of the surface | Typed world-state diffs and the history | The context of the host agent |
| Detail after compaction | Lost in the prompt, kept in the log | Lost in the prompt; fresh windows can read the old windows | Not applicable |
| Sub-agents | Several providers, and agent teams with a task graph | Agent paths, mailboxes, a message board | None (`chat` only) |
| LM calls from model code | `agent(prompt, {schema})` in workflow scripts | Only through tools | `chat()`, one completion |
| Prompt cache | Prompts that are the same byte for byte, and snapshots only on change | World-state diffs, speculative compaction | The host agent decides |

### 2.8 dspy4s components

| Component | Where | Use in the harness |
|---|---|---|
| `Module[I, O]`, `ProgramCall`, `Prediction` | `programs/contracts` | The harness is a `Module[I, O]`. |
| `Predict[I, O]`, `PredictorKey` (D2) | `programs/strategies/Predict.scala` | Each LM step of the harness is a `Predict` leaf. The optimizers find its trace entries by its key. |
| `OptimizableStructure`, `ProgramRunner` | `programs/optimization`, `programs/ProgramRunner.scala` | All optimizers need these two instances. |
| `AgentLoop.run` | `programs/runtime/AgentLoop.scala` | The bounded loop: `Continue`, `Done`, or the `onExhausted` result. |
| `ReplCodeInterpreter`, `ReplInterpreterFactory`, `SandboxTool` | `core/contracts/CodeInterpreter.scala` | The RLM interpreter contract. All three implementations run Python. |
| `RLM[I, O]` | `programs/strategies/RLM*.scala` | The action leaf (`variables_info, repl_history, iteration -> reasoning, code`), the extract leaf, `SUBMIT`, `llm_query`, the budgets. |
| `History` | `core/contracts/History.scala` | The adapter replays each message as earlier turns. The policy can see its trajectory as chat turns. |
| `TracedRun`, `captureFailureTraces` | `programs/optimization/TracedRun.scala` | The optimizers read the leaf calls from the trace. |
| `Gepa`, `FeedbackMetric` | `gepa` | Instruction search with text feedback, for example test output. GEPA already moves a `History` input into the context key of a reflective record. |
| `ParallelExecutor`, `isolatedAttempt` | `programs/runtime`, `core/runtime` | Parallel rollouts with isolated trace deltas. |
| `McpTools.convert` | `mcp` | MCP tools as `ToolFunction` values. |
| `CallbackEvent` | `core/contracts/Callbacks.scala` | Events for a UI, with no new event system. |

The gaps for a code harness:

1. No Scala interpreter. The three interpreters (`DenoPyodideInterpreter`, `LocalPythonInterpreter`,
   `SubprocessPythonInterpreter`) run Python.
2. RLM has Python in its prompt text, its fence check, its name check, and in `SandboxOutputField.pythonType`.
3. `llm_query` calls the LM directly. It writes no trace entry, so the optimizers cannot see or tune it.
4. RLM is one call. It has no conversation, no compaction, and no streaming.
5. Nothing isolates the workspace of one rollout from the workspace of another rollout.
6. `LmRequest` has no prompt-cache marks. An OpenAI-compatible provider caches by prefix with no marks, but an
   Anthropic provider needs them.

## 3. Shared foundation

The recommended design (section 4) needs the eight parts in this section. The three earlier designs of section 5
needed F1 to F6 too.

### F1. An embedded Scala REPL

```scala
trait ScalaRepl extends AutoCloseable:
  def run(cell: Cell)(using RuntimeContext): Either[DspyError, CellResult]

final case class Cell(code: String)
final case class CellResult(output: String, outcome: CellOutcome, writes: Map[String, DynamicValue])

enum CellOutcome:
  case Ok
  case CompileError(diagnostics: Vector[String])
  case Failed(exception: String)
  case TimedOut(after: FiniteDuration)
  case Submitted(value: DynamicValue.Record)
```

- Use `dotty.tools.repl.ReplDriver` from `scala3-repl_3`, with the Scala version of the build (the 3.10
  nightly). Scala 3.8.0 added `-Xrepl-interrupt-instrumentation`, so a cell timeout can stop a loop.
- In the `true` mode of that flag, each REPL class loader has its own copy of the stop flag (`StopRepl`). So
  a timeout in one REPL does not stop another REPL.
- Give each REPL its own output stream. Set `redirectOutput` of the driver to `false`, because otherwise
  `ReplDriver` calls `System.setOut` around each run. Run each cell on its own thread in `Console.withOut`, which
  has a value for each thread. Forward `System.out` and `System.err` to `Console` one time, at the start of the
  host. Then parallel sessions do not share a lock. Phase 0 confirmed this setup (section 11).
- Bind an `ExecutionContext` for each REPL in the preamble. A thread of the global pool keeps the output stream of
  the thread that made it, so the global pool sends output to the wrong REPL.
- Bind the handlers (F2) in the preamble of each REPL through a registry key for that session. Do not use one
  global slot.
- Share only `scala.*` and the guest API package with the REPL class loader, as atc does. Hide the class
  resources of the shared classes, so that the instrumentation does not define a second copy of them.
- Compile each cell with the options of the guest API: `-language:experimental.captureChecking` and
  `-Yexplicit-nulls`. Import `language.experimental.safe` last in the preamble, as atc does, so that model code
  cannot name the host internals or use `caps.unsafe`. A regex validator can give early errors, but the evasion
  tests of tacit show that such a validator can be bypassed (2.6). Safe mode and the class loader are the real
  checks.
- Keep the current error split: a failure of the REPL is a `Left`, and a failure of the user code is a `Right`
  with an outcome.
- A cell commits only if it completes. The outcomes `Ok` and `Submitted` keep the definitions of the cell and give
  its store writes in `writes`. After `CompileError`, `Failed` or `TimedOut`, a later cell sees only the definitions
  from before the cell, and `writes` is empty. For a failure at run time, the driver does this itself with
  `invalidObjectIndexes`: after `val x = 21; val boom: Int = 1 / 0`, a later cell gets `Not found: x`. A check on the
  3.8.2 driver showed this on 2026-11-01, and the check did not run on the 3.10 nightly. For a timeout, the host marks
  the wrapper as invalid (atc), or it keeps the earlier REPL state (tacit). This rule covers definitions and the
  store (4.2). It does not undo the effects of the cell, or a change to a mutable object that an earlier cell made.
- A later variant runs the REPL in a forked JVM. That variant isolates memory and crashes. Capture checking
  does not need it, because the build already uses the nightly compiler. Phase 0 recommends the REPL in the same
  JVM, with five guards (section 11).

### F2. The guest API: effects, views, modes and handlers

The guest API is a small set of traits, one for each effect. Each trait is an effect signature and a
capability. The model calls direct-style functions, and a `using` parameter selects the capability. This gives
the algebraic structure (operations and interpreters) without monadic syntax in the model code. The build uses
the 3.10 nightly, so capture checking tracks which capabilities each cell uses.

Two separate axes decide what a cell can do:

| Axis | Chosen by | Checked | Examples |
|---|---|---|---|
| Mode | The givens of the REPL preamble: which capabilities exist, and in which view | At compile time | full, local, read-only, controller |
| Handler | The value behind each given: what a call does | At run time | `Files.local`, `overlay`, `memory`, `recorded`, `replayed`, `guarded` |

The two axes combine freely. For example, read-only mode can run with an overlay handler, and full mode can run
with a replay handler.

atc has one `Interface` trait with all the operations, separate capability types, and one host class made of
parts (`HostFiles`, `HostProcesses`, `HostNetwork`). F2 makes the split public. Each effect gets its own handler,
and each role gets its own set of capabilities. The model still reads one API reference: the sources of the
traits of its session, in a fixed order.

#### Effects and views

Start with the four operations of pi, as typed Scala functions, and add the store of 4.2. This is a sketch:

```scala
// The guest side. This source text, with its doc comments, is the API reference in the prompt.
trait Cap extends caps.Stateful, caps.ExclusiveCapability

/** The files of the workspace. The bare `Files` can only read, and `Files^` can also write. */
trait Files extends Cap:
  /** The lines `from` to `to` of `path`, with line numbers. */
  def read(path: String, from: Int, to: Int): String
  /** Writes `content` to `path`. Makes the parent directories. */
  update def write(path: String, content: String): Unit
  /** Replaces the one occurrence of `oldText`. Fails when `oldText` occurs zero times or more than one time. */
  update def edit(path: String, oldText: String, newText: String): Unit

/** Commands in the workspace. A session has this capability, or it has none. */
trait Shell extends caps.ExclusiveCapability:
  /** Runs `command` and stops it after `timeoutSeconds`. A command can change files, so it needs `Files^`. */
  def exec(command: String, timeoutSeconds: Int)(using Files^): ExecResult

/** The session state that survives a restart. The bare `Store` can only load, and `Store^` can also store. */
trait Store extends Cap:
  /** The value under `key`, from a snapshot at the start of the cell and the writes of the cell. */
  def load[A: Data](key: String): Option[A]
  /** Writes `value` under `key`. The write commits only if the cell completes. */
  update def store[A: Data](key: String, value: A): Unit

// The functions that the model calls.
object api:
  def read(path: String)(using f: Files): String = f.read(path, 1, Int.MaxValue)
  def read(path: String, from: Int, to: Int)(using f: Files): String = f.read(path, from, to)
  def write(path: String, content: String)(using f: Files^): Unit = f.write(path, content)
  def edit(path: String, oldText: String, newText: String)(using f: Files^): Unit = f.edit(path, oldText, newText)
  def exec(command: String)(using s: Shell^, f: Files^): ExecResult = s.exec(command, 120)
  def exec(command: String, timeoutSeconds: Int)(using s: Shell^, f: Files^): ExecResult =
    s.exec(command, timeoutSeconds)
  def load[A: Data](key: String)(using s: Store): Option[A] = s.load[A](key)
  def store[A: Data](key: String, value: A)(using s: Store^): Unit = s.store(key, value)
```

`Data` is a type class that the guest API owns. Safe code cannot name the `Schema` of zio-blocks
([CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), 5.1). A value in an entry is now `Json`, in place of the
`DynamicValue` in the code of this document, because the JSON codec of `DynamicValue` changes values (same document,
4.1 and 12).

The rules:

- **Two views for state.** A trait that extends `caps.Stateful` has two views. The bare type is the read-only
  view, and `T^` is the full view. A method marked `update def` compiles only through the full view. A full
  capability can go where a read-only one is expected, but a read-only capability cannot go where a full one is
  expected.
- **One view for an exclusive capability.** `Shell` and `Network` have one view. A session has the capability,
  or it has none.
- **An operation lists every capability that its effects can reach.** A command can change files, so `exec`
  needs `Shell^` and also `Files^`. Then a read-only session cannot write a file through a shell, even if it has
  a `Shell`. atc has the same rule: `def exec(command: String)(using Exec^, FileSystem^)`. The rule also holds on
  the trait itself, so code that calls `shell.exec` directly needs `Files^` too. For `exec`, the rule holds only
  if a sandbox limits the command to these capabilities (F7, F8).
- **Overloads, not default arguments.** A function that takes a capability uses overloads for its optional
  parameters. atc gives this rule for capture checking.
- **Top-level functions.** The model calls `read("build.sbt")`, not `files.read(...)`. The functions of `api`
  take the capability as a `using` parameter.

#### Modes as sets of givens

The preamble of each REPL declares the givens, through a registry key for that session (F1). The mode decides
which givens exist, and in which view:

| Mode | Givens in the preamble |
|---|---|
| Full | `files: Files^`, `shell: Shell^`, `net: Network^`, `llm: Llm^`, `agents: Agents^`, `state: Store^` |
| Local | `files: Files^`, `shell: Shell^`, `llm: Llm^`, `agents: Agents^`, `state: Store^` |
| Read-only | `files: Files` (the read-only view), `llm: Llm^`, `state: Store^` |
| Controller (7.3) | `sessions: Sessions^`, `files: Files` (the read-only view), `state: Store^` |

`Llm` (F4) and `Agents` (F5) are exclusive capabilities too. An LM call costs money and sends data to a provider,
and a child agent can use each capability that it gets, so both need a capability. Each mode has `Store^`, because
the store changes only the state of the session.

The results:

- In local mode, `httpGet(url)` needs a `Network^`. No given has that type, so the cell does not compile.
- In read-only mode, `write(path, text)` needs a `Files^`. The given `files` is the read-only view, so the cell
  does not compile. `exec` does not compile either, because there is no `Shell` and no `Files^`.
- The model gets the compile error as an observation. It learns the limits of its mode from the error.
- A mode change needs a new REPL, because the preamble changes. atc does the same. A replay into a stricter mode
  can fail to compile, and replay (8.4) counts that as a divergence.

A root capability can add one more rule. In atc, `FileSystem^{io}` comes from a root `io: IOCap^`, and a callback
of `Classified.map` can capture only read-only views. A root capability gives F2 the same rule for `Classified`
values (question 5 of 9.4).

#### The notation header

The model must learn the notation. So the API reference starts with a short header, as atc does at the top of
`Interface.scala` ("Reading this file"):

```scala
// How to read this file. Read the capture-checking types as permission bits:
//   - `T^` is the full view of a capability, and the bare `T` is its read-only view. `Files^` can write,
//     and `Files` can only read. A full capability can go where a read-only one is expected. A read-only
//     capability cannot go where a full one is expected.
//   - `update def` marks an operation that changes state. It compiles only through a full view. On a
//     read-only view, the error is "Cannot call update method ...".
//   - A function lists each capability that it needs as a `using` parameter. `exec` needs `Shell^` and
//     `Files^`, because a command can change files.
//   - When a compile error names a capability that you do not have, your mode does not permit the operation.
```

The harness also adds a hint after each frequent capture-checking error, as atc does in `ToolOutput`. For
example, after "Cannot call update method" the hint says that the session is read-only. In the controller, the
hint says to start a work session for that operation.

#### Handlers

The value behind each given is a handler. The host composes the handler for each session, for example
`recorded(guarded(Files.overlay(base), policy))`. Handler transformers compose:

| Handler | Purpose |
|---|---|
| `Files.local(root)` | The real files under `root`. |
| `Files.overlay(base)` | Copy on write over `base`, for one rollout. |
| `Files.memory(snapshot)` | Files in memory, for tests. |
| `Shell.sandboxed(root, policy)`, `Shell.local(cwd)`, `Shell.denied` | Commands in an OS sandbox (F8), real commands with no sandbox (only for tests), or no commands. |
| `Store.snapshot(state)` | A snapshot of the store and a write buffer for one cell. `CellResult.writes` holds the buffer. |
| `recorded(h)` | Commits `EffectStarted` before each call and `EffectDone` after it (8.3). |
| `replayed(log)` | Answers each call from a log. |
| `guarded(h, policy)` | Checks a policy before each call. A deny rule wins, as in atc. |

Laws:

- `guarded(allowAll)(h)` behaves as `h`.
- `guarded(p)(guarded(q)(h))` behaves as `guarded(p and q)(h)`.
- For the calls in `log`, `replayed(log)` gives the same results as the handler from which `recorded` wrote `log`.

A mode does not replace the `guarded` handler. The type says that code can write. The guard says where: which
paths, which commands, and which hosts. atc also checks its policy at run time, on each call.

### F3. A typed submit

The harness makes the `submit` function from the output `Shape` of the base signature. For
`Report(summary: String, changedFiles: List[String])`, the guest sees this function:

```scala
def submit(summary: String, changedFiles: List[String]): Nothing
```

A wrong argument type is a compile error. The model sees the error and corrects the code in the next step. RLM
finds a type error only after the run, and it returns a `[Type Error]` observation. The host decodes the
submitted record with the output shape, as RLM does.

### F4. LM calls from code that the optimizers see

`llmQuery(prompt)` runs through a `Predict` leaf, `subQuery: Predict[Query, Answer]`. Each call writes a trace
entry with the key of the leaf. Then the optimizers see the leaf, and GEPA can tune its instructions. One counter
(`LlmCallLimit`) holds for the full call tree, as in RLM.

The LM is a capability of F2:

```scala
trait Llm extends caps.ExclusiveCapability

def llmQuery(prompt: String)(using Llm^): String
def llmQueryBatched(prompts: Seq[String])(using Llm^): Vector[String]
```

The handler of `Llm` runs the `subQuery` leaf and counts the budget, so a mode with no `Llm` given cannot call an
LM. An overload for `Classified` values, as in atc and tacit, depends on question 5 of 9.4.

### F5. RLM semantics

- The inputs of the base signature enter the REPL as typed values. Strings, numbers, booleans and collections
  become Scala values. The prompt shows only a summary of each value: the name, the type, the size, and a
  preview (the RLM `variables_info`).
- `llmQuery` and `llmQueryBatched` (F4).
- `rlm(task, inputs)` is a recursive call of the same program. The child gets a new REPL, a depth limit that is
  one less than the limit of the parent, and the shared LM call budget. The child gives its submitted value to
  the parent code.
- `rlm` takes the capability `Agents^` (F2). The mode of the child is at most the mode of the parent: the child
  can get fewer capabilities or read-only views, but never more. Codex roles and deepseek-harness providers have
  the same rule (2.4, 2.5).
- The budgets are Iron types: the current `IterationLimit`, `LlmCallLimit` and `OutputCharLimit`, and two new
  types, `DepthLimit` and `CellTimeout`.

### F6. Workspace isolation

A `Workspace` value has `fork(): Either[DspyError, Workspace]`. A fork is a git worktree, a temporary copy, or
an overlay (F2). Each rollout of an optimizer runs in its own fork. The metric reads that fork, for example to
run the tests. Without forks, parallel rollouts write the same files.

### F7. The threat model of commands

The capability checks of F1 and F2 cover the code that the Scala compiler checks: the cells of the model and the
calls into the guest API. The checks stop at the process boundary. A child process runs with all the OS
permissions of the user. The atc README says: "An allowed command is arbitrary code, run with your privileges,
outside the sandbox."

Thus the rule of F2 "An operation lists every capability that its effects can reach" does not hold for `exec`
with no sandbox. `exec` lists `Shell^` and `Files^`, but a command can also reach the network, the files outside
the workspace, and the classified files. In local mode, `httpGet(url)` does not compile, but `exec("curl ...")`
compiles and runs.

Three groups of commands get around the checks:

| Group | Examples | What the group gets around |
|---|---|---|
| Programs that run other programs or code | Shells, interpreters, `find -exec`, `xargs`, `env`, `timeout`, `awk` with `system()`, GNU `sed` with its `e` command | All the checks |
| Programs that run code from the workspace | `make`, `sbt`, `mill`, `npm run`, `cargo build`, `pytest`, test runners, linters with plugins, git with hooks | All the checks, after a write through `Files^` |
| Programs that read or send data | `cat`, `cp`, `curl`, `git fetch`, `npm install` | `Network`, the host list, and `Classified` |

No allow list can remove the second group. A coding agent edits code and runs the tests, so it runs code that it
wrote. The atc README gives `./mill app.test` as a narrow command to approve, but the agent can write the tests
that this command runs. That test code compiles with no capture checks and no safe mode. So a session with
`Shell^` and `Files^` can run arbitrary code.

A deny list does not close the gap either. atc denies `bash` by name, and its docs say that this does not block
`/bin/bash`, a wrapper, or a renamed interpreter. atc also denies `git *--output*`, because `git diff` can read
files outside the repository.

The checks still have value:

- With no `Shell`, the guarantees hold, if the trusted base is correct. Many tasks need only `read`, `write`,
  `edit`, search, the permitted hosts, and `llmQuery`.
- With `Shell`, the mode decides if a cell can start a command at all. The `guarded` handler decides which
  commands can run, and the `recorded` handler writes each command into the log.
- The compile errors keep a cooperative model inside its mode. Against a prompt-injected model, only a boundary
  at the OS level helps.

The rules for the harness:

1. Treat `exec` as an effect with the full authority of its sandbox. The type of `exec` gives no other
   guarantee.
2. Run each command in an OS sandbox with a policy from the capabilities in scope (F8). Then the rule of F2 holds
   for `exec` too.
3. Give guest functions in place of frequent commands, for example search, git through a library, and a `test()`
   function. A guest function runs in the trusted base, so the checks cover it. A `test()` function still runs
   the tests in the sandbox, because the tests are code that the agent wrote.
4. Treat a write to a file that a permitted program runs as code as code execution. Examples are build files,
   tests, hooks and plugin configurations. Keep `.git` read-only, as the atc project template does.
5. Keep the allow list and the deny list of the `guarded` handler, as in atc. They prevent mistakes, but they are
   not a boundary.

### F8. Sandboxes

An OS sandbox applies a policy to a process and to each process that it starts. Capture checking stops at the
process boundary (F7), so the sandbox is the boundary for commands.

The JVM has no sandbox inside the process: JDK 24 disabled the Security Manager permanently (JEP 486). So the
checks for code in the REPL are capture checking, safe mode and the class loader (F1). The check for a child
process is an OS sandbox.

What the analyzed harnesses do:

| Harness | Sandbox for commands |
|---|---|
| atc, tacit | None. A command must match an allow list. atc also has a deny list. |
| pi | None built in. An example extension (`examples/extensions/sandbox`) runs `bash` through `@anthropic-ai/sandbox-runtime`, with sandbox-exec on macOS and bubblewrap on Linux. Its configuration has file rules and a list of permitted domains. |
| uniichat | None in the source. |
| deepseek-harness | `dsh-sandbox-local`: bubblewrap or Landlock on Linux, Seatbelt on macOS, and an ACL runner on Windows. The modes are `read-only`, `workspace-write` and `danger-full-access`. If no backend works, the call fails with `SANDBOX_UNAVAILABLE`, and the command does not run. The policy has only file effects, with no network rules, and the bwrap and Seatbelt profiles do not limit reads. PTC programs run under the same policy as Bash. |
| Codex | Seatbelt (`/usr/bin/sandbox-exec`) on macOS, bubblewrap and seccomp on Linux, and a Windows sandbox. The modes are `read-only`, `workspace-write` and `danger-full-access`. Network access is off by default in the first two modes. A policy can make some roots unreadable. A network proxy puts the real secrets in place of dummy credentials (2.5). |

#### The policy from the capabilities

The handler of `Shell` makes the sandbox policy of each command from the capabilities in scope at the `exec`
call:

| Capabilities at the `exec` call | Policy of the child process |
|---|---|
| No `Shell` (read-only mode, the controller) | No child process. |
| `Shell^` and `Files^` (local mode) | Writes only under the root of the workspace or its fork (F6), and in a private temporary directory. No network. |
| `Shell^`, `Files^` and `Network^` (full mode) | The policy of local mode, plus the network through a proxy that applies the host list of the `guarded` handler. |
| Each mode | The classified paths and the secrets of the user (for example `~/.ssh`, `~/.aws` and `.env`) are not readable. The other paths outside the workspace are read-only. |

Law: the authority of a child process is at most the authority of the capabilities in scope at its `exec` call.
With this law, the rule of F2 holds for `exec`.

`Shell.sandboxed(root, policy)` is the handler for this. It makes the policy from the mode of the session and
from the `guarded` policy, and it runs each command under that policy. `Shell.local(cwd)` stays only for tests.
If no backend works on the host, `exec` fails, and the command does not run, as in deepseek-harness.

#### Levels of isolation

| Level | Examples | What it isolates |
|---|---|---|
| A sandbox on the same host | Seatbelt, bubblewrap, Landlock, seccomp | The files and the network of each command. The command shares the kernel and the file system of the host. |
| A container | Docker, Podman | A file system and a network of its own. The container shares the kernel of the host. |
| A separate kernel | gVisor (a kernel in user space), Firecracker (a microVM) | A kernel of its own. |
| A remote executor | A cloud sandbox, a build server | A machine of its own. |

The first harness uses a sandbox on the same host, because a work session changes the files of the user. A
container or a microVM fits the rollouts of the optimizers (F6), because each rollout has its own fork.

A bug in the trusted base can break the checks inside the process: in the compiler, in the capture checker, or in
a handler. Capture checking and safe mode are experimental compiler features. So a later version can also run the
full harness process in a sandbox: writes only in the workspace and in the session store, and network only to
the LM provider and the permitted hosts. The forked REPL JVM of F1 can run in the sandbox of local mode.

The limits:

- A sandbox controls paths, the network and system calls. It cannot follow a `Classified` value. A child that can
  read a secret, and that has an output channel back to the model, can leak the secret. Thus the classified paths
  stay unreadable for each child process.
- A sandbox does not prevent damage inside the workspace, for example a deleted file. The forks and the overlay
  handler (F2, F6) cover that.
- Apple marks `sandbox-exec` as deprecated ("The sandbox-exec command is DEPRECATED"), but Codex and
  deepseek-harness use it on macOS.

## 4. The recommended design

[CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md) is now the current design, and it takes the place of this section.
This section stays as the step before that design. Each part links to the section with its details. Section 5 tells how
this section came from three earlier designs.

### 4.1 The parts

| Part | What it is | Details |
|---|---|---|
| Guest API | One trait for each effect. A mode is a set of givens that the compiler checks. A handler decides what each call does. | F2 |
| REPL | One embedded `ReplDriver` for each session, in the same JVM, with capture checking and safe mode. Its state is a cache, and the store holds the state that must survive. | F1, 4.2, 11 (Phase 0) |
| Sandbox | An OS sandbox for each command, with a policy from the capabilities in scope | F7, F8 |
| Session | A journal, a scope, a mode, a context strategy, and a REPL. The state of the session is a fold of the journal, and a pure decider checks each commit. | 4.2, 7, 8 |
| Policy | A `Predict` leaf that writes the next cell, or a reply with no cell | 4.2 |
| Context strategy | A window, a prefix summary, or the memory tree. It derives data from the log, and it makes the policy input. | 7.2, 6 |
| RLM | Inputs as REPL values, `llmQuery` through a `Predict` leaf, and `rlm(...)` children with at most the mode of the parent | F4, F5, 4.4 |
| Controller | One session that never ends, with the memory tree and the controller mode. It starts the work sessions, and it sees them through a ledger, a graph, and probes. | 7 |
| Runtime | An actor for each session. The log is an event journal, and each guest API call in a cell is an event. | 8 |
| Optimization | `Harness` is a `Module` with `Predict` leaves, so the current optimizers can tune it. | 4.3, 10 |

A turn, in short:

1. A message arrives for a session. It comes from the user, from the controller (`tell`), or from a child. The
   decider commits an `Input`. In an idle session, it also commits `TurnStarted`, and a worker starts the turn.
2. Each step of the turn commits `StepStarted`. The context strategy updates its derived data and makes the policy
   input, with each new `Input`.
3. The policy replies, and the worker commits `Acted`. If the reply has a cell, the REPL runs the cell with the
   givens of the mode.
4. Each guest API call of the cell goes through its handler. The `recorded` handler commits `EffectStarted` before
   the effect and `EffectDone` after it (8.3). At the end of the cell, the worker commits `Observed`, with the store
   writes of a cell that completed.
5. The turn ends at `submit(...)`, at a reply with no cell, at the end of the budget, or at an `Abort`. The worker
   commits `TurnEnded`. The goal check can start one more turn (4.4).
6. The session sends its events to the controller (7.4).

### 4.2 The session kernel

The kernel comes from design B (5.2). It has four values, one fold, and one rule:

> Only committed state is observable.

The rule comes from `pi-durable` ([CODE_HARNESS_DURABILITY.md](CODE_HARNESS_DURABILITY.md), 3.1). Each change to a
session is an entry in its journal, and the state of the session is a fold of the journal. Thus a restart and a
reopen use the same fold as a live session.

```scala
// 1. The log: one append-only journal of plain data with a Schema. `Entry` is the one event type of a session (8.2).
final case class Logged(seq: Long, meta: Meta, entry: Entry) derives Schema

// The data that the model never sees. `required` tells a reader what to do with an entry that it cannot decode (8.2).
final case class Meta(source: Source, taint: Taint, required: Boolean) derives Schema

enum Source derives Schema:
  case User, Controller, Harness, Policy
  case Child(session: SessionId)

enum Taint derives Schema:
  case Trusted, External

enum Entry derives Schema:
  case Started(versions: Versions)                            // the compiler and the guest API (8.4)
  case Input(request: RequestId, text: String)                // `meta.source` is the sender
  case TurnStarted
  case StepStarted(step: Int)                                 // the intent of the LM call
  case Acted(reasoning: String, text: String, code: Option[String]) // the LM outcome, and the intent of the cell
  case EffectStarted(id: EffectId, op: String, args: DynamicValue.Record) // the intent of a guest API call (8.3)
  case EffectDone(id: EffectId, result: EffectResult)         // the outcome of the guest API call (8.3)
  case Observed(output: String, outcome: CellOutcome, writes: Map[String, DynamicValue]) // the outcome of the cell
  case Child(task: String, session: SessionId, result: Either[String, DynamicValue.Record])
  case StopRequested(reason: String)
  case TurnEnded(outcome: TurnOutcome)
  case Ended(outcome: Outcome, reason: String)                // 7.9
  case Reopened(versions: Versions)                           // 7.9
  case ScopeSnapshot(head: Sha, clean: Boolean)               // at the end of each turn (7.9)

enum TurnOutcome derives Schema:
  case Replied(text: String)                                  // a reply with no cell
  case Submitted(value: DynamicValue.Record)                  // a cell called `submit(...)`
  case Exhausted                                              // the step limit of a program call
  case Aborted(reason: String)                                // an `Abort` stopped the turn
  case Crashed                                                // a crash cut the turn
  case Failed(error: String)                                  // the LM call or the REPL failed

// The fold of the log.
final case class SessionState(
    log     : Vector[Logged],
    phase   : Phase,                     // Idle, InTurn, Stopping, Closed
    store   : Map[String, DynamicValue], // the writes of the cells that completed
    requests: Set[RequestId]             // the inputs that the journal already has
)

// 2. The context: a strategy (7.2) derives data from the log, and it makes the policy input.
final case class PolicyInput(
    task       : String,
    variables  : Vector[String],         // the names in the REPL
    stored     : Vector[String],         // the keys of the store
    observation: String,
    history    : History
)

// 3. The policy: a Predict leaf. The optimizers find it by its key.
//    task, variables, stored, observation, history -> reasoning, text, code
final case class PolicyStep(reasoning: String, text: String, code: Option[String])
val policy: Predict[PolicyInput, PolicyStep]

// 4. The interpreter: the REPL of the session, with the givens of its mode (F2). A recoverable failure is an
//    observation. A fatal failure is a `Left`.
val interpreter: ScalaRepl
```

History message `k` holds the observation before step `k` as its input, and step `k` as its output. The adapter
replays these messages as chat turns. Each step adds turns at the end, so the prompt prefix stays the same, and
the provider prompt cache can hold it. An `Input` commits when it arrives, possibly during a cell. The projection
shows it at the first `StepStarted` after it, so a new input also adds only to the end of the prompt.

#### The commit

```scala
trait Journal:
  /** The decider checks the entries, one atomic write stores them, and the fold gives the new state. */
  def commit(entries: Entry*)(using RuntimeContext): Either[DspyError, SessionState]
```

`commit` returns after the write reaches the backend, so an intent is in the journal before its effect starts. The
caller gives the `Meta` of each entry, and the sketches leave it out. A stream delta to a UI is not state: a client
can show it, and a crash can lose it.

Each effect has an intent and an outcome:

| Effect | Intent | Outcome |
|---|---|---|
| The LM call of the policy | `StepStarted` | `Acted` |
| A cell | `Acted` with code | `Observed` |
| A guest API call in a cell, with `llmQuery` and `rlm(...)` | `EffectStarted` | `EffectDone` |

#### The steps of a turn

The code in this part uses `AgentLoop`. In [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), 4.6, `drive` now takes its
place.

```scala
def step(derived: Derived, i: Int)(using RuntimeContext)
    : Either[DspyError, AgentLoop.Step[Derived, TurnOutcome]] =
  for
    state  <- journal.commit(Entry.StepStarted(i))                  // the state has each new input
    next   <- strategy.update(state.log, derived)                   // 7.2
    reply  <- policy(strategy.project(state.log, next, limits.contextBudget))
    _      <- journal.commit(Entry.Acted(reply.output.reasoning, reply.output.text, reply.output.code))
    status <- reply.output.code match
      case None       => Right(AgentLoop.Step.Done(TurnOutcome.Replied(reply.output.text)))
      case Some(code) => runCell(code, next)
  yield status

def runCell(code: String, derived: Derived)(using RuntimeContext)
    : Either[DspyError, AgentLoop.Step[Derived, TurnOutcome]] =
  for
    result <- interpreter.execute(Cell(code))                       // each guest API call commits its own entries
    cell    = result.observation
    _      <- journal.commit(Entry.Observed(cell.output, cell.outcome, cell.writes))
  yield cell.outcome match
    case CellOutcome.Submitted(value) => AgentLoop.Step.Done(TurnOutcome.Submitted(value))
    case _                            => AgentLoop.Step.Continue(derived)

def turn(start: Derived, lastStep: Derived => Either[DspyError, TurnOutcome])(using RuntimeContext)
    : Either[DspyError, SessionState] =
  val outcome = AgentLoop.run(start, 0, limits.iterations)(onExhausted = lastStep)(step)
  journal.commit(Entry.TurnEnded(outcome.fold(TurnOutcome.fromError, identity)))
```

The journal holds the state of the session, so the state of the loop is only the derived data. The derived data
has a journal of its own (8.2). The worker of a turn runs this loop outside the decider (8.5). `fromError` gives
`Aborted` for a rejection after a stop, and `Failed` for another error.

The projection cuts long observations, and it adds a note that tells the model how to read the remainder. The
log keeps the full output.

#### The end of a turn

The same step serves a chat turn and a program call ([CODE_HARNESS_LOOPS.md](CODE_HARNESS_LOOPS.md), 5.2 and 5.3).
Only the caller differs:

| Event | Chat turn | Program call (`harness(input)`) |
|---|---|---|
| A cell calls `submit(...)` | The turn ends with `Submitted`. | The value is the output. |
| A reply with no cell | The turn ends with `Replied`. The text is the answer. | The `finisher` leaf makes the output, as the forced submit of `ReActV2` does. |
| The step limit | `lastStep` makes one more policy call with a note to answer. The harness does not run a cell from this call, as in atc. | `lastStep` gives `Exhausted`, and the `finisher` leaf makes the output. |

#### Input and stop

The decider of the session (8.5) has two commands for a turn that runs. They come from the mailbox of the session:

```scala
enum Command:
  case Send(request: RequestId, text: String, source: Source, taint: Taint) // a soft preemption
  case Commit(entries: Vector[Entry])                                       // from the worker
  case Abort(reason: String)                                                // a hard abort
```

| Phase | `Send` | `Commit` | `Abort` |
|---|---|---|---|
| `Idle` | Commits `Input` and `TurnStarted`. | Rejected | No effect |
| `InTurn` | Commits `Input`. | Accepted | Commits `StopRequested`. |
| `Stopping` | Commits `Input`. | Accepted only for an outcome: `EffectDone`, `Observed`, `TurnEnded`. | No effect |
| `Closed` | Rejected. The session must reopen first. | Rejected | No effect |

- **A soft preemption** lets the model decide. `Send` adds a message, and the policy reads it at the next step
  (Codex, uniichat, deepseek-harness). A `Send` with a known `RequestId` adds nothing, so a repeated input runs one
  time (`pi-durable`). If an `Input` waits when `TurnEnded` commits, `TurnStarted` commits with it. `StopRequested`
  clears the inputs that wait: the next turn shows them, but they do not start it.
- **A hard abort** stops the work. The host stops the LM transport, the REPL (the stop flag and an interrupt) and
  the process groups. The next intent of the worker gets a rejection, and the worker ends with `Aborted`. In the
  REPL, only the cell thread gets the interrupt, so the commits of the worker complete. After the cell ends, the worker
  closes each open effect with `EffectResult.Unknown`. An interrupt does not wait for a commit in progress, so the
  decider drops a late outcome of a closed effect ([CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md), 4.1).
- **A stop does not cut a commit.** A journal write and the `Observed` of a cell complete after a stop. The worker
  commits `TurnEnded` only after the REPL and the processes are quiet (deepseek-harness, Codex).
- **A cell that does not stop.** After a grace period, the host marks the process as unclean (guard 3 in section
  11), and the decider commits `TurnEnded` itself. A later commit from that worker gets a rejection.

#### State between cells

The session has two kinds of state between cells:

| State | What it holds | After a restart |
|---|---|---|
| The store | `Data` values (F2), under a key. It is a fold of the `writes` in `Observed`. | It comes back from the journal. |
| The REPL state | Definitions and live objects. It is a cache. | A new REPL starts. Replay (8.4) can rebuild the definitions. |

- `store(key, value)` and `load(key)` are guest functions (F2). A cell sees a snapshot of the store and its own
  writes. The writes commit only if the cell completes (Codex), and the journal keeps them (pi).
- A cell changes the REPL definitions only if it completes (F1). There is no rollback for the effects of a
  cell, or for a change to a mutable object that an earlier cell made.
- After a restart with no replay, the model gets a note with the names that it lost. `stored` shows what it can
  `load`, so the model knows what it must compute again.
- A value with no `Data` instance, for example a process handle, cannot go into the store. The type checks this.

#### After a crash

The fold gives the state, and the harness repairs the log with new entries. The old entries do not change:

1. An `EffectStarted` with no `EffectDone` gets `EffectDone(id, EffectResult.Unknown)`. Nothing runs the effect
   again automatically (8.3).
2. A cell with no `Observed` gets `Observed` with the outcome `Failed` and no writes.
3. An open turn gets `TurnEnded(Crashed)`.

The user can then continue the work, or a new turn with a note can. The rules for that decision are still open
([CODE_HARNESS_DURABILITY.md](CODE_HARNESS_DURABILITY.md), section 6).

#### Laws

| Law | Statement |
|---|---|
| Commit | A reader sees an entry only after its commit. |
| Fold | `SessionState` is the fold of the log with `evolve` (8.5). The worker keeps no other session state. |
| Effect | An intent commits before its effect, and the outcome commits after the effect. |
| Store | The store is the fold of the `writes` of `Observed`. A cell that does not complete has no `writes`. |
| Input | Two `Send` commands with one `RequestId` give one `Input`. |
| Stop | After `StopRequested`, the decider accepts no new intent in that turn. An `Input` from before the stop starts no turn. |
| Projection | `project` depends only on the log and the derived data (7.2). |
| Log | No part of the harness changes or removes an entry of the log. |
| Fork | A fork at entry `k` starts with the first `k` entries of the parent log. |
| Loop | At most `limits.iterations` policy calls in one turn. `next` holds this limit ([CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), 4.3). |
| Handlers | The laws of F2. |
| Mode | The mode of a child is at most the mode of its parent (F5). |
| Budget | The `subQuery` calls of one call tree, with the children, are at most `LlmCallLimit`. |

### 4.3 The module

```scala
enum Strategy:
  case Window
  case PrefixSummary(compactor: Predict[CompactInput, Summary])
  case MemoryTree(compress: Predict[CompressInput, Line], merge: Predict[MergeInput, Line])  // section 6

final case class Harness[I, O](
    signature: Signature[I, O],
    policy   : Predict[PolicyInput, PolicyStep],  // the step
    finisher : Predict[FinishInput, O],           // the fallback of a program call (4.2)
    subQuery : Predict[Query, Answer],            // F4
    goal     : Predict[GoalInput, GoalVerdict],   // the goal check (4.4)
    strategy : Strategy,                          // its leaves join the leaves of the harness
    env      : HarnessEnv,                        // handlers, workspace, REPL factory, mode; not optimizable
    limits   : HarnessLimits                      // not optimizable
) extends Module[I, O]
```

- The `OptimizableStructure` instance has arity 4, plus the leaves of the strategy: none for a window, one for a
  prefix summary, and two for the memory tree. `arity(p)` is a function of the value, so a sum type works. The
  fields with no leaves need `OptimizableStructure.empty` instances.
- The `ProgramRunner` instance comes from the input shape of `signature`, as for RLM.
- `forward` makes a REPL with the givens of the mode, runs one session to its end, and closes the REPL in a
  `finally`, as RLM does.

Two entry points use the same kernel:

- `harness(input)`: one task in one session, in a workspace fork (F6). The journal is in memory or in a file, and
  no actor is necessary. The optimizers use this entry point.
- The session actor (8.5): interactive turns, the controller, and `tell`.

### 4.4 RLM, subagents and the goal check

- The inputs of `I` enter the REPL as typed values, and the prompt shows a summary of each value (F5).
- `llmQuery` and `llmQueryBatched` take `Llm^` and run the `subQuery` leaf (F4).
- `rlm(task, inputs)` takes `Agents^`. It starts a child session with the same `Harness` value, a new REPL, and a
  mode that is at most the mode of the parent. The child uses the same leaves, so its calls have the same keys.
  The parent log gets one `Child` entry with the result. The child log stays separate, so the parent context
  stays small.
- Later, the current `RLM` program can become a `Harness` with the Python REPL handler and the RLM prompt. Then
  dspy4s has one code program, as decision D1 intends.

There are two kinds of subagents:

| Function | Semantics | Use |
|---|---|---|
| `rlm(task, inputs): A` | Synchronous. The child runs inside the cell and gives a typed value. It has a depth limit and the shared budget. | A part of a computation (RLM). |
| `spawn(task): AgentRef` | Asynchronous. The child runs in the background with the view of the parent and its own log. Its report comes back as an `Input` entry from the child, which starts a turn. `tell(ref, message)` sends it a message. | Long work in the interactive shell. |

For the optimizer entry point (one task), the harness waits for all spawned agents before it ends, or it turns
`spawn` off. Then each rollout is closed.

A work session that the controller starts (7.3) is not a child. It has its own scope, the user can talk to it,
and its events go to the controller. Open question 5 of section 11 asks if `spawn(...)` is still necessary next
to these sessions.

The goal check is a `Predict` leaf with a `Noul` output (decision D6): "is the goal met now?". On `false`, the
harness adds a "keep working" message and runs one more turn. ReAnchor can calibrate its threshold later. 9.3
proposes two rules for this automatic work: a human must arm it again after a restart, and a goal is "blocked"
only after three turns with the same blocker.

### 4.5 Why this design

- **It is functional.** The log is a value, the projection is pure, and the decider is pure. The effects go
  through handlers. Only the handlers, the builder of the derived data, and the REPL have effects.
- **One journal holds the truth.** The phase of a session, its store, and its effects are folds of one event
  type. Only a commit makes a change visible, so a live session and a reopened session have one model.
- **A restart needs no replay.** The store comes back from the journal, and the REPL state is a cache. Replay
  (8.4) is an addition that also brings back the definitions.
- **No entry of a log is lost.** A context strategy changes only what the model sees. The memory tree can give
  back each detail with `zoom`.
- **The compiler checks the authority of each session.** Its mode is a set of givens (F2).
- **The optimizers see each LM call.** The leaves are the policy, the finisher, `subQuery`, the goal check, the
  leaves of the strategy, and the leaves of the controller (7.8).
- **The prompt prefix stays stable.** The history grows at its end, and the memory tree has a prefix law (6.2).
- **One design serves the optimizer and daily use.** For the optimizer, an episode is a workspace fork and one
  task. For daily use, sessions and the controller run as actors.

The costs:

- There is more new code than in an RLM configuration (5.1).
- Each guest API call costs two commits, and the caller waits for each commit. On SQLite, a check measured about
  140 µs for each commit with no full flush of the disk cache, and 5 to 9 ms with `PRAGMA fullfsync` ([CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md), section 2). Choice 17 of
  [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md) uses the full flush only for some commits.
- The journal stores the result of each guest API call, so a large `read` or `exec` result costs storage (8.4).
- The builder of the derived data runs in the background, so it adds concurrency.
- In a session with the memory tree, each message costs one compaction call on a cheaper model (6.5).
- `LmRequest` has no prompt-cache marks (gap 6). An Anthropic provider needs them.

### 4.6 What is open

The decisions so far are in 7.10 and 8.7. The open questions are in 8.7, 9.4 and section 11.

## 5. Design history

The recommended design came from three earlier designs. This section keeps a short record of each design, and
of the comparison between them.

### 5.1 Design A: the harness is an RLM configuration

The idea: make the RLM interpreter contract independent of the language, and add a Scala implementation. The
harness is then an `RLM[I, O]` value with the Scala REPL and the workspace API. The changes were a
`ReplLanguage` in `programs` (the Python text of `RLMReplProtocol` moves into `ReplLanguage.python`), `llm_query`
through a `subQuery` leaf, and a `ScalaReplInterpreter` in the new module:

```scala
val harness: RLM[Task, Report] =
  CodeHarness(
    signature = Signature.derived[Task, Report]("code_task", "Do the task in the workspace."),
    workspace = Workspace.local(Path.of(".")),
    limits    = HarnessLimits(iterations = 30, llmCalls = 50)
  )
```

Why it changed:

- One call is one task. There is no conversation, no steering, and no compaction.
- The REPL state ends with the call, and the trajectory is one text field.
- `SandboxTool` passes `DynamicValue` records, and the RLM interpreter contract has no capabilities and no
  modes.

What stays: the change of `llm_query` to a `subQuery` leaf (F4) also helps the current Python RLM. Design A
stays available as a cheap first check of the foundation.

### 5.2 Design B: a session algebra

The idea: a small kernel of four values and one fold, as in pi. The log is the state, a pure projection makes
the policy input, each LM step is a `Predict` leaf, and the REPL is a handler. RLM is the meaning of a code step,
not a separate program. The log was a tree by parent id. A compaction was a middleware that added a `Compacted`
entry, and that entry replaced a prefix in the projection.

Why it changed:

- A compaction removed detail from the prompt, and the agent could not get it back.
- A `Compacted` entry was a change of the log for a concern of the context.

What stays: the kernel (4.2), the module (4.3), RLM as a child session (4.4), and the laws. The context
strategies of 7.2 replace the compaction middleware, because a strategy never changes the log.

### 5.3 Design C: one log with a memory tree

The idea: keep the kernel of design B, and replace its compaction with the uniichat memory. There is one log that
never ends, a binary tree of summaries over the log, and a view that is a cut through the tree.

Why it changed: one log for the full harness gives no isolated session for a narrow task. Section 7 kept the
memory tree for the controller, and it made each work session a separate log with its own context strategy.

What stays: the memory tree, as one context strategy and as the strategy of the controller (section 6), the
two compaction leaves, and the goal check (4.4).

### 5.4 The comparison of the three designs

| Criterion | Design A | Design B | Design C |
|---|---|---|---|
| New code | Small | Medium | Large |
| Reuse of the current RLM | Full | The RLM loop parts | The RLM loop parts |
| Conversation and resume | No | Yes, from the session log | Yes, one log that never ends |
| Compaction | No | Replaces a prefix in the projection | Adds summary nodes; nothing is lost |
| Detail after compaction | Not applicable | Lost in the prompt | Available through `zoom` |
| Optimizable leaves | 3 (action, extract, subQuery) | 4 (policy, finisher, compactor, subQuery) | 6 (policy, finisher, compress, merge, subQuery, goal) |
| LM calls from code in the trace | Yes, after F4 | Yes | Yes |
| Subagents | `rlm(...)` as a sandbox tool | `rlm(...)` as a child session | `rlm(...)` and `spawn(...)` |
| Trajectory form | One text field | Chat turns through `History` | View lines, then chat turns through `History` |
| Prompt cache | A prefix of the history field | A stable prefix until a compaction | A stable prefix by the prefix law |
| Laws | The current RLM and loop laws | Log, projection, middleware, handlers | The laws of B, and the memory laws |
| Change to the current code | RLM changes (`ReplLanguage`, `subQuery`) | No change to RLM in the first version | No change to RLM in the first version |

## 6. The memory tree

The memory tree is one context strategy of 7.2, and it is always the strategy of the controller (7.1). It comes
from uniichat (2.3). The algebra below is pure. Only the builder, which runs the two compaction leaves, has an
effect.

### 6.1 Memory algebra

```scala
opaque type MsgId = Long
final case class Message(kind: Kind, text: String, date: Instant) derives Schema

// Node (level, index) covers the messages [index * 2^level, (index + 1) * 2^level).
final case class NodeId(level: Int, index: Long):
  def span: (MsgId, MsgId)
  def halves: Option[(NodeId, NodeId)]  // None at level 0
  def parent: NodeId

final case class Line(text: String)      // at most 512 bytes

// A persistent value: the log and the built nodes. A fork shares both.
final case class Memory(log: Vector[Message], built: Map[NodeId, Line])

// A cut through the tree that covers [0, n), oldest first.
final case class View(parts: Vector[NodeId])

object Memory:
  def append(m: Memory, msg: Message): Memory
  def ready(m: Memory): Vector[NodeId]        // the nodes not built whose halves are built
  def build(m: Memory, id: NodeId, line: Line): Memory
  def roots(n: Long): Vector[NodeId]          // one root for each set bit of n
  def zoom(m: Memory, id: NodeId): Either[Message, (Line, Line)]

object View:
  def extend(v: View, upTo: MsgId): View      // adds level-0 lines at the end
  def fit(v: View, m: Memory, high: Int): View // merges the most due pairs down to high / 2
```

Two `Predict` leaves give the summary measure:

```scala
val compress: Predict[CompressInput, Line]  // one message -> one line
val merge   : Predict[MergeInput, Line]     // two adjacent lines -> one line
```

A source of 512 bytes or less is its own line, with no LM call. A builder runs the ready nodes on
`ParallelExecutor`, in message order, and adds each line with `build`. The builder is the only part with an
effect. `Memory` and `View` are pure.

### 6.2 Laws

| Law | Statement |
|---|---|
| Coverage | The spans of a view are disjoint and in order, and together they cover `[0, n)`. |
| Zoom | For a built node `p`, a view that shows the two halves of `p` merges back to the view that shows `p`. |
| Prefix | Between two `fit` batches, the later view is the earlier view with lines added at its end. The prompt cache depends on this law. |
| Immutability | `build` never changes a built node. A fork at message `n` shares every node whose span ends before `n`. |
| Order | A node builds only after its two halves. |
| Size | After `fit`, the size of the view is at most `high`, when the parents that it needs are built. |
| Roots | `roots(n)` has one node for each set bit of `n`, and their spans cover `[0, n)` in order. |

### 6.3 Turn and projection

With the memory tree, the projection of 4.2 has three parts:

1. The view up to the first message of the turn. This part is the stable prefix.
2. The messages of the turn in full.
3. The steps of the turn as `History` turns.

The cells and the outputs of a turn go into the log as messages, so later turns see them as summary lines. A
turn waits until the nodes that its view needs are built (uniichat `settle`).

### 6.4 Code mode and RLM

- `zoom(ref)` and `date(id)` are guest functions. Code can walk the tree in a loop. A JSON tool needs one
  model step for each zoom.
- The full log is an RLM input: a REPL value with a summary in the prompt, and the view is that summary. The
  RLM rule "large inputs stay in the REPL" and the uniichat rule "the view is the memory" are the same rule.
- Code can call `llmQuery` on the lines of a subtree, for example to find a past decision. uniichat forbids a
  search of the memory. Section 11 lists this as an open question.

### 6.5 Costs

- The tree, the view, the builder, and the two leaves are new code.
- The builder runs in the background, so it adds concurrency. A turn must wait for the nodes that it needs.
- Each message costs one compaction call on the cheaper model.
- `LmRequest` has no prompt-cache marks (gap 6). An Anthropic provider needs them.

## 7. Sessions and the controller

This section describes the work sessions of the recommended design (section 4), each with a pluggable context
strategy, and one controller conversation above them.

### 7.1 Two kinds of conversation

- **Work sessions.** Each session has a scope: a directory, a worktree with a branch, or a scratch directory.
  The user opens a session and talks to it directly. Each session has its own context strategy (7.2).
- **The controller.** One conversation that never ends, as in uniichat. Its strategy is always the memory tree.
  It starts sessions, makes their scopes, and knows about all sessions.

The user goes to the controller to start or steer work. Then the user zooms into a session and does the work
there. A project with a repository already has a natural scope. For work with no such structure, the controller
makes the directories, the worktrees and the branches as necessary.

The uniichat subagents are not sessions in this sense:

| Property | uniichat subagent | Work session |
|---|---|---|
| The user can send it a message | No. The user commands are `stop`, `goal`, `go`, `guess`, `pick` and `out`. Only Unii can use `tell`. | Yes |
| Compaction | None. Each call gets the main view and the full log of the subagent (`Tools.ask`). | A context strategy (7.2) |
| Scope | `<work>/<Name>` on a device | A directory, a worktree with a branch, or a scratch directory |

A subagent is a short worker. A new UI page does not make it a session.

### 7.2 Context strategies

All strategies obey one rule: the session keeps its full log, and a strategy never changes the log. A strategy
owns only its derived data and its projection.

```scala
// Data that a log gives, kept up to date as the log grows.
trait Derivation[D]:
  def empty: D
  def update(log: Log, d: D)(using RuntimeContext): Either[DspyError, D]  // can call Predict leaves

// A derivation that also makes the LM context.
trait ContextStrategy[D] extends Derivation[D]:
  def project(log: Log, d: D, budget: Int): Context                       // pure
  def guestApi: GuestApi                                                 // for example `zoom`
```

| Strategy | Derived data | Projection |
|---|---|---|
| Window | None | The last messages that fit |
| Prefix summary (atc, pi) | Summary entries | The last summary, then the messages after it |
| Memory tree (uniichat, section 6) | The summary nodes | A cut through the tree, and `zoom` in the guest API |

- The memory tree keeps the full log, so it compacts only the prompt. It is the only strategy that adds a guest
  function.
- The log does not change, so a session can change its strategy later. The new strategy derives its data from
  the same log.
- One builder runs all derivations of a log in the background: the context strategy, and the graph of 7.5.

Laws:

- `project` depends only on the log and the derived data.
- `update` never changes the log.
- Two updates give the same structure as one update on the full log. For the memory tree, the structure is the
  set of nodes. The text of a node can differ, because an LM writes it.

### 7.3 The controller API

The controller is a mode of F2: a set of givens. It can manage work, but it cannot do the work:

```scala
enum Scope:
  case Directory(path: Path)
  case Worktree(repo: Path, branch: String, base: String)
  case Scratch(name: String)

trait Sessions extends caps.ExclusiveCapability:
  def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef
  def list(): Vector[SessionCard]
  def tell(session: SessionRef, message: String): Unit
  def observe(session: SessionRef, question: String): String  // a probe (7.4)
  def zoom(session: SessionRef, ref: String): String
```

The preamble of the controller declares `sessions: Sessions^` and the read-only view `files: Files`, so the
controller can answer questions about a project. It has no `Shell` and no `Files^`. So `write` and `edit` do
not compile, because they need `Files^`, and `exec` does not compile, because it needs `Shell^` and `Files^`.
The hint after such an error, and the controller instructions, tell the model to start a session for that work.

The other option is a nudge: a classifier finds "real work" in the controller and tells the user to start a
session. The classifier can be wrong in both directions, and it needs its own tests. A restriction is a fact of
the type system, and a test can check it.

The controller has two kinds of access to a session, with different effects:

| Function | Effect on the session |
|---|---|
| `observe(session, question)` | None. It is a probe on the session log. |
| `tell(session, message)` | The message goes into the session log. The session agent reads it and can act on it. |

Rules for `tell`:

- The message has the source `Controller` (4.2), so the user and the agent know who sent it.
- If a turn runs, the message goes in between two steps, as in uniichat. If the session is idle, the message
  starts a turn, also when the user is not in the session.
- The reply goes back to the controller as an event.
- A rule for all sessions is a `tell` to each open session.

The controller delegates. It never runs an effect on the scope of a session. When the user confirms an action in
the controller, for example the removal of a worktree, the controller sends the confirmation to the session with
`tell`, and the session does the action.

### 7.4 What the controller sees

The model has no state between calls. The state of a session is its log, plus the REPL and the workspace. So:

- **A session is an unfold.** It starts from a seed (the request). Each step reads inputs (user messages, and
  data from sources) and adds entries to the log. Strictly, it is a stream transducer,
  `(State, Input) => (State, Output)`, because inputs arrive during the run. The log is its trace.
- **An observation is a fold over the log.** "The decisions", "the actors" and "the ingredients of a recipe" are
  functions `Log => A`. In dspy4s, an observation is a program with a typed output, for example
  `Signature[SessionLog, Ingredients]`. The log is a large input, so RLM fits it: the log stays in the REPL,
  the view is its summary, and code can zoom.

A session has two kinds of output. External outputs (files, commits, rows) go through tools, so the harness can
record them exactly. Internal outputs (conclusions, decisions, ideas) stay in the conversation, so an LM must
extract them. The controller sees three layers:

| Layer | Source | LM | Content |
|---|---|---|---|
| Ledger | The `recorded` handler (F2) | No | The sources that the session read, and the artifacts that it made |
| Fixed observations | The ontology extraction (7.5) at the end of each turn | Yes | Goals, decisions, findings, questions, concepts, and their links |
| Probes | `observe(session, question)`: an RLM call on the session log | Yes | Any question |

In code mode the ledger is exact. One cell can read 50 files, and the handler still records each read.

The controller log gets an event only when layer 1 or layer 2 changes in a way that matters: a session starts
or ends, a decision is made, or an artifact is added. The raw messages of a session stay out of the controller
log. With all messages, the controller memory tree would grow with the work of every session, and the old
summaries would lose the important items. A `SessionCard` shows the goal, the scope, the status, the
artifacts, the decisions, and the open questions of a session.

### 7.5 The ontology

An ontology is a value, and it is pluggable, as a context strategy is. The mechanism stays the same for all
ontologies: typed facts, typed links, a source for each fact, and time (7.6). An ontology gives only the kinds
of facts and the kinds of links.

```scala
final case class Kind(name: String, description: String) derives Schema
final case class Ontology(name: String, facts: Vector[Kind], links: Vector[Kind]) derives Schema
```

- The extraction leaf gets the ontology as data. The kinds become the allowed values of the `kind` fields of
  its output (`FieldSpec.enumValues`). So the adapter tells the model the kinds, and a check with no LM call
  rejects an unknown kind.
- Each session has one ontology. The default comes from the controller configuration. A project can give its
  own ontology, for example one for recipes with the kind `Ingredient`.
- The graph is a derivation of the log (7.2). So when a session changes its ontology, it derives a new graph
  from the same log.

The default ontology is small and general. A large ontology costs more, and its extractions are less precise.
The default kinds of facts:

| Kind | Example |
|---|---|
| Goal | "Add a code harness module" |
| Decision | "Use the memory tree for the controller", with its reason and a status: proposed, accepted, or replaced |
| Finding | "uniichat subagents have no compaction" |
| Question | "Who ends a session?", open or answered |
| Artifact | `docs/code-harness/CODE_HARNESS.md`, a commit (from the ledger) |
| Source | `uniichat/Memory.ts` (from the ledger) |
| Concept | "memory tree", "controller", "context strategy" |

The default kinds of links are `comes from`, `answers`, `refines`, `contradicts`, `replaces`, `decides`,
`depends on`, `produces`, `uses`, and `mentions`.

Rules:

- **Each fact keeps its source.** Each node and each link keeps its session id and its message ids. The
  controller can zoom from a fact to the messages that support it.
- **Replacement.** A new decision `replaces` an old one, and the old one stays in the graph. uniichat uses the
  same rule: "its latest word on a thing is the truth".
- **Explicit marks.** The agent or the user can call `decide(text)` or `note(finding)`. These marks are more
  precise than an extraction. The extraction only has to find what the marks do not cover.
- **One graph for each session.** The most difficult part of a knowledge graph is to find where two sessions
  use two names for the same concept. So the first version keeps one graph for each session. A merge across
  sessions comes later.

### 7.6 Time

The questions "where did this concept first appear?" and "how did we get to this decision?" need time on each
fact. The log index is a clock: message 120 comes after message 80. Each fact keeps the first message that
mentions it, all the messages that mention it, and their dates.

The extractor sees each line with its message id (`id+n|text`, as in uniichat). It must cite the ids in its
output:

```scala
opaque type KindName = String  // a kind of the ontology of the session (7.5)
final case class Fact(kind: KindName, text: String, at: Vector[MsgId]) derives Schema
final case class Link(from: FactId, to: FactId, kind: KindName, at: MsgId) derives Schema
```

A check with no LM call rejects each id that is not in the part of the log that the extractor saw.

The graph is a log of assertions, each one with a message id. For example:

- at m12: the concept "controller" appears
- at m40: decision D is proposed
- at m45: D is accepted
- at m90: D2 replaces D

The graph at time `t` is a fold of the assertions up to `t`. This is the same pattern as the session log: the
log is the truth, and the graph is a derivation (7.2). The queries:

| Query | Result |
|---|---|
| `firstMention(c)` | The first assertion about `c` |
| `timeline(c)` | All the assertions about `c`, in order |
| `stateAt(t)` | What the session knew at message `t` |
| `lineage(f)` | The facts that led to `f`. The query goes back along the links `comes from`, `answers`, `refines`, `contradicts` and `replaces`, and it sorts the facts by time. |

The discussion that added this section is an example. The lineage of "time on each fact" is:

1. Sessions have pluggable compaction, and one controller uses the memory tree.
2. A question: what does the controller see of a session?
3. All messages would make the controller memory too large, so the controller must see only the important
   information.
4. A session is an unfold, and an observation is a fold over its log.
5. A small ontology, as a knowledge graph, is a standard set of observations.
6. Facts need time, so that you can ask where a concept appeared and how you got there.

Each step comes from the step before it. An extractor that writes `comes from` links with message ids can make
this list again.

Time and the memory tree work together. A message id is a leaf of the tree, so the answer to "where was X
introduced?" is a leaf. The nodes above that leaf give its context with less detail. When one subtree has many
mentions of a concept, that subtree is a phase of the conversation about the concept.

The difficult part is to connect new facts to old ones at the end of a turn, for example: "this decision comes
from that question in an earlier turn". So the extractor gets the current concepts with their ids as an input,
and its instructions tell it to use the old id for the same concept.

A later option: extract a small graph for each leaf of the memory tree, and merge two graphs at each node.
Graph union is associative when the names agree, but a text summary is not associative. So the tree shape suits
the graph well.

### 7.7 Graph access and the flow of information

By default, information goes from the sessions to the controller. A session writes only its own graph, through
its extraction and its explicit marks. The controller sees the changes as events (7.4).

Read access is wider. The main way to use the graph is a chat in a session: the user asks a question, and the
agent queries the graph with code. So each session gets a read-only guest API:

```scala
enum GraphScope:
  case ThisSession
  case Session(ref: SessionRef)
  case Controller
  case AllSessions  // the results of each session, with no merge of names (7.5)

trait Graph:
  def ontology: Ontology                                      // the kinds that the session can query
  def concepts(scope: GraphScope): Vector[FactRef]
  def find(text: String, scope: GraphScope): Vector[FactRef]
  def firstMention(fact: FactRef): Assertion
  def timeline(fact: FactRef): Vector[Assertion]
  def stateAt(session: SessionRef, at: MsgId): Vector[FactRef]
  def lineage(fact: FactRef): Vector[Assertion]
```

- Each result keeps its session id and its message ids, so the agent can zoom to the source.
- The controller has a graph of its own log too. A session can read it with `GraphScope.Controller`.
- A query over all sessions gives the results of each session separately. The agent or the user decides when
  two names are the same concept.
- The controller gets the same API, and the functions of 7.3.

### 7.8 Optimization of the controller

- The extraction leaves are `Predict` leaves with typed outputs, so the optimizers can tune them.
- A metric for the graph: ask "where was X introduced?" and "what led to D?". Compare the answer from the graph
  with the answer from a probe on the full log.
- The cited message ids make part of this check exact. A first mention is correct or wrong.
- The controller has its own leaves: its policy, and the leaf that writes a `SessionCard`.

### 7.9 The end of a session

A session holds different resources, and an end means something different for each one:

| Resource | Can the harness make it again? |
|---|---|
| The log | No. The memory tree, the graph, and the links from the controller come from it. |
| Derived data (memory tree, graph) | Yes, from the log. |
| The store | Yes. It is a fold of the log (4.2). |
| The live REPL | Partly. The definitions are lost, but the log still has the cells. Replay (8.4) can rebuild them. |
| A worktree with no changes | Yes, from the branch. |
| A worktree with uncommitted changes | No. |
| A merged branch | Yes. The base branch has the commits. |
| A branch that is not merged | No. The reflog keeps it only for a short time. |
| A scratch directory | No. |

Three principles come from this table.

**1. The end is an event, not a deletion.** The harness appends `Ended(outcome, reason)` to the log, and the log
stays. The user can reopen a closed session. The harness then appends `Reopened`, and it makes the scope again
if necessary. uniichat has the same rule: nothing is lost.

**2. Make a plan first, then remove.** A pure function makes a plan from the state of the scope:

```scala
enum Outcome:
  case Merged, Parked
  case Abandoned(reason: String)
  case Replaced(by: SessionRef)

enum ReleaseStep:
  case RemoveWorktree(path: Path)
  case DeleteBranch(name: String)
  case KeepRef(name: String, commit: Sha)  // makes a later delete reversible
  case ArchiveDirectory(path: Path, to: Path)
  case Keep(resource: String, reason: String)

def releasePlan(scope: ScopeState, outcome: Outcome): Vector[ReleaseStep]  // pure
```

A handler runs the plan, and the ledger records each step. A dry run shows the plan with no effect. Tests can
check the laws of the plan:

- A worktree with uncommitted changes never gets `RemoveWorktree`.
- A branch that is not merged never gets `DeleteBranch` without a `KeepRef` before it.

**3. Reversible steps run automatically, and irreversible steps need the user.** When it costs little, the plan
first makes an irreversible step reversible. For example, it adds a ref `archive/<session>` before it deletes a
branch, or it makes an archive file before it deletes a directory. A step that stays irreversible needs a
confirmation from the user.

The default plans:

| Outcome | Log | Worktree | Branch | Scratch directory |
|---|---|---|---|---|
| Merged | Keep | Remove | Delete (the base has the commits) | Not applicable |
| Parked | Keep | Keep | Keep | Keep |
| Abandoned | Keep | Remove if it has no changes | Keep a ref, then delete | Archive, then delete |
| Replaced | Keep | Remove if it has no changes | Keep | Keep |

The plans are values, so a user or a project can change them.

Who ends a session:

- The user, in the session or through the controller. The controller sends the end to the session with `tell`
  (7.3), and the session runs the plan.
- The session agent can propose an end. The goal check (the `Noul` leaf of 4.4) gives a good signal. The
  user then confirms the outcome.

The ledger after a removal: a ledger entry such as "wrote `src/Foo.scala` in worktree X" points to nothing
after the worktree is gone. The log stays, so the facts that cite message ids keep their sources. Only the
artifact entries need more. A small solution: at the end of each turn, the session records the commit hash of
its scope and whether the scope has changes (`ScopeSnapshot(head, clean)`). The release plan keeps a ref before
it deletes a branch. Then a path and a commit hash still point to the file. The same snapshot gives the
`ScopeState` of `releasePlan`.

A content hash for each write is not in the first version. A hash only shows that two contents are the same.
To get a content back, the harness must also store it, so each write costs a hash and storage. A write that the
session never commits is lost when its scope is removed. The plans prevent most of these losses, because they
never remove a worktree with changes.

### 7.10 Decisions and open questions

Decisions, 2026-10-21:

1. The ontology is pluggable (7.5). The default kinds are an example. The mechanism is the important part.
2. By default, information goes from the sessions to the controller. A session reads the ontology and the
   graphs only on demand, through the `Graph` API (7.7). Nothing goes into its context automatically.
3. The first version keeps one graph for each session.
4. A user can start a session directly in a repository, outside the controller. The session still sends its
   events to the controller.
5. There is one controller for each user.
6. The controller acts on sessions through `observe` and `tell` (7.3). A `tell` to an idle session starts a
   turn.
7. The controller delegates. It never runs an effect on the scope of a session. A confirmation in the
   controller goes to the session with `tell`, and the session does the action.
8. The end of a session is an event. The log always stays, and the user can reopen a closed session (7.9).
9. The plans of 7.9 are the default plans. A user or a project can change them.
10. At the end of each turn, the session records a `ScopeSnapshot` with the commit hash of its scope (7.9). The
    first version does not record a content hash for each write.

Section 7 has no open questions now.

## 8. Runtime: actors, events, and replay

This section describes how a session runs. It adds three ideas to section 7: a session is an actor, its log is
an event journal, and a replay of the journal can rebuild the REPL state.

### 8.1 A session is an actor

The design of section 7 already works with messages:

| Section 7 | Actor term |
|---|---|
| A user message, or a `tell` from the controller | A message in the mailbox |
| One turn at a time. A message that arrives during a turn goes in between two steps. | The actor handles one message at a time |
| The log, the REPL, the scope | Private state |
| `observe(session, question)` | A request and a reply (`ask`) on a read model |
| Events from the session to the controller | Publish and subscribe |

kyo-ai also models agents as long-lived actors (`docs/refactor/kyo-ai-comparison.md`).

### 8.2 The log is an event journal

| Section 7 | Event sourcing term |
|---|---|
| The log is the truth, and it is append-only | The event journal |
| `Entry` (4.2): one type for all events of a session | Events |
| Derivations: the memory tree, the graph, `SessionCard` | Projections (read models) |
| Reopen a session | Recover the state by replay |

There is one difference from standard event sourcing. An LM makes the memory tree and the graph. To derive them
again costs money, and the result is not the same. So the harness stores the derived data too, in a journal of
its own. uniichat also keeps its messages (`main/`) and its summary nodes (`tree/`) in separate files.

Each `Logged` record has a `required` mark (4.2). A reader that cannot decode an entry stops a rebuild if the mark is
`true`, and it skips the entry if the mark is `false` (deepseek-harness). Thus a later version can add optional
entries, and an earlier reader can still read the log.

### 8.3 Events inside a cell

An `Acted` entry holds the full cell, and one cell can do many actions. But each action of a cell goes through a
handler (F2). So the `recorded` handler commits two entries for each guest API call, at the time of the call. The
cell events of a cell are its `Acted`, each `EffectStarted` and `EffectDone`, and its `Observed` (4.2). They use
these types:

```scala
final case class EffectId(cell: Long, n: Int)         // `cell` is the `seq` of the `Acted` entry

enum EffectResult derives Schema:
  case Value(value: DynamicValue, rendered: String)   // the typed value for a replay, and the text for a reader
  case Error(message: String)                         // the call failed
  case Unknown                                        // a crash cut the call
```

- The unit of an event is a guest API call, not a line of code.
- The pure computation between two calls needs no event. The code and the recorded results give the same output
  again.
- This is the model of durable execution, as in Temporal: the cell is the workflow function, and each guest API
  call is an activity.

Each effect needs one more rule. `EffectStarted` goes into the journal before the effect, and `EffectDone` goes in
after it. After a crash, a start with no done marks an effect whose result is unknown. The harness appends
`EffectDone(id, EffectResult.Unknown)`, so the log stays consistent (deepseek-harness). Nothing runs the effect again
automatically. The harness then asks the user, or it uses an idempotent operation. uniichat has the same rule for
its acts.

### 8.4 REPL replay

**The problem.** The REPL state is the set of definitions that the earlier cells made (`val`, `def`, `class`,
`import`), and the objects that those definitions hold in memory. A restart removes all of it: a crash, a
session that reopens the next day, or a session that moves to another machine. The agent uses these definitions
in later cells, so its next cell fails with an error such as `Not found: counts`. atc adds the note "the REPL
restarted", and the model must do the work again. With a memory tree, the code of an old cell is a one-line
summary, so the model cannot see the exact code that it must run again.

The store of 4.2 gives back the values that the model saved with `store`. Replay gives back the remainder: the
definitions and the values in REPL variables. So replay is optional, and the store is enough for a restart.

**The mechanism.** Replay starts a new REPL and runs each recorded cell again, in order. The cell code is the
same, but the `replayed` handler takes the place of each real handler. When a cell calls the guest API, the
handler gives the recorded result, and it does not do the effect. An example:

```scala
// Cell 1
val build = read("build.sbt")
val modules = "lazy val (\\w+)".r.findAllMatchIn(build).map(_.group(1)).toVector

// Cell 2
def testsOf(m: String): Vector[String] =
  exec(s"rg --files modules/$m/src/test").stdout.linesIterator.toVector
val counts = modules.map(m => m -> testsOf(m).size).toMap

// The session restarts. Then the model writes cell 3:
counts.filter(_._2 == 0).keys
```

Without replay, cell 3 fails, because `counts` does not exist. With replay, the harness first runs cells 1 and 2
again:

- `read("build.sbt")` gives the recorded content. The disk is not read.
- Each `exec` gives the recorded output. No process starts.
- `llmQuery` gives the recorded answer. No LM call occurs, so the replay costs nothing.
- `write` gives its recorded result. The file already has that content, so no write occurs.
- `load` gives the value from the store at that point in the log. The store is a fold of the log, so `load` needs
  no event.

After the replay, `build`, `modules`, `testsOf` and `counts` exist again, and cell 3 works. The REPL state after
replay is a function of the journal only, not of the files now. So replay restores the REPL to its exact state
at the end of the last cell. If another session changed the files since then, `counts` can be old. That is also
true when no restart occurs.

**How a call finds its recorded result.** Inside one cell, the handler finds the recorded effects by order, and
it checks that the operation and the arguments are the same. With `parallel`, the order of the calls can change,
so the handler finds an effect by its operation and its arguments in the cell.

**Divergence.** The replay can find that a cell does not do what the journal says:

| Cause | How the harness finds it |
|---|---|
| The code reads time, a random number, or an environment value that no handler records | A call has other arguments than the recorded call, or the output is different |
| The cell calls an effect that the journal does not have | No recorded effect agrees with the call |
| The compiler or the guest API changed since the session ran | A compile error, or a different output. The journal records both versions, and the harness can skip replay when they differ. |
| A value is a handle to a live resource, for example a process | Replay cannot make the resource again. The handle becomes a stale handle that fails with a clear message. |

At the first divergence, the replay stops. The REPL keeps the definitions of the cells before that point. The
harness tells the model which cells it replayed and which names exist. Temporal has the same check.

**Which cells replay.** A cell that did not compile defined nothing, so the replay skips it. A cell that failed
at run time has an invalid wrapper in the Scala REPL (atc calls `skipInvalidWrapper`), so the replay skips it
too. The other cells stay consistent, because the replay never reads the real world. A later improvement: the
compiler knows which names each cell defines and uses, so the harness can replay only the cells whose names the
live code needs.

**The cost.**

- Storage: the journal holds each result that the code saw (4.2). Small results (`write`, `llmQuery`) cost little.
  Large `read` and `exec` results cost the most. A content store can keep each content one time only. Decision
  10 of section 7 records no content for writes; replay needs the content of reads.
- Time: each cell compiles again. Phase 0 measured 12 ms to 55 ms (median) for a cell in a warm REPL, and 73 ms
  for the replay of three cells (section 11). A long session with complex cells can take some minutes.

**Other uses.**

1. Fork at cell `k`: replay cells 1 to `k` into a new REPL, and continue on another path with the REPL state of
   that point.
2. Optimization with no effects: replay a recorded run up to step `k`, and test a new instruction for the later
   steps only. The earlier steps cost no LM calls and no tool runs.
3. Tests: a recorded session is a deterministic test with no network and no files.
4. Debug: run a session again, exactly as it ran.

Replay also tests the F2 law "`replayed(log)` gives the same results as the handler from which `recorded` wrote
`log`" on full cells.

**Preparation in the first version.** If replay comes later, three steps in the first version make it a small
change:

1. Send all nondeterminism through handlers: a `Clock`, a `Random`, and a fixed order for `parallel` results.
   This also helps the tests.
2. Write the cell events of 8.3, with the typed value of each result (4.2).
3. Record the compiler version and the guest API version in the journal (`Started` and `Reopened`, 4.2).

The later change is then only to add the replay of cells.

### 8.5 The decider and the runtime

The algebra of a session is a pure decider. A runtime is an interpreter of it:

```scala
trait Decider[C, E, S]:
  def initial: S
  def decide(command: C, state: S): Either[Rejection, Vector[E]]  // pure
  def evolve(state: S, event: E): S                                // pure
```

Laws:

- The state is `events.foldLeft(initial)(evolve)`.
- Two deciders compose into one decider.

The decider owns the state machine of the session (the phases and the commands of 4.2) and the release plan of
7.9. The long turn (the LM calls and the REPL) runs in a worker outside the decider. The worker commits its entries
through the `Journal` of 4.2, and it waits for each commit. With an actor, a commit is a message to the actor, and
the actor is the one writer of the journal.

| Runtime | What it gives | Cost |
|---|---|---|
| Direct style: one virtual thread and one queue for each session (or Ox actors) | It agrees with the current dspy4s style: `Either`, direct calls, `RuntimeContext` | Persistence and distribution are new code |
| Pekko Typed, with Pekko Persistence (`EventSourcedBehavior`) and Cluster Sharding | Journal plugins, snapshots, projections, one entity for each session id across machines | A large runtime. A turn blocks, so it must run outside the actor (`pipeToSelf`). The thread-local `RuntimeContext` needs `ContextPropagation`. |
| An effect system (ZIO, kyo, or Cats Effect): a fiber, a queue, and a fold | Typed effects and cancellation | The project evaluated kyo-compat, but it did not adopt it (`docs/refactor/composite-primitives.md`) |

`decide` maps directly to the command handler of `EventSourcedBehavior`, and `evolve` maps to its event handler.
So a Pekko interpreter of the decider alone is a small adapter. The log reads, the forks and the REPL replay do not
fit in the same way ([CODE_HARNESS_RUNTIME.md](CODE_HARNESS_RUNTIME.md), 5.5).

[CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md) analyzes which parts of Kyo can serve as this runtime, and it tests a small
session host on `kyo-actor`.

### 8.6 Distribution

A session is tied to one machine: its worktree is on one disk, and its REPL is in one JVM. When a Pekko cluster
changes, Cluster Sharding moves an entity to another node, and the scope cannot move with it. Distribution is
useful in two places:

- **Optimizer rollouts.** Each rollout has everything that it needs: a workspace fork, a REPL, and one task. So
  rollouts on many machines give more runs in parallel.
- **Remote devices.** uniichat runs tools on each computer that has its terminal open. A session can stay in one
  place while its tools run on a device.

### 8.7 Decisions and open questions

Decisions, 2026-10-21:

1. The first runtime uses direct style (8.5). A Pekko interpreter can come later.
2. Distribution is an advanced feature for a later version (8.6).

Decisions, 2026-11-01:

3. The session is a pure decider with laws (8.5).
4. `Entry` is the one event type of a session, and the cell events of 8.3 are in the first version. Only committed
   state is observable (4.2).
5. A cell changes state only if it completes. A typed `store` and `load` hold the state that must survive, and the
   REPL state is a cache (4.2, F1, F2).
6. The journal stores the typed value of each result and the rendered text (8.3).
7. The kernel has both stop rules: `submit(...)` and a reply with no cell (4.2).
8. An `Input` commits when it arrives, and the policy reads it at the next step. `Send` and `Abort` are separate
   commands (4.2).
9. Each entry has a source, a taint and a `required` mark, and each input has a request id (4.2).

Open questions:

1. Is REPL replay a first-version feature? Or is it a later feature, with the three preparations of 8.4 in the first
   version? The store of 4.2 makes a restart possible with no replay. Data from Phase 0 (section 11): replay worked
   with a small amount of code (one handler that records and one that replays). Two guards of the REPL in the same
   JVM start a new REPL, and replay keeps the definitions across that change. One guard restarts an unclean host.
   The other guard limits a REPL to about 300 cells.
2. Does the session host use `kyo-actor`, and does the journal use `kyo-sql-sqlite`
   ([CODE_HARNESS_KYO.md](CODE_HARNESS_KYO.md), section 7)? This needs JDK 25, and it changes decision 1 for the host.

## 9. Findings from deepseek-harness, Codex and tacit

This section lists what the three harnesses of 2.4 to 2.6 mean for the design. Section 8.7 lists the proposals that
are now decisions, and 9.4 lists the open questions.

### 9.1 What they confirm

- Code mode only is a real mode in Codex (`ToolMode::CodeModeOnly`) and in deepseek-harness (the PTC executor).
  tacit gives the first measured results for code mode in Scala (2.6).
- Codex and deepseek-harness show the tools as generated type declarations. deepseek-harness sorts them by
  name, so the prompt is the same byte for byte. F2 does the same with Scala signatures.
- Both send each call from code through the same guarded path as a direct call, tagged with its source. This is
  the handler design of F2.
- Codex threads are actors with one serial loop and a mailbox, and an idle child unloads (8.1).
- deepseek-harness projections are pure folds (7.2). Its surface is the same kind of view as the cut through the
  memory tree (section 6).
- deepseek-harness records the nested calls of a program under the checkpoint of the outer call. The cell events
  of 8.3 fill this gap.

### 9.2 What they challenge

**Persistent REPL state.** Codex removed its persistent REPL, and deepseek-harness makes it opt-in and never
durable. Both give explicit state instead. Codex commits the `store` writes of a cell only if the cell
completes. tacit applies the same rule to the REPL state: a cell that times out leaves the earlier state. The
proposal combines these ideas:

- Keep the persistent REPL.
- A cell changes the REPL state only if it completes.
- Add a typed `store(key, value)` and `load(key)` with `Schema` values, committed only if the cell completes.
  This store is the durable state of a session.
- Then REPL replay (8.4) is useful, but not necessary. After a restart, `load` gives the important state back at
  once.

Section 4.2 now has this design (decision 5 of 8.7).

**Cells that run longer than a step.** Codex returns from `exec` after a time budget, with a cell id and the
output so far, and the model calls `wait(cellId)` later. F1 now stops a cell at its timeout. The proposal adds
`CellOutcome.Running(cellId, partialOutput)` and a guest function `wait(cellId)`. Then a turn can have cells that
still run, and the decider of 8.5 must know about them.

**Typed values for replay.** deepseek-harness stores only the rendered text of a result, so its replay cannot
rebuild the values that code saw. For the replay of 8.4, the journal must store the typed value of each result,
with its `Schema`, and also the rendered text. Each effect gets a deterministic id `(cell, n)`, as in Codex, and
replay finds a recorded result by that id. Section 8.3 now has this design (decision 6 of 8.7).

### 9.3 Proposals by area

| Area | Proposal | Source | Changes |
|---|---|---|---|
| REPL | A fresh-cell mode next to the persistent REPL, for probes and `rlm(...)` children | tacit, Codex | F1, F5 |
| REPL | Options for each cell in a pragma on the first line, with unknown keys rejected | Codex | F1 |
| Guest API | `applyPatch(patch)` with the Codex patch format and its fuzzy match in four passes, next to `edit` | Codex | F2 |
| Guest API | Deferred tools that code finds by search, and the API reference on demand for a large tool set | Codex, tacit | F2 |
| Guest API | A capture-checked guest API. F2 now has it, with the views of atc in place of `caps.SharedCapability`. | tacit, atc | F2 (done) |
| Guest API | `secretHeaders` and dummy credentials, so that real secrets never enter the code or the journal | tacit, Codex | F2, 8.3 |
| Context | Typed world-state sections that render only their changes | Codex, deepseek-harness | 4.2 (projection) |
| Context | A fourth strategy: fresh windows, with tools that read the earlier windows | Codex | 7.2 |
| Context | Speculative compaction with a generation check, for the prefix-summary strategy | Codex | 7.2 |
| Context | Cheap steps first: cut tool outputs with no LM call, then summarize; reject a summary that does not shrink | deepseek-harness | 7.2 |
| Journal | An unknown required event stops a rebuild, and optional events carry a mark | deepseek-harness | 4.2, 8.2 (done) |
| Journal | Crash repair by append. An effect with no end has an unknown outcome and never retries automatically. | deepseek-harness | 4.2, 8.3 (done) |
| Journal | A fork points into the immutable log of its parent | Codex | 4.2, 8.2 |
| Journal | Raw events first, a deterministic reducer later; edges between sessions carry item ids | Codex | 7.4 |
| Journal | Metadata on each item that the model never sees: source and taint | Codex | 4.2 (done), 7.5 |
| Controller | Typed `tell` modes with an expected turn id, and typed rejection reasons | Codex | 7.3 |
| Controller | The inbox algebra (target and wake), and the message kind and form | deepseek-harness | 7.3 |
| Controller | `observe` as one answer on a fork with no tools, next to the RLM probe | Codex | 7.4 |
| Controller | Advisory write scopes when two sessions work in one repository | deepseek-harness | 7.3 |
| Controller | A human arms automatic work again after a restart. A goal is "blocked" only after three turns with the same blocker. | deepseek-harness, Codex | 4.4, 7.3 |
| Graph | Extraction for each session, then a consolidation driven by a diff for the merge across sessions | Codex | 7.5 |
| Graph | Citation counts as a use signal for the rank and the decay of facts, and as a reward for the optimizers | Codex | 7.5, 7.8 |
| Graph | Taint: facts from external content carry a mark, and the controller does not trust them | Codex | 7.5 |
| Graph | The derivations (the compactor and the extractor) see only masked `Classified` values. A trusted LM can process the classified parts. | tacit | 7.4, 7.5, 8.3 |
| Safety | A reviewer `Predict` leaf in the `guarded` handler, with a fixed output, a circuit breaker, and a cheap classifier first | Codex, deepseek-harness | F2 |
| Safety | Guard policies as data, with examples that the loader checks | Codex | F2 |
| Safety | A validator, if there is one, gets an evasion test suite. The compiler and the class loader are the real check. | tacit | F1 |
| Evaluation | Recorded sessions as model streams, for tests with no API key | deepseek-harness | 8.4 |
| Evaluation | Critical-path time (inference, review, tools, user wait) as objective scores for GEPA | Codex | 10 |
| Evaluation | Best-of-N attempts of one task, each with its own diff | Codex | 10 |
| Phases | An MCP server for the REPL and the guest API, so that another agent can test them early | tacit | 11 |

### 9.4 Decisions and open questions

Questions 1 and 3 have a decision (8.7). The other questions are open:

1. Does the harness add a typed `store`/`load` with commit-on-success, next to the persistent REPL (9.2)? Yes (4.2).
2. Does the first version have the yield protocol, with `Running` and `wait` (9.2)?
3. Does the journal store the typed value of each result, which replay needs, or only the rendered text (9.2)? It
   stores both (8.3).
4. Does the harness expose the REPL and the guest API as an MCP server early, before its own loop exists (9.3)?
5. Do the derivations see only masked `Classified` values, with an optional trusted LM for the classified parts
   (9.3)?

## 10. Optimization

What the optimizers tune:

- **Policy instructions.** These are the work rules, for example "read a file before you edit it" and "run the
  tests before you submit". Keep the API reference and the REPL rules out of these instructions. Then GEPA
  cannot change the API description. This needs a fixed prompt part that is not in `instructions`. One option is
  an input field that the projection fills. For the prompt cache, a fixed preamble in the system message is
  better. The adapters do not have this feature now.
- **Demos.** Full trajectories are too long for demos. Use GEPA (instructions only) for the policy.
  BootstrapFewShot can collect demos for the short leaves (`subQuery`, `compactor`, `compress`, `merge`).
- **Compaction leaves (section 6).** The `compress` and `merge` instructions decide what the memory keeps. A
  memory metric tests them with no workspace. Take a long log, and make questions whose answers are in old
  messages. The policy answers with the view and a zoom budget. The score is the share of correct answers. The
  feedback names the line that dropped the needed item. GEPA then tunes the two instructions.
- **Model for each leaf.** `Predict` has its own `lm` field. The compaction leaves can use a cheaper model, as
  in uniichat.
- **Metric.** A `FeedbackMetric` runs the tests in the fork of the rollout. The feedback text has the names of
  the failed tests, the last lines of the test output, and the size of the diff.
- **Reflection data.** In a run that passed, GEPA picks one call of each leaf at random. The `history` input of
  a policy call holds the run up to that step, and the metric gets the full trace. So the feedback can describe
  the full run.
- **Rollouts.** Each rollout needs its own workspace fork (F6) and its own REPL (F1). The REPL has no global
  lock, so rollouts can run in parallel on `ParallelExecutor`.
- **Cost.** Each rollout is a full agent run. Use a small task set and the metric-call budget of GEPA.
- **Decision types (D6).** The goal check (4.4) is a `Noul` leaf. ReAnchor can calibrate its threshold against the
  metric.
- **Controller leaves (section 7).** The extraction leaves and the controller policy have their own metric
  (7.7): the answers from the graph must agree with probes on the full log.

A source of tasks is the history of this repository. A commit that makes a test pass gives a task (the parent
commit and the test) and a metric (the test passes).

## 11. Phases and open questions

The phases build the kernel first, with the window strategy, so a harness for one task works before the memory
tree exists. Design A (5.1) stays available as an early check of the foundation.

| Phase | Work | Result |
|---|---|---|
| 0. Experiment | Embed the `ReplDriver` of the build (the 3.10 nightly) in a test. Check the persistent state, the interrupt at a timeout, a separate output for each REPL, and two REPLs on parallel threads. Measure the start time and the memory of one REPL. | Done on 2026-10-21: a REPL in the same JVM, with guards (see the Phase 0 results). |
| 1. Foundation | F1, F2 (`read`, `write`, `edit`, `exec`), F3, F6. F7 and F8: `exec` runs only in a sandbox. The guards of the Phase 0 results. Capture checking and safe mode for guest code, the modes of F2, the notation header, and the error hints. Phase 0 did not test capture checking in guest code, so this phase tests it first. | A REPL that runs model code against the guest API, in each mode. |
| 2. Kernel | The journal with the commit rule, `Entry` and `Meta`, `store` and `load`, the policy leaf, `Harness` on `drive`, both stop rules, the window strategy (the full log as `History`), the laws, `OptimizableStructure`, `ProgramRunner`. The session decider with `Send` and `Abort`, and the cell events (4.2, 8.3, 8.5). `Clock` and `Random` handlers. | A harness for one task that the optimizers accept. |
| 3. RLM | F4, F5: variables, `llmQuery` through `subQuery`, `rlm(...)` children, the shared budget, the depth limit. | RLM in the harness. |
| 4. Memory tree | `Memory`, `View`, the memory laws, the `compress` and `merge` leaves, the builder, `zoom` and `date` as guest functions. The memory-tree strategy (section 6). | A memory that loses no detail. |
| 5. Optimization | A `FeedbackMetric` from tests, the memory metric, a small task set, one GEPA run. | A first measured improvement, or a reason for its absence. |
| 6. Work sessions | `Derivation`, `ContextStrategy` (window, prefix summary, memory tree), scopes (directory, worktree, scratch), a CLI that opens one session, steering, the goal check, `ScopeSnapshot`, the end of a session and its release plans (7.9). The direct-style runtime: one virtual thread and one queue for each session (8.5). | A coding agent for daily use, one session at a time. |
| 7. Controller | The controller API (7.3), the ledger events, pluggable ontologies, the extraction at the end of each turn, the time queries (7.6), the `Graph` guest API in sessions (7.7), probes, `SessionCard`, the graph metric. | One controller above all sessions. |
| Later | A forked JVM for isolation. MCP tools as guest functions (`McpTools`). A policy variant with `run_scala` as a native tool through the adapter. Prompt-cache marks in `LmRequest`. RLM on the kernel. REPL replay (8.4), if it is not in the first version. A Pekko interpreter of the decider, and distribution (8.6). | |

### Phase 0 results

The experiment ran on 2026-10-21, with the 3.10 nightly and JDK 21 on an Apple M4 Pro. The full report is
`docs/code-harness/CODE_HARNESS_PHASE0.md`. The code of the experiment is in `experiments/repl-phase0/`.

| Check | Result |
|---|---|
| Persistent state | Pass. The definitions stay after a compile error and after a failure at run time. |
| Timeout | Pass for loops in guest code. A loop stops 0 to 3 ms after the stop flag, the earlier output comes back, and later cells run. |
| Output for each REPL | Pass, with no lock. Two REPLs printed 2,000 lines each at the same time, each into its own buffer. |
| Parallel REPLs | Pass. A timeout in one REPL does not stop another REPL. 8 REPLs compile about 5.8 times as many cells per second as one REPL. |
| Host API | Pass. The preamble takes its handlers from a registry by a key for that REPL. A guest call is a direct method call (115 ns). |
| Replay (8.4) | Pass. Three cells replayed with 0 real effects, and the next cell worked. The replay took 73 ms, and the live run took 187 ms. |
| Cost | The first REPL in a JVM: 787 ms. Each more REPL: 85 ms and about 6 MiB of heap. A forked JVM: about 1 s and about 200 MiB of resident memory for each REPL. |

Problems:

1. A loop inside library code that calls no guest code cannot be stopped. Its thread uses one CPU core until
   the JVM ends, because JDK 21 cannot stop a thread from outside.
2. A guest that fills the heap makes the other REPLs fail. With `-Xmx4g`, it also stopped the host JVM in 4 of 4
   runs.
3. The memory of a REPL grows with the square of the number of cells: 348 MiB after 600 cells. Each new cell
   keeps one import context for each earlier cell. A forked JVM has the same problem.
4. `ExecutionContext.global` sends output to the wrong REPL, because a thread of the global pool keeps the
   `Console.out` of the thread that made it.
5. A guest `System.out` goes to the output of the host, unless the host forwards it.
6. The timeout includes the compile time of the cell.
7. The setup uses internal names of the REPL (`private[repl]`), which a new nightly can change.
8. A guest `System.exit` stops the host. The experiment did not test this.

The recommendation is a REPL in the same JVM, behind the `ScalaRepl` contract (F1), with five guards:

1. Set `redirectOutput` to `false`. Run each cell on its own thread in `Console.withOut` and `Console.withErr`.
   Forward `System.out` and `System.err` to them one time, at the start of the host.
2. Bind an `ExecutionContext` for each REPL in the preamble. Reject `ExecutionContext.global`, `new Thread`,
   `System.exit` and `Runtime` in guest code, with safe mode or a validator, as atc does.
3. When a worker thread does not end after a stop, mark the host process as unclean. Restart the host at the
   next safe point, for example after a batch of rollouts, and rebuild each REPL with replay.
4. Monitor the heap. Above a limit, close the REPL whose cell thread allocated the most memory. The experiment
   did not test this guard.
5. Limit a REPL to about 300 cells. Then start a new REPL and replay the cells into it.

Guards 3 and 5 use replay (8.4) to rebuild the definitions. With no replay, the new REPL has only the store of 4.2,
and the model gets a note. A forked JVM stays the fallback: use it if unclean restarts occur often, or if the harness
must run code from a source that it does not trust.

The experiment did not test a journal on disk, the order of `parallel` results in a replay, capture checking in
guest code, a forked JVM with a protocol for the guest calls, guard 4, Linux, or Windows.

### Open questions

1. **REPL host.** Phase 0 recommends a REPL in the same JVM, with the guards of the Phase 0 results. A forked
   JVM is the fallback.
2. **End signal.** Decided: both `submit(...)` and a reply with no cell (4.2, decision 7 of 8.7).
3. **RLM.** Does the current `RLM` move onto the kernel later, so that dspy4s has one code program?
4. **Log shape.** Section 7 answers this question with both shapes: a session for each task, and one controller
   log that never ends. For the optimizer, an episode is a fork and one task.
5. **Subagents.** `rlm(...)` stays. With the sessions of section 7, is `spawn(...)` still necessary, or does the
   controller `start` replace it?
6. **Memory access.** Is `zoom` the only way to read the memory, as in uniichat? Or can code also search the
   memory, for example with `llmQuery` over a subtree?
7. **Permissions.** F7 and F8 answer the main part: a `guarded` handler with a policy (atc), and an OS sandbox
   for each command. Which sandbox backends come first? Does the full harness process also run in a sandbox?
8. **Tasks.** Where do the evaluation tasks come from: the history of this repository, a public benchmark, or
   both?
9. **Controller.** Section 7.10 lists the decisions and the open questions about the sessions and the controller.
10. **Runtime.** Section 8.7 lists the decisions and the open questions about the runtime.
11. **Findings.** Section 9.4 lists the open questions from deepseek-harness, Codex and tacit.

## 12. Alternatives considered

- **A free monad that the model writes.** The model writes `for` comprehensions over an operation type, and the
  host interprets the program value. This permits an inspection before the run. But models write direct-style
  code with fewer errors, and the handlers of F2 already give record, replay and guard. So the design keeps the
  direct style.
- **The pi tools as JSON tools.** This is not code mode. It adds a second action language next to the REPL.
- **The uniichat tools as JSON tools.** `zoom`, `spawn` and `tell` work well as tools. But in code mode they
  are guest functions, so one cell can do many zooms. A second action language is not necessary.
- **Compaction that replaces a prefix (atc, pi).** It is simpler than the memory tree. But the agent cannot get
  the lost detail back, and each compaction changes the prompt prefix.
- **A Python REPL with the current interpreters.** This needs no new interpreter. But the guest API is then
  untyped, and it has no capture checking. A mode is then only a run-time check, and the compile-time checks of
  F2 and F3 do not exist.
