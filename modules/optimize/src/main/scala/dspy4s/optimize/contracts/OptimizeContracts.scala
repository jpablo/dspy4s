package dspy4s.optimize.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.contracts.EvaluationResult

final case class CandidateProgram[P](
    program: P,
    score: Double,
    evaluation: Option[EvaluationResult] = None,
    metadata: Map[String, Any] = Map.empty
)

final case class OptimizationReport[P](
    bestProgram: P,
    candidates: Vector[CandidateProgram[P]],
    metadata: Map[String, Any] = Map.empty
)

trait Teleprompter[P]:
  def name: String

  def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]]
