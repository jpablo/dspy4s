# Code harness: analysis and design proposals

Status: proposal, 2026-10-19, with sections 7 and 8 added on 2026-10-21. Nothing in this document exists in
the code yet.

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
- Design A makes the harness an RLM configuration. Design B makes a session algebra in which RLM is the
  meaning of a code step. Design C keeps the kernel of design B and replaces its compaction with the
  uniichat memory tree.
- Section 7 extends design C. Work sessions have a pluggable context strategy, and the memory tree is one of
  the strategies. One controller conversation that never ends uses the memory tree, starts the sessions, and
  sees each session through a ledger, a knowledge graph with time and a pluggable ontology, and probes.
- Section 8 describes the runtime. A session is an actor, its log is an event journal, and each guest API call
  in a cell is an event. A replay of the journal can rebuild the REPL state. The first runtime uses direct
  style, and distribution comes later.
- The recommendation is design C with the sessions and the controller of section 7, on the runtime of section
  8. It is built in phases on the kernel of design B, after a short experiment with the embedded REPL.

## 1. Goal

The `harness` module gives a coding agent with these properties:

- **Code mode only.** The model acts only through Scala code that it writes. A Scala REPL runs the code.
- **Reuse.** The harness uses the current modules: signatures, `Predict`, `AgentLoop`, the interpreter contract,
  `History`, and the optimizers.
- **Optimizable.** BootstrapFewShot, COPRO, MIPROv2 and GEPA can tune the harness as they tune any other program.
- **RLM in the first version.** Large inputs stay in the REPL. Code can call the LM, and code can start a child
  agent.
- **Functional design.** The state is a value. The effects go through handler values. The important parts have
  laws.

Section 2 analyzes the three reference harnesses and the dspy4s components. Section 3 gives the foundation
that all designs share. Sections 4, 5 and 6 give the three designs. Section 7 extends design C with work
sessions and a controller, and section 8 describes the runtime of a session. Section 9 compares the designs, and
section 10 describes the optimization. Section 11 gives a recommendation, the phases, and the open questions.
Section 12 lists the alternatives.

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
| LM from code | `chat(message)`: one completion, with no tools and no history. atc has no sub-agents and no recursion. |
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
- Capture checking needs the nightly compiler. dspy4s builds on 3.8.4.

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
| Code mode | The `codemode` package runs model JavaScript in QuickJS. The only capability is a call to an injected tool. The nested tool calls do not go into the LM context. |
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
| Acts | The log records each act, for example a tool job. After a crash, the server reads the log and does not do a recorded act again. |
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

### 2.4 The three harnesses side by side

| Topic | atc | pi | uniichat |
|---|---|---|---|
| Action language | Scala code (one tool) | JSON tools (four) | JSON tools (MCP) |
| State | A message list and a live REPL | A tree of session entries | One log that never ends |
| LM context | Old exchanges dropped, or a summary of the prefix | A projection; a compaction replaces a prefix | A cut through a summary tree |
| Detail after compaction | Lost | Lost in the prompt, kept in the file | Kept: `zoom` gives it back |
| Sub-agents | None | None (an example extension) | Background, one level, report as a message |
| LM from code | `chat()`, one completion | None | None |
| Prompt cache | A stable system prompt | System messages in the middle of the conversation | The view changes only at its end; block marks |

### 2.5 dspy4s components

| Component | Where | Use in the harness |
|---|---|---|
| `Module[I, O]`, `ProgramCall`, `Prediction` | `programs/contracts` | The harness is a `Module[I, O]`. |
| `Predict[I, O]`, `PredictorKey` (D2) | `programs/strategies/Predict.scala` | Each LM step of the harness is a `Predict` leaf. The optimizers find its trace entries by its key. |
| `OptimizableStructure`, `ProgramRunner` | `programs/optimization`, `programs/ProgramRunner.scala` | All optimizers need these two instances. |
| `AgentLoop.run` | `programs/runtime/AgentLoop.scala` | The bounded loop: `Continue`, `Done`, or the `onExhausted` result. |
| `ActionInterpreter`, `ActionOutcome` | `programs/contracts/ActionInterpreter.scala` | A recoverable failure is an observation. A fatal failure is a `Left`. |
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

