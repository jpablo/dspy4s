package dspy4s.gepa

import dspy4s.programs.predictors.PredictorId
import dspy4s.programs.predictors.PredictorTraversal

/** A GEPA candidate program: a map from stable predictor identity to optional instruction text — the genome the
  * optimizer mutates. `None` (no instructions) and `Some("")` (explicitly empty instructions) are distinct states.
  * Instructions are the only thing GEPA evolves; the program's structure (fields, demos, wiring) is fixed. Unlike
  * upstream's attribute-path strings, [[PredictorId]] remains lawful for anonymous algebraic composition.
  */
type Candidate = Map[PredictorId, Option[String]]

object Candidate:

  /** The seed candidate: each predictor's current instruction, keyed by stable traversal identity. */
  def seed[P](program: P)(using ps: PredictorTraversal[P]): Candidate =
    ps.readIdentified(program).iterator.map { entry =>
      entry.id -> entry.parameters.instructions
    }.toMap

  /** Apply a candidate's instructions back onto the same traversal identities. PredictorTraversal absent from the candidate
    * keep their instruction.
    */
  def applyTo[P](program: P, candidate: Candidate)(using ps: PredictorTraversal[P]): P =
    val updated = ps.readIdentified(program).map { entry =>
      candidate.get(entry.id).fold(entry.parameters) { instructions =>
        entry.parameters.copy(instructions = instructions)
      }
    }
    ps.replace(program, updated)

  /** Human-readable view for diagnostics and UI. A vector is used because structural display names are not the unique
    * identity key and may collide in unusual user-defined `PredictorTraversal` instances.
    */
  def named[P](program: P, candidate: Candidate)(using ps: PredictorTraversal[P]): Vector[(String, Option[String])] =
    ps.readIdentified(program).flatMap(entry => candidate.get(entry.id).map(entry.displayName -> _))
