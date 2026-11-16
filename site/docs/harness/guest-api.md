# The guest API

A cell is the Scala 3 code of one step of the model. The cell acts only through the functions of the guest API. With
them, it reads and writes files, runs commands, asks a model, and keeps values in the store. The harness compiles each cell in a
REPL with capture checking and safe mode.

Each function takes its capability as a `using` parameter. The REPL binds a given only for each capability that the
mode of the session gives. Thus a call that the mode does not give does not compile.

## The capabilities and the calls

There are seven capabilities: `Files`, `Shell`, `Network`, `Llm`, `Store`, `Sessions` and `Children`. The bare type of
a capability can only read. The type `T^` can also change something. For example, the bare `Files` gives `read`, and
`Files^` also gives `write` and `edit`. Five calls need no capability, and each cell can use them.

| Call | Needs | What it does |
|---|---|---|
| `println(value: Any): Unit` | nothing | Prints to the output of the cell. The next step sees this output. |
| `now(): Long` | nothing | Gives the milliseconds since the epoch. The journal records the value. |
| `input[A: Data](name: String): A` | nothing | Gives the value of an input field of the session. The sessions of the terminal client have no input field. |
| `submit(value: String): Nothing` | nothing | Ends the cell and the turn with the value. See [submit](#submit). |
| `zoom(ref: String): String` | nothing | Gives a part of the memory of this session. See [Memory](memory.md). |
| `read(path: String): String` | `Files` | Gives the text of a file in UTF-8. A path is relative to the folder of the session. A file that does not exist, a path outside the folder, or a closed file such as `.env` gives an error. |
| `write(path: String, content: String): Unit` | `Files^` | Replaces the whole file, and makes the folders of the path. A read-only part such as `.git` gives an error. |
| `edit(path: String, oldText: String, newText: String): Unit` | `Files^` | Replaces the one place of `oldText`. No place, or more than one place, gives an error, and the file does not change. |
| `exec(command: String): String` | `Shell^`, `Files^` | Runs the command with `/bin/sh -c` in the folder of the session, waits for its end, and gives stdout and stderr as one text. |
| `spawn(command: String, report: Boolean = false): JobId` | `Shell^`, `Files^` | Starts a job, and gives its id at once. With `report = true`, the end of the job sends a message to the session. |
| `jobOutput(job: JobId): String` | `Shell^` | Gives the output of the job until now. A very long output keeps only its last part. |
| `waitJob(job: JobId, maxSeconds: Int): JobStatus` | `Shell^` | Waits until the job ends or `maxSeconds` pass. The status is `Running`, `Exited(code)`, `Stopped` or `Lost`. |
| `stopJob(job: JobId): Unit` | `Shell^` | Stops the job. Its status is then `Stopped`. |
| `httpGet(url: String): String` | `Network^` | Gives the body of the answer to a GET request. The host list of the session can refuse the host. |
| `llmQuery(prompt: String): String` | `Llm^` | Gives the answer of a model. The model sees only the prompt, so put each fact that it needs into the prompt. |
| `llmQueryBatched(prompts: Vector[String]): Vector[String]` | `Llm^` | Gives the answers in the order of the prompts. The host runs some prompts at the same time. |
| `keys(): Vector[String]` | `Store` | Gives the keys of the store of this session, in order. |
| `load[A: Data](key: String): Option[A]` | `Store` | Gives the value of the key, or `None`. A value that does not fit the type `A` gives an error. |
| `store[A: Data](key: String, value: A): Unit` | `Store^` | Keeps the value under the key. The store survives a restart. |
| `sessions(): Vector[SessionCard]` | `Sessions` | See [Sessions and the controller](sessions.md). |
| `observe(session: SessionRef, question: String): String` | `Sessions`, `Llm^` | See [Sessions and the controller](sessions.md). |
| `zoom(session: SessionRef, ref: String): String` | `Sessions` | See [Sessions and the controller](sessions.md). |
| `tell(session: SessionRef, message: String): Unit` | `Sessions^` | See [Sessions and the controller](sessions.md). |
| `start(goal: String, scope: Scope, strategy: StrategyName): SessionRef` | `Sessions^` | See [Sessions and the controller](sessions.md). |
| `rlm[A: Data](task: String, inputs: Map[String, String] = Map.empty): A` | `Children^` | Runs a child with the task, and gives the value of its `submit`. |
| `rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])]): Vector[A]` | `Children^` | Runs one child for each task, some at the same time, and gives the values in the order of the tasks. |

A cell of a work session reads a file and counts its lines:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:read-and-count"
```

A later cell can use the names of an earlier cell, for example `lines`. This cell writes a file and changes one
place:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:write-and-edit"
```

Use `exec` for a short command. Use `spawn` for a long command, because a job continues after its cell ends:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:run-a-job"
```

For the limits of the commands and the files, see [The sandbox and approvals](sandbox.md).

A batch of queries is one call. Give the text that the model needs inside each prompt:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:ask-a-model"
```

## Modes

A mode gives each capability one of three levels: `None`, `Read` or `Full`. `Full` gives `T^`. `Read` gives the bare
type, but only for `Files`, `Store` and `Sessions`. For `Shell`, `Network`, `Llm` and `Children`, `Read` gives
nothing.

| Capability | Work session | Controller | `Mode.full` | `Mode.readOnly` |
|---|---|---|---|---|
| `Files` | `Full` | `Read` | `Full` | `Read` |
| `Shell` | `Full` | `None` | `Full` | `None` |
| `Network` | `None` | `None` | `Full` | `None` |
| `Llm` | `Full` | `Full` | `Full` | `Full` |
| `Store` | `Full` | `Full` | `Full` | `Full` |
| `Sessions` | `None` | `Full` | `Full` | `Read` |
| `Children` | `None` | `None` | `Full` | `None` |

The terminal client starts a work session and the controller with the modes of the first two columns. Thus no session
of the terminal client can call `httpGet`, `rlm` or `rlmBatched`. In a work session, `httpGet` gives this error:

```text
No given instance of type dspy4s.harness.guest.Network^ was found for parameter n of method httpGet in object api
```

The model gets an API reference: the parts of the source of the guest API that compile in its mode. The reference
starts with these two lines:

```text
// The guest API. A cell is Scala 3 with capture checking and safe mode.
// The reference shows only the calls that the mode of this session gives.
```

## Safe mode and capture checking

Safe mode limits the code that a cell can call. A cell can call the guest API and the parts of the Scala library that
safe mode allows. Examples are `Vector`, `Map`, `Option`, `scala.util.Try` and `java.time.Instant`. A cell cannot
make a thread or an executor, or stop the JVM. It reads the files and the environment only through the guest API.
These calls do not compile:

- `new Thread(...)` and `scala.concurrent.ExecutionContext.global`
- `System.exit`, `System.getenv` and `java.nio.file.Files`
- `System.currentTimeMillis` (use `now()`)
- `asInstanceOf` and `sys.error`

The error names the definition, for example
`Cannot refer to class Thread in package java.lang from safe code since it is neither compiled in safe mode nor tagged with @assumedSafe`.
For some errors, the harness adds a hint line. This cell gets the hint of `sys.error`:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:refused-by-safe-mode"
```

```text
-- Error: ----------------------------------------------------------------------
1 |sys.error("the build failed")
  |^^^^^^^^^
  |Cannot refer to method error in package object sys from safe code since it is neither compiled in safe mode nor tagged with @assumedSafe
1 error found
Hint: Safe mode refuses sys.error. To fail the cell with a message, use throw IllegalStateException("the reason").
```

Capture checking gives the two levels of a capability: the bare type and `T^`. It also refuses a top-level `var`,
and a capability in a place that must be pure. Each of these errors has a hint. In safe mode, a top-level `val` of a new `Array` needs an
explicit type. After that error, the harness starts a new REPL for the next cell.

## The store and Data

A value in the store needs a `Data[A]`. `Data` exists for the numbers, `String`, `Char`, `Boolean`, `Unit`, `Instant`,
`Duration`, `Vector`, `List`, `Seq`, `IndexedSeq`, `Set`, `Map`, the tuples, `Option` and `Either`. A case class or an
enum gets a `Data` with no `derives` clause. For an `Array` or a `Range`, use `.toVector`. A `JobId` and a
`SessionRef` are plain data, so the store can hold them.

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:store-a-value"
```

The value goes into the store only when the cell completes. A failed cell changes nothing in the store. The JSON of a
value holds no type name, so a new type with the same fields reads the value in a later turn:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:load-a-value"
```

## submit

`submit` comes from the preamble of the session. In a session of the terminal client, it is
`submit(value: String): Nothing`. In a program call, `submit` takes the fields of the output type. A cell that calls
`submit` ends at once, and the turn ends with the value. After `submit`, each later guest API call of the cell fails. A
cell that ends by `submit` defines no name.

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/guest-api.scala:submit-a-result"
```

## The limits of a cell

| Limit | Value | What happens |
|---|---|---|
| The time of a cell | 30 s | The clock counts the compile and the guest code. It stops during each guest API call. At the limit, the cell stops with `The cell stopped after 30000 ms.` |
| The cells of one REPL | 300 | The harness starts a new REPL with the preamble, the store and the note of lost names. |
| The guest API calls of one cell | 10,000 | Each later call of the cell fails at once, and the error tells the model to do more work in each call. A refused call does not stop the clock of the cell, so the timeout still applies. |
| The echo of a `val` | 400 characters | The echo gets the note `[The echo of this value has N more characters. Print the value, or a part of it, to see them.]`. The text of `println` stays whole. |
| The output of a cell | 4 MiB | The REPL keeps the first 4 MiB of the output. |
| The text that the model sees of a cell | 4,000 characters | The text gets the note `[N more characters were cut. Print a smaller part of the value to see them.]`. |

A new REPL does not have the names of the earlier cells. A new REPL starts, for example, after the cell limit, after a
compile error that stops the compiler, and when a session opens again. The model then sees a note like this one:

```text
The harness started a new REPL, because the REPL ran its limit of cells. These names of earlier cells are gone: readme, lines. Define them again if you need them.
```

## Try it

1. Start `dspy4s-harness` in a folder with a `README.md`. Type
   `How many lines does README.md have? Use read.` You must see a cell with the call line `read README.md`, and a
   reply with a number. Compare the number with the output of `wc -l README.md`.

2. Type `Store the number of lines of README.md under the key readme-lines.` You must see the call line
   `store readme-lines`. In the next turn, type `Use load to read the key readme-lines, and show the value.` You must
   see the call line `load readme-lines` and the same number.

3. Type `Start the command "sleep 5 && echo done" as a job with spawn, wait for it with waitJob, and show its output.`
   You must see a call line that starts with `$ sleep 5 && echo done`, and a reply with `done`.

Next: [The sandbox and approvals](sandbox.md).