All three designs need the six parts in this section.

### F1. An embedded Scala REPL

```scala
trait ScalaRepl extends AutoCloseable:
  def run(cell: Cell)(using RuntimeContext): Either[DspyError, CellResult]

final case class Cell(code: String)
final case class CellResult(output: String, outcome: CellOutcome)

enum CellOutcome:
  case Ok
  case CompileError(diagnostics: Vector[String])
  case Failed(exception: String)
  case TimedOut(after: FiniteDuration)
  case Submitted(value: DynamicValue.Record)
```

- Use `dotty.tools.repl.ReplDriver` from `scala3-repl_3:3.8.4`. Scala 3.8.0 added
  `-Xrepl-interrupt-instrumentation`, so a cell timeout can stop a loop.
- In the `true` mode of that flag, each REPL class loader has its own copy of the stop flag (`StopRepl`). So
  a timeout in one REPL does not stop another REPL.
- Give each REPL its own output stream. Send the guest `println` through `Console.withOut`, which has a value
  for each thread. Then parallel sessions do not share a lock.
- Bind the handlers (F2) in the preamble of each REPL through a registry key for that session. Do not use one
  global slot.
- Share only `scala.*` and the guest API package with the REPL class loader, as atc does. Hide the class
  resources of the shared classes, so that the instrumentation does not define a second copy of them.
- Keep the current error split: a failure of the REPL is a `Left`, and a failure of the user code is a `Right`
  with an outcome.
- A later variant runs the REPL in a forked JVM. That variant isolates memory and crashes, and it can use the
  nightly compiler for capture checking.

### F2. The guest API as effect signatures with handlers

The guest API is a small set of traits. Each trait is an effect signature. The model calls direct-style
functions, and a `using` parameter selects the handler. This gives the algebraic structure (operations and
interpreters) without monadic syntax in the model code.

Start with the four operations of pi, as typed Scala functions:

```scala
// The guest side. This source text, with its doc comments, is the API reference in the prompt.
trait Files:
  /** The lines `from` to `to` of `path`, with line numbers. */
  def read(path: String, from: Int, to: Int): String
  /** Writes `content` to `path`. Makes the parent directories. */
  def write(path: String, content: String): Unit
  /** Replaces the one occurrence of `oldText`. Fails when `oldText` occurs zero times or more than one time. */
  def edit(path: String, oldText: String, newText: String): Unit

trait Shell:
  /** Runs `command` in the workspace. Stops it after `timeoutSeconds`. */
  def exec(command: String, timeoutSeconds: Int): ExecResult
```

The host gives a handler value for each trait. Handler transformers compose:

| Handler | Purpose |
|---|---|
| `Files.local(root)` | The real files under `root`. |
| `Files.overlay(base)` | Copy on write over `base`, for one rollout. |
| `Files.memory(snapshot)` | Files in memory, for tests. |
| `Shell.local(cwd)`, `Shell.denied` | Real commands, or no commands. |
| `recorded(h)` | Writes each call and its result into the session log. |
| `replayed(log)` | Answers each call from a log. |
| `guarded(h, policy)` | Checks a policy before each call. A deny rule wins, as in atc. |

Laws:

- `guarded(allowAll)(h)` behaves as `h`.
- `guarded(p)(guarded(q)(h))` behaves as `guarded(p and q)(h)`.
- For the calls in `log`, `replayed(log)` gives the same results as the handler from which `recorded` wrote `log`.

The read-only mode is a choice of handlers. On 3.8.4, the handler checks the mode at run time. On the nightly
compiler, capture checking can check the mode at compile time, as in atc.

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

### F5. RLM semantics

- The inputs of the base signature enter the REPL as typed values. Strings, numbers, booleans and collections
  become Scala values. The prompt shows only a summary of each value: the name, the type, the size, and a
  preview (the RLM `variables_info`).
