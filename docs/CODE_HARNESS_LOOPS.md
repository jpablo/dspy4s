# Code harness: the agent loops compared

Status: analysis, 2026-10-24. This document adds to [CODE_HARNESS.md](CODE_HARNESS.md). It compares the agent
loop of each project that section 2 of that document analyzes, and the loops of dspy4s. The facts come from the
source code of each project on 2026-10-24.

## Summary

- The core of a code harness is a ReAct loop. In each step, the model gives an action, the harness runs it, and
  the observation goes back to the model. All the loops in this document have this step.
- The step is almost the same in all the loops. The differences are in four other parts: the state between
  steps, the context of each step, the stop rule, and the outer loop that starts turns.
- The five interactive harnesses stop at a reply with no tool call. The dspy4s programs stop at an explicit
  action (`finish`, `submit` or `SUBMIT`), and they have a fallback when the step limit is reached.
- No interactive harness has a step limit. They limit tool calls, tokens, time or retries.
- All five harnesses use the native reasoning of the model. Only the dspy4s programs have a thought field.
- Most code modes run each cell in a new process or isolate (pi, deepseek-harness, Codex). Only atc, the sessions
  of tacit, and `RLM` keep a REPL from cell to cell. pi and Codex keep state between cells in an explicit store.
- All five harnesses accept a user message during a turn, and they add it between two steps. `AgentLoop.run`
  has no input of this kind.
- tacit has no loop. It supplies the action language to the loop of another agent.
- Code in pi code mode can call a classifier model and an image model (3.2). Sections 2.2 and 2.7 of
  CODE_HARNESS.md had this wrong until 2026-10-24.

## 1. Is the core a ReAct loop?

Yes. A ReAct loop repeats one step until a stop rule holds:

1. The model reads the context, and it gives a thought and an action.
2. The harness runs the action.
3. The observation goes into the trajectory.
4. A rule decides if the loop continues.

In a code harness, the action is a code cell, and the observation is the output of the cell. Each loop in this
document has this step. Section 2 gives the dspy4s form of the step, and section 3 gives the form of each harness.

But the ReAct step does not describe all of a code harness. Four parts around the step make the differences
between the harnesses:

| Part | What it decides | Sections |
|---|---|---|
| State between steps | What the next step can use. In ReAct, the trajectory is the only state. Most code modes run each cell in a new process or isolate, so the log and the files are the only state. Some add a second state: a persistent REPL that keeps the values and definitions of earlier cells (atc, the sessions of tacit, `RLM` in one call), or an explicit store (pi, Codex). | 5.8 |
| Context of each step | Which part of the log the model sees, and when compaction runs. This decides the cost of each step and the use of the prompt cache. | 5.7 |
| Stop rule | What ends the loop, what the result is, and what occurs when a limit is reached. | 5.2, 5.3 |
| Outer loop | What starts a turn: a user message, a queued message, a subagent report, or a goal check. ReAct describes only the loop inside one turn. | 5.6 |

Also, the thought is now mostly the native reasoning of the model. In the five interactive harnesses, the thought
is not a field of the reply (5.4).

Thus the core of a code harness is a ReAct step loop with code as the action, plus the four parts in the table.

## 2. The dspy4s loops

Paths are relative to `modules/programs/src/main/scala/dspy4s/programs/`.

### 2.1 `AgentLoop.run`

`runtime/AgentLoop.scala` is the shared loop. It has 43 lines.

```scala
enum Step[+St, +R]:
  case Continue(state: St)
  case Done(result: R)

def run[St, R](state: St, iteration: Int, maxIterations: Int)(
    onExhausted: St => Either[DspyError, R]
)(
    step: (St, Int) => Either[DspyError, Step[St, R]]
): Either[DspyError, R]
```

- A step gives `Continue` with a new state, `Done` with a result, or a `Left`.
- A `Left` ends the loop at once.
- After `maxIterations` steps, `onExhausted` makes the result from the last state.
- The loop does not define an action or an observation. Each program supplies its own step.
- The step gets only the state and the iteration number.

