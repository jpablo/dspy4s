# dspy4s `harness-kernel`

`harness-kernel` is the kernel of the code harness. A session keeps a journal, a list of entries that only grows. The
state of the session is a fold of the journal, and pure functions decide each step. The kernel runs no code, calls no
LM, and reads no file. It uses [`harness-guest`](../harness-guest/README.md) and zio-blocks.

## Main concepts

- **The journal holds the truth.** Each change of a session is an `Entry`. A `Logged` line holds the entry with its
  `seq` and its `Meta`. The entries of one turn obey a small grammar:

  ```
  Turn ::= TurnStarted Step* TurnEnded
  Step ::= StepStarted [ Acted [ Cell ] ]
  Cell ::= ( EffectStarted | EffectDone )* Observed
  ```

  `Input`, `StopRequested`, `JobsStopRequested`, `Granted`, and `ScopeRecorded` can occur at any place.
- **The state is a fold.** `evolve(state, logged)` is the only place where the state changes. It is total and pure.
  `state(journal)` is `journal.foldLeft(SessionState.initial)(evolve)`.
- **The next piece of work is an unfold.** `next(state)` reads only the state. It gives the next piece of work: wait,
  call the policy, run a cell, or end the turn. Thus a session is an unfold of work and a fold of results.
- **A command becomes entries only through `decide`.** A client, a worker, or a person sends a `Command`.
  `decide(command, state)` gives the entries to commit, or a `Rejection`. It keeps each journal inside the grammar.
- **An intent before each effect.** A guest API call commits `EffectStarted` before it runs and `EffectDone` after it.
  After a crash, `repair` closes each open effect with `EffectResult.Unknown`. Nothing runs an effect with an unknown
  outcome again automatically.

```scala
val s0 = state(journal)
decide(Command.Send(RequestId("r1"), "Fix the test", Map.empty), s0) match
  case Right(entries) => // Input and TurnStarted: commit them, then next(...) gives CallPolicy(0, false)
  case Left(rejected) => // for example Rejection.SessionClosed
```

## Main types

### The journal

| Type | Description |
|------|-------------|
| `Entry` | The entries of a journal: `Activated`, `Input`, `TurnStarted`, `StepStarted`, `Acted`, `EffectStarted`, `EffectDone`, `Observed`, `StopRequested`, `JobsStopRequested`, `Granted`, `ScopeRecorded`, `TurnEnded`, and `Ended`. |
| `Logged` | One line of the journal: an entry with its `seq` and its `Meta`. |
| `Meta` | The data that the model never sees: the time, the `Source`, the `Taint`, and `required`. `required` tells a reader what to do with an entry that it cannot decode: stop the rebuild, or skip the entry. |
| `Source` / `Taint` | The sender of an entry (`User`, `Controller`, `Harness`, `Policy`, or `Child`), and `External` for a value from outside the session. |
| `EffectResult` | The outcome of a guest API call: `Value` (the `Json` for a replay and the text for a reader), `Error`, `NeedsApproval`, or `Unknown`. |
| `CellOutcome` / `TurnOutcome` / `SessionOutcome` | The outcome of a cell, of a turn, and of a session. |
| `SessionId` / `RequestId` / `EffectId` / `Versions` | The ids, and the versions that a replay needs. A repeat of a `RequestId` commits nothing. An `EffectId` holds the `seq` of the `Acted` entry of its cell and the number of the call in that cell. |
| `needsFlush` / `readOnlyOps` | The entries that must reach the disk before `commit` returns: an `Input`, an `Activated`, and an `EffectStarted` of an operation that is not read-only. |

### The state and the steps

| Type | Description |
|------|-------------|
| `SessionState` | The fold of the journal: the configuration, the position of the open turn, the open effects, the store, the inputs, the request ids, the grants, the pending calls, and the jobs. |
| `Position` | Where the open turn is: `NoTurn`, `BeforeStep`, `InPolicy`, `InCell`, `Answered`, or `Finished`. |
| `evolve` / `state` / `forked` | One step of the fold, the fold of a journal, and the state of a fork at the end of a prefix of its parent. A fork has no grants, no pending calls, and no jobs, and it keeps the inputs. |
| `Next` / `next` | The next piece of work: `Wait`, `CallPolicy`, `RunCell`, or `End`. |
| `Command` / `Rejection` / `decide` | What a client asks, why `decide` refuses it, and the entries that a command commits. |
| `closing` / `repair` | The entries that close the open parts of a turn, and the entries after a crash. A stop and a crash close a turn with the same entries. |
| `Fold[E, S, A]` | A view of a journal as a fold with its own state. `map` changes the result, and `zip` runs two folds in one pass. |

### The configuration, the guard, and the reports

