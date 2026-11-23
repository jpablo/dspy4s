# The inspector

The host records each HTTP exchange with the model endpoint in a file next to the journal of the session. The
inspector is a local web server that shows the journals and the exchanges of a root. It only reads the files, so it
can run while a session runs.

An exchange is one HTTP request to the model endpoint and its reply. A retry is a second exchange.

## The exchange file

Each session has its exchange file next to its journal:

```text
<root>/sessions/<id>.db            the journal
<root>/sessions/<id>.http.jsonl    the exchange file
```

The default root is `~/.dspy4s/harness`, and `--root DIR` gives another root. The file holds each model call of each
activation of the session. These are the calls of the policy, the summary calls `compress`, `merge` and `shorten`, the calls of
`llmQuery` (the predictor `subQuery`), and the probes. Each exchange has two JSON lines with the same `id`:

- The `request` line, which the host writes when the exchange starts. It holds `session`, `after` (the last `seq` of
  the journal), `predictor`, `probe` (only for a probe), `at`, `url`, `headers`, `stream` and `body`. The `body` is a
  JSON string with the exact text of the request body.
- The `reply` line, which the host writes when the exchange ends. It holds `at`, `first` (the time of the first line of
  a stream), `status`, `headers`, and `end`. A plain reply has `body`. A stream has `lines`: each raw line of the reply,
  with the `data:` prefix and the empty lines. A failed exchange also has `error`.

An example, with parts cut:

```text
{"kind":"request","id":"e3b0c442","session":"9c1f5d2a","after":41,"predictor":"policy","at":1762950000123,"url":"https://api.example.com/v1/chat/completions","headers":{"Authorization":"«secret:Authorization»"},"stream":true,"body":"{\"model\":\"my-model\",\"messages\":[...],\"stream\":true}"}
{"kind":"reply","id":"e3b0c442","at":1762950003456,"first":1762950000610,"status":200,"headers":{"content-type":"text/event-stream"},"lines":["data: {...}","","data: [DONE]"],"end":"closed"}
```

- `end` is `done`, `closed` or `failed`. A complete stream of the OpenAI client ends `closed`, and its last line is
  `data: [DONE]`.
- An exchange with a `request` line and no `reply` line is open: the call still runs, or the process stopped during
  the call.
- Each text goes through the mask of the secrets of the host. The value of a header with a secret name shows the mark
  of its name, for example `«secret:Authorization»`.

This `jq` command prints the last message of the request body of each policy call:

```bash
jq -r 'select(.kind == "request" and .predictor == "policy") | .body | fromjson | .messages[-1].content' \
  ~/.dspy4s/harness/sessions/<id>.http.jsonl
```

The exchange file is on by default. Set `DSPY_EXCHANGES=off` to turn it off, in the environment or in
`~/.dspy4s/harness/.env`:

```bash
DSPY_EXCHANGES=off dspy4s-harness
```

## Start the inspector

In a session, type `/inspect`. The client starts the inspector of its root and prints the link to the page of the
session:

```text
› /inspect
The inspector runs at http://127.0.0.1:7411/s/<id> . It stops when this client ends.
```

- When another client of the same root already serves the inspector on the port, the client uses it, and the text
  says so. That inspector stops when that client ends.
- When another program has the port, the client starts the inspector on a free port, and the text names the port that
  was taken.
- `--port N` of the session sets the first port, and `--port 0` takes a free port.

To run the inspector with no session, start it on its own:

```bash
dspy4s-harness --inspect
dspy4s-harness --inspect --root ~/test-root --port 0
```

The client prints the address and waits:

```text
The inspector of <root> runs at http://127.0.0.1:7411/ . Ctrl-C ends it.
```

- The server listens on `127.0.0.1` only. The default port is 7411. `--port 0` takes a free port.
- `--inspect` takes only `--root` and `--port`.
- The server answers only `GET`. A request with a `Host` header other than `127.0.0.1:PORT` or `localhost:PORT` gets
  the status 403.
- The server opens each journal read-only for one request. It changes no file of the root.

## The pages

**The sessions, at `/`.** A table of the sessions of the root, the newest first, with the columns `Last`, `Status`,
`First message`, `Folder`, `Session` and `Parent`. The rows come from the catalog, and from each journal that the
catalog does not have. The first message links to the page of the session. The probes of a session show under its
first message, with a link to the journal of each probe.

**One session, at `/s/<id>`.** The page has three views. A session with more than 2,000 entries shows only its last
2,000, with a note in the bar. The raw `journal.jsonl` has all of them. The bar at the top has these parts:

- the facts: the id, the folder, the status, the counts of the entries and of the exchanges, and the totals of the
  turns on the page;
