# dspy4s `harness-guest`

`harness-guest` is the guest API of the code harness. A cell is the Scala 3 code of one step of a model, and the
harness runs it in a REPL. A cell calls the functions of this module to read files, run commands, call an LM, and keep
values. The module also has the modes that limit a cell, and the `Data` type class for the values that leave a cell. It
uses only zio-blocks, and no other dspy4s module.

The traits of this module are a contract. Other modules give the handlers: [`harness-repl`](../harness-repl/README.md)
binds them in a REPL, and [`harness-sandbox`](../harness-sandbox/README.md) and [`harness`](../harness/README.md) give
the real handlers.

## Main concepts

- **A capability is a given.** Each function of `api` takes its capability as a `using` parameter, for example
  `read(path)(using Files)`. The REPL binds a given only for each capability that the mode of the session gives. Thus a
  call that the mode does not give does not compile.
- **Two levels of access in one type.** A cell compiles with capture checking. The bare type of a capability can only
  read, and `T^` can also change something. For example, the bare `Files` gives `read`, and `Files^` also gives `write`
  and `edit`.
- **A mode is a product of seven chains.** `Mode` has one `Access` level (`None < Read < Full`) for each of the seven
  capabilities. `meet` gives the lower level of each capability. Thus the modes form a bounded meet-semilattice, with
  `Mode.full` at the top.
- **One codec for the values that leave a cell.** A value that goes to a later cell, to the store, or to the host needs
  a `Data[A]`. Safe code cannot name the zio-blocks `Schema`, so the guest API has its own type class. `Data` encodes a
  value as zio-blocks `Json`, decodes it with a path for each error, and gives its `DataShape`.
- **The source text is the API reference.** The build copies `Guest.scala` and `SessionTypes.scala` into the resources
  of the module. The harness and the MCP server give this text to the model as the API reference.

```scala
// A cell in a mode with Files^ and Store^, for a task with an Int output.
val lines = read("build.sbt").linesIterator.toVector
store("count", lines.size)
write("notes/count.txt", lines.size.toString)
submit(lines.size)
```

## Main types

### Capabilities and the API

| Type | Description |
|------|-------------|
| `Cap` | A capability with a read-only view. `Files`, `Store`, `Sessions`, and `Children` extend it. |
| `Files` | The files of the workspace: `read`, and with `Files^` also `write` and `edit`. `edit` replaces the one place of a text. It fails, and the file does not change, when the text occurs no time or more than one time. |
| `Shell` | The commands of the workspace: `exec`, and the jobs `spawn`, `jobOutput`, `waitJob`, and `stopJob`. A command call also needs `Files^`. |
| `Llm` | An LM call from a cell: `query`, and `queryBatched`, which is one call whose prompts run at the same time. |
| `Network` | `get`, the body of a GET request. The proxy of the sandbox applies the host list. |
| `Store` | The session state that survives a restart: `keys` and `load`, and with `Store^` also `store`. |
| `Sessions` | Other sessions: `list`, `zoom`, and `observe`, and with `Sessions^` also `tell` and `start`. |
| `Children` | A child inside the cell: `rlm` and `rlmBatched`. |
| `Turn` | What a cell gets with no capability: the clock (`now`) and the input values of the session (`input`). |
| `api` | The functions that a cell calls, for example `read`, `exec`, `store`, `input`, and `rlm`. |
| `JobId` / `JobStatus` | The id of a job, which stays valid in a later cell, and the status that `waitJob` gives. |
| `NeedsApproval` | The exception of a call that needs a person. Safe mode rejects its constructor, so a cell cannot make one. |

### Modes and sessions