### 2.2 `ReAct` and `ReActV2`

`ReAct` (`strategies/ReAct.scala`) runs on `InterpretedTrajectoryAgent` (`runtime/InterpretedTrajectoryAgent.scala`).
That trait makes each step a state machine: generate the step, prepare the action, interpret the action, record
the outcome, and decide. `ReActV2` (`strategies/ReActV2.scala`) is experimental, and it has its own loop.

| Topic | `ReAct` | `ReActV2` |
|---|---|---|
| Thought | The output field `next_thought`. | The output field `next_thought`. |
| Action | One tool call in each step: the fields `next_tool_name` and `next_tool_args`, as text. | The field `tool_calls`: many calls in each step, run in order. With native function calling, the calls are provider tool calls. |
| Observation | The text of the tool result. A tool error gives `Execution error in <name>: <message>`. An LM error is a `Left`. | A tool result in the `History`. An unknown tool gives `Unknown tool: <name>`. |
| Stop | The injected `finish` tool, or a step with no tool name. | A `submit` call that succeeds, or a step with no tool calls. |
| At the limit | The extract leaf reads the trajectory and gives the outputs. It also runs after `finish`. | A forced submit: one more step with `tool_choice` set to `submit`. |
| State | A `Vector[TrajectoryEntry]`, rendered as text for each step. | A `History` of events. |
| Context overflow | Remove the oldest entry and try again, up to 3 attempts. If the overflow stays, the loop ends and the extract leaf runs. | The loop ends, and the forced submit runs. A parse error does the same. |

### 2.3 `RLM`

`RLM` (`strategies/RLM.scala`) is the code loop of dspy4s. Its step is in `strategies/RLMExecution.scala`.

| Topic | What RLM does |
|---|---|
| Step | The action leaf reads `variables_info`, `repl_history` and `iteration`, and it gives `reasoning` and `code`. The interpreter runs the code. |
| Thought | The output field `reasoning`. |
| Action | One code cell in each step. |
| Observation | The output of the cell, cut at `maxOutputChars`. A code error is an `[Error]` observation. A `SUBMIT` value that does not decode is a `[Type Error]` observation. A failure of the interpreter itself is a `Left`, and the run ends. |
| Stop | A `SUBMIT` value that decodes to the outputs of the signature. |
| At the limit | The extract leaf reads the REPL history and gives the outputs. |
| State | The REPL history (`Vector[ReplEntry]`) and the state of the interpreter. The inputs go into the REPL again before each cell, so a change to an input variable does not stay. |
| LM from code | `llm_query` and `llm_query_batched`, under one `LlmCallLimit`. These calls write no trace entry (CODE_HARNESS.md, F4). |
| Tests | `ScriptedLm` (`programs/support`) gives scripted replies. |

The three programs have no outer loop, no input during the loop, and no compaction. One program call is one loop.

## 3. The harnesses

Each table uses the same topics. The `file:line` references are relative to the path in the heading.

### 3.1 atc (`/Users/jpablo/GitHub/atc/app/src/atc`)