- `View:` with the buttons `Chat`, `Trajectory` and `Entries`;
- `Follow`, on by default: each second, the page asks for the new entries and adds them;
- in the `Entries` view only: `Entry:` with the buttons `Card`, `Scala` and `JSON` for all the rows, and `Filters`,
  with a box for each kind of entry and a box for each predictor of the exchanges;
- `Files`, a menu with `Raw:` and `Download:`, and the links `journal.jsonl` and `http.jsonl`.

**The `Chat` view** opens first. It shows the session as you saw it in the client, in one column in the center:

- Your message is a bubble on the right, with its minute. Hover over the minute to see the time to the millisecond.
- The reply has no bubble. It shows its Markdown: code blocks with a `Copy` button, inline code, bold, lists, tables
  and links. A Scala code block has the colors of Scala. A line under the reply gives its minute and the time of the
  turn.
- Between the message and the reply, one line gives the work of the turn: a mark, the time, the steps, the cells, the
  API calls, the tokens of the model calls and the errors, for example `✓ Worked 6.2 s · 4 steps · 3 cells · 2 calls ·
  20.3k → 806 tokens · 1 error`. The mark is ✓ for a reply or a submitted value, ✗ for each other end, and `…` for a
  turn that runs.
- Click the line of the work to see one line for each step: the text of the model, the first line of its cell and of
  the output with ✓ or ✗, and the time of the step. `Open in the trajectory` shows the turn in the `Trajectory` view.
- A change of the session is one line of words between two rules, for example `Session started 17:26 ·
  gpt-6.1-terra · effort low · work mode · 10 steps a turn`. The records of the harness, such as `ScopeRecorded`, and
  the memory summaries between the turns are only in the `Trajectory` view.

The bar shows the totals of the turns on the page, for example `3 turns · 1 min 1 s of work · 145.2k → 3.2k tokens`.
The raw files are in the `Files` menu.

**The `Trajectory` view** opens each turn. Each step has its time and one row for each part:

| Row | What it shows |
|---|---|
| `MODEL` | a model call of the policy: the text, the reasoning (folded), and a link to the page of the exchange |
| `CELL` | the first line of the code, the count of its lines, and the outcome; the whole code is folded, with the colors of Scala |
| `CALL` | an API call of the cell: the operation, the arguments and the result in short |
| `OUTPUT` | the first line of the output of the cell; the rest is folded |
| `MEMORY` | the summary calls of the memory, in gray, with a link to each exchange |
| `LLM` | another model call, for example of `llmQuery` |
| `NOTE` | each other entry, for example `Activated` or `StopRequested`, on one line |

A row has four columns of numbers: the time, the tokens in and out, the part of the prompt from the cache of the
endpoint, and the tokens per second of the reply. A row with an error has a red kind.

**The prompt.** A `SYSTEM` row shows the prompt of the policy. The `Chat` and `Trajectory` views both show it. The
first `SYSTEM` row comes before the first turn. Another `SYSTEM` row comes before each turn whose prompt changed, for
example `The prompt changed: task`. The prompt has these parts:

- the instructions: the system message up to the field `task`;
- the fields `task` and `apiReference`;
- the URL and the options of the request, for example `model` and `reasoning_effort`.

The memory changes at each turn, so it is not a part. The page of each exchange shows it. Click the row to open a box
with these tabs:

| Tab | What it shows |
|---|---|
| `Instructions` | the text of the instructions, with the marker lines such as `[[ ## task ## ]]` in color |
| `Task` | the field `task` as Markdown. Click `Raw` to see the exact text, and `Markdown` to go back. |
| `API (N)` | each call of `object api`, with its first sentence. Click a call for its signature and its comment. The Scala source is folded. The signatures and the source have the colors of Scala. |
| `Settings` | the call, the URL, each option, the place of the fixed fields, the size of each part, and the configuration of the last `Activated`, with its preamble in the colors of Scala |
| `Changes` | only for a prompt that changed: the line diff of each part that changed. The box opens on this tab. |

**The `Entries` view** shows the entries of the journal in the order of their `seq`. A step has an indent, and the
parts of a step (`Acted`, the calls, `Observed`) have two indents.

Each entry row has its `seq`, its kind, its time and its source, and the buttons `card`, `scala` and `json`:

| View | What it shows |
|---|---|
| `card` | a formatted card: for example the reasoning (folded), the text, the code of a cell, the output and the outcome |
| `scala` | the entry as a Scala value, with indents and colors |
| `json` | the `entry` field of the row, as the file has it, with indents and colors |

