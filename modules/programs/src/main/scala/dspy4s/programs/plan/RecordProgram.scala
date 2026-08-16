package dspy4s.programs.plan

import dspy4s.signatures.Shape

/** A typed program with one explicit dynamic-record input boundary.
  *
  * Core composition does not need a record codec. Evaluation and optimization do, because their datasets use dynamic
  * records. Keeping the codec in this wrapper prevents every custom composite from needing a hand-written runner.
  */
final case class RecordProgram[I, O](program: Program[I, O], inputShape: Shape[I])

