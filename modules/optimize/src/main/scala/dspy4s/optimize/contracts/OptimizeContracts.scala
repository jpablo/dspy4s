package dspy4s.optimize.contracts

import dspy4s.evaluate.contracts.EvaluationResult

final case class CandidateProgram[P](
    program   : P,
    score     : Double,
    evaluation: Option[EvaluationResult] = None,
    metadata  : Map[String, Any]         = Map.empty
)

final case class OptimizationReport[P](
    bestProgram: P,
    candidates : Vector[CandidateProgram[P]],
    metadata   : Map[String, Any] = Map.empty
)