- `llmQuery` and `llmQueryBatched` (F4).
- `rlm(task, inputs)` is a recursive call of the same program. The child gets a new REPL, a depth limit that is
  one less than the limit of the parent, and the shared LM call budget. The child gives its submitted value to
  the parent code.
- The budgets are Iron types: the current `IterationLimit`, `LlmCallLimit` and `OutputCharLimit`, and two new
  types, `DepthLimit` and `CellTimeout`.

### F6. Workspace isolation

A `Workspace` value has `fork(): Either[DspyError, Workspace]`. A fork is a git worktree, a temporary copy, or
an overlay (F2). Each rollout of an optimizer runs in its own fork. The metric reads that fork, for example to
run the tests. Without forks, parallel rollouts write the same files.

## 4. Design A: the harness is an RLM configuration

### Idea

Make the RLM interpreter contract independent of the language, and add a Scala implementation. The harness is then
an `RLM[I, O]` value with the Scala REPL and the workspace API.

### Changes

| Module | Change |
|---|---|
| programs | Add `ReplLanguage`: the prompt text, the fence tags, the identifier rule, and the type names. Move the Python text from `RLMReplProtocol` into `ReplLanguage.python`. Python stays the default. |
| programs | Send `llm_query` through a `subQuery` leaf (F4). The arity of the RLM `OptimizableStructure` changes from 2 to 3. |
| harness (new) | `ScalaReplInterpreter` (an implementation of `ReplCodeInterpreter`), its factory, the guest API (F2), the typed submit (F3), `Workspace` (F6), and the constructor `CodeHarness.apply`. |

### Sketch

```scala
final case class Task(request: String) derives Schema
final case class Report(summary: String, changedFiles: List[String]) derives Schema

val harness: RLM[Task, Report] =
  CodeHarness(
    signature = Signature.derived[Task, Report]("code_task", "Do the task in the workspace."),
    workspace = Workspace.local(Path.of(".")),
    limits    = HarnessLimits(iterations = 30, llmCalls = 50)
  )

val report = harness(Task("Fix the failed test in modules/lm"))

// The current optimizers accept the harness with no change.
val result = Gepa[RLM[Task, Report]](metric = TestsPass(), reflectionLm = lm, config = GepaConfig())
  .compile(harness, trainset)
```

### What the model sees

One `Predict` call for each step. The input has the instructions, `variables_info`, `repl_history` as one text
field, and the step number. The output has `reasoning` and `code`.

### Good points

- Little new code. The loop, the budgets, the extract fallback and the optimizer instances exist now.
- RLM from the start, because the harness is RLM.
- The change to `llm_query` (F4) also helps the current Python RLM.

### Weak points

- One call is one task. There is no conversation, no steering, and no compaction. The history grows until the
  output limit cuts it.
- The REPL state ends with the call.
- `SandboxTool` passes `DynamicValue` records. The typed API (F2) must go around this contract, so the harness uses
  only part of it.
- The trajectory is one text field. The model does not see its steps as chat turns.

## 5. Design B: a session algebra

### Idea

A small kernel of four values and one fold, as in pi. The log is the state. A pure projection makes the policy
input. Each LM step is a `Predict` leaf. The REPL is a handler. RLM is the meaning of a code step, not a
separate program.

### Kernel

```scala
// 1. The log: append-only, a tree by parent id. Plain data, kept in a JSONL file through its Schema.
enum Entry derives Schema:
  case Input(text: String)                             // a task or a user message
  case Acted(reasoning: String, code: String)          // a policy output
  case Observed(output: String, outcome: CellOutcome)  // a REPL result
  case Compacted(summary: String, firstKept: EntryId)  // replaces a prefix in the projection
  case Child(task: String, session: SessionId, result: Either[String, DynamicValue.Record])
  case Submitted(value: DynamicValue.Record)

final case class Node(id: EntryId, parent: Option[EntryId], entry: Entry) derives Schema
final case class Session(nodes: Vector[Node], leaf: Option[EntryId]) derives Schema

// 2. The projection: a pure function from the log to the policy input.
final case class PolicyInput(task: String, variables: Vector[String], observation: String, history: History)
type Projection = Session => PolicyInput

// 3. The policy: a Predict leaf. The optimizers find it by its key.
//    task, variables, observation, history -> reasoning, code
val policy: Predict[PolicyInput, PolicyStep]

// 4. The interpreter: the REPL behind the current ActionInterpreter contract.
val interpreter: ActionInterpreter[Cell, CellResult]
```

