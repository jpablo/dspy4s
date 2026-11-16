# Sessions and the controller

A session is one conversation with the model, and its journal keeps each step. Without flags, the terminal client opens
one work session in a folder. With `--controller`, it opens the controller: a session that never ends, that starts
work sessions, sends them messages, and gets their reports.

This page tells how the controller starts sessions, where their folders are, how they report, and how they end.

## One session or many

| Command | The first session that the client shows |
|---|---|
| `scripts/dspy4s-harness [FOLDER]` | A new work session in `FOLDER`, by default the current folder. |
| `scripts/dspy4s-harness --session ID` | The session `ID` again, with its own configuration. |
| `scripts/dspy4s-harness --controller` | The controller of the root. |

A work session that you open has the scope `Directory(FOLDER)`, the owner `the person`, and the context strategy
`MemoryTree`. The controller has the fixed id `controller`, one for each root. It works in your home folder (the scope
`Home`). It can read files, ask the model, use its store and use the other sessions. It has no shell, so `write`,
`edit` and `exec` do not compile in its cells.

The task of the controller lists the commands of the client. So you can ask the controller which command to type,
for example to change the reasoning effort. Only you can type a command.

One client process holds the controller and each session that it starts. The reports reach the controller while the
client runs. When the client ends, the host closes each open session and stops its jobs. The journals stay.

## The files of a host

The root of the host is `~/.dspy4s/harness`, and `--root DIR` changes it. The host makes the folders of `start` in
the folder `work` beside the root. The reason is that the sandbox hides the root from the commands of a session.

| Path | Content |
|---|---|
| `~/.dspy4s/harness/sessions/<id>.db` | The journal of a session (SQLite). |
| `~/.dspy4s/harness/catalog.db` | The catalog: one row for each session. |
| `~/.dspy4s/harness/probes/<caller>.<cell>.<n>.db` | The journal of one `observe` call. |
| `~/.dspy4s/harness/archive/<id>` | The scratch folder of an abandoned session. |
| `~/.dspy4s/work/<id>` | The folder of a `Scratch` or a `Worktree` scope of `start`. |

## Scopes

A scope tells where a session works. `start` takes three scopes:

| Scope in `start` | What the host does | The folder of the session |
|---|---|---|
| `Scope.Directory(path)` | It uses the folder. `path` must be the absolute path of a folder. | `path` |
| `Scope.Worktree("", branch, repo, base)` | It makes a local clone of `repo` with the new branch `branch` from `base`, and it makes the same branch in `repo`. | `~/.dspy4s/work/<id>` |
| `Scope.Scratch("")` | It makes a new empty folder. | `~/.dspy4s/work/<id>` |

- Give an empty path to a `Worktree` and to a `Scratch`. The host makes the folder.
- Give `repo` as an absolute path. `repo` must not have the branch `branch` yet, and `branch` must not be `base`.
- `start` refuses `Home`, `Fork`, and a repository inside the folder of a session that has not ended.
- Before a `start` with a `Directory` scope, the controller asks you, because that session works in your folder.
  Type `/allow` to approve the call. [The sandbox and approvals](sandbox.md) tells more.

The clone of a `Worktree` comes from `git clone --shared --single-branch`, and the session commits on its branch
there. After each turn, the host puts the tip of the branch into `repo` with `git push --force-with-lease`. git refuses
this push when you moved the branch in `repo`, or when the branch is checked out there. Then the session gets one
message that starts with `The host did not update the branch`, and the commits stay in the clone.

## Context strategies

The third argument of `start` is the context strategy of the new session (`StrategyName`):

| Name | The context of the model |
|---|---|
| `StrategyName.MemoryTree` | The memory tree of the earlier turns. See [Memory](memory.md). |
| `StrategyName.Window` | The earlier turns as messages. When they do not fit, the oldest turns go first. |
| `StrategyName.PrefixSummary` | It has no code yet. Do not use it. |

## The guest API of the controller

The mode of the controller gives `Files` (read only), `Llm^`, `Sessions^` and `Store^`. These functions use
`Sessions`:

| Function | What it does |
|---|---|
| `start(goal, scope, strategy): SessionRef` | It starts a work session in the background, with `goal` as its first message. |
| `sessions(): Vector[SessionCard]` | It gives the card of each other session of the host. |
| `tell(session, message): Unit` | It sends a message, and it returns after the session keeps it. A session that has ended gives an error. |
| `observe(session, question): String` | A probe: a read-only program reads the journal of the session and answers. |
| `zoom(session, ref): String` | It gives a part of the memory of the session. See [Memory](memory.md). |

A cell of the controller that starts a scratch session:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sessions.scala:start-scratch"
```

The new session gets a random UUID as its id, and the owner `the session controller`. Its mode gives `Files^`,
`Shell^`, `Llm^` and `Store^`, with no network and no `Sessions`, so it cannot start sessions. It uses the model of
the controller. Its task tells it to end the work with a cell that calls `submit("...")` with the result as a text.

A list of the other sessions, a message, and a probe:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sessions.scala:list-sessions"
```

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sessions.scala:tell-and-observe"
```

A probe has only `Llm^`, so it cannot read files or run commands. Its model calls use the budget of the current turn
of the controller. A session in a clone of a repository:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sessions.scala:start-worktree"
```

## Reports

A session that the controller started sends reports to the controller. A report is a message with the outcome of a
turn or of the session, and it starts a turn of the controller. The client shows it in a line with
`[the session <id>]`, for example `[the session <id>] The turn ended with submit: <value>`.

A report of a turn has a second line: `The session <id> stays open. To give it more work, send it a message with
tell.` A session stays open after each turn. So, for a change to the work of a session, ask the controller to `tell`
that session. A new session starts again from an empty folder.

- The goal of `start` is a message from the controller. After a message from the controller, each end of a turn
  gives one report, until the work ends. This rule also applies to a turn that your message starts.
- The work ends at a turn that ends with `submit`, or at a turn that used all its steps. It also ends at a stop, and at
  the end of the session. A turn that ends with a reply does not end the work, because the reply can hold a question.
- A report that the controller did not get comes when the controller opens again. The controller gets each report one
  time.
- After 8 turns in a row that no message of the person started, a message from another sender waits in the journal.
  Your next message starts the turn that reads it.

The text gives the outcome in words, for example `The turn ended with a reply: <reply>`,
`The turn crashed: its host stopped during the turn.` or `The session ended: <outcome>, <reason>.`

## The session card

`sessions()` gives a `SessionCard` for each other session, and `/sessions` prints one line for each card.

| Field | Value |
|---|---|
| `session` | The `SessionRef` of the session. |
| `goal` | The goal of `start`, or an empty text. |
| `scope` | The scope, or `None` before the session starts. |
| `owner` | `the person`, `the session <id>`, `an optimizer` or `a cell`. |
| `parent` | The parent of a fork. No code makes a fork today, so it is `None`. |
| `status` | `idle`, `in a turn`, `stopping`, `releasing`, or `ended (<outcome>)`. The outcome is `merged`, `parked`, `abandoned` or `replaced by <id>`. |
| `lastSeq` | The `seq` of the last entry of the journal. |
| `read`, `made`, `commands` | The number of files that the session read, the paths that it wrote or edited, and the number of its commands. |
| `head`, `clean` | The git head of the scope after the last turn, and `true` when git shows no change, no new file and no ignored file. Both are `None` for a folder with no git. |

## The end of a session

You end a work session with `/close` in the client, when no turn is open. The controller has no function for it. The
host makes a release plan from the outcome and the state of the scope, and the client prints it. Then the host stops
the jobs of the session, runs the items of the plan, and ends the session. After the release, the client shows the
controller.

| Command | Clone of a `Worktree` | Its branch | `Scratch` folder |
|---|---|---|---|
| `/close` or `/close parked` | Keep | Keep | Keep |
| `/close merged` | Remove it if it is clean | Delete it if it is in `base`, else keep it | Keep |
| `/close abandoned REASON` | Remove it if it is clean | Keep it as `refs/archive/<id>`, then delete it | Move it to `~/.dspy4s/harness/archive/<id>` |
| `/close replaced ID` | Remove it if it is clean | Keep | Keep |