| Topic | What atc does |
|---|---|
| Loop | `Turn.run` (`agent/Agent.scala:259`) is a tail-recursive loop over `round()`. `Agent.turn` (:230) starts it for each user turn. |
| Step | `round()` (:275) takes queued input, compacts the history if necessary, fits the history to the window, and calls the model. The pure function `CompletionPolicy` (`agent/CompletionPolicy.scala:18`) reads the reply and gives the next state. Then `runTools` runs the calls. |
| Thought | The native reasoning of the provider, for example Anthropic adaptive thinking. Only the same model gets it again, in an opaque payload. Text before the tool calls stays in the message. |
| Action | Native tool calls to `run_scala(code)`. A reply can have many calls, and they run in order (:376). A cancel, queued user input, or a `Revise` permission decision skips the remaining calls with an error result. |
| Observation | `ToolOutput.renderForModel` (`agent/ToolOutput.scala:57`) adds hints for frequent errors. Above 40000 characters, it keeps the first two thirds and the last third. Compile errors, exceptions, validator errors and timeouts are observations. Other exceptions end the turn as `Failed`. |
| Stop | A complete reply with no tool call (`Next.Finish`). The prompt says: `Ending without a tool call means "finished".` The text of that reply is the answer. The other ends are `Blocked`, `Interrupted` and `LimitReached`. |
| Limits | 200 tool calls in a turn (`maxToolCalls`). In interactive mode, the user can add 200 more. After the limit, each call gets the result "…exhausted; answer the user now.", and the model has 2 more rounds. A cell has 300 s. There is no step limit and no token budget for a turn. |
| State | `Conversation` holds a mutable list of immutable messages. The REPL state is in `ReplSession` (`sandbox/ReplSession.scala`), and it stays from turn to turn. |
| Context | Before each request, `ContextManager.prepare` removes old exchanges to fit the window. At 80 % of the window, an LM call summarizes the history, but only between rounds. |
| Input during a turn | New input goes into a queue, and it cancels the model request in progress (:89). The next round takes it. A tool call in progress finishes first. |
| Outer loop | `App.interactive` (`App.scala:219`) reads a line and starts a turn, in a loop. |
| Nested loops | None. `chat()` in the REPL is one completion with no tools. |
| Tests | `ScriptedModel` gives scripted replies, and an in-memory `ToolRunner` takes the place of the REPL (`app/test/src/atc/AgentCoreLoopSuite.scala`). |

### 3.2 pi (`/Users/jpablo/GitHub/pi-mono/packages`)

| Topic | What pi does |
|---|---|
| Loop | `runLoop` (`agent/src/agent-loop.ts:163`). The `Agent` class (`agent/src/agent.ts:188`) contains it, and `AgentSession` (`coding-agent/src/core/agent-session.ts`) drives it. |
| Step | Take the steer messages, call `prepareRequest`, stream the reply through `transformContext` and `convertToLlm`, run the tool calls, add the results, and call `finishTurn`. |
| Thought | Native `thinking` blocks. Text blocks can come before the tool calls. |
| Action | Native tool calls, with a check of the arguments. The calls run in parallel by default (`agent.ts:253`). They run in order if the configuration says so, or if one tool has `executionMode: "sequential"`. |
| Observation | One `toolResult` for each call. `bash` and `read` cut their output at 2000 lines or 50 KB. An unknown tool, bad arguments, a hook block, a tool exception and an abort are observations. A model stop reason `error` or `aborted` ends the loop. |
| Stop | No tool calls and no steer messages (`agent-loop.ts:183`). The loop also stops when each result of a batch has `terminate: true`, or when `finishTurn` returns `end`. There is no finish tool. The answer is the last assistant message. |
| Limits | No step limit. The `bash` timeout is optional. A model request has 3 retries. |
| Context | In the coding agent, `prepareRequest` puts the session projection in place of the messages. Compaction runs before the next reply when the tokens exceed the window minus 16384. |
| Input during a turn | `steer()` messages enter after the current tool batch. `followUp()` messages wait until the agent would stop, and then they start another pass. `abort()` uses an `AbortController`. |
| Outer loop | Three levels: the tool loop, the follow-up loop in `runLoop`, and `AgentSession`, which calls `agent.continue()` for retries, overflow compaction and queued messages. |
| Nested loops | No built-in subagents. Code mode is one tool that runs JavaScript in QuickJS. Calls to `tools.x()` go through the same hooks, and only the script output goes back to the model. |
| Tests | A scripted `streamFn`, or the `faux` provider with `fauxToolCall` and `fauxText` (`ai/src/providers/faux.ts`). |