History message `k` holds the observation before step `k` as its input, and step `k` as its output. The adapter
replays these messages as chat turns. Each step adds turns at the end, so the prompt prefix stays the same, and
the provider prompt cache can hold it.

### The loop

```scala
def step(session: Session, i: Int)(using RuntimeContext)
    : Either[DspyError, AgentLoop.Step[Session, Outcome]] =
  for
    prepared <- middleware(session)                   // compaction, output limits
    action   <- policy(projection(prepared))
    result   <- interpreter.execute(Cell(action.output.code))
  yield
    val next = prepared
      .append(Entry.Acted(action.output.reasoning, action.output.code))
      .append(Entry.Observed(result.observation.output, result.observation.outcome))
    result.observation.outcome match
      case CellOutcome.Submitted(value) => AgentLoop.Step.Done(Outcome.Submitted(next, value))
      case _                            => AgentLoop.Step.Continue(next)

AgentLoop.run(start, 0, limits.iterations)(onExhausted = finish)(step)
```

### Hooks as values

A middleware is a `Session => Either[DspyError, Session]`. The middlewares make a monoid: the identity, and
composition from left to right. The pi hooks become values of this type:

- `compactWhen(threshold)(compactor)`: when the projection is too large, it adds a `Compacted` entry. The
  `compactor` is a `Predict` leaf.
- `limitOutput(chars)`: it cuts long observations, and it adds a note that tells the model how to read the
  remainder.

The laws: the monoid laws. A compaction does nothing below the threshold. After a compaction, the projection
keeps every entry from `firstKept` without a change.

### The module

```scala
final case class Harness[I, O](
    signature: Signature[I, O],
    policy   : Predict[PolicyInput, PolicyStep],  // the step
    finisher : Predict[FinishInput, O],           // the RLM extract fallback when the budget ends
    compactor: Predict[CompactInput, Summary],
    subQuery : Predict[Query, Answer],
    env      : HarnessEnv,                         // handlers, workspace, REPL factory; not optimizable
    limits   : HarnessLimits                       // not optimizable
) extends Module[I, O]
```

- The `OptimizableStructure` instance has arity 4. It is derived, or hand-written as for RLM. The fields
  without leaves need `OptimizableStructure.empty` instances.
- The `ProgramRunner` instance comes from the input shape of `signature`, as for RLM.
- `forward` makes a REPL, runs the loop, and closes the REPL in a `finally`, as RLM does.

Two entry points use the same kernel:

- `harness(input)`: one task in one session. The optimizers use this entry point.
- `Harness.turn(session, repl, message)`: one interactive turn. A CLI keeps the `Session` value and the live
  REPL. The CLI is a small program outside the module.

### RLM in design B

- The inputs of `I` enter the REPL as values (F5).
- `llmQuery` uses the `subQuery` leaf (F4).
- `rlm(task, inputs)` starts a child session with the same `Harness` value. The child uses the same leaves, so
  its calls have the same keys. The parent log gets one `Child` entry with the result. The child log is a
  separate session, so the parent context stays small.
- Later, the current `RLM` program can become a `Harness` with the Python REPL handler and the RLM prompt. Then
  dspy4s has one code program, as decision D1 intends.

### Session files

The log is plain data with a `Schema`, so a JSONL file can hold it. The REPL state is not in the log. On a
resume, the harness adds a note: the REPL restarted, and the earlier definitions are gone. atc does the same.
Section 8.4 describes replay, which can rebuild the REPL state from the journal.

### Laws

