# dspy4s `harness-sandbox`

`harness-sandbox` runs the commands of a cell in an OS sandbox. It makes the profile of a child process from the mode
of the session, and it gives `SandboxedShell`, the handler of `Shell`. On macOS the backend is Seatbelt
(`sandbox-exec`). On Linux it is bubblewrap (`bwrap`) with a seccomp filter. When no backend works, `exec` fails, and
the command does not run. It uses [`harness-kernel`](../harness-kernel/README.md) for `SessionConfig`, and the guest
API of [`harness-guest`](../harness-guest/README.md).

## Main concepts

- **A profile is what a child process can do.** `Profile` has four observations: `canRun`, `canRead`, `canWrite`, and
  `canConnect`. They define a profile, and a backend must allow at most what they allow.
- **The profile comes from the mode.** A child process needs `Shell^` and `Files^`, because `exec` takes both. The
  child can then write under the scope and under the private temporary directory of the session, but not in the `.git`
  folder of the scope. The secret paths and the folder of the journals are hidden in each mode. A child has the network
  only with `Network^` too, and then only through the proxy of the host list.
- **Secret files.** Before each command, the shell finds each file with a secret name in the writable roots, for
  example `.env` or `.env.local`. It hides these files from that command. A command can make a new file with a secret name.
  That file holds only data of the command, and the next command cannot read it.
- **An allowlist of variables.** The harness process holds the keys of the LM provider. A command gets only the names
  of `Environment.allowed` and the names of `SessionConfig.env`. A name that contains a secret word, for example `KEY`,
  `TOKEN`, or `SECRET`, never goes through.
- **A proxy for the host list.** `HostProxy` is an HTTP proxy on the loopback address. It lets a request through only
  when the `HostList` allows its host at its port, and it answers 403 for another host or port. The proxy resolves the
  names, so a child needs no DNS.

```scala
val layout = Layout.forSession(home = Path.of(sys.props("user.home")), journals = journalFolder)
val shell  = SandboxedShell(config, layout, HostList(Set("github.com")), Backends.detect())
try shell.exec("git status")(using files) // the output, and a last line for an exit code that is not 0
finally shell.close()
```

## Main types

| Type | Description |
|------|-------------|
| `Backend` | An OS sandbox: `name`, `works()`, and `wrap(profile, cwd, argv, proxyPort)`, which gives the command line. |
| `Backends` | `all`, and `detect()`, the first backend that works on this host. |
| `Seatbelt` | The backend for macOS. Its SBPL profile denies by default. A child can see only the processes of its own sandbox, and it cannot reach launchd and the other services of the system. |
| `Bubblewrap` | The backend for Linux. The root is read-only, and the writable roots are writable. An empty folder or the null device covers each secret. A new pid namespace hides the other processes. |
| `Seccomp` | The BPF filter that bubblewrap loads for each command. It refuses `socket` for `AF_UNIX`, `socketpair` for a datagram pair, `io_uring_setup`, and each call of another ABI. It exists for aarch64 and x86_64. |
| `Profile` / `Net` | What a child can do, and its network: `Closed`, or `Proxy(hosts)`. `hide` gives a profile that allows less. |
| `Profiles` | `forMode`, the profile of a mode, and `forCommand`, the profile of a mode with the secret files that exist when the command starts. |
| `Layout` | The places of the host that a profile names: the private temporary directory, the folder of the journals, the secret paths (for example `~/.ssh` and `~/.aws`), and the secret names. `Layout.forSession` makes one. |
| `SecretIndex` | The walk that finds the secret files, with a cache of the folders between two commands. |
| `HostList` | The patterns of the proxy: `name`, `name:port`, `name:*`, and `*.domain`. A pattern with no port allows only the ports 80 and 443. |
| `HostProxy` | The proxy of a host list. It serves at most `limit` connections at a time, and it ends a connection after the idle time. |
| `Environment` | The allowlist of variables, the secret words, and `forChild`, which gives the variables of a command. |
| `SandboxedShell` / `SandboxError` | The handler of `Shell`, and the error of a command that did not run. |