Code in pi code mode can call more than the injected tools. Until 2026-10-24, sections 2.2 and 2.7 of
CODE_HARNESS.md said that it could only call an injected tool. The `models` object of code mode also has
`classify(model, context)` and `generateImages(model, context)`
(`coding-agent/docs/codemode.md`, `coding-agent/src/extensions/codemode/execute.ts:49`). `classify` answers typed
questions about a record with a classifier model. At most four of these calls run at the same time in one script.
Code cannot call a chat model.

### 3.3 uniichat (`/Users/jpablo/GitHub/uniichat`)

| Topic | What uniichat does |
|---|---|
| Loop | `Agent.loop` (`Agent.ts:167`). `Chat.turn` (`Server.ts:498`) calls it through `Chat.call`. |
| Step | Build the request body, stream the reply, write the entries, run each tool use through MCP `tools/call`, and take the messages that arrived during the run. |
| Thought | Native reasoning: Anthropic adaptive thinking, or OpenAI reasoning summaries. The thinking blocks go back to the model in the same call. The log does not keep them. |
| Action | Native tool calls to MCP tools. A step can have many uses, and they run in order. |
| Observation | A `tool_result` with text and images. The loop does not cut the output, but the tools do: `shell` keeps the head and the tail at 30000 characters, and `read` and `zoom` return pages. An unknown tool and an RPC error are observations. A refusal, `max_tokens`, or a request that fails after all tries ends the loop. |
| Stop | A step with no tool use and no new message (`Agent.ts:207`). The text of that step is the answer. There is no finish tool. |
| Limits | No step limit. 8 tries for each request. Up to 128000 output tokens. Idle stream timeouts of 300 s or 1200 s. No tool timeout. |
| Context | `Chat.call` builds the view once for each call: summary lines, devices, agents at work, and the new messages. In the loop, the view stays the same, and only the items of the steps grow. |
| Input during a turn | A user message enters the running call after the tool results, if it fits next to the view. If not, it waits for the next call. `stop` aborts the call. |
| Outer loop | `Chat.turn` calls `Chat.call` again while messages wait. A turn starts from a user message, a `work` report, the goal message "The goal isn't satisfied yet: keep working.", or a resume after a restart. |
| Nested loops | Each subagent runs the same `Agent.loop` with its own prompt and log, in the background. A subagent cannot spawn. |
| Tests | No model interface. Tests put a fake Anthropic server in the place of `globalThis.fetch`. |

### 3.4 deepseek-harness (`/Users/jpablo/GitHub/deepseek-harness/packages`)

| Topic | What deepseek-harness does |
|---|---|
| Loop | `ReactLoopAgent` (`core/agent-loop/src/agent.ts:104`). `kick()` runs `while (await this.turn()) {}`, and `turn()` (:320) runs the steps. |
| Step | `preStep` takes inbox input, resolves the tools, builds the system prompt, and runs the `agent/pre-step` hooks. Then the request hooks run, `deriveMessages()` builds the messages from the surface, the reply streams, and the tool calls run. The session log gets an event for each part, from `step/start` to `step/end`. |
| Thought | Native reasoning, as `reasoning` blocks in the assistant message. |
| Action | Native tool calls. A call that is not concurrency-safe runs alone, in order. Safe calls run in a pool of `maxParallelToolCalls` (default 10). The results commit in model order. |
| Observation | One `tool/result` for each call. A tool exception, an unknown tool, and a guard or approval denial are `isError` results. The `spill-policy` plugin keeps the head and the tail of a large result, and it stores the full text. A model failure with no retry ends the turn. |
| Stop | A reply with no tool calls (`agent.ts:580`), `max-tokens` (:577), or a tool that calls `concludeTurn()`, for example structured output. The turn closes only if no next-step message waits. |
| Limits | No step limit. Output tokens for each request, a timeout for each tool, 120 s for `run_code` (600 s maximum), retries, and compaction at 0.8 of the window. |
| Context | Each step builds the system prompt and the messages again from the surface. Compaction runs on `agent/pre-step`, and on a context overflow inside the same step. |
| Input during a turn | `send(message, target, wake)`. `steer` enters at the next step, `followup` at the next turn, and `inject` at the next step with no wake. Next-step input keeps the turn open. |
| Outer loop | `kick` opens turns while the inbox has input. When the agent is idle, the goal driver sends a `<goal_round>` follow-up, up to `maxGoalRounds`. |
| Nested loops | Subagents are child `ReactLoopAgent`s with their own sessions, at depth 1 by default. In PTC mode, the model sees only `run_code`, and code calls the tools through the same pipeline. |
| Tests | `dsh-llm-replay` plays recorded model streams, and `assertConsumed()` checks that the test used each one. |

