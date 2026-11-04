# Code harness: the concept inventory

Status: inventory, 2026-11-01. This document lists the domain concepts of the code harness and the kind of state
that each one has. It makes no modeling decisions and no architecture decisions. Those come after this list. The
concepts come from [CODE_HARNESS.md](CODE_HARNESS.md) (the proposal) and from the source of the analyzed harnesses.
It uses the kernel of section 4.2 of the proposal, as it was on 2026-11-01.

## Summary

- "State" is not one thing. The concepts have eight kinds of state, and each kind has different rules (section 1).
- The units of work nest: session, turn, step, cell, effect. Each unit is a mix of kinds. Only its status is a small
  state machine (section 2).
- "The session state" is the largest mix. It holds a status, a log, values, a live REPL, and files on a disk.
- The inventory has 106 concepts in ten groups (section 3).
- Many terms have more than one meaning, for example "turn", "policy", "scope", "replay" and "fork" (section 4).
- Ten scenarios test the boundaries between the concepts. The proposal now answers three of them, and the others are
  open questions (section 5).

## 1. The kinds of state

| Kind | What it is | Examples |
|---|---|---|
| Control | A small set of named states with named transitions | Session status, turn status, step phase, cell status, effect status |
| Record | Facts that are only appended | The log, the cell events, the ledger, the trace |
| Data | Small values that can change and that can be serialized | Mode, goal, budgets, explicit store |
| Derived | Values made from records. A pure function makes some of them, and an LM makes others. | The window (pure), the memory tree (LM) |
| Live | Objects in a running process. In general, they cannot be serialized. | The REPL state, a running cell, a mailbox in memory |
| World | Things outside the harness, with their own truth | Files, git, OS processes, remote services |
| Remote | Things that the LM provider holds and that the harness cannot read | The prompt cache, an encrypted reasoning payload |
| Transient | Things for delivery only. Nothing needs them later. | Stream deltas, status lines |

The REPL state is the most open kind. Each cell can add definitions and objects, and the model writes those cells
at run time. So the state has no schema that the harness knows in advance.

Some concepts hold parts of several kinds. The tables call such a concept Composite. A session is the main example.

## 2. The units of work

```text
Session                    a conversation with a durable identity and a log
└─ Turn                    from one accepted input to its end
   └─ Step                 one policy call and what it starts
      ├─ Model request     one request to the LM, with its attempts
      └─ Cell              one unit of code that the REPL runs
         └─ Effect         one guest API call
            ├─ Process     an OS process from `exec` or `spawn`
            ├─ LM call     `llmQuery`
            └─ Child       a child session, from `rlm(...)`
```

The tree is not strict:

- A process can continue after its cell ends. In atc, a process from `spawn` can run until the session ends.
- In Codex, a cell can continue after its step, and after its turn.
- Compaction and the goal check run between the units, and they make their own LM calls.
- uniichat has one more level between turn and step: a `call`, with one fixed view.

### 2.1 The same units in each harness

| Term in this document | Codex | pi `agent` | `pi-durable` | uniichat | deepseek-harness | atc | dspy4s programs |
|---|---|---|---|---|---|---|---|
| Session | thread | session | conversation | chat | session | conversation | none |
| Turn | turn | run of one `prompt()` | run | turn | turn | turn | one program call |
| Step | one pass of the `run_turn` loop | **turn** | **turn** | step | step | round | iteration |
| Model request | sampling request | stream | `request` phase of `pi.generation` | `Agent.step` | attempt | `completeRound` | `Predict` call |
| Cell | cell (`exec`) | script | script | none | program (`run_code`) | snippet (`run_scala`) | code block (`RLM`) |
| Effect | nested tool call | nested tool call | nested tool task | none | sub-call | `Interface` method call | sandbox tool call |

### 2.2 The parts of each unit

Each unit is a mix of kinds. This table splits each one.

| Unit | Control | Record | Data | Live | World or remote |
|---|---|---|---|---|---|
| Session | Status | The log | Mode, strategy, goal, store, usage totals | The activation, the REPL, the mailbox in memory | The scope, the processes, the provider session identity |
| Turn | Status | `Input`, `TurnStarted`, `StopRequested`, `TurnEnded` and `ScopeSnapshot` entries | Step count, call counts | The worker, the cancel handle | None |
| Step | Phase | `StepStarted` and `Acted` entries | Attempt count | The worker stack | None |
| Model request | Stop reason | The reply, the usage | Attempt number, pinned options | The stream, the HTTP connection | The prompt cache, a deferred response handle |
| Cell | Status | `Acted` with the code, `Observed` with the store writes | Effect counter | The thread, its stack, its local values | Only through its effects |
| Effect | Status | `EffectStarted`, `EffectDone` | Effect id, operation, arguments | The running call | The file, the process, or the service that it changes |
| Process | Status | Its output, its exit code | Command, working directory | The OS handle, the streams | The OS process |
| Child session | The same as a session, and an owner | Its own log, and one effect in the parent | The same as a session | Its own activation | Its own scope |