- A plan item is `RemoveWorktree`, `DeleteBranch`, `KeepRef`, `ArchiveDirectory` or `Keep`.
- The plan changes only a folder that the host made and that no other open session uses. A `Directory` scope always
  stays.
- A clone with a change that git does not keep (also an ignored file) stays, and its branch stays.
- Merge the branch into `base` before `/close merged`. Else the branch stays.
- When you open again a `Scratch` session whose release moved its folder to the archive, the host moves the folder
  back from `~/.dspy4s/harness/archive/<id>` before the session starts.
- An item that cannot be undone needs your confirmation. The client prints
  `The step "<item>" cannot be undone. Type yes to run it.` Another answer skips the item.

## The commands of the client

`/close`, `/show` and `/stopall` act on the session that the client shows. The commands also work in a client without
`--controller`. [The terminal client](terminal-client.md) has the full list.

| Command | What it does |
|---|---|
| `/sessions` | It prints one line for each session: `<id>  <status>  <goal or scope>  (<N> files made)`. |
| `/open ID` | It shows the session `ID`. Give the full id. |
| `/controller` | It shows the controller again. |
| `/reopen ID` | It opens again the session `ID`, which has ended, and shows it. |
| `/close [parked]`, `/close merged`, `/close abandoned REASON`, `/close replaced ID` | It prints the release plan, runs it, and ends the session. |
| `/show ID` | It shows the cell `ID` (`12`) or the call `ID` (`12.0`) in full. |
| `/stopall` | It stops the turn and each job of the turn. |
| `/wait SECONDS` | It waits before the next line, for a script. |

## Restart and reopen

`--session ID` takes the id or the start of the id, and `--list` lists the sessions. `--session` also opens a session
that has ended. A session that the controller started does not open with `--session`, because its reports reach only
a host where the controller is open. Start the client with `--controller`, and type `/open ID`. `/open` of a session
that has ended prints `The session <id> has ended. Type /reopen <id> to open it again.`

At the start of a host, each session that the controller started and whose turn was open opens again. The host ends
that turn as `Crashed`, and the controller gets the report when it opens.

After a restart, the session has a new REPL:

- The values of the earlier cells are gone. When names are lost, the first step gets the note
  `The harness started a new REPL, because the session opened again. These names of earlier cells are gone: <names>. Define them again if you need them.`
- The store comes back from the journal. Keep a value for a later run with `store`, and read it with `load`. See
  [The guest API](guest-api.md).
- The context of the model still holds the earlier turns, because the host makes it from the journal.

## Try it

1. Start the controller with `scripts/dspy4s-harness --controller`. You see
   `The session controller is open. Type a message, or /help for the commands.` Then type:

    ```text
    Start a Scratch session. Its goal: write hello.txt with the text hello, then submit the path.
    ```

    You see a cell that calls `start(...)` and a reply with the id. Some seconds later, you see a line with
    `[the session <id>] The turn ended with submit:`.

2. Type `/sessions`. You see a line for the controller and a line for the new session with `(1 file made)`. Type
   `/open <id>`. You see `The client shows the session <id>.` and the goal after `[the controller]`. Type
   `/controller` to go back.

3. Type this message:

    ```text
    Use observe to ask the session <id> which files it wrote.
    ```

    You see a cell that calls `observe(...)`, and a reply that names `hello.txt`. The folder
    `~/.dspy4s/harness/probes/` now has a file `controller.<cell>.<n>.db`.

4. Type `/open <id>`, then `/close abandoned a test`. Among the lines, you see these:

    ```text
    The release plan of the session <id>:
    - move <home>/.dspy4s/work/<id> to <home>/.dspy4s/harness/archive/<id>
    move <home>/.dspy4s/work/<id> to <home>/.dspy4s/harness/archive/<id>: done
    The client shows the session controller.
    ```

    Then `ls ~/.dspy4s/harness/archive/<id>` shows `hello.txt`.

Next: [Memory](memory.md).
