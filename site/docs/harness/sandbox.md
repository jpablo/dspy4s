# The sandbox and approvals

Each command of a cell (`exec` or `spawn`) runs in an OS sandbox. On macOS the sandbox is Seatbelt
(`/usr/bin/sandbox-exec`). On Linux it is bubblewrap (`bwrap`) with a seccomp filter. The file calls `read`, `write`
and `edit` start no process, but they use the same rules.

The guard checks each call of the guest API before the call runs. A guard rule can stop a call until the person
approves it with `/allow`.

## The backend

The client looks for a backend when it starts. A backend works only when `/bin/sh -c true` runs under it. bubblewrap
needs a `bwrap` in `/usr/bin`, `/bin` or `/usr/local/bin`, and an aarch64 or x86_64 processor. To check the program,
run `ls -l /usr/bin/sandbox-exec` on macOS or `command -v bwrap` on Linux. When no backend works, the session opens,
but each command fails with this error:

```text
No OS sandbox works on this host, so exec did not run the command. macOS needs /usr/bin/sandbox-exec, and Linux needs bwrap.
```

## What a command can do

The terminal client gives each session the work mode. In this mode a cell has `Files^` and `Shell^`, and it has no
`Network^`. The client has no flag that changes the mode.

| Effect | Rule |
|---|---|
| Write | Only under the folder of the session (the scope) and under the private temporary folder of the session. The control paths stay read-only. |
| Read | Each path of the host, apart from the closed paths. |
| Closed paths | The secret paths of the home folder, the folder of the journals (`~/.dspy4s/harness`, or the `--root` folder), and each file or folder with a secret name. A command can neither read nor write them. |
| Network | None. |
| Processes | A command sees only the processes of its own sandbox. |
| Time and output | A command stops after 600 s. The output keeps at most 200 000 bytes of the standard output and the standard error. A last line tells about a cut, the time limit, or an exit code that is not 0, for example `[exit code 1]`. |
| File calls | Only under the folder of the session, with the same closed and read-only paths. The errors are `<path> is outside the workspace`, `<path> is closed to this session` and `<path> is read-only or closed`. |

`TMPDIR`, `TMP` and `TEMP` give the private temporary folder. The host makes a new temporary folder each time that the
session opens. It deletes the folder when the session ends or when the client ends.

### Control paths

A control path is a path in the folder of the session. A program outside the sandbox runs it as code, or reads it as
settings that start a program. A control path stays read-only in each mode, also for `write` and `edit`.

| Control path | The program that reads it |
|---|---|
| `.git/hooks`, and the folder that `core.hooksPath` names when it is in the folder of the session | git |
| `.git/config`, `.git/config.worktree` and `.git/commondir` | git |
| The hooks and the configuration of each submodule in `.git/modules` | git |
| `.git` itself, and `commondir`, `gitdir` and `config.worktree` in each folder of `.git/worktrees` | git |
| A file or a folder with the name `.git` below the top of the folder | git |
| `.vscode/settings.json` and `.vscode/tasks.json` | VS Code |
| `.idea` | IntelliJ |
| `.claude/settings.json` and `.claude/settings.local.json` | Claude Code |

The table gives the list on macOS. On Linux, all of `.git` takes the place of the git rows, and only the paths that exist
when the session opens are read-only. The host reads `core.hooksPath` once each time that the session opens. The rules
apply only in the folder of the session. In the temporary folder, a command can make and change a repository.

### Local git