### 2.3 Candidate states

These names come from the sources. They are candidates, and this list chooses none of them.

| Unit | Candidate states | Source |
|---|---|---|
| Session | not loaded, idle, active, system error | Codex `ThreadStatus` |
| Session | ended, reopened | Proposal, 7.9 |
| Session | idle, in a turn, stopping, closed | Proposal, `Phase` (4.2) |
| Turn | replied, submitted, exhausted, aborted, crashed, failed | Proposal, `TurnOutcome` (4.2) |
| Turn | in progress, completed, interrupted, failed | Codex `TurnStatus` |
| Turn | finished, interrupted, blocked, limit reached, failed | atc `TurnOutcome` |
| Turn | completed, blocked, aborted, error, max-tokens, interrupted | deepseek-harness `TurnEndReason` |
| Step | prepare, request, retry, poll, tools | `pi-durable` `pi.generation` |
| Step | run tools, resume, finish, blocked | atc `CompletionPolicy.Next` |
| Step | generating, preparing, interpreting, recording, deciding | dspy4s `InterpretedTrajectoryAgent.State` |
| Model request | pending, stop, length, tool use, error, aborted, deferred | pi `StopReason` |
| Cell | ok, compile error, failed, timed out, submitted | Proposal, `CellOutcome` (F1) |
| Cell | yielded, terminated, result | Codex `RuntimeResponse` |
| Effect | started, done with a value, done with an error, done with an unknown outcome | Proposal, 8.3 |
| Effect | call, execute (with `replay` safe or unsafe) | `pi-durable` `pi.tool` |
| Input | queued, placed, done, unanswered | `pi-durable` submissions |
| Any task | pending, running, waiting, sleeping, completing, terminal | `pi-durable` `TaskState` |
| Any task outcome | completed, failed, aborted, orphaned, faulted | `pi-durable` `TaskOutcome` |

## 3. The inventory

A concept with no note is in the proposal. A note names the harness when a concept comes only from that harness.
The last column says what could rebuild the concept after a restart. It describes what is possible, not what the
harness must do:

| Value | Meaning |
|---|---|
| Stored | It is in the log or in a file. |
| Fold | A pure fold of the log can make it. |
| Replay | Only a new run of the cells can make it. |
| Stored (LM) | It is stored. A new computation costs money and gives a different result. |
| Config | The configuration or the code gives it. |
| World | It is outside the harness. The harness must read it again. |
| Provider | The provider decides. The harness has no control. |
| Lost | Nothing can rebuild it. |

### 3.1 The record

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Log | The append-only list of the entries of one session | Record | Stored |
| Entry | One fact in the log: `Activated`, `Input`, `TurnStarted`, `StepStarted`, `Acted`, `EffectStarted`, `EffectDone`, `Observed`, `StopRequested`, `JobsStopRequested`, `Granted`, `ScopeRecorded`, `TurnEnded`, `Ended` | Record | Stored |
| Entry metadata | The source, the taint and the `required` mark of an entry. The model never sees them. | Record | Stored |
| Cell event | An entry of one cell: `Acted`, `EffectStarted`, `EffectDone`, `Observed` | Record | Stored |
| Ledger | The sources that a session read and the artifacts that it made | Record | Stored |
| Trace | The record of the leaf calls that the optimizers read | Record | Stored, if kept |
| Usage | The tokens and the cost of each LM call | Record | Stored |
| Transcript | The conversation as a person reads it | Derived | Fold |

### 3.2 The definition of the agent

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Policy | The `Predict` leaf that writes the next cell, or a reply with no cell | Data | Config |
| Instructions | The text that the policy leaf and the other leaves use | Data | Config |
| Optimized parameters | The instructions and the demonstrations that an optimizer found | Data | Stored |
| Model choice | The LM and its reasoning level | Data | Stored |
| Project instructions | `AGENTS.md`, `CLAUDE.md` and skills | World | World |
| Context strategy | The rule that makes the policy input: a window, a prefix summary, or the memory tree | Data | Stored |
| Mode | The set of capabilities of a session, and their views | Data | Stored |
| Guest API | The traits and the functions that a cell can call | Data | Config |
| API reference | The source text of the guest API in the prompt | Derived | Config |
| Capability | A value that gives permission for one kind of effect | Live | Config (the preamble binds it again) |
| Handler | The value that decides what a guest API call does | Live | Config |
| Guard rules | The allow rules and the deny rules of the `guarded` handler | Data | Config |
| Sandbox profile | The OS policy of a child process | Derived | Fold, from the mode |
| Limits | Iterations, LM calls, output size, depth, cell timeout | Data | Config |
| Middleware | A function that changes the session before a step | Data | Config |

