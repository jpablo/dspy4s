# The screen of the terminal client: a proposal

Status: a proposal for the review (2026-11-11), and phase A of section 9 is built. It covers each element that the
terminal client shows during a work session: the history, the part that runs now, the input and the footer. Section
10.6 of [CODE_HARNESS_DESIGN.md](CODE_HARNESS_DESIGN.md) has the client as it is now. Section 12 gives each place
where phase A differs from this proposal.

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
| a command, while the walk for secret files runs | `the scan for secret files: 640,000 folders` |
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
  ~/code/shop · work mode · example-model · memory 12k of 48k · 1 job · 1 call waits
```

- The folder, the mode and the model come from the `Activated` entry.
- `memory 12k of 48k` is the size of the last view of the memory and its budget. The journal does not have this
  value, so the host must give it, as it gives `Activity`.
- The count of jobs and of pending calls come from the state of the session.
- A part that has no value does not show.

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
   later change can send the last lines of a command from the host, as `Activity` does for the walk for secret files.
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
