# dspy4s `harness-repl`

`harness-repl` runs the cells of a session in a Scala 3 REPL. A REPL compiles each cell with capture checking and safe
mode, and it binds the givens of the mode of the session. A cell gives a `CellOutcome` of
[`harness-kernel`](../harness-kernel/README.md). It uses [`harness-guest`](../harness-guest/README.md) and the Scala
compiler (`scala3-repl`). It is the only harness module that adds the compiler as a dependency.

## Main concepts

- **One REPL for each activation, in the same JVM.** `ScalaRepl` wraps a `ReplDriver` (`HarnessDriver`). Many REPLs
  can run in one JVM. Each cell runs on its own thread with `Console.withOut`, so a REPL does not change `System.out`
  of the JVM.
- **The mode decides what compiles.** A new REPL first runs its own code: the imports, a given for each capability
  that the mode gives, and `submitValue`. Then it runs `import language.experimental.safe` and the preamble of the
  session, with its types, `submit`, and a `val` for each input. With `Full`, a capability is a given of type `T^`.
  With `Read`, `Files`, `Store`, and `Sessions` are givens of the bare type, and the other capabilities are absent.
- **`submit` ends a cell.** `submit` throws a `SubmitSignal`, and the cell ends with `CellOutcome.Submitted` and the
  value as `Json`. After `submit`, each later guest API call of the cell fails. A cell that ends by `submit` defines no
  name.
- **The clock of a cell counts only guest code.** A gate stops the clock during each guest API call. The cell timeout
  applies to the rest of the time, with the compile.
- **The protections of Phase 0.** A REPL has these protections:
  - the stop flag at the timeout, with the interrupt instrumentation of the compiler
  - a catch around the run
  - an invalid wrapper for a cell that does not complete, so a later cell sees only the definitions from before it
  - the `unclean` flag for a thread that does not stop
  - the heap guard, and the cell limit
- **The note of lost names.** A new REPL does not have the definitions of the old one, for example after a crash, a
  reopen, a change of mode, or a full REPL. `lostNames(journal)` gives each name that a completed cell defined, with
  that cell. It reads only the journal, so the note is the same after each of these events.

```scala
// A mode that reads the files and gives nothing else. `turn` and `files` are handlers of the host.
val none = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)
val repl = ScalaRepl(none.copy(files = Access.Read), Bindings(turn, files = Some(files)))
try
  repl.run("""val text = read("README.md")""") // the outcome is CellOutcome.Ok
  repl.run("""println(text.length)""")         // a later cell sees `text`
  repl.run("""write("x.txt", text)""")         // CellOutcome.CompileError: the mode gives no Files^
finally repl.close()
```

## Main types

| Type | Description |
|------|-------------|
| `ScalaRepl` | One REPL. `run(code)` gives a `CellRun`. `unclean`, `outOfMemory`, and `full` tell the host to start a new REPL. The parameters are the mode, the bindings, the preamble, the cell timeout (default 30 s), the cell limit (default 300), and `secondTry`. |
| `Bindings` | The handlers behind the givens: the `Turn`, and an optional handler for each capability. A new `ScalaRepl` fails when the mode gives a capability that has no handler. |
| `CellRun` | The output of a cell, at most 4 MiB, and its `CellOutcome`. |
| `HeapGuard` | A daemon thread that checks the heap each 100 ms. When a heap pool is above 85% after its last collection, the guard stops the cell that runs and that allocated the most memory, and that REPL closes. The other REPLs of the JVM go on. |
| `HostBindings` | The registry of the bindings of each REPL, by a key. The first code of a REPL takes its bindings from it, and `submit` records its value in it. |
| `SubmitSignal` | The signal of `submit`. It is a `ControlThrowable`, so `NonFatal` and `Try` do not catch it. |
| `topLevelNames` / `lostNames` | The top-level names of a cell, from the parser only, and the note of lost names. |
| `hint` | A hint after a compile error that is hard for a model to read, for example a top-level `var`, a capability in a place that must be pure, or a derivation of `Data` that goes too deep. |
| `HarnessDriver` / `FlagRendering` | The `ReplDriver` of the harness. `FlagRendering` records whether an evaluation threw. |

## Design notes

- **A second try for a capture error.** The capture checker can reject a cell for the capture set of the enclosing
  object. Then the REPL runs the cell again in two parts: the type definitions first, then the rest. A failed second
  part also hides the first part.
- **The class loader shares the host classes.** `SandboxLoader` gives the packages `scala.`, `dspy4s.harness.`, and
  `zio.blocks.` from the class loader of the host. It hides the class resources. With the interrupt instrumentation,
  the REPL loader makes an instrumented copy of each class whose bytecode it can read. The shared classes, for example
  the registry of the bindings, must stay the classes of the host.
- **The output of each REPL is its own.** `StdRouting` sets `System.out` and `System.err` one time to streams that
  write to `Console.out` and `Console.err` of the current thread. Thus a guest `System.out.println` goes to the buffer
  of its REPL, with no lock.
- **The compiler options.** A cell compiles with `-language:experimental.captureChecking`,
  `-language:experimental.modularity`, `-Yexplicit-nulls`, and `-Xrepl-interrupt-instrumentation:true`. The class path
  of a cell holds the Scala library, the guest API, the JSON of zio-blocks, and the host bindings.
- **A full REPL.** The memory of a REPL grows with the square of its cells. After the cell limit, `full` is true. The
  host then starts a new REPL with the preamble, the store, and the note of lost names.
- **`HarnessDriver` is in the package of the REPL.** `Rendering` is `private[repl]`, so the driver is in
  `dotty.tools.repl`. Phase 0 tested this driver.

## Source layout

| File | Contents |
|------|----------|
| `dotty/tools/repl/HarnessDriver.scala` | `HarnessDriver` and `FlagRendering` |
| `dspy4s/harness/repl/ScalaRepl.scala` | `ScalaRepl`, `Bindings`, `CellRun`, `HostBindings`, `SubmitSignal`, the class loader (`SandboxLoader`), and the streams of the output (`StdRouting`) |
| `dspy4s/harness/repl/Guards.scala` | the gate of the guest API calls, the gated handlers, and `HeapGuard` |
| `dspy4s/harness/repl/Names.scala` | `topLevelNames` and `lostNames` |
| `dspy4s/harness/repl/Hints.scala` | `hint` |

## Tests

Run `sbt harnessRepl/test` from the root of the repository. `ScalaReplLaws` runs real cells. It checks the givens of
each mode, the types of a preamble, `submit`, and the definitions that stay. It also checks the clock of a cell, the
second try, and the heap guard. `ReplLaws` checks the top-level names, the note of lost names, and the hints. The tests do not run in
parallel, because the heap guard is one for the JVM.

## Relation to the design

This module is section [6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#6-the-repl) of the design. The protections
come from the Phase 0 experiment, in [CODE_HARNESS_PHASE0.md](../../docs/code-harness/CODE_HARNESS_PHASE0.md).