### 3.3 The code runtime

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| REPL | The compiler and the running code of one session | Live | Replay |
| Compiler state | The names and the types that earlier cells defined | Live | Replay |
| Definition | A `val`, `def`, `class` or `import` that a cell made | Live | Replay |
| Value | An object that a definition holds in memory | Live | Replay, if the code is deterministic |
| Class loader | The loader of the guest code, with the shared guest API package | Live | Config |
| Preamble | The code that the REPL compiles before the first cell | Data | Config |
| Cell thread | The thread that runs a cell, with its stack | Live | Lost, or Replay |
| Input variables | The inputs of the signature as REPL values (F5) | Data | Stored |
| Explicit store | Values that go from cell to cell through `store` and `load`. It holds the `writes` of the cells that completed (4.2). | Data | Fold |
| `Data` value | A value that can leave a cell. The journal holds it as `Json` | Data | Stored |
| Clock and random | The time and the random numbers that a cell sees. Safe mode does not close them | World | Stored, if a replay needs them |
| Language versions | The compiler version and the guest API version | Data | Stored |

### 3.4 The world

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Scope | The place of the work of a session: a directory, a worktree, or a scratch directory | World | World |
| Files | The contents of the scope | World | World |
| Git state | The branch, the head commit and the changes | World | World |
| Workspace fork | A copy of the scope for one rollout (F6) | World | World |
| Process | An OS process that a cell started | World | World, if it still runs |
| Process environment | The variables and the working directory of a command | Data | Config |
| Device | A computer that runs tools for a session (uniichat) | World | World |
| External service | A remote system that a command or a call changes | World | World |
| Secret | A value that the model must not see, for example `Classified` (atc, tacit) | World | World |

### 3.5 The LM provider

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Prompt cache | The cached prefix of earlier requests | Remote | Provider |
| Provider session identity | The id that keeps a conversation on the same cache (`pi-durable`) | Data | Stored |
| Opaque reasoning payload | A reasoning block that the harness sends back only to the same model (atc) | Remote | Stored, if kept |
| Deferred response | A handle for a reply that comes later (`pi-durable`) | Remote | Stored, then Provider |
| Rate limit | The state of the quota of the account | Remote | Provider |

### 3.6 The derived data

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Policy input | The context of one step | Derived | Fold, from the log and the derived data |
| Window | A part of the log, by a pure rule | Derived | Fold |
| Compaction entry | A summary that takes the place of a prefix in the projection | Derived | Stored (LM) |
| Memory tree | A binary tree of summaries over the log | Derived | Stored (LM) |
| View | A cut through the memory tree | Derived | Fold, from the tree |
| Knowledge graph | The facts that an LM extracts from the log | Derived | Stored (LM) |
| `SessionCard` | A short description of a session for the controller | Derived | Stored (LM) |
| Catalog | One row for each session: the deterministic part of its `SessionCard` | Derived | Fold, from the logs |
| Usage totals | The sums of the usage records | Derived | Fold |

### 3.7 The interaction

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Input | A message from the user, the controller or a child | Record | Stored, after it is accepted |
| Mailbox | The messages that wait for the decider. An accepted message is an `Input` entry (4.2). | Live | Lost |
| Steer | An input that enters between two steps of the current turn (`Send`, 4.2) | Record | Stored, after it is accepted |
| Follow-up | An input that waits for the end of the current turn (pi, deepseek-harness) | Record | Stored, after it is accepted |
| Interrupt | A request to stop the current work (`Abort`, 4.2) | Record | Stored, as `StopRequested` |
| Cancel handle | The object that stops running work | Live | Lost |
| Request id | The key that finds a repeated input | Data | Stored |
| Approval | A question to a person about one action, with its answer | Control and Record | The question is Stored, but a waiting cell thread is Lost |
| Grant | The answer to an approval: a guard rule and a lifetime | Record | Stored |
| Sleep | A wait for a time (`pi-durable`: stored; Codex: a `sleep` tool) | Control | Stored in `pi-durable` |
| Client | A terminal or a page that shows a session | Live | Lost |
| Published event | A message from the session to its clients and to the controller | Transient | Fold, for the stored part |
| Stream delta | A part of a model reply on its way to a client | Transient | Lost |

