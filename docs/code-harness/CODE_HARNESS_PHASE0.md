# Code harness, Phase 0: an embedded Scala REPL

This document is the full report of "Phase 0. Experiment" in `docs/code-harness/CODE_HARNESS.md`. The section
"Phase 0 results" of that document gives a summary. Section numbers such as 8.4 refer to
`docs/code-harness/CODE_HARNESS.md`.

The experiment embeds `dotty.tools.repl.ReplDriver` in a JVM, and it measures seven properties. The data answers
open question 1 of section 11: a REPL in the same JVM, or a forked JVM.

The code of the experiment is a separate sbt project in `tmp/repl-experiment/` of the main checkout. It is not
committed.

## Short answer

| Question | Answer | Data |
|---|---|---|
| 1. Persistent state | Yes | A `val`, a `def` and a `case class` of one cell are visible in later cells. The state stays after a compile error and after a failure at run time. |
| 2. Timeout | Yes, for loops in guest code | An infinite loop stopped at 2 s, less than 1 ms after the stop flag. The output before the stop came back. The REPL ran later cells after five stops. |
| 3. Output | Yes | Two REPLs on two threads printed 2,000 lines each, at the same time. Each buffer had its own lines only. There is no `System.out` swap and no lock. Diagnostics come back as text. |
| 4. Parallel | Yes | REPL 1 stopped at its 2 s timeout. REPL 2 ran a 4 s loop at the same time and ended normally. Each REPL loader has its own `StopRepl` class. |
| 5. Host API | Yes | The preamble takes the handlers out of a registry with a key for that REPL. After two preambles, the registry is empty. Two REPLs called `read` 5,000 times each, at the same time. Each REPL got only its own handler. |
| 6. Replay (8.4) | Yes | The replay of three recorded cells in a new REPL gave the same outputs, with 0 reads, 0 writes, 0 processes and 0 LM calls. Cell 4 then worked. |
| 7. Cost | See section 7 | First REPL in a JVM: 787 ms. Each later REPL: 85 ms and about 6 MiB of heap. A forked JVM: about 1 s and about 200 MiB of RSS for each REPL. |

The experiment also found three problems:

- The stop flag cannot stop a loop inside library code that does not call guest code. The thread uses one CPU core
  until the JVM ends.
- A guest that fills the heap makes the other REPLs fail. With `-Xmx4g`, it also stopped the host JVM in 4 of 4
  runs.
- A REPL keeps memory that grows with the square of the number of cells: 348 MiB after 600 cells. This is true in
  the same JVM and in a forked JVM.

The recommendation is a REPL in the same JVM, with the guards of the section "Recommendation".

## Setup

| Item | Value |
|---|---|
| Date | 2026-10-21 |
| Computer | Apple M4 Pro, 14 cores, 48 GiB of memory, macOS |
| JDK | OpenJDK 21.0.1, G1, `-Xmx4g -Xss4m` |
| Scala | `3.10.2-RC1-bin-20261015-88be2f5-NIGHTLY` and `org.scala-lang:scala3-repl_3` of the same version |
| REPL options | `-color:never -Xrepl-interrupt-instrumentation:true` |

Run all experiments, from the root of the main checkout:

```bash
cd tmp/repl-experiment && sbt run
```

Run some experiments (the names are `cost`, `growth`, `state`, `timeout`, `output`, `parallel`, `host`, `replay`,
`fork`, `oom` and `stuck`):

```bash
cd tmp/repl-experiment && sbt "run state timeout"
```

The file `tmp/repl-experiment/results-raw.txt` is the full output of the run that this document reports. The
program does all checks, so a new run makes a new report. The times change between runs, in some cases by a factor
of 2. The checks gave the same result in each run.

The nightly repository was not reachable on 2026-10-21, and the coursier cache has no `scaladoc_3`. So the
`build.sbt` of the experiment excludes `org.scala-lang:scaladoc_3`.

## How the experiment works

The paths are relative to `tmp/repl-experiment/`.