### 3.5 Codex (`/Users/jpablo/GitHub/codex/codex-rs`)

| Topic | What Codex does |
|---|---|
| Loop | `run_turn` (`core/src/session/turn.rs:174`), with the step loop at :473. |
| Step | Take the steer input, build the prompt from a copy of the history, and stream the reply. Each tool call starts when its item is complete in the stream. At the end of the stream, the outputs go into the history, and the loop checks compaction and the stop rule. |
| Thought | Native `Reasoning` items. Text before a tool call is a message in the `Commentary` phase. |
| Action | Native Responses items: `FunctionCall`, `CustomToolCall` (for `exec`), `LocalShellCall` and `ToolSearchCall`. Each call is a task. A tool that supports parallel calls takes a read lock, and the other tools take the write lock (`core/src/tools/parallel.rs:220`). |
| Observation | A `FunctionCallOutput` item. The truncation applies only to the live history, and the rollout file keeps the full output. Tool errors and hook blocks are observations. `Fatal`, a context overflow and a usage limit end the turn. A stream error has up to 5 retries. |
| Stop | `needs_follow_up` is false: no tool call, no pending input, and the reply did not set `end_turn` to false. A Stop hook can block the stop and add a prompt. The answer is `last_agent_message`. |
| Limits | No step limit. Auto-compaction at a token limit, a shared session budget, 10 s for a shell command, and a yield after 10 s for a code cell. |
| Context | The full history, normalized by `for_prompt`. Compaction runs before a request, during a turn at the token limit, after a turn, and in the background. |
| Input during a turn | Steer input goes into a queue, and the next step takes it. A `preempt` token can stop the current stream. `Op::Interrupt` aborts all tasks. Approvals go through the tool orchestrator. |
| Outer loop | `submission_loop` receives the operations and starts the tasks. `RegularTask::run` calls `run_turn` again while input waits. |
| Nested loops | `spawn_agent` starts a separate agent thread, at depth 1 by default. In code mode, nested tool calls go through the same runtime, with hooks and approvals. A cell that runs past its yield time continues, and the model calls `wait` in a later step. A cell can continue after its turn ends. |
| Tests | A wiremock server plays prepared SSE responses (`core/tests/common/responses.rs`). |

### 3.6 tacit (`/Users/jpablo/GitHub/tacit/src/main/scala`)

| Topic | What tacit does |
|---|---|
| Loop | None. The host agent runs the loop, for example Claude Code with its built-in tools turned off. tacit supplies the action language. `StartMCP` (`Main.scala:22`) reads one JSON-RPC message from each line, and it handles one request at a time. |
| Action | `execute_scala(code)` runs the code in a new REPL. `execute_in_session(session_id, code)` runs it in a kept REPL. |
| One action | A regex validator with 39 forbidden patterns, then a parse that allows only `:type`, `:doc` and `:imports`, then the run with an optional timeout. The REPL compiles with capture checking, in safe mode. |
| Observation | One text result: the REPL output, `Error: <msg>`, or `(no output)`. A failed cell sets `isError: true` (`mcp/McpServer.scala:207`). A validator error, a compile error, a timeout and an unknown session are normal tool results. A runtime exception counts as a success, with the stack trace in the output. The output is cut at 10 MiB. |
| Stop | No submit and no result value. The host agent decides. |
| Limits | `--exec-timeout-ms`, with no default. 100 sessions. 30 s for `exec`. 10 s for HTTP. |
| State | A session lives until a delete or the end of the process. After a timeout or a compile error, the session keeps its earlier state. |
| LM from code | `chat(String)` and `chat(Classified[String])`: one completion each. |
| Tests | MUnit suites call the executor and the server with no model. |

