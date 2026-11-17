# The terminal client

The terminal client `scripts/dspy4s-harness` opens one work session and shows it. At a terminal, it uses the JLine
line editor and a screen with a live block and a footer. In a pipe, or with `--script`, it prints plain lines.

At the start, the client prints `The session <id> is open. Type a message, or /help for the commands.` Type your
first message after this line. [First run](first-run.md) tells how to start the client and how to set the model.

## The parts of the screen

| Part | What it shows |
|---|---|
| History | The finished steps and turns, in the scrollback of the terminal. |
| Live block | The step that runs now. Each 125 ms, the client draws it again when it changed. |
| Input | A dim rule, then the prompt `› ` and the line that you type. |
| Footer | One line of facts about the session, on the last row of the terminal. |

A turn while its first cell runs:

```text
› Why do the tests of the cart fail?
  I'll run the tests of the cart first.
  ▾ Cell #3
    │ exec("sbt cart/test")
    ⠹ $ sbt cart/test
⠹ 4 s · step 1 of 10 · ctrl-c stops · ctrl-o details
────────────────────────────────────────────────────────────────────────
›
  ~/code/shop · work mode · my-model · effort low · context 18k of 1M (2%) · memory 12k of 40k chars
```

The same turn after its end:

```text
› Why do the tests of the cart fail?
  ▸ Reasoning · 14 characters
  I'll run the tests of the cart first.
  ▸ Ran 1 command · exit 1 · 4.4 s  #3

● Two tests fail on the rounding.
  The total rounds each line.

  Done · 2 steps · 1 cell · 12 s
```

### The live block

- The call to the policy is the call to the model that writes the next step. During this call, the live block shows
  the draft: the reasoning, then the last 16 lines of the text, with `… +N lines` above them.
- After the model wrote a step with a cell, the live block shows the text of the step and `▾ Cell #N`. Under them, it
  shows at most 8 lines of the code, and one line for each call of the guest API. A call that runs has the spinner. A
  call that ended has `✓` or `✗` and its time.
- The last row is the status line. It shows the spinner, the time of the turn, the step and the step limit. It also
  shows a label when the turn waits, `ctrl-c stops`, and `ctrl-o details` while a cell runs.
- During a turn, the cursor is a block that blinks, and the window title is `● dspy4s · shop`. With no turn, the
  title is `dspy4s · shop`.

`/reasoning` sets how much of the reasoning the client shows. The default view is `line` in the line editor, and
`short` in the plain lines.

| View | In the draft of the live block | In the history |
|---|---|---|
| `off` | nothing | nothing |
| `line` | `▸ Reasoning · N characters` | the same row |
| `short` | the last 3 lines, each after `│` | the first 400 characters, then a note |
| `full` | the last 12 lines, each after `│` | all the reasoning |

### The summary lines

At the end of a step, the client prints the step into the history. It prints the reasoning in the view of
`/reasoning`, the text of the step, and one summary line for the cell. The summary line tells what the calls did, an exit code that is not 0,
and the time. The id at the end (`#3`) is the cell id for `/show`.

A summary line uses these words: `ran 2 commands`, `read 1 file`, `edited 1 file`, `wrote 1 file`, `started job 3.5`,
`asked the model 2 questions`, `ran 1 sub-program`, `read the store`, `wrote the store` and `read the memory`. A cell
with no call says `Ran a cell · 3 lines of output`. A cell that failed starts with `✗`, for example
`✗ The cell did not compile · 2 errors  #3`. A call that waits for an approval adds a card under the summary line
([The sandbox and approvals](sandbox.md)).

Ctrl-O switches between the summary line and the full form. The full form shows the code and each call with the
first 3 lines of its result (id `#3.0`). Then it shows the outcome with at most 20 lines of output. The switch applies to the live block
and to each cell that ends after it. The history keeps each cell as the client printed it.

The reply of the model starts with `●`. The last line of a turn gives the outcome, the steps, the cells and the time,
for example `Done · 2 steps · 1 cell · 12 s`. The other outcomes give `Done · submitted`, `■ Stopped`, `Step limit`,
`✗ Failed: <error>` and `✗ The turn crashed` (the host stopped during the turn). A message that you send during a turn
prints `↳ The next step reads your message.`