| Type | Description |
|------|-------------|
| `SessionConfig` | One type for the four kinds of session: the controller, a work session, a child, and a rollout. It holds the turn kind, the step limit, the mode and its ceiling, the strategy, the scope, the owner, the guard, the preamble, the model, the goal, and more names of variables. It holds no secret, because `Activated` holds it. |
| `Owner` / `TurnKind` / `ModelChoice` | Who waits for the session, `Chat` or `Program`, and the model with the options of its provider. |
| `Guard` / `VerdictRule` / `Verdict` | The rules of a session. `Guard.verdict` gives the join of the verdicts of the rules that match a call (`Allow < Ask < Deny`), or `Allow` when no rule matches. `and` is a monoid, and `Guard.allowAll` is its identity. |
| `GuardRule` / `ArgMatch` | A rule for the calls of one operation. `ArgMatch` is `Exact`, `Prefix` (a path rule or a text rule), or `AnyArgs`. |
| `GrantLifetime` / `ActiveGrant` | A grant from a person lasts `Once`, for a `Turn`, or for the `Session`. |
| `guardCheck` / `GuardResult` | The check of one call: `Run`, `NeedsPerson` with the narrowest rule, or `Refused`. |
| `goalCheckDue` | True after a turn, when no input waits and no call is pending. |
| `reports` / `missing` | The reports of a session to its owner, as a pure function of the journal, and the reports that the owner does not have yet. |

## Design notes

- **A chat turn and a program call end in different ways.** At the step limit, a `Chat` turn gets one more policy
  call, which must answer. A `Program` call ends with `Exhausted`, and the caller runs the finisher.
- **`decide` checks the place of each entry of a `Commit`.** An `EffectStarted` needs an `n` above the `n` of each
  call of its cell, so one id names one call. An `EffectDone` of an effect that is already closed adds nothing,
  because an interrupt does not wait for a commit in progress.
- **A stop is an entry.** `Abort` commits `StopRequested`, and then `next` ends the turn with `Aborted`.
- **A grant changes only `Ask`.** A session with no person (the owner `Optimizer` or `Cell`) never gets
  `NeedsPerson`, and the call is refused. A `TurnStarted` empties the pending calls, so the model asks again if it
  still needs the call. A grant that answers a pending call also commits an `Input` with the text of `approvedText`.
  The harness writes that text, so a client cannot put words in it.
- **A path rule reads only the text.** The kernel is pure, so the normal form of a path does not read the file system.
  A relative path never matches a path rule, and a symbolic link goes around it. Thus the handler of a file call must
  put absolute normal paths in the arguments of the call.
- **A text rule ends at a word.** `git push` matches `git push origin` and `git push;x`. It does not match
  `git pushx`, `git push-x`, or `git pull`.
- **A second report changes nothing.** The request id of a report holds the session and the `seq` of its entry. Thus
  a second send has the same id, and `decide` commits nothing for it.
- **The initial state has the least authority.** Its mode is `None` for each capability, and the session is closed.
  The first entry of a journal is an `Activated` with the real configuration.

## Source layout

| File | Contents |
|------|----------|
| `Entry.scala` | `Entry`, `Logged`, `Meta`, `Source`, `Taint`, the outcomes, `JobSelector`, `readOnlyOps`, and `needsFlush` |
| `Ids.scala` | `SessionId`, `RequestId`, `EffectId`, and `Versions` |
| `SessionState.scala` | `SessionState`, `Position`, `evolve`, `state`, and `forked` |
| `SessionConfig.scala` | `SessionConfig`, `Owner`, `TurnKind`, and `ModelChoice` |
| `Command.scala` | `Command` and `Rejection` |
| `Decide.scala` | `decide`, `approvedText`, and `sourceOf` |
| `Next.scala` | `Next` and `next` |
| `Repair.scala` | `closing` and `repair` |
| `Guard.scala` | `Guard`, `GuardRule`, `ArgMatch`, `Verdict`, and `GrantLifetime` |
| `Approvals.scala` | `guardCheck`, `GuardResult`, and `goalCheckDue` |
| `Reports.scala` | `reports` and `missing` |
| `Fold.scala` | `Fold` |

## Tests

Run `sbt harnessKernel/test` from the root of the repository. The laws of section 4.7 are ScalaCheck properties.
`Schedules` makes random runs: a host with no threads sends random commands and random worker commits through
`decide`, and sometimes it crashes or loses power. The suites check the fold, the store, the inputs, the effects, the
repair, and the flush. They also check the grants, the pending calls, the reports, and the tables of `next` and
`decide`.

`src/test/mutants.toml` has 27 mutants, and each one breaks one rule of the kernel. This command applies each mutant
alone, runs the laws, and restores the code. At least one law must fail for each mutant.

```bash
python3 scripts/mutants/mutants.py modules/harness-kernel/src/test/mutants.toml
```

## Relation to the design

This module is section [4](../../docs/code-harness/CODE_HARNESS_DESIGN.md#4-the-kernel) of the design, with the
guard of section [5.5](../../docs/code-harness/CODE_HARNESS_DESIGN.md#55-approvals-a-cell-never-waits-for-a-person)
and the reports of section [8.6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#86-messages-between-sessions). A
comment with "A proposal for the review" or "For the review" marks a difference from the design, or an addition.
