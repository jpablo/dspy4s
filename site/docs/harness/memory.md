# Memory

At each step, the policy call gets a projection of the journal. The projection holds the status, the output of the
last cell, the earlier steps of the turn, and the earlier turns. The context strategy of the session makes it. The budget of a
projection is 40,000 characters (`HarnessLimits.budget`). The memory tree puts its view on top of the budget, in at
most 100,000 more characters (`HarnessLimits.viewChars`). The budget counts characters, because the harness has no
tokenizer.

This page tells how the memory tree keeps the earlier turns of a long session. It also tells how a cell reads a full
text with `zoom`, and what the summary calls cost.

## The two strategies

| Strategy | Sessions | The earlier turns in the projection |
|---|---|---|
| Window | a program call (by default), a child of `rlm`, and a session whose configuration has the strategy `Window` | one message for each earlier turn, with its messages and its reply. The newest messages that fit the budget stay, but the steps of the open turn that heard a message come first, so a long turn keeps its question. |
| Memory tree | each new session of the terminal client, and the controller | a view of summary lines, oldest first. `zoom(ref)` gives the full text of a part. |

Both strategies cut a long output, text or code with no model call. They add a note with the number of characters
that the cut removed. The open turn is the same in both: the steps of the turn that fit the budget.

The strategy is a field of the configuration of the session, so a session keeps it when it opens again. A session that
the controller starts with `start(goal, scope, strategy)` gets the strategy of that call (see
[Sessions and the controller](sessions.md)). `StrategyName.PrefixSummary` has no code yet, so a session with it does
not start.

## What the memory tree keeps

The memory log has one message for each step of the session. The text of each message starts with its speaker:

| Message | Text |
|---|---|
| A message of the person | `The person wrote: ...` |
| A message of the harness, for example the end of a job | `The harness wrote: ...` |
| A cell and its output | `The model ran a cell: ...`, then the text, the code and the output |
| The end of a turn | `The model replied: ...` |

A turn that ends in another way gives the text of its report. The control entries of the journal give no message.

The memory tree builds a binary tree of lines over the log:

- **Level 0.** One line for each message. A message of 512 bytes or less is its own line, with no model call. For a
  longer message, the leaf `compress` writes one summary line. It gets the first 16,000 characters of the message
  (`MemoryTree.MaxSource`), and its instructions ask for one line of at most 400 characters.
- **Level 1 and higher.** The part `level.index` covers the messages from `index * 2^level` up to
  `(index + 1) * 2^level - 1`. The leaf `merge` writes its line from the lines of its two halves. For example, `1.0`
  covers the messages 0 and 1, and `2.1` covers the messages 4 to 7.
- **The size of a line.** A line has at most 512 bytes of UTF-8 (`MemoryLine.MaxBytes`). When a reply of `compress`
  or `merge` is longer, the leaf `shorten` gets that reply and writes a shorter line. When the reply of `shorten` is
  also too long, the harness cuts it at the end of a character.

On this page, a call of `compress`, `merge` or `shorten` is a summary call. The memory tree builds only the parts that
the view needs: each part of the view of all the messages, and each part under it. Each message is under a part, so a
message of more than 512 bytes always gets its `compress` line. A view that holds each message as a part of level 0
needs no `merge` call.

The lines go to the table `memory` of the session file `<root>/sessions/<id>.db`. The key of a line holds a SHA-256 of
the texts under the part, so a line never goes to other messages. When a session opens again, the memory tree reads
its lines, and it makes no new summary call for them.

## The view

The view is a list of parts that cover the messages up to the reply of the last turn, oldest first. The policy call
gets it in the input field `memory`. After one short turn, the view has three parts of level 0:

````text
The memory of this session before this turn, oldest first. Each line is [ref] summary. Read a part with zoom(ref).
[0.0] The person wrote: How many lines does README.md have?
[0.1] The model ran a cell: I count the lines. ```scala println(read("README.md").linesIterator.size) ``` 42
[0.2] The model replied: README.md has 42 lines.
````

