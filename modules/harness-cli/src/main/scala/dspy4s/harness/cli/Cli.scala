package dspy4s.harness.cli

import dspy4s.harness.host.SessionHandle

import java.io.{BufferedReader, PrintStream}

/** The terminal client of a work session (CODE_HARNESS_DESIGN.md, 15, phase 7).
  *
  * `main` reads the arguments, makes the model from `OPENAI_BASE_URL`, `DSPY_MODEL` and `OPENAI_API_KEY`, starts a
  * `LocalHost` with a `WorkRuntime`, and opens the session: a new one in the folder, or the session of `--session` with
  * its own configuration. It prints the id of the session, then runs `loop`. The key goes only to the model, so no
  * journal holds it (5.6). With none of these variables, the client still starts, and the first policy call fails its
  * turn with the error of the model. A session that another host has open gives an error and the exit code 1.
  *
  * A proposal for the review: the CLI runs on JDK 21. Its start time is a check of this phase, and an AOT cache on JDK
  * 25 comes later only if the start is too slow (choice 8).
  */
object Cli:

  def main(args: Array[String]): Unit = ???

  /** The loop of the client: a thread prints `entryText` of each entry of the feed of `handle`, and this thread reads
    * the lines of `in` and runs the commands of `commandsFor`.
    *
    *   - A message, `/stop` and `/stopall` go through `send`. `/close` and `/allow` go through `run`, so the person
    *     sees a refusal, for example `Close` during a turn.
    *   - An error of `commandsFor` and a refused command print one line, and the loop goes on.
    *   - After `Behind`, the client reads the journal after its last `seq`, so it shows each entry once.
    *   - It ends at `/quit`, at the end of `in`, or when the feed ends, for example after `Close` or a failed write.
    */
  def loop(handle: SessionHandle, in: BufferedReader, out: PrintStream): Unit = ???