| File | Purpose |
|---|---|
| `src/main/scala/harness/guest/Api.scala` | The guest API: the traits `Files`, `Shell` and `Llm`, the record `Handlers`, the `Registry`, and the functions `read`, `write`, `exec` and `llmQuery` |
| `src/main/scala/dotty/tools/repl/ExpDriver.scala` | A `ReplDriver` with `redirectOutput = false`. It gives the REPL class loader and the stop flag of the REPL. |
| `src/main/scala/experiment/Sandbox.scala` | The parent class loader of each REPL. It shares `scala.*` and `harness.guest.*` with the host, and it hides the class resources, as atc does. |
| `src/main/scala/experiment/ReplSession.scala` | One REPL: its output buffer, its preamble, its `ExecutionContext`, and the run of a cell with a timeout |
| `src/main/scala/experiment/Effects.scala` | The handlers that record and replay (`Gate`), and the real effects with call counters (`RealWorld`) |
| `src/main/scala/experiment/StdRouting.scala` | The one-time forward of `System.out` and `System.err` to `Console.out` and `Console.err` |
| `src/main/scala/experiment/Experiments.scala` | The experiments and their checks |
| `src/main/scala/experiment/Main.scala` | The entry point, and `ForkProbe` (a child JVM for the cost of a forked REPL) |

A cell runs in these steps:

1. The host parses the cell with `ParseResult`.
2. A new worker thread sets `Console.withOut` and `Console.withErr` to the buffer of the REPL. Then it clears the
   stop flag of the REPL and calls the driver.
3. The host waits for the timeout. At the timeout, it sets the stop flag of that REPL and interrupts the worker.
   Then it waits up to 5 s for the worker to end.
4. After a stop, the host marks the wrapper object of the cell as invalid (`skipInvalidWrapper`, as atc does). The
   later cells keep all earlier definitions.

`ExpDriver` sets `redirectOutput` to `false`. With the default value `true`, `ReplDriver` calls `System.setOut`
and `System.setErr` around each run. That change is for the full JVM, so atc holds a lock for the full JVM around
each run. The experiment has no such lock.

In the `true` mode of `-Xrepl-interrupt-instrumentation`, the REPL loader makes an instrumented copy of each class
whose bytecode it can read through its parent. `Sandbox.SandboxLoader` hides the `.class` resources, so
`scala.Console` and `harness.guest.*` stay the same classes as in the host.

## Results

### 1. Persistent state

| Check | Result |
|---|---|
| A `val` of cell 1 (`val x = 21`) is visible in cell 2 | Pass. Cell 2 printed `y = 42`. |
| A `case class` and a `def` of cell 3 are visible in cell 4 | Pass |
| The state stays after a compile error (`val bad: Int = "text"`) | Pass |
| The state stays after a failure at run time (`val boom: Int = 1 / 0`) | Pass |

The eight cells took 38, 30, 60, 5, 275, 5, 8 and 4 ms. The first compile error took 275 ms, because the compiler
loads more classes for the first error message.

### 2. Timeout

The timeout was 2 s for the first cell and 1 s for the others. "Stop time" is the time from the stop flag to the
end of the worker thread. In all runs, the stop time was 0 to 3 ms.

| Cell | Stopped | Stop time |
|---|---|---|
| A top-level `while true` loop that prints two lines first | Yes | 0 ms |
| A loop inside a `def` | Yes | 0 ms |
| A deep recursion, `fib(90)` | Yes (the check at each method entry) | 1 ms |
| `Iterator.continually(1).foreach(_ => ())`: a library loop that calls a guest lambda | Yes | 0 ms |
| `Thread.sleep(600_000)` | Yes (`Thread.interrupt`) | 0 ms |
| `Iterator.from(0).size`: a library loop with no guest call | No (see "Problems") | not applicable |

- The output before the timeout came back: `start of cell` and `one million`.
- After the stop, the cell `println(before + 1)` printed `8`. `before` is a `val` of an earlier cell.
- After five stops, the REPL ran `println(before * 2)` and printed `14`.
- The output of the stopped `Thread.sleep` cell has the `InterruptedException` trace. The filter of the
  experiment removes only the `ThreadDeath` trace.