| Law | Statement |
|---|---|
| Projection | `projection(s)` depends only on the entries on the path from the root to the leaf. |
| Fork | After `fork(s, id)`, the path is the prefix of the old path up to `id`. |
| Compaction | After a compaction, the projection keeps every entry from `firstKept` without a change. |
| Middleware | Identity and associativity. |
| Loop | At most `limits.iterations` policy calls (the current `AgentLoop` laws). |
| Handlers | For the calls in `log`, `replayed(log)` gives the same results as the handler from which `recorded` wrote `log`. |
| Budget | The `subQuery` calls of one call tree, with the children, are at most `LlmCallLimit`. |

### Good points

- The design is functional. The session is a value, the projection is pure, and the effects go through handlers.
- Conversation, fork, resume and compaction come from the log.
- The policy sees its trajectory as chat turns through `History`, and the prompt cache holds the prefix.
- Four leaves are open to the optimizers, and all LM calls from code are in the trace.
- Each part has a law, so the tests can use the style of the current law suites.

### Weak points

- More new code than design A.
- Two code programs (RLM and `Harness`) exist until RLM moves onto the kernel.
- The `History` projection of a long trajectory needs care with the context size, so compaction is necessary
  early.
- A compaction removes detail from the prompt. The agent cannot get it back, because the projection has no
  zoom.

## 6. Design C: one log with a memory tree

### Idea

Keep the kernel of design B: the policy leaf, the REPL handler, the loop, the middleware and the handlers.
Replace the session tree and its compaction with the uniichat memory: one append-only log, a binary tree of
summaries over the log, and a view that is a cut through the tree. The compactor becomes two `Predict` leaves.
`zoom` becomes a guest function.

### Memory algebra

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

### Laws

| Law | Statement |
|---|---|
| Coverage | The spans of a view are disjoint and in order, and together they cover `[0, n)`. |
| Zoom | For a built node `p`, a view that shows the two halves of `p` merges back to the view that shows `p`. |
| Prefix | Between two `fit` batches, the later view is the earlier view with lines added at its end. The prompt cache depends on this law. |
| Immutability | `build` never changes a built node. A fork at message `n` shares every node whose span ends before `n`. |
| Order | A node builds only after its two halves. |
| Size | After `fit`, the size of the view is at most `high`, when the parents that it needs are built. |
| Roots | `roots(n)` has one node for each set bit of `n`, and their spans cover `[0, n)` in order. |

### Turn and projection

The projection of design B becomes three parts:

1. The view up to the first message of the turn. This part is the stable prefix.
2. The messages of the turn in full.
3. The steps of the turn as `History` turns.

The cells and the outputs of a turn go into the log as messages, so later turns see them as summary lines. A
turn waits until the nodes that its view needs are built (uniichat `settle`).

### Code mode and RLM

- `zoom(ref)` and `date(id)` are guest functions. Code can walk the tree in a loop. A JSON tool needs one
  model step for each zoom.
- The full log is an RLM input: a REPL value with a summary in the prompt, and the view is that summary. The
  RLM rule "large inputs stay in the REPL" and the uniichat rule "the view is the memory" are the same rule.
- Code can call `llmQuery` on the lines of a subtree, for example to find a past decision. uniichat forbids a
  search of the memory. Section 11 lists this as an open question.

### Two kinds of subagents

| Function | Semantics | Use |
|---|---|---|
| `rlm(task, inputs): A` | Synchronous. The child runs inside the cell and gives a typed value. It has a depth limit and the shared budget. | A part of a computation (RLM). |
| `spawn(task): AgentRef` | Asynchronous. The child runs in the background with the view of the parent and its own log. Its report comes back as a `work` message, which starts a turn. `tell(ref, message)` sends it a message. | Long work in the interactive shell. |

For the optimizer entry point (one task), the harness waits for all spawned agents before it ends, or it
turns `spawn` off. Then each rollout is closed.

### Goal check

A goal check is a `Predict` leaf with a `Noul` output (decision D6): "is the goal met now?". On `false`, the
harness adds a "keep working" message and runs one more turn. ReAnchor can calibrate its threshold later.