## 4. Side by side

tacit is not in this table, because it has no loop.

| Topic | atc | pi | uniichat | deepseek-harness | Codex | dspy4s `RLM` |
|---|---|---|---|---|---|---|
| Thought | Native | Native | Native | Native | Native | The `reasoning` field |
| Action | `run_scala` code | JSON tools | JSON tools (MCP) | JSON tools, or `run_code` | JSON tools, or `exec` | The `code` field |
| State between cells | A persistent REPL | A store (code mode) | No code mode | None by default | A store (code mode) | A persistent REPL in one call |
| Actions in a step | Many, in order | Many, in parallel | Many, in order | Many, in a pool of 10 | Many, started during the stream | One cell |
| Stop | No tool call | No tool call | No tool call and no new message | No tool call, or `concludeTurn()` | No follow-up needed | `SUBMIT` |
| Step limit | None (200 tool calls) | None | None | None | None | `maxIterations` |
| At the limit | "answer the user now", 2 more rounds | No limit | No limit | No limit | No limit | The extract leaf |
| Input during a turn | At the next round | After the tool batch | After the tool results | At the next step | At the next step, or a preempt | None |
| Compaction in a turn | Between rounds, at 80 % | Before a reply | None: the view is fixed for a call | Before a step, and on an overflow | Before, during and after a turn | None |
| Outer loop | A read-line loop | The follow-up loop and `AgentSession` | `Chat.turn` and the goal check | `kick` and the goal rounds | `submission_loop` and `RegularTask` | None |
| Scripted model in tests | `ScriptedModel` | The `faux` provider | A fake `fetch` | `dsh-llm-replay` | wiremock SSE | `ScriptedLm` |

## 5. What the comparison shows

### 5.1 The step is the same

All six loops make one model call in each step, run the actions of the reply, add the observations, and decide.
In each loop, a recoverable error of an action is an observation. A failure of the model call or of the runtime
ends the loop. dspy4s has the same rule in `ActionOutcome`.

### 5.2 Two stop rules

- The five interactive harnesses stop at a reply with no tool call. The answer is the text of that reply.
- The dspy4s programs stop at an explicit action: `finish`, `submit` or `SUBMIT`. The result is a typed value.
- deepseek-harness has both rules. A tool can call `concludeTurn()`, and its structured-output tool uses this.
- The harness of CODE_HARNESS.md is a `Module[I, O]`, and it is also a chat agent. Thus it needs both rules: a
  reply with no cell ends a chat turn, and `submit` (F3) ends a program call.

### 5.3 Limits and fallbacks

No interactive harness has a step limit. atc limits the tool calls of a turn, and the other harnesses limit
tokens, time or retries. At its limit, atc tells the model to answer, and it gives the model two more rounds.

The dspy4s programs have a step limit and a fallback. `ReAct` and `RLM` run an extract leaf, and `ReActV2` runs a
forced submit. The atc fallback and the forced submit use the same idea: one last step in which the model must
answer.

### 5.4 The thought

The five interactive harnesses use the native reasoning of the model, and none has a thought field. atc sends the
reasoning back only to the same model. The uniichat log does not keep it.

The dspy4s programs have a field: `next_thought` or `reasoning`. The field is an output of the leaf, so the trace
entry of the leaf has it, and the optimizers can read it. dspy4s keeps native reasoning as metadata of the LM
response (`reasoning_content`), outside the outputs of the leaf. With a reasoning model, the field adds a second,
visible reasoning to the native one.

### 5.5 Actions in a step

Each interactive harness accepts many tool calls in one reply. pi and deepseek-harness run them in parallel, and
Codex starts each call while the reply streams. `RLM` runs one cell in each step.

