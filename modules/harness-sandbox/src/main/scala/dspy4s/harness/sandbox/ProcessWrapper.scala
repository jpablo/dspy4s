package dspy4s.harness.sandbox

/** The wrapper of a process of the host (CODE_HARNESS_DESIGN.md, 10.3, phase 7). A process can outlive its host, so
  * each `exec` and each job runs under a small wrapper that holds a pipe to the host. On Linux, `--die-with-parent` of
  * bubblewrap also stops a process at the end of its host. On macOS only the wrapper does it.
  */
object ProcessWrapper:

  /** The command that runs `command` with `/bin/sh -c` in its own process group, with an empty standard input, and
    * gives its exit code. When the standard input of the wrapper closes, the wrapper stops that group with TERM,
    * `graceSeconds`, then KILL. The host holds the other end of the pipe, so a crash of the host stops the command.
    */
  def command(command: String, graceSeconds: Int): Vector[String] = ???