- The view counts at most 100,000 characters (`HarnessLimits.viewChars`). A message of 512 bytes or less counts its
  characters, and each other part counts 512, each with its reference and its newline. The count reads only the
  messages, so a summary never changes the view. A session of about 150 short messages keeps each message as its own
  part of level 0.
- A new message can give the view more than 100,000 characters. Then the view puts the part of the next level in place
  of its two halves, the oldest pair first. It continues until its lines take 50,000 characters or fewer.
- The view gets its own room on top of the budget: the header, its newline and the 100,000 characters, which is
  100,115 characters in all. The harness never cuts the view. The open turn gets the whole budget of 40,000.
- The view stays the same during a turn. The first turn of a session has no view.
- Each line break in a summary line becomes a space, so each part is one line of the text.

## Read a part with zoom

A cell reads the full text of a part with this function of the guest API:

```text
def zoom(ref: String)(using t: Turn): String
```

`zoom` needs no capability. Each mode binds `Turn`, so a cell can call `zoom` in each mode.

- A reference of level 0, for example `0.3`, gives the whole text of that message, with its speaker.
- A reference of a higher level, for example `2.1`, gives the lines of its two halves. Each line has its reference:
  `[1.2] ...` and `[1.3] ...` on two lines.
- A part that the memory does not have gives an error that names the parts of the view and the range `0.0` to `0.n`
  of the messages. Each of them zooms, so the model can zoom a part of the view and then zoom a half of it.
- A text of another form gives the error `x is not a reference of the memory. A reference is level.index, for example
  3.5.`
- In a session with the window, `zoom` gives the error `this session has no memory tree, so zoom has nothing to read`.

An error comes into the cell as an exception, so the cell fails with the text of the error. The output of the cell
also has the trace of the exception. The trace keeps the first frame of the harness, which shows where the host threw.
One line such as `... 9 frames of the guest API call` replaces the other frames of the call.

A typical cell reads the message under a short line:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/memory.scala:zoom-a-message"
```

A cell can also go down the tree, and then search the whole text of a message:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/memory.scala:zoom-down"
```

In the terminal client, each `zoom` call of a cell has a line such as `zoom 0.0`, and the summary of the cell says
`Read the memory`. A zoom of the controller into another session also names that session, for example `zoom s-1: 0.0`.

## When the summary calls run

- A builder makes the summary calls in the background, on a thread of the session. It starts with the first turn of
  an open, and it builds after each new entry of the journal. A turn never waits for it.
- At the first step of each turn, the memory tree adds the new messages of the journal to its log, and it reads the
  lines that the builder stored. A part with no stored line yet gets the start of its text as a stand-in line. The
  later steps of the turn use the same memory, so a line that the builder stores during a turn comes to the view in
  the next turn.
- The journal records the build number of the lines that the first step read, in a `DerivedRead` entry. The inspector
  shows it as a part of the step.
- A summary call starts only when no policy call of the session waits for its reply, because both go to the same
  endpoint. A summary call that runs goes on to its end.
- The summary calls of one round do not need each other, so they run at the same time, at most 4
  (`HarnessLimits.batch`). The rounds run one after the other.
- While the summary calls run, the footer and the status line show a label, for example
  `the memory: 2 of 5 summaries`, also between turns.
- Each turn has a budget of 64 summary calls (`HarnessLimits.summaryCalls`), apart from the 60 model calls of the
  turn (`HarnessLimits.modelCalls`). After the budget, the builder waits for the next turn. The store does not keep a
  stand-in, so the builder makes the part again in a later turn.
- A failed summary call gives the start of its text as the line, and the store keeps that line. So one bad message
  does not stop the session.
- A stop of a turn does not stop the builder. The close of the session stops it and cuts its calls, and the store
  keeps the lines that ended.
- A cell that calls `zoom` reads the newest lines of the builder.

