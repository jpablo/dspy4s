package dspy4s.harness.kernel

/** The entries that a command can commit (4.5). It keeps each journal inside the language of 4.1. The table of 4.5
  * gives the entries of each command, and the rules for `Commit` come after it.
  */
def decide(command: Command, s: SessionState): Either[Rejection, Vector[Entry]] = ???

/** The text of the `Input` after a grant that answers a pending call (5.5). The harness writes it, so a client cannot
  * put words in it.
  */
def approvedText(rule: GuardRule): String = ???

/** The sender of an entry that `command` commits for `sender` (5.5). The `Input` after a grant comes from the harness,
  * so its turn gives a report to the owner (8.6).
  */
def sourceOf(command: Command, sender: Source, entry: Entry): Source = ???