### 3. Output

| Check | Result |
|---|---|
| Two REPLs on two threads print 2,000 lines each. Each buffer has its own 2,000 lines and no line of the other REPL. | Pass. The two guest runs overlapped for 119 ms. |
| A type error comes back as text | Pass: `Found: ("text" : String)`, `Required: Int` |
| A syntax error comes back as text | Pass: `Line 1: an identifier expected, but '=' found` |
| A failure at run time comes back as text | Pass: `java.lang.ArithmeticException: / by zero` |
| A warning comes back as text | Pass: `method old is deprecated since 1: old` |
| A guest `System.out.println`, with no forward | Goes to the host stdout, not to the buffer |
| A guest `System.out.println` and `System.err.println`, with the one-time forward | Go to the buffer of the REPL |
| A `Future` on `ExecutionContext.global` in REPL A, then one in REPL B | Fail: all 50 lines of REPL B went into the buffer of REPL A |
| A `Future` on the `ExecutionContext` of each REPL (from the preamble), two REPLs at the same time | Pass: each buffer had its own 50 lines and 0 lines of the other REPL |

The guest `println` goes to `Console.out`, which has a value for each thread. So the worker thread of each cell
captures the output of its REPL with no lock.

The diagnostics go to the `PrintStream` of the driver. That stream is the buffer of the REPL, so the diagnostics
need no `System.out` swap. The outcome comes from `Parsed.reporter.hasErrors` (compile errors), from `SyntaxErrors`
(syntax errors), and from `Rendering.renderError` (failures at run time).

`System.out` is one value for the full JVM. The one-time forward (`StdRouting`) sets `System.out` and `System.err`
at the start of the JVM to streams that write to `Console.out` and `Console.err` of the current thread. It does
not change them again, so it needs no lock.

The global `ExecutionContext` sends output to the wrong REPL. `Console.out` is an `InheritableThreadLocal`, so a
thread of the global pool keeps the value of the thread that made it. The fix is an `ExecutionContext` for each
REPL. The preamble binds it as a `given`, and it runs each task in `Console.withOut` of its REPL. Guest code that
imports `ExecutionContext.Implicits.global` still has the problem.

### 4. Parallel REPLs

| Check | Result |
|---|---|
| Each REPL loader has its own `StopRepl` class, and the host has a third one | Pass |
| REPL 1 runs `while true do ()` with a 2 s timeout. REPL 2 runs a 4 s loop at the same time. | REPL 1 stopped after 2,005 ms. REPL 2 printed `P2 done after 4000 ms` and ended normally. |
| Both REPLs accept a later cell | Pass |
| 40 cells in each of 1, 4 and 8 REPLs at the same time give correct results | Pass |

The table gives the number of cells per second, with a compile in each cell. Rounds 1 and 2 warmed the JIT, and
rounds 3 and 4 measured the time:

| REPLs at the same time | Round 3 | Round 4 | Mean of rounds 3 and 4, relative to 1 REPL |
|---|---|---|---|
| 1 | 149 | 132 | 1.0 |
| 4 | 525 | 550 | 3.8 |
| 8 | 742 | 894 | 5.8 |

A fifth round sampled the thread states every 2 ms. With 4 REPLs, the cell threads were `RUNNABLE` in 366 of 374
samples. With 8 REPLs, they were `RUNNABLE` in 679 of 738 samples. Most other samples waited for a short lock on
the name table of the compiler (`dotty.tools.dotc.core.Names`): 52 samples, or 7%. In three earlier runs, this share
was 2% to 6%, and some samples also waited on the `JarFile` of the Scala library. So the compiler has short shared
locks, but no lock that makes the REPLs run one at a time.

### 5. Host API

The guest API is in the package `harness.guest`. The REPL loader shares this package with the host. The preamble
of each REPL has two cells:

```scala
import harness.guest.*
val hostHandlers: Handlers = Registry.take("<the key of this REPL>")
```