Each summary call is a model call, but no step waits for it. The time depends on the endpoint, the model, and the
options of the summary requests. To measure the cost, run the same task with and without `--no-memory-calls`.

## Turn off the summary calls

```bash
dspy4s-harness --no-memory-calls
```

- The memory tree makes no summary call. A message of more than 512 bytes shows its first 512 bytes in
  the view. A part above level 0 shows the start of the lines of its two halves.
- `zoom` still gives the whole text of a message. So the model must use `zoom` to read a fact after byte 512.
- The store does not keep these lines. A later run without the flag builds the summaries.
- The flag applies to each session of the host: a new session, a session that opens again with `--session`, and the
  controller.
- `--inspect` does not take the flag.

## The options of the summary requests

`DSPY_SUMMARY_OPTIONS` holds a JSON object. Each summary request sends it as the option bag of the
provider. The variable can come from the environment or from the file `~/.dspy4s/harness/.env`.

| `DSPY_SUMMARY_OPTIONS` | `DSPY_NATIVE_REASONING` | The options of each summary request |
|---|---|---|
| not set, or blank | `true` | `{"reasoning_effort": "none"}` |
| not set, or blank | not set, or another value | no option |
| `{}` | any value | no option |
| another JSON object | any value | that object |
| a text that is not a JSON object | any value | the client stops with an error and the exit code 2 |

A summary line needs no reasoning. Set the options that turn off the reasoning on your endpoint. For example, a model
on vLLM that reasons can take this object:

```bash
export DSPY_SUMMARY_OPTIONS='{"chat_template_kwargs": {"enable_thinking": false}}'
```

`/effort` changes only the policy calls. It does not change the summary requests.

## The memory part of the footer

```text
  ~/code/shop · work mode · my-model · effort low · context 18k of 1M (2%) · memory 13k of 140k chars
```

- `memory 13k of 140k chars` gives the size of the last projection that a policy call got, and its largest size, in
  characters: the budget of 40,000 and the room of the view. The projection holds the status, the last output, the
  steps of the open turn, and the view.
- The instructions, the task and the API reference are not in the count. So this part does not tell how full the
  context of the model is. The `context` part tells that.
- The client shows whole thousands, for example `13k`.
- After a session opens again, the footer has no memory part until the first step.
- In a narrow terminal, the folder gets shorter first. Then the memory part goes.

## Try it

1. Start a session with `dspy4s-harness` in a test folder. Paste this message on one line, and press Enter:

    ```text
    Notes for the shop service. The service has three parts: the web front end, the cart API and the payment worker. The front end is a single page app that talks to the cart API over HTTP. The cart API keeps the carts in Postgres and sends the paid orders to a queue. The payment worker reads the queue, calls the payment provider and writes the result back to the cart API. Each part has its own folder, its own build file and its own tests. The team runs the tests in CI for each pull request, and a release needs two approvals. The logs go to the shared log server, and each part writes one JSON line for each request. The staging port of the cart API is 9917. Reply only with OK.
    ```

    The memory message has 698 bytes, and the port starts at byte 673. Then type
    `What is the staging port of the cart API?`. You must see a reply with `9917`. After the first reply, the footer
    can show `the memory: 0 of 1 summaries` while the builder makes the line of the long message.

2. Type `/quit`. Start a new session with `dspy4s-harness --no-memory-calls`, and send the same two messages. Now the
   line `[0.0]` of the view stops before the port. You must see a cell with the call line `zoom 0.0`, and then a
   reply with `9917`.

3. Compare the two sessions. Look at the time on the end line of each turn, and at the `memory` part of the footer.

4. Run `dspy4s-harness --inspect`, and open the two sessions (see [The inspector](inspector.md)). The first session
   has an exchange row of `compress`, and of `shorten` for a reply that was too long. It has no row of `merge`,
   because its view needs no merge. The second session has only exchange rows of `policy`.

Next: [The inspector](inspector.md).