Each exchange row comes after the entry of its `after`. A policy exchange comes after the `StepStarted` entry of its
step. An exchange row has these parts:

- the link `exchange`, the predictor, and `stream` or `plain`
- the status, and the end (or `open`)
- the time to the first line, and the whole time
- the bytes of the request and of the reply, and the tokens
- the links `request` and `reply`

**One exchange, at `/s/<id>/x/<xid>`.** The title is `Exchange N of M · <predictor>`. The bar has the facts of the
exchange, the `seq` of its entry (`after #41`), the links `earlier` and `later`, and four buttons:

| View | What it shows |
|---|---|
| `Chat` | the options of the request, the tools (folded), and the messages with their roles. The system message is folded. Then the reply: the reasoning (folded), the text, the tool calls, the finish reason and the tokens. |
| `Request` | the URL, the headers, the size of the body, and the body as numbered lines with indents |
| `Reply` | the status, the end, the error and the headers, then the body. A stream has two views: `Response` (the reply put together from the chunks) and `Stream` (each raw line). |
| `Diff` | the lines of this request against the request of the last earlier exchange of the same predictor. `Against:` selects another exchange. |

- Each step sends the whole prompt again, so the diff of a policy exchange shows what the step added.
- The `Request` and `Reply` views each have a `Copy` button and a `Download` link for the exact text.
- `?tab=chat`, `?tab=request`, `?tab=reply` or `?tab=diff` opens the page on that view. `?against=<xid>` opens the
  diff against that exchange.

**The raw paths.** `/raw/<id>/journal.jsonl` gives each row of the journal as one JSON line. `/raw/<id>/http.jsonl`
gives the exchange file. `/raw/<id>/x/<xid>/request` gives the exact request body of one exchange, and
`/raw/<id>/x/<xid>/reply` gives its reply: the body, or the lines of a stream. Add `?download=1` to save the file.
The server sends the journal and the exchange file while it reads them, so a long session needs little memory. When a
read fails in the middle, the reply ends with no last chunk, and `curl` gives the exit code 18.

```bash
curl -s http://127.0.0.1:7411/raw/<id>/http.jsonl | jq -c '{kind, predictor, after}'
```

## A view in the shell

Two scripts in `scripts/live` print a session in a terminal. Run them from the root of the repository. They need
Python 3.12 and no other package.

```bash
python3 scripts/live/exchanges.py ~/.dspy4s/harness/sessions/<id>.http.jsonl
python3 scripts/live/journal.py ~/.dspy4s/harness/sessions/<id>.db
```

- `exchanges.py` prints one line for each exchange. The line starts with the number, the last `seq` before it, the
  time since the first exchange, and the predictor (or the probe). Then it gives the status, the end, the time of the
  reply, the size of the request, the tokens, and the start of the reply text. `--width CHARS` sets the length of the
  text (200 by default).
- `journal.py` prints one line for each entry: the `seq`, the time since the first entry, the source, the kind, and
  the body as JSON. `--width CHARS` sets the length of the body (1500 by default). Run it after the end of the
  client, because the file of an open session can be locked.

An example of `exchanges.py --width 60`, with values for this page:

```text
  1 after #3    +    0.0s policy       200 closed   2.41s 12119 B sent, in 3120 out 85 | [[ ## text ## ]] I count the lines of README.md. [[ ## code
  2 after #9    +   30.0s compress     200 done     0.64s 1003 B sent, in 310 out 24 | [[ ## line ## ]] The person asked for the line count of READ
  3 after #9    +   31.0s policy       open 12119 B sent
```

## Try it

1. In a folder with a `README.md`, start `dspy4s-harness`, and type `How many lines does README.md have?`. Note the
   id in the line `The session <id> is open.`. After the reply, type `/quit`.

2. Run `dspy4s-harness --inspect`, and open `http://127.0.0.1:7411/`. You must see the session, with your message in
   the column `First message`.

3. Open the session. Find the first `StepStarted` row (its card says `Step 0`). Under it, the next exchange row has
   the predictor `policy`. Click `request`. You must see the URL, the header `Authorization` with the value
   `«secret:Authorization»`, and the body of the first policy call.

4. Click `later` until the title says `policy` again, and click `Diff`. You must see the lines that the second step
   added, for example the output of the cell. Then run `exchanges.py` on the exchange file of the session. You must see
   one line for each exchange of the page.

5. Go back to the page of the session, and click `Trajectory`. Above the first turn, click the `SYSTEM` row. Click
   `API` and then the call `read`. You must see `def read(path: String)(using f: Files): String`. Click `Settings`.
   You must see the model of the session and the card of `Activated`.

Next: [Code harness](index.md).
