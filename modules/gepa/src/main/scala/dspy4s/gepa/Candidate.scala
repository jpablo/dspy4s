package dspy4s.gepa

import dspy4s.optimize.Predictors

/** A GEPA candidate program: a map from component name to that predictor's instruction text — the genome the
  * optimizer mutates (mirrors Python gepa's `dict[str, str]`). Instruction text is the only thing GEPA evolves;
  * the program's structure (fields, demos, wiring) is fixed. See PORT_GAPS G-12. */
type Candidate = Map[String, String]

object Candidate:

  /** Component name for the predictor at `index` in [[Predictors.read]] order.
    *
    * v0 uses the positional index as the name. P-c (named predictors) will replace this with the Mirror-derived
    * field labels — the dspy `named_predictors()` analog — which also unblocks Refine per-module advice. Centralized
    * here so that swap is one localized change. */
  def componentName(index: Int): String = index.toString

  /** The seed candidate: each predictor's CURRENT instruction, keyed by component name (the GEPA starting point,
    * dspy's `{name: pred.signature.instructions}`). */
  def seed[P](program: P)(using ps: Predictors[P]): Candidate =
    ps.read(program).iterator.zipWithIndex.map { (predict, i) =>
      componentName(i) -> predict.layout.instructions.getOrElse("")
    }.toMap

  /** Apply a candidate's instructions back onto the program's predictors, by component name (dspy's
    * `with_instructions` over `named_predictors`). Predictors absent from the candidate keep their instruction. */
  def applyTo[P](program: P, candidate: Candidate)(using ps: Predictors[P]): P =
    val updated = ps.read(program).zipWithIndex.map { (predict, i) =>
      candidate.get(componentName(i)) match
        case Some(instruction) => predict.copy(layout = predict.layout.withInstructions(Some(instruction)))
        case None              => predict
    }
    ps.replace(program, updated)
