package dspy4s.gepa

import dspy4s.programs.Predictors

/** A GEPA candidate program: a map from component name to that predictor's instruction text — the genome the
  * optimizer mutates (mirrors Python gepa's `dict[str, str]`). Instruction text is the only thing GEPA evolves;
  * the program's structure (fields, demos, wiring) is fixed. See PORT_GAPS G-12. */
type Candidate = Map[String, String]

object Candidate:

  /** The seed candidate: each predictor's CURRENT instruction, keyed by its component name (dspy's
    * `{name: pred.signature.instructions}`). Names come from [[Predictors.readNamed]] — `"self"` for a standalone
    * predict, field labels for a composite (P-c). */
  def seed[P](program: P)(using ps: Predictors[P]): Candidate =
    ps.readNamed(program).iterator.map { (name, predict) => name -> predict.layout.instructions.getOrElse("") }.toMap

  /** Apply a candidate's instructions back onto the program's predictors, by component name (dspy's
    * `with_instructions` over `named_predictors`). Predictors absent from the candidate keep their instruction. */
  def applyTo[P](program: P, candidate: Candidate)(using ps: Predictors[P]): P =
    val updated = ps.readNamed(program).map { (name, predict) =>
      candidate.get(name).fold(predict)(instruction => predict.copy(layout = predict.layout.withInstructions(Some(instruction))))
    }
    ps.replace(program, updated)
