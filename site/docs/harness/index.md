# Code harness

The code harness is an agent for software work. It acts only through Scala code. At each step, a model writes the
code of one Scala 3 cell. An embedded REPL runs the cell against a typed guest API. The next step sees the output of
the cell.

The harness is a part of the dspy4s repository. Each call to the model is a dspy4s `Predict` leaf. You start the
harness with the terminal client, `scripts/dspy4s-harness`.

!!! note "Status"
    The harness is pre-release, and it has no published artifact. Build it from the source of the repository with sbt,
    and run it on JDK 21. The names, the flags and the file formats can change.

## The parts

- **Cells.** The model acts only through cells. The REPL compiles each cell with capture checking and safe mode. Thus a
  cell can call only the guest API functions that the mode of its session gives.
- **The REPL.** One Scala 3 REPL runs the cells of a session, in the JVM of the host. A value of a cell stays for the
  later cells of the session.
- **The sandbox.** Each command of a cell runs in an OS sandbox: Seatbelt (`sandbox-exec`) on macOS, and bubblewrap
  (`bwrap`) on Linux. When no sandbox works on the host, a command does not run.
- **The journal.** A session is an append-only journal in a SQLite file. Each message, each step, each cell and each
  guest API call adds entries to the journal.
- **The terminal client.** The terminal client shows one session in your terminal. You type messages and commands, and
  the client shows the steps of the model.

This is a typical cell. It reads a file of the folder of the session and prints the count of its lines:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/index.scala:first-cell"
```

## The words

The pages of this section use these words with one meaning each.

| Word | Meaning |
|---|---|
| Session | A durable conversation: an id and a journal. A session stays after the client ends, and you can open it again. |
| Journal | The append-only list of the entries of a session. The host keeps it in the SQLite file `sessions/<id>.db`. |
| Host | The harness process. It runs the REPL and the handlers of the guest API. The terminal client starts one host. |
| Turn | The work from a `TurnStarted` entry to its `TurnEnded` entry. Your message starts a turn when no turn is open. |
| Step | One call to the policy (the model) and what it starts. The model gives a text and the code of one cell, or only a text. |
| Cell | One unit of Scala 3 code that the REPL runs. |
| Capability | One of the seven parts of the guest API: files, shell, network, llm, sessions, children and store. Each one is a Scala `given`. |
| Mode | The capabilities of a session, with a level for each one: `None`, `Read` or `Full`. A call that the mode does not give does not compile. |
| Scope | The place where a session works. A session of the terminal client works in the folder that you give it. |
| Job | A command that a cell starts with `spawn`. It continues to run after its cell ends. |
| `submit` | The guest API function that ends the cell and the turn with a value. In a session of the terminal client, the value is a `String`. |

A session of the terminal client has the capabilities files, shell, llm and store at the level `Full`. It has no
network, no sessions and no children. [The guest API](guest-api.md) gives the functions of each capability.

## How a turn runs

1. You type a message. The host commits an `Input` entry. When no turn is open, it also commits `TurnStarted`.
2. The host calls the policy. The model gives a text and the code of one cell, or only a text.
3. The REPL runs the cell. The journal records each guest API call before it runs and after it ends. The output of the
   cell goes to the next step.
4. A step with no cell ends the turn, and its text is the reply. A cell that calls `submit` also ends the turn.
5. The default limit is 10 steps. After 10 steps, the model gets one more call, and that call must reply. The code
   of that call does not run.

A message that you type during a turn goes to the next step of that turn. `/stop` (or Ctrl-C on an empty line) ends
the turn. An error of the model also ends the turn.

## The modules

Each module is a folder in `modules/`. The table starts with the guest API and ends with the clients.

| Module | What it holds |
|---|---|
| `harness-guest` | The guest API: the capability traits, the modes, and the `Data` type class for the values that leave a cell. |
| `harness-kernel` | The pure kernel: the journal entries, the state as a fold of the journal, and the functions that decide each step. |
| `harness-repl` | The Scala 3 REPL of the cells: capture checking, safe mode, the givens of the mode, and the API reference. |
| `harness-sandbox` | The OS sandbox of the commands: Seatbelt, bubblewrap, the allowlist of variables, and the proxy of the host list. |
| `harness` | The loop of a turn, the handlers of the guest API, the context strategies, and the mask of secrets. A `Harness` is also a dspy4s `Module[I, O]` for one task. |
| `harness-host` | The session host: a SQLite file for each journal, the catalog, the feeds of the clients, the jobs, the scopes and the controller. |
| `harness-inspect` | The inspector: a local web server that reads the journals and the exchange files of a root. |
| `harness-cli` | The terminal client: the arguments, the `.env` file, the screen and the commands. |
| `harness-mcp` | An early MCP server. It gives another agent a REPL over stdio. Its cells can read files and keep values. |

## The pages of this section

- [First run](first-run.md): build the client, give it a model, and run a first session.
- [The terminal client](terminal-client.md): the screen, the commands and the keys of the client.
- [The guest API](guest-api.md): the functions that a cell calls, and the modes.
- [The sandbox and approvals](sandbox.md): what a command can do, and how you allow a call that asks for approval.
- [Sessions and the controller](sessions.md): the files of a host, the reopen of a session, and the controller.
- [Memory](memory.md): how the model sees the earlier turns of a session.
- [The inspector](inspector.md): a local web page for the journals and the HTTP exchanges with the model.

## Not built yet

The design documents in `docs/code-harness/` also describe parts that the code does not have yet:

- the goal check after a turn (section 8.5 of the design)
- the graph of phase 10 of the plan
- the replay of a session from its recorded results
- the reviewer
- `parallel` in the guest API
- jobs that continue after a crash of the host
- a container backend for the sandbox
- LSP support
- prompt-cache marks in the requests to the model
- MCP tools as guest API functions

## Try it

Start the client in the test folder of [First run](first-run.md).

1. Type `/help`. You must see the list of the commands, after the line `Type a message to the session, or a command:`.
2. Type `How many lines does notes.txt have?`. You must see a cell that reads the file, the output of the cell, and a
   reply with the number.
3. Type `What is 6 times 7?`. The model can reply with no cell. You must see the reply, then an end line such as
   `Done · 1 step · 0 cells · 2.1 s`.
4. Type `/quit`, then run `dspy4s-harness --list`. You must see your session with the status `idle`.

Next: [First run](first-run.md).
