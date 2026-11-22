# First run

This page tells how to build and start the terminal client, how to connect it to a model, and how to run a first
session. You need a clone of the repository and a model endpoint that serves the OpenAI chat completions API.

In the commands, `<repo>` is the folder of your clone.

## Requirements

- **JDK 21.** The repository pins `openjdk-21.0.1` in `.tool-versions`. The launcher starts the `java` of your `PATH`.
- **sbt.** The launcher builds the client with sbt. The repository sets the sbt version in `project/build.properties`.
- **macOS: Seatbelt.** The sandbox uses `/usr/bin/sandbox-exec`, which is a part of macOS.
- **Linux: bubblewrap.** Install `bwrap` in `/usr/bin`, `/bin` or `/usr/local/bin`. The host must be x86_64 or
  aarch64, because the seccomp filter of the sandbox exists only for these two. `bwrap` must be able to mount `/proc`
  and `/dev`, as on a usual Linux desktop.
- **A model.** An endpoint that serves the OpenAI chat completions API (`/chat/completions`), its base URL, a key and
  the name of the model.

At its start, the client looks for a sandbox that works: it runs `/bin/sh -c true` in it. When no sandbox works, the
session still opens, but each `exec` of a cell fails with this error:

```text
No OS sandbox works on this host, so exec did not run the command. macOS needs /usr/bin/sandbox-exec, and Linux needs bwrap.
```

!!! note "No network for the commands"
    A session of the terminal client has no network capability. Under bubblewrap, a command never has a network.

## The launcher

The launcher is the script `scripts/dspy4s-harness`. Run it from the clone, or put a link to it in a folder of your
`PATH`. Run these commands in `<repo>`:

```bash
mkdir -p ~/.local/bin
ln -s "$PWD/scripts/dspy4s-harness" ~/.local/bin/dspy4s-harness
```

The launcher builds the client with sbt when one of these conditions is true:

- The class path file `modules/harness-cli/target/live-classpath.txt` is not there, or it is empty.
- A `.scala`, `.java` or `.sbt` file of the build is newer than the class path file.

The build runs `sbt -batch "export harnessCli/Runtime/fullClasspath"` and writes the class path to that file. In a
terminal, one line shows the build. It changes in place, for example:

```text
⠹ Building the CLI · Screen.scala changed · 14 s · compiling 3 Scala sources to harness-cli
```

At the end, the line becomes `✓ Built the CLI in 31 s`. After a failure, the launcher prints
`✗ The CLI did not build (31 s). The sbt log follows:` and the log of sbt. Then the launcher starts
`java -cp <class path> dspy4s.harness.cli.Cli` with your arguments.

| Variable | Effect on the launcher |
|---|---|
| `HARNESS_CLI_BUILD=1` | Build each time. |
| `HARNESS_CLI_NO_BUILD=1` | Never build: use the class path file as it is. This variable wins over `HARNESS_CLI_BUILD`. |
| `NO_COLOR` | Any value that is not empty turns the colors of the build line off. |

A locale with no UTF-8 gets ASCII signs in the build line. The launcher reads these variables from your shell only.

## The model variables

The client reads the variables of the model from the file `~/.dspy4s/harness/.env` and from its environment. It reads
that file also when you give another `--root`.

| Variable | Meaning | Default |
|---|---|---|
| `OPENAI_BASE_URL` | The base URL of the endpoint. | `https://api.openai.com/v1` |
| `OPENAI_API_KEY` | The key of the endpoint. It must not be empty. A local server that does not check keys still needs a value. | no default |
| `DSPY_MODEL` | The name of the model. With it, the client also reads the context window and the effort levels from `GET <base>/models`. | `none` |
| `DSPY_NATIVE_REASONING` | `true` tells the client that the model gives its reasoning apart from its text. | not set |
| `DSPY_REASONING_EFFORT` | The effort level of a new session, for example `low`. It goes to the model as the option `reasoning_effort`. | `low` with native reasoning, else no effort |
| `DSPY_CONTEXT_WINDOW` | The size of the context window in tokens (a whole number above 0), for the footer, `/status` and `/config`. It wins over the value of the endpoint. | the value of `GET <base>/models` |
| `DSPY_SUMMARY_OPTIONS` | A JSON object: the provider options of each summary call of the memory. `{}` sends no option. | `{"reasoning_effort": "none"}` with native reasoning, else `{}` |
| `DSPY_EXCHANGES` | `off` turns the exchange file of each session off. | on |
| `NO_COLOR` | Any value that is not empty turns the colors of the client off. | colors on |

With no key, the client still starts, and each turn fails with the error of the model. A value of
`DSPY_SUMMARY_OPTIONS` that is not a JSON object stops the client with the exit code 2.

This is a sample `.env` file. Put your values in place of the placeholders:

```text
OPENAI_BASE_URL=https://api.example.com/v1
OPENAI_API_KEY=sk-...
DSPY_MODEL=my-model
# DSPY_NATIVE_REASONING=true
# DSPY_REASONING_EFFORT=low
# DSPY_CONTEXT_WINDOW=128000
# DSPY_SUMMARY_OPTIONS={"chat_template_kwargs": {"enable_thinking": false}}
```

The rules of the file:

- A line is `NAME=VALUE` or `export NAME=VALUE`. A blank line and a line that starts with `#` give no variable.
- The client removes the spaces at the ends of a value, and a pair of quotes around it. It does not expand `$NAME`,
  and it keeps a `#` in a value.