### The footer

```text
  ~/code/shop · work mode · my-model · effort low · context 18k of 1M (2%) · memory 12k of 40k chars · 1 job · 1 call waits
```

The parts, in this order:

1. The folder of the session, with `~` for the home folder.
2. The mode, for example `work mode` or `controller mode`.
3. The model.
4. `effort LEVEL`: the reasoning effort that the requests send.
5. `context`: the prompt tokens of the last call to the policy, the context window of the model, and the percent. With
   no window, the part is only the count, for example `context 18k`. Under 1%, the percent is `<1%`.
6. `memory`: the size of the last view of the memory, and its budget, in characters ([Memory](memory.md)).
7. The number of jobs that run, and the number of calls that wait for an approval.

A part with no value does not show. The context part and the memory part show only after the first call to the policy
since the session opened. A count of tokens shows as it is under 1,000, in whole thousands under 1,000,000 (`18k`),
and else in millions with one decimal (`1M`, `1.6M`).

When the line is wider than the terminal, the footer makes it shorter in this order:

1. The folder gets shorter: `…/` and its last parts, for example `…/code/shop`, then `…/shop`. The footer takes the
   longest form that fits with the memory part.
2. When no form fits, the memory part goes. The footer takes the longest form of the folder that fits.
3. When the line still does not fit, the folder is `…` and the end of its last part. With no room, the folder is only
   `…`, and the client cuts the rest of the line at the width of the terminal.

A terminal with no UTF-8 gets ASCII glyphs, for example `...` for `…` and `-` for `·`. Set `NO_COLOR` to a value to
turn the colors off.

## The context window and the effort levels

When `DSPY_MODEL` is set, the client sends `GET <OPENAI_BASE_URL>/models` at the start, on its own thread, with a
timeout of 5 s. The request sends the key of `OPENAI_API_KEY` to the same base URL as the model calls. The client
reads the entry whose `id` is the model name, or ends with `/` and the model name. The window comes from
`context_window`, else from `max_model_len` (the field that vLLM gives). The levels come from
`reasoning_effort_levels`. The start of the client does not wait for the request.

`DSPY_CONTEXT_WINDOW`, a whole number above 0, wins over the window of the endpoint. When the request fails, the
footer has no window.

The levels that `/effort` takes are `none` and the levels of the endpoint. When the endpoint gives no levels, they are
`none`, `low`, `medium` and `high`. The level `none` asks the model for no reasoning. A new session gets the effort of
`DSPY_REASONING_EFFORT`. With no such variable and with `DSPY_NATIVE_REASONING=true`, it gets `low`. Else the
session sends no effort.

```text
› /effort
The effort is low. The levels of the model are none, low and high. /effort LEVEL sets the effort of the next steps.
› /effort turbo
The model has no effort turbo. Its levels are none, low and high.
```

## The commands

A line that starts with `/` is a command. Each other line is a message to the session. `/help` prints this list.

