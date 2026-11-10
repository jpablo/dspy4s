package dspy4s.harness.cli

import dspy4s.harness.host.SessionHandle

import java.io.{BufferedReader, PrintStream}

/** The terminal client of a work session (CODE_HARNESS_DESIGN.md, 15, phase 7).
  *
  * `main` reads the arguments, makes the model from `OPENAI_BASE_URL`, `DSPY_MODEL` and `OPENAI_API_KEY`, starts a
  * `LocalHost` with a `WorkRuntime`, and opens the session: a new one in the folder, or the session of `--session`. It
  * prints the id of the session, then runs `loop`. The key goes only to the model, so no journal holds it (5.6).
  *
  * A proposal for the review: the CLI runs on JDK 21. Its start time is a check of this phase, and an AOT cache on JDK
  * 25 comes later only if the start is too slow (choice 8).
  */
object Cli:

  def main(args: Array[String]): Unit = ???

  /** The loop of the client: a thread prints `entryText` of each entry of the feed of `handle`, and this thread reads
    * the lines of `in` and sends the commands of `commandsFor`. After `Behind`, the client reads the journal after its
    * last `seq`, so it shows each entry once. It ends at `/quit`, at the end of `in`, or after the session ends.
    */
  def loop(handle: SessionHandle, in: BufferedReader, out: PrintStream): Unit = ???
