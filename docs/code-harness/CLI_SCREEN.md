# The screen of the terminal client: a proposal

Status: a proposal for the review (2026-11-11), and phase A of section 9 is built. It covers each element that the
terminal client shows during a work session: the history, the part that runs now, the input and the footer. Section
10.6 of [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md) has the client as it is now. Section 12 gives each place
where phase A differs from this proposal. Section 14 gives the reasoning effort and the context of the model in the
footer (2026-11-12).

The proposal uses a study of seven terminal harnesses and of the desktop app of Claude Code (section 10). Section 9
gives the order of the work, and section 11 gives the open choices.

## 1. Goals

1. The person always sees that the session works, and why it waits when a wait is long.
2. The person sees what the agent did, at the level of detail that the person wants. The text of the model shows
   in full. The code, the calls and the output show as one summary line, and the person can open each one.
3. The words are short and plain. A spinner and a time already show that work runs, so a label shows only when it
   tells the person something new.
4. The design works now in the inline screen of JLine, and later in a full-screen client.

## 2. The four regions of the screen

```text
  history          the finished turns and steps, in the scrollback of the terminal
  live block       the step that runs now, redrawn about 8 times a second
  input            a rule, the prompt and the line of the person
  footer           one line of facts about the session, fixed at the bottom
```

A turn of a person, as it runs:

```text
› Why do the tests of the cart fail?

  I'll run the tests of the cart first.
  ▸ Ran 1 command · exit 1 · 4.2 s                                                  #12

  Two tests fail on the rounding of the total. Let me read the code of the total.
  ▾ Cell #17
    │ val code = read("src/cart/Total.scala")
    │ println(code.linesIterator.slice(40, 80).mkString("\n"))
    ⠹ read src/cart/Total.scala
⠹ 9 s · step 3 of 10 · ctrl-c stops · ctrl-o details
──────────────────────────────────────────────────────────────────────────────────────
› |
  ~/code/shop · work mode · example-model · memory 12k of 48k · 1 job
```

The same turn after its end, with the default detail:

```text
› Why do the tests of the cart fail?

  I'll run the tests of the cart first.
  ▸ Ran 1 command · exit 1 · 4.2 s                                                  #12
  Two tests fail on the rounding of the total. Let me read the code of the total.
  ▸ Read 1 file · 0.1 s                                                             #17
  The total rounds each line before the sum. Let me fix it and run the tests again.
  ▸ Edited 1 file, ran 1 command · 3.9 s                                            #22

● The tests of the cart failed because `Total.of` rounded each line before the sum.
  It now rounds only the sum, and the 14 tests of the cart pass.

  Done · 4 steps · 3 cells · 12 s
```

## 3. The levels of detail

A session has turns. A turn has steps. A step has the reasoning, the text, and at most one cell. A cell has code,
calls of the guest API, and output.

| Level | Collapsed form (one line) | Expanded form | Default |
|---|---|---|---|
| Turn | `▸ Turn 4 · Done · 4 steps · 12 s` | each step of the turn | expanded. Only `/collapse` or the transcript view collapses an old turn |
| Step text | | the text in full | always shown |
| Reasoning | `▸ Reasoning · 301 characters` | the text, dim, each line after `│` | collapsed |
| Cell | `▸ Ran 2 commands, read 1 file · 1.4 s  #17` | the code, each call, the output, the outcome | collapsed after its end; the cell that runs is open |
| Call | `✓ $ make test · exit 1 · 4.2 s  #17.0` | the call and all of its result | collapsed in a cell |
| Output | `+117 lines` | each line | the last 5 lines while it runs; collapsed after |
| Reply | | the text in full | always shown |

The collapsed form of a cell is a list of what its calls did, in the simple past, from the verbs of section 5. A cell
with no call says `Ran a cell · 12 lines of output`. A cell that failed starts with `✗`:

```text
  ✗ The cell did not compile · 2 errors                                              #17
  ✗ Ran 1 command, then the cell failed: java.io.IOException: no such file           #17
  ✗ The cell stopped at its time limit · 60 s                                        #17
```

The expanded form of a cell:

