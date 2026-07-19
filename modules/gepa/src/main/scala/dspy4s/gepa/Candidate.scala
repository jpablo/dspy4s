package dspy4s.gepa

import dspy4s.programs.PredictorId
import dspy4s.programs.Predictors

/** A GEPA candidate program: a map from stable predictor identity to instruction text — the genome the optimizer
  * mutates. Instruction text is the only thing GEPA evolves; the program's structure (fields, demos, wiring) is fixed.
  * Unlike upstream's attribute-path strings, [[PredictorId]] remains lawful for anonymous algebraic composition.
  */
type Candidate = Map[PredictorId, String]

object Candidate:

  /** The seed candidate: each predictor's current instruction, keyed by stable traversal identity. */
  def seed[P](program: P)(using ps: Predictors[P]): Candidate =
    ps.readIdentified(program).iterator.map { entry =>
      entry.id -> entry.predictor.layout.instructions.getOrElse("")
    }.toMap

  /** Apply a candidate's instructions back onto the same traversal identities. Predictors absent from the candidate
    * keep their instruction.
    */
  def applyTo[P](program: P, candidate: Candidate)(using ps: Predictors[P]): P =
    val updated = ps.readIdentified(program).map { entry =>
      candidate.get(entry.id).fold(entry.predictor) { instruction =>
        entry.predictor.copy(layout = entry.predictor.layout.withInstructions(Some(instruction)))
      }
    }
    ps.replace(program, updated)

  /** Human-readable view for diagnostics and UI. A vector is used because structural display names are not the unique
    * identity key and may collide in unusual user-defined `Predictors` instances.
    */
  def named[P](program: P, candidate: Candidate)(using ps: Predictors[P]): Vector[(String, String)] =
    ps.readIdentified(program).flatMap(entry => candidate.get(entry.id).map(entry.displayName -> _))
