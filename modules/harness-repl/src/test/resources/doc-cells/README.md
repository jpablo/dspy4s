# Doc cells

The files of this folder hold the Scala cells of the site pages in `site/docs/harness/`. A page includes a cell by
its path from the repository root, for example
`--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:read-a-file"`. Do not move this folder.

The suite `dspy4s.harness.repl.DocCellLaws` (test names `Doc cells: ...`) reads each `*.scala` file of this folder.
It runs the regions of one file in order, as cells of one `ScalaRepl`. A later cell can use a name from an earlier
cell of the same file.

Run the suite from the root of the repository:

```bash
sbt -batch "harnessRepl/testOnly dspy4s.harness.repl.DocCellLaws"
```

## The format

Line 1 is the mode comment. It names the mode of the REPL:

```scala
// mode: work
```

| Value | Mode | Capabilities |
|---|---|---|
| `work` | a work session of the terminal client (`WorkRuntime.workMode`) | `Files^`, `Shell^`, `Llm^`, `Store^` |
| `controller` | the controller of the terminal client (`Controller.mode`) | `Files`, `Llm^`, `Sessions^`, `Store^` |
| `full` | `Mode.full` | all seven capabilities at `Full` |
| `local` | `Mode.local` | `Mode.full` with no network |
| `read` | `Mode.readOnly` | `Files`, `Llm^`, `Sessions`, `Store^` |
| `closed` | `Mode.closed` | `Mode.full` with the bare `Sessions` |

Use `work` or `controller` for a cell that a reader can try in the terminal client. No session of the terminal client
has `Network` or `Children`.

Each cell is a region. A region name is kebab-case, and it is unique in its file:

```scala
// --8<-- [start:read-a-file]
val text = read("README.md")
println(text.linesIterator.size)
// --8<-- [end:read-a-file]
```

The marker lines are not part of the cell. Outside the regions, a file can have only empty lines and `//` comments.
Keep each cell short (3 to 12 lines).

## The outcome of a cell

- A cell must end with `CellOutcome.Ok`. A cell that calls `submit(` must end with `CellOutcome.Submitted`.
- For a cell that must not compile, put the line `// expect: compile-error` just before its start line. Then the cell
  must end with `CellOutcome.CompileError`. Use it to show what the mode or safe mode refuses.
- After a cell, the REPL must not need a restart (`needsRestart`). So do not show a top-level `val` with no type that
  safe mode refuses, for example `val xs = Array(1, 2, 3)`.

## The fakes

The REPL gets a fake handler for each capability. Each cell runs in-process, so no command runs and no model answers.

- `turn`: `FakeTurn`. `now()` gives `1000`, `input` gives no value, and `zoom(ref)` gives `the part <ref> of the
  memory`.
- files: `MemoryFiles`, in memory. It has `README.md` (5 lines), `build.sbt` and `src/main/scala/Main.scala`. A `write`
  or an `edit` changes the file, and a later `read` sees the change. An `edit` fails when the old text is not in the
  file one time. A `read` of a missing file fails.
- shell: `FakeShell`. `exec(command)` gives `the output of <command>`. `spawn` gives a new `JobId`, and each job
  ends at once with the exit code 0. `jobOutput(job)` gives `the output of <command>`.
- network: `FakeNetwork`. `httpGet(url)` gives `the body of <url>`.
- llm: `FakeLlm`. `llmQuery(prompt)` gives `the answer to <the first line of the prompt>`.
- store: `FakeStore`, in memory. A `load` sees each earlier `store` of the same cell file. The value goes in at once.
- sessions: `FakeSessions`. `sessions()` gives one idle session `s-1`. `start` gives `s-2`, then `s-3`. `tell`
  returns. `observe` and `zoom` give a text.
- children: `FakeChildren`. `rlm[String]` gives `the result of <task>`. Another type fails at run time.

The preamble is the preamble of a session of the terminal client: `submit(value: String)` and no input. Read
`DocCellLaws.scala` and the fakes in `ScalaReplLaws.scala` for the exact values.
