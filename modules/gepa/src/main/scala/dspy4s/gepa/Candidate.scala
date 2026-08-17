package dspy4s.gepa

import dspy4s.core.contracts.DspyError
import dspy4s.programs.{ParameterId, ProgramParameters}

/** A GEPA instruction candidate keyed by the program's stable parameter IDs. */
type Candidate = Map[ParameterId, Option[String]]

object Candidate:

  def seed[P: ProgramParameters](program: P): Candidate =
    ProgramParameters[P].read(program).all.iterator.map { binding =>
      binding.id -> binding.value.instructions
    }.toMap

  def applyTo[P: ProgramParameters](program: P, candidate: Candidate): Either[DspyError, P] =
    val parameters = ProgramParameters[P]
    val updated    = parameters.read(program).all.map { binding =>
      binding.id -> candidate.get(binding.id).fold(binding.value) { instructions =>
        binding.value.copy(instructions = instructions)
      }
    }.toMap
    parameters.replace(program, updated)

  def named[P: ProgramParameters](program: P, candidate: Candidate): Vector[(String, Option[String])] =
    ProgramParameters[P].read(program).all.flatMap { binding =>
      candidate.get(binding.id).map(binding.metadata.moduleName -> _)
    }