| Type | Description |
|------|-------------|
| `Access` | `None`, `Read`, or `Full`, with `<=` and `meet`. |
| `Mode` | One `Access` for each capability: `files`, `shell`, `network`, `llm`, `sessions`, `children`, and `store`. `Mode.full`, `Mode.local`, `Mode.readOnly`, `Mode.controller`, and `Mode.closed` are the modes of the table of section 5.2. A child and a rollout get `base.meet(Mode.closed)`. |
| `Scope` | Where a session works: `Home`, `Directory`, `Worktree`, `Scratch`, or `Fork` (a copy of a workspace). A path is a text, so the configuration has the same JSON on each host. |
| `StrategyName` | The context strategies: `Window`, `PrefixSummary`, and `MemoryTree`. |
| `SessionRef` / `SessionCard` | A reference to another session, and what `list` shows about a session. |

### Values

| Type | Description |
|------|-------------|
| `Data[A]` | The type class of a value that leaves a cell: `encode`, `decode`, and `shape`. There are instances for the numbers, `String`, `Char`, `Boolean`, `Unit`, `Instant`, `Duration`, the collections, `Map`, the tuples, `Option`, and `Either`. A case class or an enum gets an instance with no `derives` clause. A recursive type needs one line: `given Data[T] = Data.derived`. |
| `DataError` | A value that does not fit its type. The message gives the path, for example `at a.y: expected a field of P, found {x}`. |
| `DataShape` | The form of a value: `Plain`, `Applied`, `Record`, or `Union`. A record and a union are lazy, because a type can hold itself. |
| `preamble` | The first code of a new REPL, from the output shape and the input shapes: the types, a given for each type, `submit`, and a `val` for each input. |
| `typeText` | The Scala text of the type of a shape. |

## Design notes

- **A cell cannot make a capability.** The REPL binds each given before safe mode starts, so a cell cannot name the
  handlers. `Turn` is a given too, so a cell cannot make one.
- **`now` is an effect, and `input` is not.** `now` is a guest API call, so the journal records its value, and a replay
  gets the same time. `input` reads a value that the journal already holds.
- **The JSON holds no type name.** The preamble of another REPL can define a type with the same shape, and that type
  reads the value. For example, `Box[Int]` can be a class with an `Int` field, and a sealed trait can be an enum.
- **Each value has a JSON form.** A map with text keys is an object, and another map is an array of pairs. A
  `Double` or a `Float` with no JSON number (NaN, the infinities, and -0.0) is a string. Thus an encode never throws.
- **The preamble refuses four cases.** `preamble` gives an error for each of these cases:
  - two types with one name and two forms
  - a union case that is not a record
  - an input with the name of a guest API function
  - an input name that no Scala name can hold
- **A message for a value with no `Data`.** The `implicitNotFound` text of `Data` lists the types that can leave a
  cell. It tells the model to use `.toVector` for an `Array` or a `Range`.

## Source layout

| File | Contents |
|------|----------|
| `Guest.scala` | the capability traits, `Turn`, `JobId`, `JobStatus`, `NeedsApproval`, and `api` |
| `SessionTypes.scala` | `StrategyName`, `Scope`, `SessionRef`, and `SessionCard` |
| `Mode.scala` | `Access` and `Mode` |
| `Data.scala` | `Data`, `DataError`, `DataShape`, and the instances |
| `DataDerivation.scala` | the classes that `Data.derived` builds, and the helpers of the instances. This file has no capture checking, because the `Data` of a field is a by-name value, and capture checking counts that closure as a capability. |
| `Preamble.scala` | `preamble` and `typeText` |

## Tests

Run `sbt harnessGuest/test` from the root of the repository. `DataLaws` checks with ScalaCheck that each kind of value
comes back after `encode` and `decode`. It also checks that an error gives its path, and that `Data` writes the forms
of the table of 5.1. The last tests check the cases of `preamble`. `ModeLaws` checks the laws of the semilattice of `meet`, and the law of
`Mode.closed`.

## Relation to the design

This module is sections [5.1](../../docs/code-harness/CODE_HARNESS_DESIGN.md#51-the-guest-api) and
[5.2](../../docs/code-harness/CODE_HARNESS_DESIGN.md#52-modes) of the design. Section
[10.5](../../docs/code-harness/CODE_HARNESS_DESIGN.md#105-the-modules) gives the place of each harness module. A
comment that starts with "A proposal for the review" marks a type that the design names and does not define.