```scala
given Files = hostHandlers.files
given Shell = hostHandlers.shell
given Llm = hostHandlers.llm
given scala.concurrent.ExecutionContext = hostHandlers.ec
```

`Registry.take` removes the entry. So the handler of a REPL is in a `val` of that REPL only, and no global slot
selects a handler at call time. Guest code calls the functions directly: `read(path)`, `exec(command)`,
`llmQuery(prompt)`. A `using` parameter selects the handler.

| Check | Result |
|---|---|
| The registry has no entry after the two preambles | Pass: size 0 |
| Two REPLs on two threads call `read` 5,000 times each. Each REPL sees only its own handler. | Pass |
| Each host handler got its own 5,000 calls | Pass: 5,000 and 5,000 |
| The guest trait `harness.guest.Files` is the same class as in the host | Pass: the same identity hash code |
| `read(42)` is a compile error | Pass: `Found: (42 : Int)`, `Required: String` |

1,000,000 guest calls of `read`, with the handler work (a counter and a string), took 115 ns for each call.

### 6. Replay (section 8.4)

The workspace is a temporary folder with a `build.sbt` of three modules (`core`, `programs`, `docs`) and three test
files. The cells are the cells of section 8.4, with `find` in place of `rg`:

```scala
// Cell 1
val build = read("build.sbt")
val modules = "lazy val (\\w+)".r.findAllMatchIn(build).map(_.group(1)).toVector
// Cell 2
def testsOf(m: String): Vector[String] =
  exec(s"find modules/$m/src/test -type f").stdout.linesIterator.filter(_.nonEmpty).toVector
val counts = modules.map(m => m -> testsOf(m).size).toMap
// Cell 3
val summary = llmQuery(s"Summarize: ${counts.toList.sorted.mkString(", ")}")
write("summary.txt", summary)
println(summary)
// Cell 4, after the restart
println(counts.filter(_._2 == 0).keys.toList.sorted)
```

The steps:

1. A live REPL ran cells 1 to 3 with handlers that record. The journal got 6 effects: cell 1 `read`; cell 2
   `exec` three times; cell 3 `llmQuery` and `write`. The arguments and the results are text, 494 characters in
   total. Cell 4 printed `List(docs)`.
2. The files changed: `build.sbt` now has the modules `core` and `extra`, and `summary.txt` is deleted.
3. A new REPL ran cells 1 to 3 again with handlers that replay. Each guest call took the next record with the same
   operation and the same arguments, and gave the recorded result.
4. Then cell 4 ran live in the new REPL.

| Check | Result |
|---|---|
| The replay of each cell is ok, uses all its records, and gives the same output as the live run | Pass, for cells 1, 2 and 3 |
| The replay did no real effect | Pass: 0 reads, 0 writes, 0 processes, 0 LM calls |
| The replay did not write `summary.txt` again | Pass |
| Cell 4 works after the replay and gives the same output | Pass: `List(docs)` |
| The definitions come from the journal, not from the files now | Pass: `modules` is `Vector(core, programs, docs)`, and the disk has `core` and `extra` |
| A changed cell (it reads `other.sbt`) fails with a clear message and does no real effect | Pass: `No recorded effect for read(other.sbt)` |

The live run of cells 1 to 3 took 187 ms. The replay took 73 ms (32, 20 and 17 ms for each cell).

### 7. Cost

All numbers are for the heap after three full collections, unless the row says "RSS" (resident memory of the
process).