### 3.8 The delegation

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Controller | One session that never ends, which starts and observes work sessions | Composite | The same as a session |
| Work session | A session with a scope, which the user talks to directly | Composite | The same as a session |
| Child session | A session that a cell starts with `rlm(...)` | Composite | The same as a session |
| Subagent | A short background worker that reports as a message (uniichat, Codex, deepseek-harness) | Composite | Its log, if it has one |
| Job | A command that runs after its cell ends | Record and Live | The record is a fold of the log, and the process is Lost |
| Owner | The session or the cell that a child belongs to | Data | Stored |
| Role | A limit on the authority of a child (Codex) | Data | Stored |
| `tell` | A message from the controller into a session | Record | Stored |
| Probe | A question to a session that changes nothing in it (`observe`) | Transient | Lost |
| Report | The result of a child as a message or a value | Record | Stored |
| Shared budget | One limit for a full tree of calls (`LlmCallLimit`) | Data | Fold, over several logs |

### 3.9 The goal and the end of work

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Task | The work that a session gets at its start | Data | Stored |
| Goal | A condition that the work must meet | Data | Stored |
| Armed goal | The permission to continue by itself (deepseek-harness: never stored) | Control | Lost, by design |
| Goal check | One LM call that asks if the goal is met | Transient | Stored, as its result |
| To-do list | The steps that the model plans (atc) | Data | Stored in atc |
| Submitted value | The typed result of a program call (F3) | Record | Stored |
| Turn outcome | How a turn ended (`TurnOutcome`, 4.2) | Record | Stored |
| Outcome | How a session ended | Record | Stored |
| Release plan | The steps that end a session and release its scope (7.9) | Data | Stored |

### 3.10 The recovery and the optimization

| Concept | What it is | Kind | After a restart |
|---|---|---|---|
| Activation | The live part of a session in one process, from its start to its close or crash (a term that this document proposes) | Live | A new one |
| Worker | The code that runs a turn outside the decider (8.5) | Live | A new one |
| Decider | The pure state machine of a session (8.5) | Data | Config |
| Commit | One atomic write of entries to the log. A reader sees an entry only after it (4.2). | Record | Stored |
| Intent record | A record before an effect: `StepStarted`, `Acted` with code, `EffectStarted` | Record | Stored |
| Unknown outcome | An effect that a crash cut. The repair closes it with `EffectResult.Unknown` (8.3). | Record | Stored |
| Recorded handler | The handler that commits `EffectStarted` and `EffectDone` for each call | Live | Config |
| Replayed handler | The handler that answers each call from the log | Live | Config |
| Divergence | A replay that takes a different path from the record | Transient | Lost |
| Restart count | The number of restarts that cut a task (`pi-durable`) | Data | Stored |
| Log fork | A new log that starts with the first `k` entries of its parent | Record | Stored |
| Rollout | One run of the harness on one task, for an optimizer | Composite | Can run again |
| Task set | The tasks for an evaluation | Data | Config |
| Metric | The function that scores a rollout, with text feedback | Data | Config |
| Demonstration | An example that a leaf gets in its prompt | Data | Stored |

## 4. Terms with more than one meaning

The right column was a proposal. [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), section 3 now settles the terms.