- A line that is not right stops the client with its line number and the exit code 2.
- A variable of the environment wins over the file. The base URL and the key are the exception: when the file gives
  one of them, both come from the file.

The last rule keeps the key of your shell away from an endpoint of the file. The client also masks the value of each
secret variable (for example a name with `KEY` or `TOKEN`) in the journal and the exchange file. A value shorter than
8 characters gets no mask.

## The arguments

```text
dspy4s-harness [--root DIR] [--session ID | --controller] [--script] [--no-memory-calls] [--cell-wall-minutes N]
               [--unattended-turns N] [--port N] [FOLDER]
dspy4s-harness [--root DIR] --list
dspy4s-harness [--root DIR] --inspect [--port N]
```

| Argument | What it does | Default |
|---|---|---|
| `FOLDER` | The folder of a new session. The folder must exist. It has no effect with `--session` or `--controller`. | the current folder |
| `--root DIR` | The root of the host: the journals, the catalog and the history. | `~/.dspy4s/harness` |
| `--session ID` | Opens the session `ID` again, with its own configuration and folder. The start of the id is enough when only one session has it. | a new session |
| `--controller` | Opens the controller of the root, which starts and follows the work sessions. | off |
| `--list` | Prints the sessions of the root, the newest first, and ends. | off |
| `--inspect` | Serves the inspector of the root on `127.0.0.1` until Ctrl-C. | off |
| `--port N` | The port of the inspector of `--inspect`, and the first port of `/inspect` in a session. `0` takes a free port. | `7411` |
| `--script` | Reads plain lines from the standard input. Each line waits for the end of the turn before it. | off |
| `--no-memory-calls` | The memory makes no summary call (`compress`, `merge` or `shorten`). Each message of the memory shows its first 512 bytes. | the calls run |
| `--cell-wall-minutes N` | The wall time of one cell in minutes, with its guest API calls. `0` gives no limit. Long work goes in a job (`spawn` and `waitJob`). | `15` |
| `--unattended-turns N` | The turns in a row that a session runs with no message of yours. After them, a message of another session, of the harness or of a job waits for your next message. `0` gives no limit. | `8` |

`--session` and `--controller` do not go together. `--port` does not go with `--list`, and `--inspect` takes only
`--root` and `--port`. An unknown flag, for example `--help`, prints the usage, and the client ends with the exit code 2.
`--list` and `--inspect` do not read the `.env` file.

## Where the files go

The host makes the root and its folder `sessions` at its first start. The sandbox hides the root from the commands of
the cells. With the default root, it also hides the `.env` file.

| Path | What it holds |
|---|---|
| `<root>/sessions/<id>.db` | The journal of the session `<id>`, a SQLite file. SQLite can keep `<id>.db-wal` and `<id>.db-shm` next to it. |
| `<root>/sessions/<id>.lock` | The lock of the session while a host has it open. A second host gets the error `the session is open in another host`. |
| `<root>/sessions/<id>.http.jsonl` | The exchange file: each HTTP request to the model and its reply, as JSON lines, with the secrets masked. |
| `<root>/catalog.db` | The catalog: one row for each session. The host builds it again from the journals at each start. |
| `<root>/history` | The lines that you typed, for the line editor of the client. |

A host can also make the folders `probes` and `archive` in the root, and the folder `work` next to the root.
[Sessions and the controller](sessions.md) tells more.

## A first session

1. Make a small test folder with one file:

    ```bash
    mkdir -p ~/harness-first-run
    cd ~/harness-first-run
    printf 'one\ntwo\nthree\n' > notes.txt
    ```

2. Start the client. The first start builds the client, so it takes longer.

    ```bash
    dspy4s-harness
    ```

    The client prints this line, with the id of the new session in place of `<id>`:

    ```text
    The session <id> is open. Type a message, or /help for the commands.
    ```

3. Type a question:

    ```text
    How many lines does notes.txt have?
    ```

    During the turn, a status line shows the time, the step (for example `step 1 of 10`) and `ctrl-c stops`. The client
    shows the text of each step, the cell and its output. The reply comes last, then an end line such as
    `Done · 2 steps · 1 cell · 3.4 s`.

4. Type `/quit`. The client ends, and the session stays open.

5. List the sessions of the root:

    ```bash
    dspy4s-harness --list
    ```

    Each session has one line with its id, its status, the time of its last entry and its folder. Your first message
    comes on the next line.

6. Open the session again with the start of its id, for example `--session 3f6c`:

    ```bash
    dspy4s-harness --session <id>
    ```

    Then type `What did I ask you in the last turn?`. The reply must name your question about `notes.txt`.

The session opens in its own folder, also when you start the client in another folder. The new host has a new REPL,
so the values of the earlier cells are not there. The store of the session keeps its values. Only one host at a time
can open a session (see the lock file above).

## Try it

1. In the session, type `List the files of this folder with their sizes.`. You must see a cell that calls `exec`, and
   a reply that names `notes.txt`.
2. Type `Write the file hello.txt with the text Hello.`. Then run `cat ~/harness-first-run/hello.txt` in another
   shell. You must see `Hello`.
3. Run `ls ~/.dspy4s/harness/sessions`. You must see `<id>.db` and `<id>.http.jsonl` for your session.
4. In the test folder, run `printf 'How many lines does notes.txt have?\n' | dspy4s-harness --script`. You must see
   the reply, and the client must end after the turn.

Next: [The terminal client](terminal-client.md).