```text
  ▾ Ran 1 command · exit 1 · 4.2 s                                                  #12
    │ val out = exec("sbt cart/test")
    │ println(out.linesIterator.toVector.takeRight(20).mkString("\n"))
    ✗ $ sbt cart/test · exit 1 · 4.2 s                                              #12.0
      [error] Failed: Total 14, Failed 2, Errors 0, Passed 12
      [error] TotalLaws: the total of 3 lines is 10.01
      … +31 lines
    ✓ The cell ended · 20 lines of output
```

The numbers at the right are the ids of the journal: the cell id is the `seq` of its `Acted` entry, and a call is
`cell.n`, as a job name is now. `/show 12` prints the expanded form of the cell 12, and `/show 12.0` prints all of the
call 12.0, with no cut.

## 4. The live block

The live block is the step that runs now. It sits right above the input, as the activity line of Claude Code does.

- The text of the step comes first, when the model has written it.
- The open cell shows its code (at most 8 lines, then `… +N lines`), then one line for each call.
- A call that runs has the spinner. A finished call has `✓` or `✗` and its time.
- A command that runs shows its last 5 lines of output, dim, under it.
- Ctrl-O switches the live block between this compact form and the full form, as in ATC.
- At the end of the step, the client prints the step into the history in its collapsed form, or in its expanded form
  after Ctrl-O. The live block is then empty until the next step.

### 4.1 The status line

The last line of the live block is the status line. It replaces the spinner and the time in the prompt of PR #12, so
the line of the person does not move when the time changes.

```text
⠹ 9 s · step 3 of 10 · ctrl-c stops · ctrl-o details
```

- The time is the time of the turn: `9 s`, `1 m 51 s`.
- A label shows only for a wait that needs a reason. The label goes after the step:

| What runs | Label |
|---|---|
| a call to the model for the policy | none |
| the summaries of the memory, before the call to the policy | `the memory: 2 of 5 summaries` |
| a cell with no call | none |
| a command | none |
| a stop | `the turn stops` |

