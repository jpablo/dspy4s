# Code harness: the design space

Status: plan, 2026-10-25. This document lists the aspects of a code harness that deserve a deep comparison, and
the questions to answer for each one. It does not answer the questions. Each answer is a document of its own in
this folder.

## Purpose

The goal is to understand and compare the design, the trade-offs, the strong points and the limits of code
harnesses. The design space is large, so the comparison covers only the aspects that decide the shape of a
harness. An aspect is on the list if it passes four tests:

1. The choice has a large effect on quality, cost or safety.
2. The analyzed harnesses made different choices, so the question is still open.
3. The choice is hard to change later.
4. The choice matters for the dspy4s harness ([CODE_HARNESS.md](CODE_HARNESS.md)).

The harnesses in scope are atc, pi, uniichat, deepseek-harness, Codex and tacit (CODE_HARNESS.md, section 2),
and the dspy4s programs `ReAct`, `ReActV2` and `RLM`.

## Covered elsewhere

| Topic | Where |
|---|---|
| The agent loop: the step, the stop rules, the limits, the input during a turn, the outer loop | [CODE_HARNESS_LOOPS.md](CODE_HARNESS_LOOPS.md) |
| Execution and runtime models: the REPL host, the state between cells, persistence, replay, actors | [CODE_HARNESS_RUNTIME.md](CODE_HARNESS_RUNTIME.md), with later documents in progress |
| Durability: what survives a crash, a restart or a close, and if the work continues | [CODE_HARNESS_DURABILITY.md](CODE_HARNESS_DURABILITY.md) |

## The five aspects

| # | Aspect | The key question |
|---|---|---|
| 1 | The action interface | What does the model write, against which API, and what comes back to it? |
| 2 | Context engineering | What does the model see at each step, and at what cost? |
| 3 | Authority and safety | What can the agent do, and what stops it? |
| 4 | Delegation | How does the agent split work: subagents, recursion, or LM calls from code? |
| 5 | Evaluation and improvement | How do you measure the harness, test it with no model, and make it better? |

### 1. The action interface

Questions for each harness:

1. Which language does the model write, and how does the harness run it?
2. How does the model learn the API: in the prompt, through a search function, or on demand? How large is the
   reference?
3. What is the shape of the API: how many functions, how large each one is, and how they are named and grouped?
4. What does a call give back to the code, and what does the model see?
5. How do compile errors, runtime errors and tool errors come back? Does the harness add hints?
6. How does the harness limit the output, and does the limit tell the model the next step?

Known differences:

- The language is JSON tool calls, TypeScript (Codex, deepseek-harness, pi), Python (`RLM`), or Scala (atc,
  tacit).
- atc puts one trait of about 60 methods in the prompt. Codex and pi give declarations with a size budget and a
  search function. tacit gives the reference on demand (`show_interface`).
- In code mode, the code gets values, and the model sees only what the code prints or returns. atc and tacit
  give Scala values, and pi gives a structured value for a tool with an output schema. deepseek-harness also
  gives each tool a `render` function, which makes the text that the model sees.

Why it matters: the model can only be as good as the interface that it reads. F2 of CODE_HARNESS.md is the
current answer for dspy4s.

### 2. Context engineering

Questions for each harness:

1. What is the source of truth, and how does the harness make the context of each step from it?
2. When does compaction run, which steps does it take, and which model does it use?
3. Can the model get the detail back after a compaction? How?
4. Which standing instructions go into the context: the system prompt, project files, skills?
5. Which parts of the prompt stay the same from step to step, so that the prompt cache holds?
6. How does the harness count and limit the tokens?

Known differences:

- atc removes old exchanges. pi adds compaction entries. deepseek-harness has a surface in which an event can
  replace a range and must cite it. Codex sends world-state diffs. uniichat uses a cut through a summary tree.
- deepseek-harness makes cheap cuts before an LM summary. Codex compacts in the background. uniichat builds its
  summaries in the background with a cheaper model.
- atc loses the detail. pi and deepseek-harness keep it in the session file. uniichat (`zoom`) and Codex (tools
  that read earlier windows) let the model get it back.

