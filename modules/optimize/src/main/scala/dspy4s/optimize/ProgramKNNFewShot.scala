package dspy4s.optimize

import dspy4s.core.data.Example
import dspy4s.programs.plan.*

/** Dynamic few-shot parameterization for a typed nearest-neighbor selector.
  *
  * Retrieval stays in the injected selector program. This constructor only attaches the selected labeled examples to
  * the student's stable parameter slots for one run. The student and its static parameters remain unchanged.
  */
object ProgramKNNFewShot:

  def apply[I, O, R](
      student : RecordProgram[I, O],
      neighbors: ProgramWithEnv[I, Vector[Example], R]
  ): RecordProgramWithEnv[I, O, PredictionBackend & R] =
    val targetIds = student.program.parameters.all.map(_.id).toSet
    student.program
      .localParametersWith(neighbors) { (store, demos) =>
        val replacements = store.all.map { binding =>
          val value =
            if targetIds.contains(binding.id) then binding.value.copy(demos = demos)
            else binding.value
          binding.id -> value
        }.toMap
        store.replace(replacements)
      }
      .fromRecords(student.inputShape)
