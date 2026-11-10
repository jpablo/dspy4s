# dspy4s `harness`

`harness` runs one task with the code harness, as a dspy4s `Module[I, O]`. At each step, a model writes a text and the
code of one Scala cell. The cell runs in a REPL, and the commands of the cell run in an OS sandbox. The result comes
back through `submit`. A `Harness` is a dspy4s module, so the optimizers of dspy4s, for example GEPA, take it. It uses
the other harness modules, and the dspy4s modules [`programs`](../programs/README.md) and
[`adapters`](../adapters/README.md).

```scala
final case class Question(question: String) derives Schema
final case class Answer(answer: String) derives Schema

val signature = Signature.derived[Question, Answer]("qa", "Answer the question.")
val env = HarnessEnv(
  workspace = Some(Path.of("my-project")),
  home      = Path.of(sys.props("user.home")),
  hosts     = HostList.empty,
  backend   = Backends.detect(),
  secrets   = Secrets.known(sys.env, Map.empty)
)
val harness = Harness.forTask(signature, env)

// In a RuntimeContext with an LM and an adapter:
harness(Question("What is 6 times 7?")).map(_.output) // for example Right(Answer("42"))
```

## Main concepts

- **One call is one turn.** `forward` copies the workspace into a private folder and starts a journal in memory. It
  sends an `Input` with the values of the input fields, and `drive` runs the turn to its end. The last `TurnEnded`
  gives the result:
  - `Submitted` gives the output through `Data[O]`.
  - `Replied` and `Exhausted` run the `finisher` leaf, which writes the output from the transcript of the steps.
  - `Failed`, `Aborted`, and `Crashed` give a `DspyError`.

  At the end of the call, `env.journals` gets the journal, and the harness deletes the copy and the private folders.
- **The worker holds no decision.** `drive(journal, effects)` asks `next` of the kernel what comes, runs it through
  `TurnEffects`, and commits the result. A test gives a script as `TurnEffects`. A harness gives `HarnessEffects`,
  with its policy leaf and its REPL.
- **The journal records each guest API call.** `recorded(bindings, recorder)` wraps each handler. `Recorder` commits
  `EffectStarted` before each call and `EffectDone` after it, with the value as `Json` for a replay and as text for a
  reader. A call that the journal refuses does not run, for example after a stop.
- **Two leaves.** The policy (`Predict[PolicyInput, PolicyStep]`) gives the text and the code of each step. The
  finisher (`Predict[FinishInput, O]`) gives the output when the turn ends with no `submit`. `OptimizableStructure`
  gives these two leaves, so an optimizer can change their instructions.
- **A context strategy.** A `ContextStrategy` projects the journal into a budget of characters for each policy call.
  This phase has the `Window` strategy: the last steps of the open turn that fit the budget, oldest first.
- **The mask of secrets.** `MemoryJournal` masks each known secret value in each string of an entry before the entry
  joins the log. The mask is the text `«secret:NAME»`.

## Main types

### The harness

| Type | Description |
|------|-------------|
| `Harness[I, O]` | The module. `Harness.forTask(signature, env, limits)` makes one with the first leaves and the window strategy. It needs `Data[I]` and `Data[O]`, and a case class has both with no other code. |
| `HarnessEnv` | What an optimizer must not change: the workspace, the home folder (for the secret paths), the host list, the backend, the secrets, the mode, and the function that gets the journal of each call. The default mode `HarnessEnv.taskMode` gives `Files^`, `Shell^`, and `Store^`. |
| `HarnessLimits` | The limits of a call: the steps (default 10), the budget in characters (40 000), the cell timeout (30 s), the time limit of `exec` (600 s), and the longest observation (4 000 characters). |
| `PolicyInput` / `PolicyStep` | The input and the output of the policy leaf. The API reference is an input field, so an optimizer cannot change it. The earlier steps are a `History`, so the adapter sends them as earlier turns of the conversation. |
| `FinishInput` | The input of the finisher leaf: the task, and the steps of the call as text. |
| `HarnessGivens` | The `OptimizableStructure` and the `ProgramRunner` of a `Harness`. The companion of `Harness` extends this trait, so the givens are in the implicit scope of each `Harness`. |

### The turn and the journal

| Type | Description |
|------|-------------|
| `drive` / `TurnEffects` | The loop of the worker, and the effects of a turn: `callPolicy` and `runCell`. |
| `PolicyReply` / `CellResult` | The reply of the policy for one step, and the result of one cell. |
| `HarnessEffects` | The `TurnEffects` of a harness. `callPolicy` projects the journal and calls the policy leaf. `runCell` runs the code in the REPL. |
| `Journal` / `MemoryJournal` | A journal: the committed entries and their fold. `MemoryJournal` is the journal in memory of one call. |

### The handlers

| Type | Description |
|------|-------------|
| `Recorder` / `recorded` | The record of each guest API call in the journal. |
| `LocalFiles` | The `Files` handler on the real files of the copy, with the rules of the profile of the mode. |
| `SnapshotStore` | The store of a cell: the store at the start of the cell, and a buffer of its writes. |
| `JournalTurn` | The `Turn` of a session: the clock, and the input values from `SessionState.inputs`. |
| `Secrets` | The known secret values by name, and the mask. `Secrets.known(env, headers)` takes the values of the secret-like variables and of the headers. |

