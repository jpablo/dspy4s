package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.evaluate.{ProgramEvaluate, ProgramEvaluateOptions, ProgramMetric}
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport}
import dspy4s.programs.plan.*
import zio.ZIO

final case class ProgramCOPROConfig(
    metric         : ProgramMetric,
    breadth        : CoproBreadth          = CoproBreadth(10),
    depth          : RoundCount            = RoundCount(3),
    initTemperature: Double                = 1.4,
    evaluation     : ProgramEvaluateOptions = ProgramEvaluateOptions(),
    seed           : Long                  = 0L
)

/** Effectful coordinate-ascent prompt optimization for `RecordProgram`. */
object ProgramCOPRO:

  final case class InstructionAttempt(instruction: Option[String], score: Double)

  final case class ProposalInput(
      parameterId      : String,
      component        : String,
      fieldNames       : Vector[String],
      currentInstruction: Option[String],
      attempts         : Vector[InstructionAttempt],
      round            : Int,
      candidate        : Int
  )

  final case class Proposal(instruction: String)

  private final case class SearchState[I, O](
      program         : RecordProgram[I, O],
      candidates      : Vector[CandidateProgram[RecordProgram[I, O]]],
      proposalFailures: Int
  )

  private final case class LeafState[I, O](
      attempts        : Vector[InstructionAttempt],
      candidates      : Vector[CandidateProgram[RecordProgram[I, O]]],
      proposalFailures: Int
  )

  def apply[I, O, RP](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      proposer: ProgramWithEnv[ProposalInput, Proposal, RP],
      valset  : Option[Vector[Example]] = None,
      config  : ProgramCOPROConfig
  ): ZIO[PredictionBackend & RP, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    val bindings = student.program.parameters.all
    val evalset  = valset.getOrElse(trainset)

    if bindings.isEmpty then ZIO.succeed(emptyReport(student))
    else
      ZIO
        .foldLeft(bindings.zipWithIndex)(SearchState(student, Vector.empty, 0)) {
          case (state, (binding, leafIndex)) =>
            optimizeLeaf(state.program, binding, leafIndex, evalset, proposer, config).flatMap { optimized =>
              val bestInstruction = optimized.attempts
                .foldLeft(Option.empty[InstructionAttempt]) {
                  case (None, attempt)                                    => Some(attempt)
                  case (Some(best), attempt) if attempt.score > best.score => Some(attempt)
                  case (best, _)                                           => best
                }
                .map(_.instruction)
                .getOrElse(binding.value.instructions)
              ZIO.fromEither(setInstruction(state.program, binding.id, bestInstruction)).map { updated =>
                SearchState(
                  updated,
                  state.candidates ++ optimized.candidates,
                  state.proposalFailures + optimized.proposalFailures
                )
              }
            }
        }
        .flatMap { state =>
          ProgramEvaluate(state.program, evalset, config.metric, config.evaluation).map { finalEvaluation =>
            OptimizationReport(
              bestProgram = state.program,
              candidates = state.candidates.sortBy(candidate => -candidate.score),
              metadata = Map(
                "optimizer"          -> "copro",
                "num_candidates"     -> state.candidates.size,
                "num_proposal_failures" -> state.proposalFailures,
                "best_score"         -> finalEvaluation.score,
                "optimizable_leaves" -> bindings.size
              )
            )
          }
        }

  private def optimizeLeaf[I, O, RP](
      program  : RecordProgram[I, O],
      binding  : ParameterBinding,
      leafIndex: Int,
      evalset  : Vector[Example],
      proposer : ProgramWithEnv[ProposalInput, Proposal, RP],
      config   : ProgramCOPROConfig
  ): ZIO[PredictionBackend & RP, DspyError, LeafState[I, O]] =
    ZIO.foldLeft(0 until config.depth)(LeafState[I, O](Vector.empty, Vector.empty, 0)) { (state, round) =>
      val proposalCount = if round == 0 then config.breadth - 1 else config.breadth
      propose(
        proposer,
        binding,
        leafIndex,
        state.attempts.sortBy(_.score),
        round,
        proposalCount,
        config
      ).flatMap { case (proposals, failures) =>
        val baseline = Option.when(round == 0)(binding.value.instructions).toVector
        val seen     = state.attempts.map(_.instruction).toSet
        val instructions = (baseline ++ proposals.map(value => Some(value))).distinct.filterNot(seen)

        ZIO.foldLeft(instructions)(state.copy(proposalFailures = state.proposalFailures + failures)) {
          (current, instruction) =>
            ZIO.fromEither(setInstruction(program, binding.id, instruction)).flatMap { candidate =>
              ProgramEvaluate(candidate, evalset, config.metric, config.evaluation).map { evaluation =>
                current.copy(
                  attempts = current.attempts :+ InstructionAttempt(instruction, evaluation.score),
                  candidates = current.candidates :+ CandidateProgram(
                    program = candidate,
                    score = evaluation.score,
                    evaluation = Some(evaluation),
                    metadata = Map(
                      "parameter_id" -> binding.id.value,
                      "instruction"  -> instruction,
                      "round"        -> round
                    )
                  )
                )
              }
            }
        }
      }
    }

  private def propose[RP](
      proposer : ProgramWithEnv[ProposalInput, Proposal, RP],
      binding  : ParameterBinding,
      leafIndex: Int,
      attempts : Vector[InstructionAttempt],
      round    : Int,
      count    : Int,
      config   : ProgramCOPROConfig
  ): ZIO[RP, Nothing, (Vector[String], Int)] =
    ZIO.foldLeft(0 until count)(Vector.empty[String] -> 0) { case ((proposals, failures), candidate) =>
      val input = ProposalInput(
        parameterId = binding.id.value,
        component = binding.metadata.moduleName,
        fieldNames = binding.metadata.structure.fields.map(_.name),
        currentInstruction = binding.value.instructions,
        attempts = attempts,
        round = round,
        candidate = candidate
      )
      val rolloutId = OptimizerSupport.seedBase(config.seed) + leafIndex * 10000 + round * config.breadth + candidate
      val options = RunOptions(
        config = DynamicValues.record("temperature" := config.initTemperature),
        rolloutId = Some(rolloutId)
      )
      ProgramRunner.run(proposer, input, options).either.map {
        case Right(prediction) =>
          val instruction = prediction.output.instruction.trim
          if instruction.nonEmpty && !proposals.contains(instruction) then (proposals :+ instruction) -> failures
          else proposals -> failures
        case Left(_) => proposals -> (failures + 1)
      }
    }

  private def setInstruction[I, O](
      program    : RecordProgram[I, O],
      parameterId: ParameterId,
      instruction: Option[String]
  ): Either[DspyError, RecordProgram[I, O]] =
    program.modifyParameter(parameterId)(_.copy(instructions = instruction))

  private def emptyReport[I, O](student: RecordProgram[I, O]): OptimizationReport[RecordProgram[I, O]] =
    OptimizationReport(
      bestProgram = student,
      candidates = Vector.empty,
      metadata = Map(
        "optimizer"          -> "copro",
        "num_candidates"     -> 0,
        "best_score"         -> 0.0,
        "optimizable_leaves" -> 0
      )
    )