### Good points

- The memory loses no detail. A compaction adds nodes and deletes nothing.
- A law keeps the prompt prefix stable, so the prompt cache is reliable.
- Two compaction leaves and a goal leaf are open to the optimizers, in addition to the leaves of design B.
- The memory is a persistent value, so a fork for a rollout costs almost nothing.
- One log fits the interactive shell. For the optimizer, an episode is a fork of the memory and one task.

### Weak points

- The most new code of the three designs: the tree, the view, the builder, and the leaves.
- The background builder adds concurrency. A turn must wait for the nodes that it needs.
- Each message costs one compaction call on the cheaper model.
- `LmRequest` has no prompt-cache marks (gap 6). An Anthropic provider needs them.

## 7. Sessions and the controller

This section extends design C. It adds work sessions with a pluggable context strategy, and one controller
conversation above them.

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
| Memory tree (uniichat, design C) | The summary nodes | A cut through the tree, and `zoom` in the guest API |

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

The controller gets its own set of handlers (F2). It can manage work, but it cannot do the work:

```scala
enum Scope:
  case Directory(path: Path)
  case Worktree(repo: Path, branch: String, base: String)
  case Scratch(name: String)

trait Sessions:
  def start(goal: String, scope: Scope, strategy: StrategyName): SessionRef
  def list(): Vector[SessionCard]
  def tell(session: SessionRef, message: String): Unit
  def observe(session: SessionRef, question: String): String  // a probe (7.4)
  def zoom(session: SessionRef, ref: String): String
```

The controller can also get a read-only `Files`, so that it can answer questions about a project. It gets no
`write`, `edit` or `exec`. Code that calls them does not compile, because the controller API does not have
them. The controller instructions tell the model to start a session for such work.

The other option is a nudge: a classifier finds "real work" in the controller and tells the user to start a
session. The classifier can be wrong in both directions, and it needs its own tests. A restriction is a fact of
the type system, and a test can check it.

The controller has two kinds of access to a session, with different effects:

| Function | Effect on the session |
|---|---|
| `observe(session, question)` | None. It is a probe on the session log. |
| `tell(session, message)` | The message goes into the session log. The session agent reads it and can act on it. |

Rules for `tell`:

- The message has its own kind, `controller`, so the user and the agent know who sent it.
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
| Artifact | `docs/CODE_HARNESS.md`, a commit (from the ledger) |
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
- The session agent can propose an end. The goal check (the `Noul` leaf of design C) gives a good signal. The
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
| `Entry` (`Input`, `Acted`, `Observed`, `Ended`, `Reopened`, `ScopeSnapshot`) | Events |
| Derivations: the memory tree, the graph, `SessionCard` | Projections (read models) |
| Reopen a session | Recover the state by replay |

There is one difference from standard event sourcing. An LM makes the memory tree and the graph. To derive them
again costs money, and the result is not the same. So the harness stores the derived data too, in a journal of
its own. uniichat also keeps its messages (`main/`) and its summary nodes (`tree/`) in separate files.

### 8.3 Events inside a cell

An `Acted(code)` entry holds the full cell, and one cell can do many actions. But each action of a cell goes
through a handler (F2). So the `recorded` handler writes one event for each guest API call, at the time of the
call:

```scala
enum CellEvent:
  case CellStarted(code: String)
  case EffectStarted(id: EffectId, op: String, args: DynamicValue.Record)
  case EffectDone(id: EffectId, result: EffectResult)
  case CellEnded(output: String, outcome: CellOutcome)
```

- The unit of an event is a guest API call, not a line of code.
- The pure computation between two calls needs no event. The code and the recorded results give the same output
  again.
- This is the model of durable execution, as in Temporal: the cell is the workflow function, and each guest API
  call is an activity.

A write needs one more rule. `EffectStarted` goes into the journal before the effect, and `EffectDone` goes in
after it. After a crash, a start with no done marks an effect whose result is unknown. The harness then asks the
user, or it uses an idempotent operation. uniichat has the same rule for its acts.