| Measurement | Value |
|---|---|
| The JVM before the first REPL | heap 2 MiB, metaspace 4 MiB, RSS 60 MiB |
| The first REPL in a JVM: make the driver, then run `1 + 1` | 787 ms (driver 318 ms, first cell 457 ms). Five runs: 756 to 817 ms. |
| The memory of the first REPL | heap +16 MiB, metaspace +34 MiB (the compiler classes), RSS +162 MiB |
| A second REPL in the same JVM | 85 ms (driver 19 ms, first cell 65 ms), heap +6 MiB, RSS +5 MiB. Five runs: 71 to 123 ms. |
| A cell in a warm REPL, small (`val a1 = 1 * 2`) | median 12 ms, 90th percentile 21 ms |
| A cell in a warm REPL, with a `case class` and collections | median 55 ms, 90th percentile 93 ms (37 to 39 ms median in other runs) |
| 4 REPLs that start at the same time on 4 threads | 83 ms wall time, heap +25 MiB |
| 8 more REPLs that start at the same time | 113 ms wall time, heap +48 MiB, RSS −6 MiB (the JVM used memory that it took before) |
| After all 14 REPLs are closed and out of scope | heap 16 MiB (start 2 MiB, with the REPLs 125 MiB); metaspace stays at 49 MiB |
| A forked JVM with one REPL: process start to the result of `1 + 1` | 905, 991 and 992 ms (median 991 ms). The JVM start is 34 to 42 ms of it. |
| The memory of a forked JVM with one REPL | heap 19 MiB, RSS 196 to 209 MiB |

The heap of one REPL grows with each cell (the part "7c" of `results-raw.txt`; cells with a `case class` and
collections):

| Cells | Heap | Growth in the last 50 cells | Median cell time |
|---|---|---|---|
| 50 | 48 MiB | 29 MiB | 21 ms |
| 100 | 60 MiB | 12 MiB | 17 ms |
| 200 | 93 MiB | 18 MiB | 16 ms |
| 300 | 138 MiB | 24 MiB | 15 ms |
| 400 | 195 MiB | 30 MiB | 20 ms |
| 500 | 265 MiB | 36 MiB | 16 ms |
| 600 | 348 MiB | 43 MiB | 18 ms |

The growth for each block of 50 cells increases by about 3 MiB from one block to the next. So the total grows with
the square of the number of cells. The cell time does not grow.

An earlier run of `growth` stopped at 300 cells and printed a class histogram. (With `-Dexp.histogram=1`, the
experiment prints the histogram at its end.) The histogram shows 244,000 `Contexts$FreshContext`, 92,000
`untpd$ImportSelector` and 46,000 `ImportInfo` objects. 46,000 is about 300 × 300 / 2: each new cell keeps one
import context for each earlier cell. A quadratic fit of the data from 100 to 600 cells gives about 0.8 GiB at
1,000 cells and about 2.7 GiB at 2,000 cells. These two values are an extrapolation, not a measurement.

### 8. Isolation

These limits come from the JVM, not from the REPL.

**A loop that the stop flag cannot reach.** The cell `Iterator.from(0).size` loops inside library classes. The REPL
shares these classes with the host, so they have no stop check, and the loop calls no guest code. After the stop
flag and an interrupt, the thread did not end in 5 s. It used 1,994 ms of CPU time in 2,000 ms, and it runs until
the JVM ends. JDK 21 cannot stop a thread from outside: `Thread.stop` throws `UnsupportedOperationException` since
JDK 20. The REPL accepted later cells while the old thread ran.

**A guest that fills the heap.** This experiment runs in a child JVM, because it can stop the host. REPL 1 keeps
arrays of 8 MiB in an `object` until the heap is full. Then REPL 2 tries to allocate 500,000 strings.

| Heap of the child JVM | Runs | The host JVM | REPL 2 while REPL 1 holds the heap | REPL 2 after REPL 1 is closed |
|---|---|---|---|---|
| `-Xmx1g` | 4 | survived in 4 of 4 | failed in 4 of 4 | worked in 4 of 4 |
| `-Xmx4g` | 4 | stopped in 4 of 4: `OutOfMemoryError` in the main thread | not applicable | not applicable |

In a forked JVM, `-Xmx` limits each REPL, and a kill of the process stops any loop. In one JVM, the REPLs share one
heap.

## Problems found

1. **A library loop with no guest call cannot be stopped** (section 8). The thread uses one CPU core until the JVM
   ends.
2. **A guest that fills the heap** makes the other REPLs fail, and it can stop the host (section 8).
3. **The global `ExecutionContext` sends output to the wrong REPL** (section 3). An `ExecutionContext` for each
   REPL fixes it, for guest code that uses the `given` of the preamble.