| Term | Meanings | Proposed reading |
|---|---|---|
| State | Eight kinds (section 1) | Always name the kind. |
| Session | A durable conversation (proposal). The runtime object of one thread (Codex `Session`). The storage and its line of commits (`pi-durable` `Session`). | Session is the durable conversation. Activation is its live part. |
| Turn | One input to its end (Codex, deepseek-harness, atc, uniichat). One model reply and its tools (pi, `pi-durable`). | Turn has the first meaning. Step has the second. |
| Step | A unit of work. The result of one iteration (`AgentLoop.Step`: `Continue` or `Done`). A model reply (uniichat `Step`). | Step is the unit of work. Name the result type differently. |
| Action | A tool call. A cell. A guest API call. | Use cell or effect. |
| Policy | The `Predict` leaf (4.1). The rule of the `guarded` handler (F2). The sandbox policy (F8). atc `Policy`. | Policy is the leaf. Use guard rules and sandbox profile. |
| Scope | The workspace (7.3). `GraphScope` (7.7). A capability in scope (F2). A usage scope (`Module.apply`). The atc grant scope. | Scope is the workspace. Rename the others. |
| Task | The work of a session. A row of a task set. A durable state machine (`pi-durable`). A running turn (Codex `RunningTask`). | Task is the work of a session. |
| Agent | A definition. A running session. A subagent. A class (atc, pi). | Name the one that you mean. |
| Log | The log of the proposal. `History` is a dspy4s value in the policy input. The transcript is for people. The trace is for the optimizers. The journal is the log in event-sourcing terms. | Keep these five apart. |
| Replay | A recovery fold (Pekko). A new run of the cells (8.4). A test tool (`dsh-llm-replay`). | Use fold, cell replay, and recorded streams. |
| Snapshot | A saved state (Pekko). `ScopeSnapshot`, which is a fact about git. A copy of the store that a cell sees (Codex, `Store.snapshot` in F2). | Rename `ScopeSnapshot`, for example to `ScopeRecorded`. |
| Commit | A journal write (4.2). A git commit (`ScopeSnapshot`). The store writes of a cell that completed. | Commit is the journal write. Say git commit for git, and say that a write takes effect for the store. |
| Fork | A workspace fork (F6). A log fork (4.2). A session fork, which must also decide about the REPL, the store and the processes. | Name the three separately. |
| Mode | The capabilities of a session (F2). Code mode. A sandbox mode (Codex). `compose/Mode.scala` in dspy4s. | Mode is the set of capabilities. |
| Event | A journal event (`Entry`). A cell event, which is also an `Entry` (8.3). A `CallbackEvent` of dspy4s. A published event for clients. | Name the one that you mean. |
| Stop | A cancel. An interrupt. A steer. The end of a turn. The `stop` of a provider. `StopRequested` (4.2). | Keep interrupt (hard) and steer (soft) apart. The proposal now calls them `Abort` and `Send`, so choose one pair of names. |

The term "actor" does not appear in the inventory, because it is a way to encode a concept. In Codex,
`submission_loop` reads commands one at a time, as an actor does. But `Session` holds `Mutex<SessionState>` and
`Mutex<Option<ActiveTurn>>`, and a turn runs in a separate `tokio::spawn` task. So the input side is like an actor,
but the state is shared.

## 5. Scenarios that test the boundaries

[CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md), section 11 now answers all the scenarios. The table after this one gives
the first answers to scenarios 1, 4 and 7.

| # | Scenario | The boundary that it tests |
|---|---|---|
| 1 | The user sends a message during a cell that runs for 10 minutes. Does the cell see it? Does it stop? | Mailbox, steer, interrupt, cell |
| 2 | A cell starts `sbt ~test` with `spawn`, and the turn ends. Who owns the process? | Process, cell, turn, session |
| 3 | A session closes, and it opens the next day on another machine. Is it the same session? What occurs to the REPL and the scope? | Session, activation, scope |
| 4 | A cell defines `val x`, and its next line throws an exception. Does a later cell see `x`? | Cell, REPL, definition |
| 5 | A rollout forks a session at entry `k`. What occurs to the scope, the REPL, the store, the processes and the prompt cache? | Log fork, workspace fork, REPL |
| 6 | A cell calls `rlm(...)`, and the parent crashes during the call. Is the child an effect of the cell, a session of its own, or both? | Effect, child session, owner |
| 7 | A reply has text and no code. Does this end the step or the turn? | Step, turn, end of work |
| 8 | A cell asks for an approval, and the user answers two days later, after a restart. Where did the wait live? | Approval, effect, cell thread |
| 9 | A compaction runs between two steps. Which unit does it belong to, and can the user interrupt it? | Step, turn, derived data |
| 10 | A cell yields after 10 s and continues, as in Codex. Can a new step run during that cell? | Cell, step, REPL |

| # | Answer | Where |
|---|---|---|
| 1 | The cell does not see the message, and it does not stop. The `Input` commits when it arrives, and the policy reads it at the next step. An `Abort` stops the cell. | 4.2 |
| 4 | No. A later cell sees the definitions of the earlier cells, and it gets `Not found: x`. A check on the 3.8.2 `ReplDriver` showed this. The check did not run on the 3.10 nightly. | F1 |
| 7 | In a chat turn, it ends the turn. In a program call, the `finisher` leaf makes the output. | 4.2 |

## 6. The next step

1. Settle the terms of section 4, and add the settled terms to a glossary.
2. Answer the scenarios of section 5, and correct this inventory where they show a gap.
3. For each kind of state, choose a way to model it. Only then compare architectures.