| Command | Help text | Notes |
|---|---|---|
| `/stop` | stop the turn (Ctrl-C on an empty line does the same during a turn) | With no open turn, it does nothing. |
| `/stopall` | stop the turn and each job of the turn | With no open turn, it stops the jobs of the last turn. |
| `/allow [N] [once|turn|session]` | allow each pending call, or only the call N of the prompt and of /status | The lifetime is `once` when you give none. |
| `/jobs` | list the jobs of the session | One line for each job, for example `2.1 runs: "sbt ~test"`. |
| `/stopjob ID` | stop the job ID of /jobs, for example 12.0 | A job that has ended keeps its end, and the client prints `The session refused the command: the job ID has ended`. |
| `/status` | show the folder, the turn, the pending calls, the grants and the jobs | It also shows the effort, the context, and the turns in a row with no message of yours. |
| `/config` | show the settings: the model, the mode, the guard, the limits and the sandbox | [The settings](#the-settings) tells more. |
| `/reasoning off|line|short|full` | set how much of the reasoning of a step the client shows | The history keeps the rows that it printed. |
| `/effort [LEVEL]` | show the reasoning effort and its levels, or set the effort of the next steps | The level must be one of the levels of the model. |
| `/show ID` | show the cell ID or the call ID of the session in full, for example 12 or 12.0 | A cell shows its full form. A call shows all of its result. |
| `/wait SECONDS` | wait before the next line, for a script | A whole number, 0 or more. |
| `/help` | show this text | |
| `/close` | end the session as parked; with --controller, show and run its release plan | `/close parked` is the same. Give it when no turn is open. |
| `/close merged` | end the session as merged | |
| `/close abandoned REASON` | end the session as abandoned, with the reason | |
| `/close replaced ID` | end the session as replaced by the session ID | |
| `/sessions` | list the sessions of the host (with --controller) | |
| `/open ID` | show the session ID (with --controller) | For a session that has ended, it names `/reopen ID`. |
| `/reopen ID` | open again the session ID, which has ended (with --controller) | |
| `/controller` | show the controller again (with --controller) | |
| `/quit` | end the client. The session stays open for --session | Ctrl-D on an empty line does the same. |

!!! note "The commands of the other sessions"
    Each session of the client can reach the controller and the sessions of its root, also with no `--controller`. So
    `/sessions`, `/open ID`, `/reopen ID` and `/controller` work in a work session too. `/close` always prints the
    release plan first, asks you to type `yes` for each step that cannot be undone, and runs the plan. When the plan
    ran, the client shows the controller. Only `/quit` and the end of the input end the client.
    [Sessions and the controller](sessions.md) tells more.

## The keys

| Key | What it does |
|---|---|
| Enter | Sends the line. During a turn, the next step of the turn reads the message. |
| `\` and Enter | The line goes on to the next line. A paste keeps each `\`. |
| Ctrl-C on an empty line | During a turn, it stops the turn, as `/stop`. With no turn, it prints `No turn is open. /quit ends the client, and the session stays open.` |
| Ctrl-C on a line with text | Clears the text. |
| Ctrl-D on an empty line | Ends the client, as `/quit`. |
| Ctrl-O | Switches the detail of the cells. |
| Up and Down | Give the earlier lines. |
| Tab | Completes the commands, `once`, `turn` and `session` after `/allow` (also after `/allow N`), the views after `/reasoning`, and the levels after `/effort`. |
| Esc | The client gives it no action. |

The line editor turns off the signals of the terminal, so Ctrl-C is a key and never a signal. A job of the session
never gets a SIGINT from your Ctrl-C. A `kill -INT` of the client process does the same as Ctrl-C on an empty line.

The client keeps the lines that you type in `<root>/history`, by default `~/.dspy4s/harness/history`. The file keeps
the last 1000 lines, and the client masks each known secret in a line before it keeps the line.

## The settings

`/config` shows each setting that acts on the session and on its turns. It changes nothing. The text has eight parts:

| Part | What it shows |
|---|---|
| `Session` | the id, the owner, the goal, the scope and its folder, the strategy, and the kind of turn |
| `Model` | the name, the endpoint, each option (for example `reasoning_effort`), and the context window |
| `Mode` | the capabilities of the session. The controller also shows the most that a session that it starts can get. |
| `Guard` | each rule with its verdict, for example `Ask: start with kind starting with "Directory"` |
| `Grants` | each grant of `/allow` that is active, with its lifetime |
| `Limits` | the steps of a turn, the model calls of a turn, the two time limits of a cell, the guest API calls of a cell, the memory, and more |
| `Sandbox` | the network, the backend, the closed paths, the closed names, and the variables that a command gets |
| `Files` | the root, the journal and the exchange file of the session |

An example of the last parts in a work session, with some lines cut:

```text
Limits
  steps of a turn: 10
  turns with no person: 8
  model calls of a turn: 60
  time of the guest code of a cell: 30 s
  wall time of a cell: 15 min
  guest API calls of a cell: 10,000
  characters of the memory: 40,000
  summary calls of a turn: 64
Sandbox
  network: off
  backend: seatbelt
  closed paths: ~/.ssh, ~/.aws, ~/.azure, ~/.gnupg, ~/.kube, ~/.docker, ~/.netrc, ...
  closed names: .env, and each .env.NAME
  variables of a command: HOME, LANG, LC_ALL, LC_CTYPE, LOGNAME, PATH, SHELL, TEMP, TERM, TMP, TMPDIR, USER
Files
  root: ~/.dspy4s/harness
  journal: ~/.dspy4s/harness/sessions/<id>.db
  exchange file: ~/.dspy4s/harness/sessions/<id>.http.jsonl
```

The host records the limits and the sandbox in the `Activated` entry of each open of the session. So the values are
the values of this run, also after a reopen with other limits, for example with `--no-memory-calls`. The inspector
shows the same facts on the card of each `Activated` entry. A session of an older client recorded no limits, and
`/config` then shows `This activation recorded no limits and no sandbox.` until the next open.

## Why the turn waits

The status line shows a label only for a wait that needs a reason:

| Label | Reason |
|---|---|
| `the memory: 2 of 5 summaries` | Before the call to the policy, the memory tree makes summaries. The call waits for them. |
| `the turn stops` | A stop came, and the turn did not end yet. |

`/allow`, `/stopjob` and `/effort LEVEL` wait until the session records them, so a refusal shows at once, for example
`The session refused the command: the session has ended`. `/close` waits for its release plan, and its refusal starts
with `The release did not run:`. A message, `/stop` and `/stopall` do not wait.

After a stop, a job of the turn can still run. The client then prints
`The stop ended the turn, and a job of the turn still runs:`, a line for each such job, and the command that stops
it, for example `/stopjob 2.0 stops it.`

A message of another session, of the harness or of a job can start no turn. This occurs after 8 turns in a row with
no message of yours. The client prints the message, and then a notice:

```text
A message waits for you: the session ran 8 turns in a row with no message of yours, and the limit is 8. Type a
message, and the next turn reads it with the messages that wait. --unattended-turns N changes the limit at the next start.
```

`/status` shows the same notice, and the line `Turns in a row with no message of yours: 8 of 8.`
[Sessions and the controller](sessions.md) tells more about this limit.

## Plain lines and `--script`

With `--script`, and when the input or the output is not a terminal, the client prints plain lines. It has no live
block, no footer, no colors, no line editor and no history file. A step prints its reasoning in the view `short`, its
text, and the code of its cell in a `scala` fence. A call to `exec` prints `$ "command"` at its start, and a `spawn`
prints `[job] $ "command"`. The end of a cell prints its outcome (for example `> ok`) and its output.

In the script mode, each line waits until the session has no open turn, and the end of the input waits for the open
turn. So this command gives two turns, one after the other:

```bash
printf 'Count the files of src.\nCount the lines of README.md.\n' | scripts/dspy4s-harness --script
```

`/wait SECONDS` holds the next line for that time. Then the next line also waits for the open turn, so a script can
wait for a job that sends a message at its end. `/quit` and `/stop` wait for the open turn too. To stop a script, end
its process.

## Try it

1. Start the client with `scripts/dspy4s-harness` in a folder with a `README.md`. Type
   `How many lines does README.md have?`. You must see a status line such as `⠋ 1 s · step 1 of 10 · ctrl-c stops`,
   then `▾ Cell #N` with its code. At the end, you must see a summary line, the reply after `●`, and a last line that
   starts with `Done`.
2. Type `/effort`, then `/effort high`. You must see `↳ The effort is now high. The next step uses it.`, and
   `effort high` in the footer. (When the effort is already `high`, or the list has no `high`, use another level.)
3. Type `Write a story of 3000 words.`. While the live block shows the text, press Ctrl-C. You must see a last line
   that starts with `■ Stopped`.
4. Type `/status`. You must see `The folder: ...`, `No turn is open.` and `The effort: high.`. Then type `/show N`
   with the cell id of task 1. You must see the code, each call and the output of that cell.
5. Type `/config`. You must see the parts from `Session` to `Files`, `option reasoning_effort: "high"` in the part
   `Model`, and `network: off` in the part `Sandbox`.

Next: [The guest API](guest-api.md).
