package dspy4s.harness.kernel

/** The entries that close the open parts of a turn (4.4): an `EffectDone(Unknown)` for each open effect, in the order
  * of their ids, and an `Observed` with a failure for an open cell. A stop and a crash close a turn with the same
  * entries.
  */
def closing(s: SessionState): Vector[Entry] = ???

/** The entries after a crash (4.4): `closing` and `TurnEnded(Crashed)`, or nothing with no open turn. The old entries
  * never change, and nothing runs an effect with an unknown outcome again automatically.
  */
def repair(s: SessionState): Vector[Entry] = ???
