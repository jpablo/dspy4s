package dspy4s.harness.cli

import dspy4s.harness.kernel.Logged

/** The text of an entry for the terminal (CODE_HARNESS_DESIGN.md, 10.1), or none for an entry that the person does not
  * need to see.
  *
  *   - `Acted`: the text of the step, and the code of its cell, if any.
  *   - `Observed`: the output of the cell, cut to a few lines with a note of the rest.
  *   - `EffectDone` with `NeedsApproval`: the call and the commands that answer it (5.5).
  *   - `TurnEnded`: the outcome, apart from `Replied`, whose text the `Acted` already showed.
  *   - `StopRequested`, `JobsStopRequested`, `Granted` and `Ended`: one line each.
  *   - An `Input` from a source that is not `User`, for example the message at the end of a job: its text.
  *   - The other entries give none.
  */
def entryText(logged: Logged): Option[String] = ???