On macOS the model can run local git commands, because only the control paths of `.git` are read-only. For example, a
cell can show the state of the repository, and then commit on a new branch:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sandbox.scala:git-status"
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sandbox.scala:commit-on-branch"
```

Some git commands still fail:

- git cannot change its configuration. Three commands print an error about `.git/config`: a new branch that tracks a
  remote branch, a rename of a branch and a removal of a branch. The change of the branch still occurs.
- `git init`, `git clone` and `git submodule add` fail in the folder of the session. They work in the temporary
  folder:

  ```scala
  --8<-- "modules/harness-repl/src/test/resources/doc-cells/sandbox.scala:repo-in-temp"
  ```

- `git fetch`, `git pull` and `git push` with a remote on the network fail, because a command has no network.
- git reads `~/.gitconfig`, but a command cannot change it. Set `user.name` and `user.email` there before the session.
- A commit fails when your git settings turn on `commit.gpgsign`, because `~/.gnupg` is a secret path.
- When the folder of the session is a subfolder of a repository, `.git` is outside the folder, and each git command
  that writes fails. Start the session at the top of the repository.

On Linux, all of `.git` stays read-only. A git command that changes the repository fails there, for example `commit`,
`branch`, `switch`, `stash` or `worktree add`.

A session that the controller starts with a `Worktree` scope works in a local clone (`git clone --shared`), so its
`.git` is in its folder. After a turn that moves the tip of its branch, the host puts the new tip into your repository.
See [Sessions and the controller](sessions.md).

### Secret paths and secret names

The secret paths of the home folder include `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.docker`, `~/.netrc`, `~/.npmrc`,
`~/.git-credentials`, `~/.config/gh`, `~/.codex/auth.json`, `~/.claude/.credentials.json` and `~/Library/Keychains`.
`Layout.secretsUnder` in `modules/harness-sandbox/src/main/scala/dspy4s/harness/sandbox/Profile.scala` has the list.

The secret name is `.env`. A file or a folder is secret when its name is `.env` or starts with `.env.`, for example
`.env.local`, in each folder. `.envrc` and `notes.env.txt` are not secret. A file call refuses each secret file that
exists, on both systems. The two backends close the name for a command in different ways:

- On macOS, one Seatbelt rule closes the name in each folder for each command. The rule also stops a new file, a link
  and a move.
- On Linux, bubblewrap cannot close a name. So the shell walks the writable folders once, when the session opens, and
  bubblewrap covers each secret file that the walk found. A command then reads an empty file or an empty folder.
- The walk does not enter a folder with the name `.git`, `node_modules` or `target`, and it does not follow a link. A
  secret file in such a folder stays readable to a command on Linux.
- On Linux, a secret file that is made after the open stays readable and writable to a command until the session opens
  again.

### Environment variables

A command gets only these variables of the harness process: `PATH`, `HOME`, `SHELL`, `TMPDIR`, `TMP`, `TEMP`, `LANG`,
`LC_ALL`, `LC_CTYPE`, `LOGNAME`, `USER` and `TERM`. `TMPDIR`, `TMP` and `TEMP` point to the private temporary folder. A
name with `KEY`, `SECRET`, `TOKEN`, `PASSWORD`, `PASSWD`, `CREDENTIAL` or `AUTH` in it never goes through. Thus the key
of the model provider, for example `OPENAI_API_KEY`, never reaches a command.

### The network

A command has no network in the work mode. A cell has no `Network^` either, so this cell does not compile:

```scala
--8<-- "modules/harness-repl/src/test/resources/doc-cells/sandbox.scala:no-network"
```

A program of the library can give a mode with `Network^` and a host list (`HostList`). On macOS, a command in such a
mode reaches only a proxy on `127.0.0.1`. The proxy answers 403 for a host that is not on the list. On Linux, a
command has no network in each mode.

## What the model reads about the limits

The task of each turn starts with the folder of the session and the rules of its shell. The rules come from the profile
of the sandbox, so they agree with the host. The task holds the rules in one paragraph, and here each part has its own
line. On Linux, the list of control paths holds only the paths that exist at the open. The example is for a folder
with `.git` and no other control path.

=== "macOS"

    ```text
    The folder of the session: <folder>.

    A command and the calls write and edit can change files only under the folder of the session and the temporary folder in TMPDIR.
    In the folder of the session, these paths stay read-only, because a program outside the sandbox runs them as code or reads them as settings: .git itself, .git/hooks, .git/config, .git/config.worktree, .git/commondir, the hooks and the configuration of each submodule in .git/modules, commondir, gitdir and config.worktree in each folder of .git/worktrees, a file or a folder with the name .git below the top of the folder, .vscode/settings.json, .vscode/tasks.json, .idea, .claude/settings.json and .claude/settings.local.json.
    Git cannot change its configuration. Three commands print an error about .git/config: a new branch that tracks a remote branch, a rename of a branch and a removal of a branch. The change of the branch still happens.
    So git init, git clone and git submodule add fail in the folder of the session. A command can make a repository in the temporary folder.
    A command and a file call can neither read nor write the secret paths of the home folder (for example .ssh, .aws and .azure), the journals of the harness or a file or a folder whose name is .env or starts with ".env." (for example .env.local).
    A command has no network.
    A write in another place fails, for example with "Operation not permitted". When a task needs such a write, stop, tell the person that this session cannot do it, and give the command that the person can run.
    ```

=== "Linux"

    ```text
    The folder of the session: <folder>.

    A command and the calls write and edit can change files only under the folder of the session and the temporary folder in TMPDIR.
    In the folder of the session, these paths stay read-only, because a program outside the sandbox runs them as code or reads them as settings: .git.
    The sandbox of this host cannot close only the parts of git that run code, so all of .git stays read-only. A git command that changes the repository fails, for example commit, branch, switch, stash or worktree add.
    A command and a file call can neither read nor write the secret paths of the home folder (for example .ssh, .aws and .azure) or the journals of the harness.
    A file call can neither read nor write a file or a folder whose name is .env or starts with ".env." (for example .env.local).
    A command can neither read nor write such a file or folder when it was there at the open of the session. A command can read and write such a file or folder that is made after the open, until the session opens again.
    A command has no network.
    A write in another place fails, for example with "Operation not permitted". When a task needs such a write, stop, tell the person that this session cannot do it, and give the command that the person can run.
    ```

## Approvals

Each guard rule says `Allow`, `Ask` or `Deny` for the calls that it matches. The strongest verdict wins, and a call
that no rule matches runs. A call with `Deny` fails with `a rule denies this call`. A grant changes only `Ask`, and it
cannot open what the sandbox closes.

A call with `Ask` and no grant does not run. The cell gets a `NeedsApproval` error with the text
`<op>(<arguments>) needs an approval. End the turn and ask the person.` The journal keeps the call as a pending call,
so a `catch` in the cell cannot hide it. The model ends its turn and asks you. The screen shows the cell and a card
with the number of the call:

```text
  I push the branch.
  ▸ Ran 1 command · waits for your approval · 0.0 s  #3
  ⚠ The call 5 needs your approval
    exec "git push origin main"
    /allow 5 once · /allow 5 turn · /allow 5 session · a message refuses the call