### 8.4 REPL replay

**The problem.** The REPL state is the set of definitions that the earlier cells made (`val`, `def`, `class`,
`import`), and the objects that those definitions hold in memory. A restart removes all of it: a crash, a
session that reopens the next day, or a session that moves to another machine. The agent uses these definitions
in later cells, so its next cell fails with an error such as `Not found: counts`. atc adds the note "the REPL
restarted", and the model must do the work again. With a memory tree, the code of an old cell is a one-line
summary, so the model cannot see the exact code that it must run again.

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

- Storage: the journal must hold each result that the code saw. Small results (`write`, `llmQuery`) cost little.
  Large `read` and `exec` results cost the most. A content store can keep each content one time only. Decision
  10 of section 7 records no content for writes; replay needs the content of reads.
- Time: each cell compiles again, from 0.1 to 1 second for each cell. A long session takes some minutes.

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
2. Write the cell events of 8.3, with the metadata of each effect.
3. Record the compiler version and the guest API version in the journal.

The later change is then to store the results, and to add the replay of cells.

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

The decider owns the state machine of the session (idle, in a turn, ended, reopened) and the release plan of
7.9. The long turn (the LM calls and the REPL) runs in a worker outside the decider. The worker sends its events
back as messages.

| Runtime | What it gives | Cost |
|---|---|---|
| Direct style: one virtual thread and one queue for each session (or Ox actors) | It agrees with the current dspy4s style: `Either`, direct calls, `RuntimeContext` | Persistence and distribution are new code |
| Pekko Typed, with Pekko Persistence (`EventSourcedBehavior`) and Cluster Sharding | Journal plugins, snapshots, projections, one entity for each session id across machines | A large runtime. A turn blocks, so it must run outside the actor (`pipeToSelf`). The thread-local `RuntimeContext` needs `ContextPropagation`. |
| An effect system (ZIO, kyo, or Cats Effect): a fiber, a queue, and a fold | Typed effects and cancellation | The project evaluated kyo-compat, but it did not adopt it (`docs/refactor/composite-primitives.md`) |

`decide` maps directly to the command handler of `EventSourcedBehavior`, and `evolve` maps to its event handler.
So a Pekko interpreter is a small adapter.

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

Recommendations that are not decisions yet:

- Model the session as a pure decider with laws (8.5).
- Write the cell events of 8.3 from the first version.

Open questions:

1. Is REPL replay a first-version feature, with the stored results of reads? Or is it a later feature, with the
   three preparations of 8.4 in the first version?

## 9. Comparison

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

## 10. Optimization

What the optimizers tune:

- **Policy instructions.** These are the work rules, for example "read a file before you edit it" and "run the
  tests before you submit". Keep the API reference and the REPL rules out of these instructions. Then GEPA
  cannot change the API description. This needs a fixed prompt part that is not in `instructions`. One option is
  an input field that the projection fills. For the prompt cache, a fixed preamble in the system message is
  better. The adapters do not have this feature now.
- **Demos.** Full trajectories are too long for demos. Use GEPA (instructions only) for the policy.
  BootstrapFewShot can collect demos for the short leaves (`subQuery`, `compactor`, `compress`, `merge`).
- **Compaction leaves (design C).** The `compress` and `merge` instructions decide what the memory keeps. A
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
- **Decision types (D6).** The goal check of design C is a `Noul` leaf. ReAnchor can calibrate its threshold
  against the metric. Designs A and B do not need decision types in their first version.
- **Controller leaves (section 7).** The extraction leaves and the controller policy have their own metric
  (7.7): the answers from the graph must agree with probes on the full log.

A source of tasks is the history of this repository. A commit that makes a test pass gives a task (the parent
commit and the test) and a metric (the test passes).

## 11. Recommendation and phases