4. **A guest `System.out` goes to the host stdout** (section 3). The one-time forward of `System.out` fixes it.
5. **The memory of a REPL grows with the square of the number of cells** (section 7). This is a property of the
   REPL of the Scala compiler, so a forked JVM has it too.
6. **The timeout counts the compile time.** `ReplDriver.interpret` compiles and runs in one call, so the host
   cannot start the clock after the compile. atc has the same behavior.
7. **The REPL internals can change.** `Rendering` is `private[repl]`, so `ExpDriver` and `FlagRendering` are in
   the package `dotty.tools.repl`. The experiment also uses `redirectOutput`, `rendering.myClassLoader`,
   `State.copy` and `ReplBytecodeInstrumentation.setStopFlag`. A new nightly can change these names.
8. **A guest `System.exit` stops the host.** The experiment did not test this. atc rejects such calls with safe
   mode and a validator.

## Recommendation

Use a REPL in the same JVM for Phase 1. The data:

| | Same JVM | Forked JVM, one REPL in each |
|---|---|---|
| Time to the first result | 787 ms for the first REPL; 71 to 123 ms for each later REPL | about 1 s for each REPL |
| Memory of one more REPL | about 6 MiB of heap; one copy of the compiler classes for all REPLs | about 200 MiB of RSS |
| A guest API call | a direct method call (115 ns with the handler work) | a message to the host and back, for each call |
| A loop in guest code | stops in 0 to 3 ms | stops (a kill of the process) |
| A loop in library code with no guest call | does not stop | stops (a kill of the process) |
| A guest that fills the heap | the other REPLs fail; the host can stop | only that REPL fails |

The handlers must stay in the host process: the record and the replay of effects (8.3, 8.4), the `llmQuery` calls
through the `Predict` leaf, and the trace that the optimizers read (F4). In a forked JVM, each guest API call must
go to the host and back. The same JVM gives direct calls.

The optimizers need many rollouts at the same time. In the same JVM, 8 more REPLs cost about 50 MiB of heap and
start in about 0.1 s, and 8 REPLs compile about 6 times as many cells per second as one REPL. As forked JVMs, 8
REPLs cost about 1.6 GiB of RSS and about 1 s each (an estimate from the measurement of one forked JVM). A pool of
started JVMs can hide the start time, but not the memory.

Add these guards in Phase 1:

1. Keep `ScalaRepl` (F1) as the only contract to the REPL. Then a forked variant can replace the REPL in the same
   JVM with no change to the callers.
2. Set `redirectOutput` to `false`. Run each cell on its own thread in `Console.withOut` and `Console.withErr`.
   Forward `System.out` and `System.err` one time, at the start of the host.
3. Bind an `ExecutionContext` for each REPL in the preamble. Reject `ExecutionContext.global`, `new Thread`,
   `System.exit` and `Runtime` in guest code, with safe mode or a validator, as atc does.
4. When a worker thread does not end after a stop, mark the host process as unclean. Restart the host process at
   the next safe point, for example after a batch of rollouts. Rebuild each REPL with replay (8.4).
5. Monitor the heap. When the heap use is above a limit, stop and close the REPL whose cell thread allocated the
   most memory (`com.sun.management.ThreadMXBean.getThreadAllocatedBytes`). The experiment did not test this
   guard.
6. Limit the size of a REPL, for example to 300 cells. Then start a new REPL and replay the cells. This keeps the
   quadratic memory small.

Guards 4 and 6 need replay. The experiment shows that replay works with a small amount of code: one handler that
records and one that replays. This is data for open question 1 of section 8.7.

Change to a forked JVM if restarts for unclean processes occur often in real runs, or if the harness must run code
from a source that it does not trust.

## What the experiment did not test

- A journal on disk, and a replay in a new process. The journal of the experiment is in memory, but each record is
  text.
- The order of the results of `parallel` in a replay.
- Capture checking and safe mode in guest code.
- A forked JVM with a protocol for the guest API calls. The fork measurements cover only the start time and the
  memory.
- Guard 5 of the recommendation.
- Linux and Windows.
