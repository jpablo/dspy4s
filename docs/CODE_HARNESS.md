# Code harness: analysis and design proposals

Status: proposal, 2026-10-19. Nothing in this document exists in the code yet.

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
- The recommendation is design C, built in phases on the kernel of design B, after a short experiment with the
  embedded REPL.

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
that all designs share. Sections 4, 5 and 6 give the three designs, and section 7 compares them. Section 8
describes the optimization. Section 9 gives a recommendation, the phases, and the open questions.

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
  search of the memory. Section 9 lists this as an open question.

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

## 7. Comparison

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

## 8. Optimization

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

A source of tasks is the history of this repository. A commit that makes a test pass gives a task (the parent
commit and the test) and a metric (the test passes).

## 9. Recommendation and phases

The recommendation is design C as the target, built on the kernel of design B. Design C agrees with the
functional style of dspy4s: the memory is a persistent value, the view is a pure function with laws, and only
the builder has an effect. It loses no detail, it keeps the prompt prefix stable by a law, and it gives the
optimizers the most leaves. The phases build design B first with a simple projection, so a harness for one task
works before the memory tree exists. Design A stays available as an early check of the foundation.

| Phase | Work | Result |
|---|---|---|
| 0. Experiment | Embed `ReplDriver` 3.8.4 in a test. Check the persistent state, the interrupt at a timeout, a separate output for each REPL, and two REPLs on parallel threads. Measure the start time and the memory of one REPL. | A decision: a REPL in the same JVM, or a forked JVM. |
| 1. Foundation | F1, F2 (`read`, `write`, `edit`, `exec`), F3, F6. | A REPL that runs model code against the guest API. |
| 2. Kernel | The log, the policy leaf, `Harness` on `AgentLoop`, a simple projection (the full log as `History`), the laws, `OptimizableStructure`, `ProgramRunner`. | A harness for one task that the optimizers accept. |
| 3. RLM | F4, F5: variables, `llmQuery` through `subQuery`, `rlm(...)` children, the shared budget, the depth limit. | RLM in the harness. |
| 4. Memory tree | `Memory`, `View`, the memory laws, the `compress` and `merge` leaves, the builder, `zoom` and `date` as guest functions. The projection of design C. | A memory that loses no detail. |
| 5. Optimization | A `FeedbackMetric` from tests, the memory metric, a small task set, one GEPA run. | A first measured improvement, or a reason for its absence. |
| 6. Interactive shell | A CLI on one log that never ends, `spawn` and `tell`, the goal check, steering. | A coding agent for daily use. |
| Later | A forked JVM with the nightly compiler and capture checking. MCP tools as guest functions (`McpTools`). A policy variant with `run_scala` as a native tool through the adapter. Prompt-cache marks in `LmRequest`. RLM on the kernel. | |

### Open questions

1. **REPL host.** A REPL in the same JVM (fast start, simple), or a forked JVM (isolation, nightly compiler)?
   Phase 0 gives the data.
2. **End signal.** Only `submit(...)`, as in RLM? Or also a reply with no code, as in atc and uniichat? For
   interactive turns, a reply with no code is natural.
3. **RLM.** Does the current `RLM` move onto the kernel later, so that dspy4s has one code program?
4. **Log shape.** One log that never ends (uniichat), or a session for each task (pi)? In both cases, an episode
   for the optimizer is a fork and one task.
5. **Subagents.** `rlm(...)` only, `spawn(...)` only, or both?
6. **Memory access.** Is `zoom` the only way to read the memory, as in uniichat? Or can code also search the
   memory, for example with `llmQuery` over a subtree?
7. **Permissions.** No checks (pi: use a container), or a `guarded` handler with a policy (atc)?
8. **Tasks.** Where do the evaluation tasks come from: the history of this repository, a public benchmark, or
   both?

## 10. Alternatives considered

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
