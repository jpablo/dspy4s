package dspy4s.optimize

import dspy4s.core.contracts.{ContextWindowExceededError, DspyError, DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.evaluate.{ProgramEvaluate, ProgramEvaluateOptions, ProgramMetric}
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport}
import dspy4s.programs.plan.*
import zio.ZIO

final case class ProgramInferRulesConfig(
    metric         : ProgramMetric,
    numCandidates  : CandidateCount          = CandidateCount(10),
    numRules       : RuleCount               = RuleCount(10),
    initTemperature: Double                  = 1.0,
    bootstrap      : ProgramBootstrapFewShotConfig = ProgramBootstrapFewShotConfig(),
    evaluation     : ProgramEvaluateOptions  = ProgramEvaluateOptions(),
    seed           : Long                    = 0L
)

/** Effectful natural-language rule induction for `RecordProgram`. */
object ProgramInferRules:

  final case class RuleInput(
      parameterId      : String,
      component        : String,
      fieldNames       : Vector[String],
      currentInstruction: Option[String],
      examples         : Vector[Example],
      numRules         : Int,
      candidate        : Int,
      leaf             : Int
  )

  final case class Rules(value: String)

  private final case class Built[I, O](program: RecordProgram[I, O], inductionFailures: Int)

  def apply[I, O, RR](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      inducer : ProgramWithEnv[RuleInput, Rules, RR],
      teacher : Option[RecordProgram[I, O]] = None,
      valset  : Option[Vector[Example]] = None,
      config  : ProgramInferRulesConfig
  ): ZIO[PredictionBackend & RR, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    val (effectiveTrain, effectiveVal) = valset match
      case Some(values) => trainset -> values
      case None         =>
        val split = trainset.size / 2
        trainset.take(split) -> trainset.drop(split)

    if student.program.parameters.size == 0 then ZIO.succeed(emptyReport(student))
    else
      ProgramBootstrapFewShot(
        student,
        effectiveTrain,
        teacher,
        config.bootstrap.copy(metric = Some(config.metric), seed = config.seed)
      ).flatMap { bootstrapReport =>
        val base = bootstrapReport.bestProgram
        ProgramEvaluate(base, effectiveVal, config.metric, config.evaluation).flatMap { baselineEvaluation =>
          val baseline = CandidateProgram(
            program = base,
            score = baselineEvaluation.score,
            evaluation = Some(baselineEvaluation),
            metadata = Map("candidate" -> -1, "baseline" -> true)
          )
          ZIO
            .foldLeft(0 until config.numCandidates)(Vector(baseline) -> 0) {
              case ((candidates, failures), candidateIndex) =>
                buildCandidate(base, effectiveTrain, inducer, candidateIndex, config).flatMap { built =>
                  ProgramEvaluate(built.program, effectiveVal, config.metric, config.evaluation).map { evaluation =>
                    val candidate = CandidateProgram(
                      program = built.program,
                      score = evaluation.score,
                      evaluation = Some(evaluation),
                      metadata = Map("candidate" -> candidateIndex, "baseline" -> false)
                    )
                    (candidates :+ candidate) -> (failures + built.inductionFailures)
                  }
                }
            }
            .map { case (candidates, failures) =>
              val best = candidates.tail.foldLeft(candidates.head) { (current, candidate) =>
                if candidate.score > current.score then candidate else current
              }
              OptimizationReport(
                bestProgram = best.program,
                candidates = candidates.sortBy(candidate => -candidate.score),
                metadata = Map(
                  "optimizer"          -> "infer_rules",
                  "num_candidates"     -> candidates.size,
                  "num_induction_failures" -> failures,
                  "best_score"         -> best.score,
                  "optimizable_leaves" -> base.program.parameters.size
                )
              )
            }
        }
      }

  private def buildCandidate[I, O, RR](
      base          : RecordProgram[I, O],
      examples      : Vector[Example],
      inducer       : ProgramWithEnv[RuleInput, Rules, RR],
      candidateIndex: Int,
      config        : ProgramInferRulesConfig
  ): ZIO[RR, DspyError, Built[I, O]] =
    ZIO.foldLeft(base.program.parameters.all.zipWithIndex)(Built(base, 0)) {
      case (built, (binding, leafIndex)) =>
        induce(binding, examples, inducer, candidateIndex, leafIndex, config).flatMap {
          case None => ZIO.succeed(built.copy(inductionFailures = built.inductionFailures + 1))
          case Some(rules) =>
            val instruction = appendRules(binding.value.instructions, rules)
            ZIO.fromEither(built.program.modifyParameter(binding.id)(_.copy(instructions = instruction))).map(
              updated => built.copy(program = updated)
            )
        }
    }

  private def induce[RR](
      binding       : ParameterBinding,
      examples      : Vector[Example],
      inducer       : ProgramWithEnv[RuleInput, Rules, RR],
      candidateIndex: Int,
      leafIndex     : Int,
      config        : ProgramInferRulesConfig
  ): ZIO[RR, Nothing, Option[String]] =
    def loop(current: Vector[Example]): ZIO[RR, Nothing, Option[String]] =
      if current.isEmpty then ZIO.none
      else
        val input = RuleInput(
          parameterId = binding.id.value,
          component = binding.metadata.moduleName,
          fieldNames = binding.metadata.structure.fields.map(_.name),
          currentInstruction = binding.value.instructions,
          examples = current,
          numRules = config.numRules,
          candidate = candidateIndex,
          leaf = leafIndex
        )
        val rolloutId = OptimizerSupport.seedBase(config.seed) + candidateIndex * 10000 + leafIndex * 100
        val options = RunOptions(
          config = DynamicValues.record("temperature" := config.initTemperature),
          rolloutId = Some(rolloutId)
        )
        ProgramRunner.run(inducer, input, options).either.flatMap {
          case Right(prediction) =>
            val rules = prediction.output.value.trim
            ZIO.succeed(Option.when(rules.nonEmpty)(rules))
          case Left(_: ContextWindowExceededError) if current.size > 1 =>
            ZIO.suspendSucceed(loop(current.dropRight(1)))
          case Left(_) => ZIO.none
        }

    loop(examples)

  private def appendRules(base: Option[String], rules: String): Option[String] =
    val preamble = "Please adhere to the following rules when making your prediction:"
    Some(Vector(base.getOrElse("").trim, s"$preamble\n$rules").filter(_.nonEmpty).mkString("\n\n"))

  private def emptyReport[I, O](student: RecordProgram[I, O]): OptimizationReport[RecordProgram[I, O]] =
    OptimizationReport(
      bestProgram = student,
      candidates = Vector.empty,
      metadata = Map(
        "optimizer"          -> "infer_rules",
        "num_candidates"     -> 0,
        "best_score"         -> 0.0,
        "optimizable_leaves" -> 0
      )
    )