Why it matters: on long tasks, this decides both quality and cost. CODE_HARNESS_LOOPS.md touches it only in
section 5.7.

### 3. Authority and safety

Questions for each harness:

1. What can a session do by default, and how does the user widen or narrow that?
2. Where are the checks: at compile time, at run time, in an OS sandbox, or in an LLM reviewer? Which check is
   the real boundary?
3. Who approves an action, and what occurs when no approver answers?
4. How does the harness protect secrets?
5. How does the harness treat untrusted content, for example web pages, files and tool output?
6. What authority does a child process or a subagent get?

Known differences:

- atc and tacit check capabilities at compile time. Codex and deepseek-harness run commands in an OS sandbox.
  Codex and deepseek-harness also have an LLM reviewer.
- atc and tacit protect secrets with `Classified` values. Codex puts dummy credentials in the place of real
  secrets, behind a network proxy.
- After a denied call, deepseek-harness lets the model ask for one wider mode, and a human approves it.

Why it matters: mistakes here are expensive. F7 and F8 of CODE_HARNESS.md cover only the sandbox.

### 4. Delegation

Questions for each harness:

1. Which forms of delegation exist: subagents, recursive calls, LM calls from code?
2. What does a child get: context, tools, authority, budget?
3. How does the result come back: as a message, as a typed value, or in a file?
4. Does the parent wait, or does the child run in the background?
5. How do agents coordinate: messages, mailboxes, shared task lists?
6. What limits the tree of agents: depth, count, shared budgets?

Known differences:

- uniichat, Codex and deepseek-harness run subagents in the background, and the subagents report as messages.
  `RLM` makes recursive calls that return a value. atc (`chat`) and pi (`models.classify`) make one-shot LM calls
  from code.
- In Codex and deepseek-harness, a child gets at most the authority of its parent, and the default depth is 1.
- deepseek-harness teams share a task graph. Codex agents share a message board.

Why it matters: this decides how a harness scales past one context window. `RLM` and the controller
(CODE_HARNESS.md, section 7) meet here.

### 5. Evaluation and improvement

Questions for each harness:

1. How do the tests run the harness with no model?
2. Which benchmarks or metrics does the project use?
3. Which parts of the behavior are fixed text, and which parts can change from data?
4. Does the harness learn from earlier runs, and how does it keep bad data out?
5. What does a trace record, and can an optimizer use it?
6. How does the project compare two versions of the harness?

Known differences:

- atc and pi use scripted models. deepseek-harness plays recorded model streams (`dsh-llm-replay`). Codex uses
  a mock HTTP server that plays prepared streams.
- tacit reports results on τ²-bench and SWE-bench Lite.
- The prompts of the five interactive harnesses are fixed text. Codex makes memories from earlier runs. dspy4s
  has `Predict` leaves that the optimizers tune, for example with GEPA.

Why it matters: dspy4s can differ most from the other harnesses here. The comparison shows what the others do in
place of optimization, and what the dspy4s harness must expose to the optimizers.

## Left out on purpose

| Topic | Reason |
|---|---|
| The stop rules, the goals, and the user input during a turn | CODE_HARNESS_LOOPS.md covers them. |
| Session persistence, replay, the REPL host, and actors | The analysis of execution and runtime models covers them. CODE_HARNESS_DURABILITY.md covers what survives a crash. |
| The process and host model of a sandbox | The runtime analysis covers it. Aspect 3 treats a sandbox only as a boundary of authority. |
| The terminal UI, the provider adapters, the configuration formats, the MCP plumbing, and the edit algorithm of a tool | These are details. They do not change the shape of a harness. |

## Method

Each deep comparison uses the method of CODE_HARNESS_LOOPS.md:

1. Answer the questions of the aspect for each harness, with facts from the source and `file:line` references.
2. Add a short Scala sketch where it makes a choice clear.
3. Put the answers side by side in one table.
4. End with the consequences for the dspy4s harness.

The proposed order is the order of the table. Aspect 1 comes first, because F2 depends on it, and because Scala
as the action language is the main choice of the project.