- With no open turn, the client shows no status line.
- The window title shows `● dspy4s · shop` during a turn and `dspy4s · shop` with no turn, as ATC does.
- The cursor is a blinking block during a turn (PR #12).

## 5. Calls of the guest API

Each call has one line. While it runs, it starts with the spinner. At its end, it starts with `✓` or `✗` and ends with
its time.

| Op | Line | Verb in the summary |
|---|---|---|
| `exec` | `$ sbt cart/test · exit 1 · 4.2 s` | ran N commands |
| `spawn` | `$ sbt ~compile · job 12.0` | started job 12.0 |
| `stopJob` | `stop job 12.0` | stopped job 12.0 |
| `read` | `read src/cart/Total.scala` | read N files |
| `write`, `edit` | `write notes.md`, `edit src/cart/Total.scala` | wrote N files, edited N files |
| `llmQuery`, `llmQueryBatched` | `ask the model · 3 questions` | asked the model N questions |
| `rlm`, `rlmBatched` | `run a sub-program: <first line of the task>` | ran N sub-programs |
| `load`, `store`, `keys` | `load plan`, `store plan`, `keys of the store` | read or wrote the store |
| `zoom` | `zoom 3.5` | read the memory |

- A command shows as `$ <command>` on one line. Each hidden character shows as an escape, with the rule of
  `visible` of PR #10. Section 11 asks if the quotes of PR #10 must stay outside the approval card.
- A command with an exit code that is not 0 is a failure: `✗` and `exit N`.

## 6. Approvals, jobs and notices

### 6.1 The approval card

A call that needs an approval ends the turn. The history then shows the card, in yellow:

```text
  ⚠ The call 23 needs your approval
    exec "git push origin main"
    the rule: exec with a command that starts with "git push"
    /allow 23 once · /allow 23 turn · /allow 23 session · or type what to do instead
```

The command in the card keeps the quotes and the escapes of PR #10, because there the person decides. The footer shows
`1 call waits` until the person answers.

### 6.2 Jobs

- The start of a job is part of the summary of its cell: `started job 12.0`.
- The message at the end of a job is one line in the history: `● Job 12.0 ended · exit 0 · 31 s  #12.0`.
- The footer counts the jobs that run: `1 job`.
- After a stop, the notice of PR #10 stays: `■ The turn stopped. The job 12.0 still runs: /stopjob 12.0`.

### 6.3 Other notices

| Event | Line |
|---|---|
| a new REPL | `● A new REPL started. These names are gone: a, b` |
| an error of the session | `✗ <the error>` in red |
| a message of the person during a turn | `↳ The next step reads your message.` |

## 7. The end of a turn

One dim line after the reply:

| Outcome | Line |
|---|---|
| `Replied` | `Done · 4 steps · 3 cells · 12 s` |
| `Aborted` | `■ Stopped · 2 steps · 14 s` |
| `Exhausted` | `Step limit · 10 steps · 2 m 3 s` |
| `Failed` | `✗ Failed: <error> · 3 steps · 63 s` |
| `Crashed` | `✗ The turn crashed · 3 steps · 63 s` |

## 8. The input and the footer

### 8.1 The input

- A dim rule `─` over the full width, then the prompt `› `. The person types on that line. A line that ends with `\`
  goes on to the next line, as now.
- During a turn the person can type. Enter sends the message, and the next step of the turn reads it, because the
  kernel gives each new message to the next step. The client prints `↳ The next step reads your message.`
- Up and Down give the history. Tab completes the commands and the lifetimes after `/allow`. The client of PR #10
  has no completion, so this part is new.
- Ctrl-C on an empty line during a turn stops the turn. Else it clears the line. Ctrl-D ends the client.
- The client does not use Esc as a key. JLine reads Esc as the start of an Alt key.

### 8.2 The footer

One line under the input, in a JLine `Status`:

```text
  ~/code/shop · work mode · example-model · memory 12k of 48k chars · 1 job · 1 call waits
```

- The folder, the mode and the model come from the `Activated` entry.
- `memory 12k of 48k chars` is the size of the last view of the memory and its budget. The journal does not have this
  value, so the host must give it, as it gives `Activity`.
- The count of jobs and of pending calls come from the state of the session.
- A part that has no value does not show.
- Section 14 adds the reasoning effort and the context of the model, and gives the unit of the memory part.

## 9. The order of the work

**Phase A: the inline screen, with JLine.** Each part uses JLine 4.4.6 of the build: `Display`, `Status`,
`BindingReader` and `KeyMap` are in its jars.

1. The live block and the status line are the prompt of the reader: JLine allows a prompt with more than one line,
   and the busy widget of PR #12 already sets the prompt again about 8 times a second. An experiment must confirm
   that JLine redraws a prompt of a changing height with no lost lines. Before Enter accepts a line, the widget sets
   the prompt back to `› `, so the live block does not stay in the history.
2. The history gets each finished step through `printAbove`, in its collapsed form.
3. The footer is a JLine `Status` of one line, as in ATC.
4. `/show N`, Ctrl-O for the live block, and `/reasoning` with the default "collapsed".
5. The line at the end of a turn.

**Phase B: the transcript view.** Ctrl-T opens a view of the whole session on the alternate screen, with JLine
`Display`. It shows the tree of section 3. ↑ and ↓ move, → or Enter opens, ← closes, `e` opens all, `c` closes all,
and `q` goes back. Codex has the same view on Ctrl-T. This gives the person each level of detail for each old turn,
as the desktop app of Claude Code does.

**Phase C, later: a full-screen client.** The study of 2026-11-11 chose TamboUI for a richer client. There, each
collapsed line opens with a click.

Each phase keeps the script mode as it is: plain lines, with no live block and no colors.

### 9.1 Colors and glyphs

- Only the 16 ANSI colors, so each terminal theme works. `NO_COLOR` turns the colors off.
- Green for `✓`, red for `✗` and errors, yellow for `⚠` and the approval card, dim for the reasoning, the output, the
  hints and the times, and one accent (cyan) for `›` and `●`.
- An ASCII set for a terminal with no Unicode, as in ATC: `*` for `●`, `+` for `✓`, `x` for `✗`, `!` for `⚠`, `>`
  for `▸`, `v` for `▾`, `|` for `│`, and `| / - \` for the spinner.

## 10. The sources

| Harness | What this proposal takes from it |
|---|---|
| Claude Code (desktop app) | the prose in full and the calls as summary lines that open (`Ran 5 commands ›`); groups in groups; the activity line above the input; the facts under the input |
| Codex | `Ran` and `Running` for commands; `Worked for 1m 3s` at the end; the status row above the input; a transcript view on Ctrl-T |
| ATC | the same parts (Scala, JLine `Status`); the footer; Ctrl-O for the block that runs; `/output N` for a kept result; `└ ok 12 ms`; the window title with `●`; the ASCII glyphs |
| pi | the last 5 lines of a command that runs; `Elapsed` and `Took` for each call; the rules around the input |
| opencode | `esc interrupt` next to the busy mark; one line for each tool; `▣ Build · model · 12.3s` at the end |
| DeepSeek (removed TUI) | only the 16 ANSI colors; `… +N lines (Ctrl+O to expand)`; a time for each phase |
| Hermes | the interrupt hint during a turn; a time for each call |

Most of these harnesses show no word for a call to the model. Codex and pi show `Working`, opencode and DeepSeek show
only a mark, and only ATC says `waiting for model`.

## 11. Open choices

1. The prompt: `›` (Codex, Hermes) or `>` (now).
2. The quotes of a command: only in the approval card, or on each call line as in PR #10.
3. The time on the status line: the time of the turn (this proposal), or the time of the part that runs (PR #12).
4. The text of each step: in full (this proposal, as Claude Code does), or only in the expanded form of a step.
5. The writing rules forbid the "-ing" forms, so this proposal uses the simple past (`Ran`, `Read`) and no word for a
   call to the model. Can a label of the screen use a word such as `Working`?
6. The keys: Ctrl-O for the live block and Ctrl-T for the transcript view, or one key for both.

## 12. What phase A changed in this proposal

Phase A follows the defaults of section 11. These points differ from the text above:

1. The journal gets the output of a call only at its end. So the live block shows no output while a command runs. A
   later change can send the last lines of a command from the host through `Activity`.
2. The reasoning has a fourth view, `line`, which is the default of the line editor: one line with the size of the
   reasoning. `/reasoning off|line|short|full` sets it. The plain lines keep `short` as their default.
3. The id of a call shows only after the end of the call, because the id is for `/show`.
4. `/status` says `Step 2 of 30.` during the call to the model, with no words.
5. The host gives the memory of the footer only after the first call to the policy of an activation, so after a
   reopen the footer has no memory part until the next step.
6. Ctrl-O switches the detail of the live block, and of each cell that ends after the switch. The history keeps each
   cell as it was printed.

## 13. The text of a step while the model writes it

During a call to the model, the live block showed only the status line. In the live runs, one call took up to 44 s,
and minutes with the reasoning of the model on. These choices were made with the user on 2026-11-11:

- The live block shows the reasoning and the text of a step while the model writes them. The code shows when the
  step commits, as before.
- Only the call to the policy streams. A work session never calls the finisher. The summaries of the memory, the
  `llmQuery` leaf and the probes show nothing until they end.
- Only the line editor shows it. The plain lines and the script mode do not change.

How it works:

- The text that the model writes now is a draft. A draft is not state ([CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md),
  4.1: a stream delta is transient). It never goes into the journal, and a crash loses it.
- The host keeps the draft of the call that runs, and a client polls it as it polls the activity
  (`SessionHandle.draft`). So a slow client never makes the model wait, and a client loses no part of a draft.
- A draft holds the native reasoning and the reply as it came, with no parse. `Draft.text` gives the text of the step
  from the reply so far, with the rules of `PolicyAdapter.values` and of the chat format: the section of `text`, else
  the reply before the first marker, and never a section of `code` or a ```` ```scala ```` fence. A last line that
  can still become a marker or a fence waits for its line end, so a marker never shows. For the whole reply,
  `Draft.text` is the text of the step.
- The stream of the policy is a preview: its tokens do not count as output that a person saw. So the predictor still
  tries the fallback adapter after a parse error, and a step does not depend on a client. A new model call of the same
  step, for example the call of the fallback adapter, starts a new draft.
- A stop can cut a call whose thread goes on. Each call to the policy has a number, and the end of the call closes
  it, so a late token of a cut call shows nowhere.
- `DSPY_TRACE` keeps the stream. The traced model streams when its model streams, and it writes the line of a call at
  the end of its stream.
- The live block shows the reasoning in the view of `/reasoning`: `off` shows nothing, `line` shows its size, `short`
  shows its last 3 lines, and `full` shows its last 12 lines. Then it shows the last 16 lines of the text, with
  `… +N lines` above them.

The live run of 2026-11-11 used the line editor in a pseudo-terminal of 120 columns and 40 rows, the private endpoint
with the open model of 27B parameters, native reasoning, and `DSPY_TRACE`:

- A story of about 300 words showed in the live block paragraph by paragraph, for 2.5 s before its step committed.
  The reasoning line grew while the model wrote, and no marker and no fence showed on the screen.
- A longer story showed its last 16 lines with `… +1 line` above them. Ctrl-C during the call ended the turn
  `Aborted` 0.5 s later, and the live block then had no draft.
- A turn with a cell read the file in two steps and replied `notes.txt has 42 lines.`, as in the live runs before
  this change. The trace has one line for each call, also for a call that streamed.
- A call to the model of a short step took 0.3 to 0.6 s, and the summaries of the memory before it took 1 to 2.5 s.
  So in a short step, the draft shows only for a few frames.

## 14. The effort and the context in the footer

Status: built on the branch `cli-footer-effort` (2026-11-12). The choices are in 14.3, and 14.7 gives each place where
the code differs from the plan. In a live session, the footer was this line:

```text
  ~/code/api · work mode · example-model · memory 64 of 40k
```

The person asked what `memory 64 of 40k` means. The person also asked for three new facts and one new command:

- the footer shows the reasoning effort of the model;
- a command changes the effort;
- the footer shows the size of the context of the model, and the percent of it that the session uses.

### 14.1 The memory part now

The memory part counts characters, not tokens.

- `64` is the size of the view of the memory that the last call to the policy got (`Projection.size`):
  the status section, the last observation and the earlier steps. At the start of a session, the view is small.
- `40k` is `HarnessLimits.budget`, 40,000 characters. When the view gets larger than the budget, the memory tree puts
  summaries in place of the older lines (7.1 of [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md)).
- The instructions, the task and the API reference are not in the count. So the part does not tell how full the
  context of the model is.

The budget of the memory is a separate choice from the context of the model. With a context of 1,048,576 tokens, the
budget of 40,000 characters is a small part of it.

### 14.2 What the code and the endpoint give now

- **The effort is always `low`.** `NativeReasoning` takes `reasoning_effort` from the options of the request, else
  from the default options of the model, else `low`. The output of the policy (`PolicyStep`) has a `Reasoning` field,
  and `DSPY_NATIVE_REASONING=true` gives the model the reasoning capability. The `Predict` of the policy has no
  options. So each call to the policy asks for `low`. This comes from the code. No trace confirms it yet.
- **The configuration has a place for the effort.** `ModelChoice.options` is the option bag of the provider in the
  `SessionConfig`. No code puts it in a request yet. No command changes the configuration of a session: only
  `Activate` and `Begin` give one.
- **The name `/reasoning` is already a command.** It sets how much of the reasoning of a step the client shows.
- **The private endpoint gives the facts of each model.** Its `GET /models` gives, for each model, `context_window`,
  `supports_reasoning` and `reasoning_effort_levels`. The model of the live session has a window of 1,048,576 tokens
  and the levels `low` and `high`. These fields are not in the standard API. vLLM gives `max_model_len` in their place.
  The ids of the endpoint have the prefix of an owner, for example `owner/example-model`, and `DSPY_MODEL` has none.
- **No code keeps the token counts.** Each reply has `usage.prompt_tokens` (`LmResponse.usage`), and a stream asks
  for it with `stream_options.include_usage`. The harness does not read it. The callbacks get it, because the
  `LmEndEvent` of a call holds its `LmResponse`.

### 14.3 The choices

These choices were made with the user on 2026-11-12. Each item gives the option that was taken, then the option that
was not taken.

1. **The place of the effort: a new entry of the journal.** A reopen keeps the effort, and the journal tells which
   effort each step used, also for an optimizer. Not taken: a value of the host only. That change is smaller, but a
   reopen loses the effort, and the journal does not record it.
2. **The name of the command: `/effort`.** `/reasoning` already sets the view. Not taken: `/reasoning effort LEVEL`.
3. **The level `none` turns the reasoning off.** This choice changed after the probe (14.6). `/effort none` sends the
   text `none`, and the endpoint takes it. `NativeReasoning` treats only a null as no effort, so the prompt stays the
   same, and the reasoning of a step is empty. The list of the endpoint does not give `none`, so the client adds it
   to the levels of each model. Not taken: no level to turn the reasoning off, as in the first plan. Also not taken: a
   null effort, which changes the prompt, because the reasoning becomes a text field of the output.
4. **The token counts go into the journal in a later step.** 4.2 of the design names a fold of the usage totals, and
   no code has it yet. Not taken: the counts in the `Acted` entry of each step, in this change.
5. **A narrow terminal: the memory part goes first.** When the footer is wider than the terminal, it shows no memory
   part. Not taken: the footer cuts the line at the width of the terminal.

### 14.4 How it works

1. **The effort in the journal (kernel).**
   - A new command `ChangeModel(model: ModelChoice)` gives a new entry `ModelChanged(model)`. The fold sets
     `config.model` of the state. An open session takes the command at each time, also during a turn. A closed
     session refuses it.
   - The command takes a whole `ModelChoice`, so a later `/model` can use it. This plan changes only the options.
   - A new session with native reasoning gets the key `reasoning_effort` at its start: the value of the new variable
     `DSPY_REASONING_EFFORT`, else `low`. So the configuration always holds the effort that the requests send, and the
     footer does not repeat the default of the adapter.
2. **The effort in the request (harness).**
   - `HarnessEffects.callPolicy` gives `config.model.options` of the state of the journal to the call to the policy,
     as the options of the call. So a change applies at the next call to the policy.
   - Only the call to the policy. The summaries of the memory keep `DSPY_SUMMARY_OPTIONS`, and `llmQuery` keeps its own
     options.
3. **The facts of the model (CLI).**
   - At the start, a daemon thread reads `GET <OPENAI_BASE_URL>/models`, with a timeout of 5 s. It takes the entry
     whose id is `DSPY_MODEL`, or ends with `/` and `DSPY_MODEL`.
   - It reads the window from `context_window`, else from `max_model_len`, and the levels from
     `reasoning_effort_levels`. The new variable `DSPY_CONTEXT_WINDOW` wins over the endpoint.
   - When the request fails, the footer has no window and the client takes the levels `low`, `medium` and `high`.
     The start does not wait for the request.
   - The client adds the level `none` to the levels of each model (14.3, choice 3).
   - The key goes to the same base URL as the calls to the model. The doc of `Cli` must say so.
4. **The tokens of the last call (host).**
   - A callback in the context of each call to the policy keeps the `usage` of its `LmEndEvent`, as
     `PolicyStream.around` does for the draft. When a step makes two model calls, for example with the fallback
     adapter, the last call wins.
   - The meter of the activation gives `ContextUse(prompt, completion)` next to `MemoryUse`, and a client reads it
     through `SessionHandle`, as it reads the memory. A summary or a query does not change it.
   - After a reopen, the footer has no context part until the next step, as for the memory (12, item 5).
5. **The footer, `/effort` and `/status` (CLI).**

   ```text
     ~/code/api · work mode · example-model · effort low · context 18k of 1M (2%) · memory 64 of 40k chars
   ```

   - `effort low` is the effort of the configuration. With no `reasoning_effort` key, for example in an older
     journal, the part does not show.
   - `context 18k of 1M (2%)` is the count of prompt tokens of the last call to the policy, the window, and the percent
     as a whole number. The next call is larger, because it also holds the last step. With no window, the part is
     `context 18k`. Under 1%, the percent is `<1%`.
   - A count under 1,000 shows as it is. A count under 1,000,000 shows in whole thousands (`18k`). A larger count shows
     in millions with one decimal and no `.0`, so 1,048,576 is `1M` and 1,572,864 is `1.6M`.
   - `memory 64 of 40k chars` gives the unit of the memory part.
   - `/effort` shows the effort and the levels of the model. `/effort high` sends `ChangeModel` and prints
     `The next step uses the effort high.` A level that is not in the list gives an error with the list. Tab completes
     the levels, as it completes the views after `/reasoning`.
   - `/effort none` turns the reasoning off for the next steps, and the footer shows `effort none`. A step with this
     effort has no reasoning, so the screen shows no reasoning for it.
   - `/status` also gives the effort and the context. The plain lines and the script mode take `/effort` too. Only
     the line editor has the footer.

### 14.5 The order of the work

1. A probe, with no code: two small requests with `low` and `high` to the endpoint, to compare their
   `reasoning_tokens`. This shows if the endpoint uses the effort. A second check: a streamed call gives `usage` at
   its end. Done on 2026-11-12: 14.6 has the results.
2. The laws come first in each module, and each one fails before its change:
   - kernel: `ChangeModel` gives `ModelChanged` in an open session, also during a turn, and a closed session refuses
     it; the fold sets the model; the entry goes through its `Schema` with no change;
   - harness: a fake model gets the effort of the configuration, and after a `ModelChanged` the next call to the policy
     gets the new effort; a summary does not get it; with the effort `none`, the request has
     `"reasoning_effort": "none"`, the prompt has no reasoning field, and a reply with no reasoning gives a step;
   - host: the meter gives the prompt tokens of the last call to the policy, and a summary or a query does not change
     them;
   - CLI: the facts of a `GET /models` text, with `context_window`, with `max_model_len`, with an owner prefix and
     with no entry; the parts of the footer, also with no window and with no effort; the counts `18k`, `1M` and
     `1.6M`; `/effort` with no level, with a level of the list, with `none` and with a level that is not in it.
3. The code of each module, in the order kernel, harness, host, CLI.
4. A mutant for the rule of `ChangeModel` and for the options of `callPolicy`, the nodes of the blueprint, and 8.2.
5. A live run in a pseudo-terminal: `/effort high`, a step, and the footer before and after it.

### 14.6 The probe of 2026-11-12

The probe sent small chat requests to the private endpoint, with no harness. The question was a count of
permutations, which takes about 2,000 tokens of reasoning. Each number is the `reasoning_tokens` of one request to the
model of the live session.

| request                                  | reasoning tokens      |
| ---------------------------------------- | --------------------- |
| no effort                                | 1,550                 |
| `"reasoning_effort": "low"`              | 2,080, 2,156, 2,300   |
| `"reasoning_effort": "high"`             | 2,158, 1,908, 2,496   |
| `"reasoning": {"effort": "low"}`         | 1,624, 1,765, 1,747   |
| `"reasoning": {"effort": "high"}`        | 9,557, 2,139, 2,354   |
| `"none"` in each of the two forms        | 0, 0                  |

The requests with 2,300 and 2,496 tokens used the id with the owner prefix. The results:

- The endpoint reads `reasoning_effort`. A level that is not `none`, `minimal`, `low`, `medium`, `high` or `xhigh`
  gives the status 400. `medium` gives no error for the model of the session, but its list has only `low` and `high`.
- For the model of the session, `none` turns the reasoning off. `low` and `high` gave no clear difference: one
  request with `high` used 9,557 tokens, and the other five were in the range of `low`.
- A second open model, of 20B parameters, used 499 tokens with `low`, and the limit of 16,000 tokens with `high`. So the
  endpoint gives the effort to a model whose template uses it.
- That second model gives `reasoning_tokens` 0 also when it reasons. So the context part uses only `prompt_tokens`.
- A stream with `include_usage` gives the usage in its last chunk.
- The first stream waited 266 s for a reply of 64 tokens, and the next one took 0.5 s. The probe does not show the
  cause.

What this changes in the plan:

- The requests keep the key `reasoning_effort`. The form `reasoning` gave no clearer difference.
- For the model of the session, `/effort high` can give the same reasoning as `low`. The footer shows the level that
  the requests send, and the help of `/effort` does not say that a higher level gives a longer reasoning.
- Choice 3 has a new fact. The endpoint takes the level `none`, and `NativeReasoning` treats only a null as no effort.
  So the text `none` keeps the prompt as it is, and the model gives no reasoning. The list of the model does not
  give `none`. On 2026-11-12, the user took `none` for the first version (14.3, choice 3).

### 14.7 What the code changed in the plan

- `DSPY_REASONING_EFFORT` applies also with no native reasoning. A person who sets it wants the effort in the requests.
  Only the default `low` needs `DSPY_NATIVE_REASONING=true`.
- The client reads `GET /models` only with `DSPY_MODEL`. So a client with no model sends no request to the default base
  URL.
- `/effort LEVEL` waits for its commit, as `/close` does, so the person sees a refusal, for example after the end of
  the session.
- The percent of the context is the nearest whole number, so `18k of 1M` is `2%`. Under 1% it is `<1%`.
- The tokens come from a usage tracker around the call to the policy (`UsageScope.track`). A context with
  `trackUsage = false` gives no context part. When a step makes two model calls, the last one gives the tokens.
- A `ModelChanged` shows one line in the history: `The effort is now high. The next step uses it.`
- The host and the CLI have no file of mutants. The new mutants are in the kernel (3) and in the harness (3).