In code mode, one cell can make many tool calls (pi, deepseek-harness, Codex). Thus one cell in each step does not
limit the work of a step.

### 5.6 Input during a turn, and the outer loop

All five interactive harnesses accept a user message during a turn. They add it between two steps. Codex can also
stop the current stream with a preempt.

Each interactive harness also has an outer loop that starts turns. Codex has four levels of loops, and pi has
three. uniichat and deepseek-harness start a turn for a goal check, and uniichat also starts a turn for a
subagent report.

`AgentLoop.run` has neither part, because its step gets only the state and the iteration number. In design B,
a middleware can read an inbox before each step. The session actor (CODE_HARNESS.md, 8.1) can run the turn loop.

### 5.7 Context between steps

atc, pi, deepseek-harness and Codex can compact the history between two steps of one turn. uniichat builds its view
once for each call, and it does not change the view inside the call. This keeps the prompt cache valid.

The dspy4s programs render the full trajectory for each step. Only `ReAct` removes old entries, and only on a
context overflow.

### 5.8 State between cells

| Code runtime | Where a cell runs | State between cells |
|---|---|---|
| atc | One `ReplDriver` for the session | The REPL state stays from cell to cell and from turn to turn. |
| tacit | `execute_scala`: a new REPL. `execute_in_session`: a kept REPL. The model chooses. | Only in a kept REPL. After a timeout or a compile error, the session keeps its earlier state. |
| pi code mode | A new QuickJS sandbox for each call | `store(key, value)` and `load(key)`. A write stays only if the script succeeds, and the session log keeps it. |
| deepseek-harness PTC | A new Node process for each call, by default. The `persistent-in-memory` mode is opt-in. | None by default. The opt-in REPL is never in the session log. |
| Codex code mode | A new V8 isolate for each cell | `store(key, value)` and `load(key)`. A cell sees a snapshot, and its writes commit only if the cell completes. Codex removed its persistent JavaScript REPL. |
| dspy4s `RLM` | One interpreter for each program call, closed at the end of the call | The interpreter state stays from iteration to iteration. The inputs go into the REPL again before each cell. |

uniichat has no code mode.

A persistent REPL is the less common choice. Three of the six code runtimes start each cell fresh by default
(pi, deepseek-harness, Codex), and two of them give an explicit store with commit on success (pi, Codex). With a
fresh cell, no state stays in a process between cells. The log, the files, and the store hold all of it.

The harness of CODE_HARNESS.md keeps a persistent REPL (F1). Then the REPL state is a second state next to the log.
A replay must rebuild it (CODE_HARNESS.md, 8.4), and 9.2 of CODE_HARNESS.md asks if an explicit store goes next to
it.

### 5.9 Tests

Each loop can run with a scripted model, except uniichat, which puts a fake server in the place of `fetch`. tacit
tests its executor with no model.

## 6. Consequences for the harness

- Use `AgentLoop.run` for the step loop. The step of design B (CODE_HARNESS.md, section 5) already has the ReAct
  form: a middleware, the policy, the interpreter, two log entries, and a decision.
- Support both stop rules. A reply with no cell ends a chat turn, and `submit` ends a program call (5.2).
- Keep a step limit and a fallback for a program call. For a chat turn, give the model one last step to answer, as
  atc does (5.3).
- Read the inbox between steps. A message from the controller API (CODE_HARNESS.md, 7.3) goes into the log before
  the next step. The middleware of design B is the place for this (5.6).
- Put the turn loop in the session actor (CODE_HARNESS.md, 8.1). It starts a turn for a user message, a subagent
  report, or a goal check (5.6).
- Decide how the policy gives its thought: the `reasoning` field, native reasoning, or both. The optimizers read
  the outputs of the leaf, and only the field is an output (5.4).
- Keep one cell in each step. One cell can make many guest API calls, so a step can do much work (5.5).
- Use `ScriptedLm` and a recorded interpreter to test the loop with no model and no REPL, as atc does with
  `ScriptedModel` and `ToolRunner` (5.9).
