package dspy4s.harness.sandbox

/** The wrapper of a process of the host (CODE_HARNESS_DESIGN.md, 10.3, phase 7). A process can outlive its host, so
  * each `exec` and each job runs under a small wrapper that holds a pipe to the host. The end of the pipe is the one
  * signal of a stop: the host closes it, or the host crashes. On macOS only the wrapper stops a command at the end of
  * its host. On Linux, `--die-with-parent` of bubblewrap also does it, but not during the start of bubblewrap.
  *
  * The wrapper is a bash script, because `set -m` of bash gives a background job its own process group with no
  * terminal, on macOS and on Linux. `dash`, the `/bin/sh` of Ubuntu, refuses `set -m` with no terminal. bash gives a
  * background job `/dev/null` as its standard input, so the pipe of the host moves to a file descriptor of its own
  * first. Job control prints notices on the standard error, so the standard error of the command moves to another one.
  * The watcher reads with the `read` of the shell, so a normal end leaves no process behind, and it writes to
  * `/dev/null`, so the output of a command ends when the command and the wrapper end.
  *
  * The stop, at the end of the pipe:
  *
  *   1. STOP to the process group of the command, so that no process of the group starts a new process.
  *   1. The tree of the command, from `ps`: each process under it, also in another session. bubblewrap puts the sandbox
  *      in a new session (`--new-session`), so the group does not hold it. A stop during the start of bubblewrap can
  *      come before its `--die-with-parent` works, so only the tree finds that process.
  *   1. TERM to the group and to the tree, then CONT.
  *   1. A wait until no process of the group or of the tree runs, at most the grace period and one second more (the
  *      clock of bash counts whole seconds). A zombie does not run: with no init, a stopped process can stay a zombie.
  *      Then KILL.
  *
  * During a stop, the wrapper ends after the watcher, so the end of the wrapper is the end of the stop. With no `ps`,
  * the tree is empty, the group still gets the stop, and the wait also counts the zombies. The host sends no signal to
  * the processes of a wrapper that runs, because a TERM to the watcher stops the stop.
  */
object ProcessWrapper:

  private val Script =
    """exec 3<&0 4>&2 2>/dev/null
      |set -m
      |/bin/sh -c "$1" </dev/null 2>&4 3<&- 4>&- &
      |command=$!
      |stopping=0
      |trap 'stopping=1' USR1
      |( while IFS= read -r _ <&3; do :; done
      |  kill -USR1 "$$"
      |  kill -STOP -- -"$command"
      |  tree=$(ps -A -o pid= -o ppid= | awk -v root="$command" '
      |    { parent[$1] = $2 }
      |    END {
      |      found[root] = 1
      |      do { more = 0; for (p in parent) if (!(p in found) && (parent[p] in found)) { found[p] = 1; more = 1 } } while (more)
      |      for (p in found) if (p != root) printf "%s ", p
      |    }')
      |  kill -TERM -- -"$command" $tree
      |  kill -CONT -- -"$command" $tree
      |  left() {
      |    ps -A -o pid= -o pgid= -o stat= | awk -v group="$command" -v tree="$tree" '
      |      BEGIN { n = split(tree, t); for (i = 1; i <= n; i++) want[t[i]] = 1 }
      |      $3 !~ /^Z/ && ($2 == group || ($1 in want)) { found = 1 }
      |      END { exit !found }'
      |  }
      |  ps -A -o pid= >/dev/null || left() { kill -0 -- -"$command" $tree; }
      |  end=$((SECONDS + $2 + 1))
      |  while [ "$SECONDS" -lt "$end" ] && left; do sleep 0.1; done
      |  kill -KILL -- -"$command" $tree ) >/dev/null 4>&- &
      |watcher=$!
      |exec 3<&-
      |while :; do
      |  wait "$command"
      |  code=$?
      |  kill -0 "$command" || break
      |done
      |if [ "$stopping" = 1 ]; then wait "$watcher"; else kill -KILL -- -"$watcher"; fi
      |exit "$code"
      |""".stripMargin

  /** The command that runs `command` with `/bin/sh -c` in its own process group, with an empty standard input, and
    * gives its exit code. When the standard input of the wrapper closes, the wrapper stops the group and the tree of
    * the command with TERM, at most `graceSeconds` and one second more, then KILL, and ends after the stop. The host
    * holds the other end of the pipe, so a crash of the host stops the command.
    */
  def command(command: String, graceSeconds: Int): Vector[String] =
    Vector("/bin/bash", "-c", Script, "dspy4s-wrapper", command, graceSeconds.max(0).toString)

  /** The shell text of `argv`: each argument in single quotes, so `/bin/sh -c` runs the same command line. */
  def quoted(argv: Vector[String]): String = argv.map(arg => "'" + arg.replace("'", "'\\''") + "'").mkString(" ")
