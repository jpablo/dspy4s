# dspy4s `harness-mcp`

`harness-mcp` is an early MCP server of the code harness. It gives a REPL and the guest API to another agent, for
example Claude Code, over stdio. It exists before the harness has its own loop. A cell can read the files of the
workspace and keep values for a later cell. It cannot write files, run commands, or use the network. It uses
[`harness-repl`](../harness-repl/README.md), the secret paths and names of
[`harness-sandbox`](../harness-sandbox/README.md), and the MCP Java SDK.

## Use

Get the class path of the server from the root of the repository. The command compiles the modules first. The class
path holds the `target` folders of the build, so get it again after `sbt clean`.

```bash
CP=$(sbt -batch -no-colors 'export harnessMcp/Runtime/fullClasspath' | tail -1)
```

Then start the server with the folder of the workspace as its argument. With no argument, the workspace is the
working directory.

```bash
java -cp "$CP" dspy4s.harness.mcp.McpServer /path/to/workspace
```

To add the server to Claude Code, give the same command to `claude mcp add`:

```bash
claude mcp add dspy4s-harness -- java -cp "$CP" dspy4s.harness.mcp.McpServer /path/to/workspace
```

## Main concepts

- **Two tools.** `run_cell(code)` runs one cell and gives its output. `api_reference` gives the parts of the source
  text of the guest API that compile in the mode of this server, after a header that tells the notation and the
  capabilities of this server.
- **One REPL for all calls.** A later cell sees the definitions of the earlier cells that completed. One cell runs at
  a time.
- **A read-only mode.** `McpServer.mode` gives the bare `Files` and `Store^`, and nothing else. `exec` needs `Files^`,
  and the server has no handler that writes files, so it gives no `Shell^` and no `Network^`. It has no LM and no
  sessions, so it gives no `Llm`, `Sessions`, or `Children`.
- **The secrets stay closed.** A read fails for a path outside the workspace, also through a symbolic link. It also
  fails for a secret path of the home folder, and for the real target of that path. It also fails for a path with a
  part that has a secret name, for example `.env`.
- **Only the protocol goes to stdout.** `main` sets `System.out` to stderr before anything else writes. Thus a late
  print of a library goes to stderr. The output of a cell goes to the buffer of its REPL.

## Main types

| Type | Description |
|------|-------------|
| `McpServer` | The server of one workspace. `runCell(code)` gives the text of a cell, and true when the cell did not complete. `close()` closes the REPL. |
| `McpServer` (object) | `mode`, `main`, `serve` (the two tools on an input and an output stream), `render` (the text of a `CellRun`), and `reference` (the API reference). |
| `DirectoryFiles` | The `Files` handler: a read under the root, with the rules for the secrets. `write` and `edit` fail. |
| `MemoryStore` | A store in memory. It keeps the JSON text of each value, so `load` reads the value back with the type of the cell that calls it. |
| `SystemTurn` | The clock of the host, and no inputs. The server has no journal, so it does not record the values of `now`. |

## The result of a cell

| `CellOutcome` | The text | An error result |
|---------------|----------|-----------------|
| `Ok` | the output, or `(no output)` | no |
| `CompileError` | the diagnostics, with a hint when one applies | yes |
| `Failed` | the output, and `The cell failed: ...` | yes |
| `TimedOut` | the output, and `The cell stopped after ... ms.` | yes |
| `Submitted` | the output, and `The cell submitted <value>.` with the JSON of the value | no |

## Source layout

| File | Contents |
|------|----------|
| `McpServer.scala` | `McpServer`, the two tools, and the API reference |
| `Handlers.scala` | `DirectoryFiles`, `MemoryStore`, and `SystemTurn` |

## Tests

Run `sbt harnessMcp/test` from the root of the repository. `McpLaws` checks the rules of the workspace, the secrets,
the store, and the API reference. An end-to-end test starts the server in a child JVM, runs cells over stdio, and
checks that only the protocol goes to stdout. Thus the tests fork a JVM.

## Relation to the design

This module is the early MCP server of section [6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#6-the-repl) of the
design. The secrets follow section [5.6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#56-secrets-and-taint).