## Design notes

- **The output of `exec`.** The output is the standard output and the standard error as one text, with at most
  `maxOutput` bytes (default 200 000). A last line tells about a cut, the end of the time limit (default 600 s), and an
  exit code that is not 0. The standard input is empty, and `TMPDIR` is the private temporary directory.
- **No process stays.** At the end of a command, the handler stops each child process that it saw. It sends TERM,
  waits a grace period of 2 s, and then sends KILL. A child can leave its parent, so the handler keeps each process
  that it sees while the command runs.
- **A backend works only when a probe runs.** `works()` runs `/bin/sh -c true` under the backend, with a profile like
  the profile of a session. Thus a host where the program exists but each command fails has no backend. An example is
  a container that does not let bwrap mount `/proc`.
- **Bubblewrap has no network yet.** The network namespace of bubblewrap is always new, so a command has no network,
  also in a mode with `Network^`. The proxy needs a bridge into the namespace, and that comes later. Less network is
  less authority, so the law of section 5.4 holds.
- **The seccomp filter is outside each writable root.** Else a command could change the filter of a later command.
  Thus `Bubblewrap.wrap` refuses a profile with a writable root that holds the file of the filter.
- **Real paths.** Seatbelt compares the real path of a file, so `Layout.forSession` resolves the symbolic links. For
  each secret path that is a symbolic link, the layout holds the link and its target.
- **The cache of the secret index.** The index lists a folder again when its last-modified time, its change time, or
  its file key changes. It also lists a folder again when the folder changed less than `settle` (default 2 s) before
  the find that listed it. The file time on Linux can be coarse, and git uses the same rule for a racy file.
- **The jobs come later.** `spawn`, `jobOutput`, `waitJob`, and `stopJob` throw `UnsupportedOperationException`. They
  come with the session host of phase 7.

## Source layout

| File | Contents |
|------|----------|
| `Backend.scala` | `Backend`, and the helpers that the backends share: the probe, and the check for a process that runs |
| `Backends.scala` | `Backends` |
| `Seatbelt.scala` | `Seatbelt` and its SBPL policy |
| `Bubblewrap.scala` | `Bubblewrap` and the arguments of `bwrap` |
| `Seccomp.scala` | `Seccomp` and its BPF program |
| `Profile.scala` | `Layout`, `HostList`, `Net`, `Profile`, `Profiles`, and `SecretIndex` |
| `Environment.scala` | `Environment` |
| `HostProxy.scala` | `HostProxy` |
| `SandboxedShell.scala` | `SandboxedShell` and `SandboxError` |

## Tests

Run `sbt harnessSandbox/test` from the root of the repository. The escape tests (`SeatbeltLaws` and `BubblewrapLaws`,
from `EscapeProbes`) start real child processes that try each effect. The tests compare what a child did with what the profile allows, in both directions.
The test JVM has the variable `DSPY4S_FAKE_SECRET_TOKEN`, so a test can look for it from a child. The other suites
check the profiles, the allowlist of variables, the proxy, the shell, the SBPL policy, and the seccomp program.

The bubblewrap tests skip on macOS. This script runs the laws on Linux in a podman container, and writes the report
`modules/harness-sandbox/target/test-reports/TEST-linux.xml`:

```bash
scripts/sandbox-linux/run.sh
```

`src/test/mutants.toml` has 36 mutants, and each one breaks one rule of section 5.4 or 5.6. This command applies each
mutant alone, runs the laws, and restores the code. At least one law must fail for each mutant. The mutants of
Seatbelt need macOS, because the escape tests skip on another host.

```bash
python3 scripts/mutants/mutants.py modules/harness-sandbox/src/test/mutants.toml
```

## Relation to the design

This module is sections [5.4](../../docs/code-harness/CODE_HARNESS_DESIGN.md#54-the-sandbox) and
[5.6](../../docs/code-harness/CODE_HARNESS_DESIGN.md#56-secrets-and-taint) of the design, with the handler `Shell`
of section [5.3](../../docs/code-harness/CODE_HARNESS_DESIGN.md#53-handlers).