### The context

| Type | Description |
|------|-------------|
| `ContextStrategy` / `Derivation` | A strategy: derived data that an update can change, and a pure projection of the journal and the derived data into a budget. |
| `Projection` / `Status` | What a strategy gives for one step (the status, the last observation, and the earlier steps), and the status section. |
| `Window` | The window strategy, and `Window.cut`, which cuts a long text and adds a note of the rest. |

## Design notes

- **No writer thread.** A call needs no writer and no thread other than the cell thread. `MemoryJournal.run` holds a
  lock, because a cell thread and a stop can call it at the same time.
- **The store changes only when a cell completes.** The writes of a cell go into its `Observed` entry. A cell that
  did not complete has no writes.
- **Paths in the arguments are absolute.** The record of a file call holds the absolute normal path, so a path rule
  of a guard can match it.
- **The budget counts characters, not tokens.** Thus the projection needs no tokenizer. `Window` cuts the last
  observation to half of the budget. It cuts the observation, the text, and the code of each earlier step to an eighth.
  The newest steps that fit the rest of the budget stay in the history.
- **A REPL that is not safe stops the turn.** After each cell, `runCell` reads `unclean`, `outOfMemory`, and `full` of
  the REPL. When one is true, it closes the recorder and commits a stop. Thus a thread of the old cell reaches no
  handler, and the error of `Aborted` gives the reason.
- **No code is no cell.** A code field that is empty, `null`, or `None` holds no code, because the chat adapter keeps
  the text of an empty field.
- **The copy of the workspace.** The copy keeps each symbolic link as a link. It leaves out each file that is not a
  regular file, a folder, or a link, for example a named pipe, because the open of a named pipe can wait with no limit.
- **Links made by a command.** A command can make links, and the JVM is not in the sandbox. Thus `LocalFiles` refuses
  a path with a link to a place that does not exist, and a file with more than one hard link. A read and a write do
  not follow a link at the last part of the path.
- **The journal of a call is always one turn.** An exception during a call closes the open turn with a stop, the
  entries of a repair, and `TurnEnded(Crashed)`.
- **The mask is a second guard.** It does not find a value that a command cuts or encodes. A value shorter than 8
  characters is not a known value, because its mask would change common text. A value with « or », and a value that
  is a part of a mask, are not known values either.
- **The limits of phase 4.** A call is a `Program` turn with the owner `Optimizer` and the guard `Guard.allowAll`.
  `recorded` has a record only for the files, the shell, the store, and the turn, so a handler of another capability
  is an error. The leaves for `llmQuery` and for the goal of a session come in phases 5 and 7.

## Source layout

| File | Contents |
|------|----------|
| `Harness.scala` | `Harness`, `forTask`, the copy of the workspace, the task text, and the transcript |
| `HarnessConfig.scala` | `HarnessEnv`, `HarnessLimits`, and `FinishInput` |
| `HarnessGivens.scala` | `HarnessGivens` |
| `HarnessEffects.scala` | `HarnessEffects` |
| `Worker.scala` | `drive`, `TurnEffects`, `Journal`, `PolicyReply`, and `CellResult` |
| `MemoryJournal.scala` | `MemoryJournal` |
| `Handlers.scala` | `Recorder`, `recorded`, `LocalFiles`, `SnapshotStore`, and `JournalTurn` |
| `Context.scala` | `PolicyInput`, `PolicyStep`, `Projection`, `Derivation`, `ContextStrategy`, and `Status` |
| `Window.scala` | `Window` |
| `Secrets.scala` | `Secrets` |

## Tests

Run `sbt harness/test` from the root of the repository. `HarnessLaws` runs a harness with a scripted model, a real REPL,
and the sandbox of the host. `DriveLaws`, `JournalLaws`, `HandlerLaws`, `WindowLaws`, and `SecretLaws` check the laws of
each part. `StructureLaws` checks the leaves, and that a harness has the `OptimizableStructure` and the
`ProgramRunner` that each optimizer needs. The tests fork a JVM and do not run in parallel, because the heap guard of
the REPL is one for the JVM.

`src/test/mutants.toml` has 29 mutants, and each one breaks one rule of the harness. This command applies each mutant
alone, runs the laws, and restores the code. At least one law must fail for each mutant.

```bash
python3 scripts/mutants/mutants.py modules/harness/src/test/mutants.toml
```

## Relation to the design

This module is phase 4 of the plan in section [15](../../docs/code-harness/CODE_HARNESS_DESIGN.md#15-the-plan). It
follows sections [4.6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#46-the-worker) (the worker),
[5.3](../../docs/code-harness/CODE_HARNESS_DESIGN.md#53-handlers) (the handlers),
[5.6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#56-secrets-and-taint) (the mask),
[7](../../docs/code-harness/CODE_HARNESS_DESIGN.md#7-the-context) (the context), and
[9](../../docs/code-harness/CODE_HARNESS_DESIGN.md#9-programs-and-optimization) (programs and optimization).