The recommendation is design C as the target, built on the kernel of design B, with the sessions and the
controller of section 7 and the runtime of section 8. Design C agrees with the functional style of dspy4s: the memory is a persistent value,
the view is a pure function with laws, and only the builder has an effect. It loses no detail, it keeps the
prompt prefix stable by a law, and it gives the optimizers the most leaves. Section 7 keeps these properties for
the controller, and it lets each work session choose its context strategy.

The phases build design B first with a simple projection, so a harness for one task works before the memory
tree exists. Design A stays available as an early check of the foundation.

| Phase | Work | Result |
|---|---|---|
| 0. Experiment | Embed `ReplDriver` 3.8.4 in a test. Check the persistent state, the interrupt at a timeout, a separate output for each REPL, and two REPLs on parallel threads. Measure the start time and the memory of one REPL. | A decision: a REPL in the same JVM, or a forked JVM. |
| 1. Foundation | F1, F2 (`read`, `write`, `edit`, `exec`), F3, F6. | A REPL that runs model code against the guest API. |
| 2. Kernel | The log, the policy leaf, `Harness` on `AgentLoop`, a simple projection (the full log as `History`), the laws, `OptimizableStructure`, `ProgramRunner`. The session decider and the cell events with metadata (8.3, 8.5). `Clock` and `Random` handlers. | A harness for one task that the optimizers accept. |
| 3. RLM | F4, F5: variables, `llmQuery` through `subQuery`, `rlm(...)` children, the shared budget, the depth limit. | RLM in the harness. |
| 4. Memory tree | `Memory`, `View`, the memory laws, the `compress` and `merge` leaves, the builder, `zoom` and `date` as guest functions. The projection of design C. | A memory that loses no detail. |
| 5. Optimization | A `FeedbackMetric` from tests, the memory metric, a small task set, one GEPA run. | A first measured improvement, or a reason for its absence. |
| 6. Work sessions | `Derivation`, `ContextStrategy` (window, prefix summary, memory tree), scopes (directory, worktree, scratch), a CLI that opens one session, steering, the goal check, `ScopeSnapshot`, the end of a session and its release plans (7.9). The direct-style runtime: one virtual thread and one queue for each session (8.5). | A coding agent for daily use, one session at a time. |
| 7. Controller | The controller API (7.3), the ledger events, pluggable ontologies, the extraction at the end of each turn, the time queries (7.6), the `Graph` guest API in sessions (7.7), probes, `SessionCard`, the graph metric. | One controller above all sessions. |
| Later | A forked JVM with the nightly compiler and capture checking. MCP tools as guest functions (`McpTools`). A policy variant with `run_scala` as a native tool through the adapter. Prompt-cache marks in `LmRequest`. RLM on the kernel. REPL replay with stored results (8.4), if it is not in the first version. A Pekko interpreter of the decider, and distribution (8.6). | |

### Open questions

1. **REPL host.** A REPL in the same JVM (fast start, simple), or a forked JVM (isolation, nightly compiler)?
   Phase 0 gives the data.
2. **End signal.** Only `submit(...)`, as in RLM? Or also a reply with no code, as in atc and uniichat? For
   interactive turns, a reply with no code is natural.
3. **RLM.** Does the current `RLM` move onto the kernel later, so that dspy4s has one code program?
4. **Log shape.** Section 7 answers this question with both shapes: a session for each task, and one controller
   log that never ends. For the optimizer, an episode is a fork and one task.
5. **Subagents.** `rlm(...)` stays. With the sessions of section 7, is `spawn(...)` still necessary, or does the
   controller `start` replace it?
6. **Memory access.** Is `zoom` the only way to read the memory, as in uniichat? Or can code also search the
   memory, for example with `llmQuery` over a subtree?
7. **Permissions.** No checks (pi: use a container), or a `guarded` handler with a policy (atc)?
8. **Tasks.** Where do the evaluation tasks come from: the history of this repository, a public benchmark, or
   both?
9. **Controller.** Section 7.10 lists the decisions and the open questions about the sessions and the controller.
10. **Runtime.** Section 8.7 lists the decisions and the open questions about the runtime.

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
  untyped, and the compile-time checks of F2 and F3 do not exist.