```

The number is the `seq` of the journal entry. `/status` also lists the pending calls with their numbers. To answer,
type `/allow [N] [once|turn|session]`. With no number, `/allow` allows each pending call. With no lifetime, the grant
is `once`. A grant covers only the same operation with the same arguments.

| Lifetime | The grant ends |
|---|---|
| `once` (the default) | After the first call that it allows |
| `turn` | At the end of the turn |
| `session` | When the session ends. A session that opens again asks again. |

After the grant, the screen shows `✓ Allowed once: exec "git push origin main".` Then the harness sends the model
the message `` The person approved a call of `exec`. Make the call again if you still need it. `` With no open turn,
the grant starts a new turn, so the model can make the call again.

!!! warning "A message refuses the call"
    Each message that you send starts a new turn, and a new turn removes the pending calls. So a question such as
    "how do I approve it?" also refuses the call. Type `/allow` first, and then ask your question. When the model
    still needs a refused call, it asks again in a later turn.

Two kinds of session have no person, so they cannot ask: a child of `rlm` and a run of an optimizer. There, a call
with `Ask` fails with `no person can answer in this session`.

The default guard has a rule only in a mode with `Network^`: `exec` and `spawn` with the command prefix `git push`
get `Ask`. The work mode of the terminal client has no `Network^`, so a work session has no rule, and none of its
calls asks for an approval.

The controller has one rule: a `start` with a `Directory` scope gets `Ask`. The reason is that the new session works
in a folder of the person. A `start` with a `Scratch` or a `Worktree` scope runs with no approval.

## macOS and Linux

| Topic | macOS (Seatbelt) | Linux (bubblewrap) |
|---|---|---|
| `.git` | Only the control paths are read-only, so local git works. | All of `.git` is read-only. |
| A control path that does not exist yet | Read-only | Writable until the session opens again |
| Secret names | Closed in each folder, also for a new file | Closed only for the files that the walk found at the open |
| The open of a session | No walk | One walk of the writable folders, which takes longer in a large folder |
| A read of a closed path | `Operation not permitted` | An empty file or an empty folder |
| A write outside the writable folders | `Operation not permitted` | `Read-only file system` |
| Network in a mode with `Network^` | Only the proxy of the host list | None |
| Other limits | No access to launchd and the other services of the system | A new pid namespace, and a seccomp filter that refuses UNIX sockets |

## Try it

Make a test repository with a secret file, and start the client in it (see [First run](first-run.md)):

```bash
mkdir -p ~/sandbox-demo && cd ~/sandbox-demo && git init -q
printf 'first line\n' > notes.txt && printf '.env\n' > .gitignore
git add notes.txt .gitignore && git commit -q -m start
printf 'TOKEN=example\n' > .env
<repo>/scripts/dspy4s-harness ~/sandbox-demo
```

1. Type `Write the text hello to /tmp/outside.txt with a shell command.` The command fails with
   `Operation not permitted` on macOS, or `Read-only file system` on Linux. The task tells the model to stop and to
   give you the command.
2. Type `Show me the content of .env.` A `read` fails with `.env is closed to this session`. On macOS, `cat .env` fails
   with `Operation not permitted`. On Linux, `cat .env` shows an empty file.
3. Type `Run git status, then commit a change of notes.txt on a new branch named demo.` On macOS, the commit occurs,
   and `git log --oneline demo` in your shell shows it. On Linux, the commit fails, because `.git` is read-only.
4. Type `/allow`. The client shows `No call waits for an approval.`, because the work mode has no `Ask` rule.
5. Type `/quit`. Start the controller with `<repo>/scripts/dspy4s-harness --controller`, and type
   `Start a session in the folder <home>/sandbox-demo with the goal: count the lines of notes.txt.` Give the absolute
   path of the folder. You must see a card that starts with `⚠ The call`. Type `/allow`. You must see
   `✓ Allowed once:`, and then the model makes the call again and the session starts.

Next: [Sessions and the controller](sessions.md).
