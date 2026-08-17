package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.evaluate.{Evaluate, EvaluateOptions, Metric}
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport}
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.*
import zio.ZIO

final case class MIPROv2Config(
    metric              : Metric,
    numCandidates       : CandidateCount  = CandidateCount(5),
    numTrials           : TrialCount      = TrialCount(10),
    maxBootstrappedDemos: DemoCount       = DemoCount(4),
    maxLabeledDemos     : DemoCount       = DemoCount(4),
    initTemperature     : Double          = 1.0,
    evaluation          : EvaluateOptions = EvaluateOptions(),
    seed                : Long            = 0L
)

/** Effectful joint instruction and demonstration search for `RecordProgram`.
  *
  * Candidate generation uses visible typed programs. Parameter updates use stable IDs. A seeded pure planner creates
  * the random trials, and effectful evaluation interprets those plans.
  */
object MIPROv2:

  final case class ProposalInput(
      parameterId       : String,
      component         : String,
      fieldNames        : Vector[String],
      currentInstruction: Option[String],
      trainset          : Vector[Example],
      demos             : Vector[Example],
      candidate         : Int
  )

  final case class Proposal(instruction: String)

  private type DemoAssignment = Map[ParameterId, Vector[Example]]
  private type Instructions   = Map[ParameterId, Vector[Option[String]]]

  private final case class DemoState(assignments: Vector[DemoAssignment], failures: Int)
  private final case class ProposalState(values: Instructions, failures: Int)
  private final case class TrialPlan(demoIndex: Int, instructionIndexes: Map[ParameterId, Int])

  def apply[I, O, RP](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      proposer: ProgramWithEnv[ProposalInput, Proposal, RP],
      teacher : Option[RecordProgram[I, O]] = None,
      valset  : Option[Vector[Example]]     = None,
      config  : MIPROv2Config
  ): ZIO[PredictionBackend & RP, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    val bindings = student.program.parameters.all
    if bindings.isEmpty then ZIO.succeed(emptyReport(student))
    else
      for
        demos        <- generateDemoCandidates(student, trainset, teacher, config)
        instructions <- generateInstructionCandidates(
                          bindings,
                          trainset,
                          demos.assignments,
                          proposer,
                          config
                        )
        plans   = planTrials(bindings, demos.assignments.size, instructions.values, config)
        report <- evaluateTrials(
                    student,
                    valset.getOrElse(trainset),
                    demos,
                    instructions,
                    plans,
                    config
                  )
      yield report

  private def generateDemoCandidates[I, O](
      student : RecordProgram[I, O],
      trainset: Vector[Example],
      teacher : Option[RecordProgram[I, O]],
      config  : MIPROv2Config
  ): ZIO[PredictionBackend, Nothing, DemoState] =
    val zeroShot = student.program.parameters.all.map(binding => binding.id -> Vector.empty[Example]).toMap
    ZIO.foldLeft(0 until config.numCandidates)(DemoState(Vector(zeroShot), 0)) { (state, candidate) =>
      BootstrapFewShot(
        student,
        trainset,
        teacher,
        BootstrapFewShotConfig(
          metric = Some(config.metric),
          maxBootstrappedDemos = config.maxBootstrappedDemos,
          maxLabeledDemos = config.maxLabeledDemos,
          seed = config.seed + candidate
        )
      ).either.map {
        case Right(report) =>
          val assignment =
            report.bestProgram.program.parameters.all.map(binding => binding.id -> binding.value.demos).toMap
          if state.assignments.contains(assignment) then state
          else state.copy(assignments = state.assignments :+ assignment)
        case Left(_) => state.copy(failures = state.failures + 1)
      }
    }

  private def generateInstructionCandidates[RP](
      bindings       : Vector[ParameterBinding],
      trainset       : Vector[Example],
      demoAssignments: Vector[DemoAssignment],
      proposer       : ProgramWithEnv[ProposalInput, Proposal, RP],
      config         : MIPROv2Config
  ): ZIO[RP, Nothing, ProposalState] =
    ZIO.foldLeft(bindings.zipWithIndex)(ProposalState(Map.empty, 0)) { case (state, (binding, leaf)) =>
      val initial = Vector(binding.value.instructions)
      ZIO.foldLeft(0 until config.numCandidates)(initial -> 0) { case ((values, failures), candidate) =>
        val seedDemos = demoAssignments.lift(1).flatMap(_.get(binding.id)).getOrElse(Vector.empty)
        val input     = ProposalInput(
          parameterId = binding.id.value,
          component = binding.metadata.moduleName,
          fieldNames = binding.metadata.structure.fields.map(_.name),
          currentInstruction = binding.value.instructions,
          trainset = trainset,
          demos = seedDemos,
          candidate = candidate
        )
        val options = RunOptions(
          config = DynamicValues.record("temperature" := config.initTemperature),
          rolloutId = Some(OptimizerSupport.seedBase(config.seed) + leaf * 10000 + candidate)
        )
        ProgramRunner.run(proposer, input, options).either.map {
          case Right(prediction) =>
            val instruction = prediction.output.instruction.trim
            val proposed    = Option.when(instruction.nonEmpty)(instruction)
            if proposed.exists(value => !values.contains(Some(value))) then (values :+ proposed) -> failures
            else values                                                                          -> failures
          case Left(_) => values -> (failures + 1)
        }
      }.map { case (values, failures) =>
        state.copy(
          values = state.values.updated(binding.id, values),
          failures = state.failures + failures
        )
      }
    }

  private def planTrials(
      bindings    : Vector[ParameterBinding],
      demoCount   : Int,
      instructions: Instructions,
      config      : MIPROv2Config
  ): Vector[TrialPlan] =
    val random = new scala.util.Random(config.seed)
    Vector.tabulate(config.numTrials) { _ =>
      TrialPlan(
        demoIndex = random.nextInt(demoCount),
        instructionIndexes = bindings.map { binding =>
          binding.id -> random.nextInt(instructions(binding.id).size)
        }.toMap
      )
    }

  private def evaluateTrials[I, O](
      student     : RecordProgram[I, O],
      evalset     : Vector[Example],
      demos       : DemoState,
      instructions: ProposalState,
      plans       : Vector[TrialPlan],
      config      : MIPROv2Config
  ): ZIO[PredictionBackend, DspyError, OptimizationReport[RecordProgram[I, O]]] =
    Evaluate(student, evalset, config.metric, config.evaluation).flatMap { baselineEvaluation =>
      val baseline = CandidateProgram(
        program = student,
        score = baselineEvaluation.score,
        evaluation = Some(baselineEvaluation),
        metadata = Map("trial" -> -1, "baseline" -> true)
      )
      ZIO.foldLeft(plans.zipWithIndex)(Vector(baseline)) { case (candidates, (plan, trial)) =>
        ZIO.fromEither(applyTrial(student, demos.assignments(plan.demoIndex), instructions.values, plan)).flatMap {
          program =>
            Evaluate(program, evalset, config.metric, config.evaluation).map { evaluation =>
              candidates :+ CandidateProgram(
                program = program,
                score = evaluation.score,
                evaluation = Some(evaluation),
                metadata = Map(
                  "trial"               -> trial,
                  "baseline"            -> false,
                  "demo_index"          -> plan.demoIndex,
                  "instruction_indices" -> plan.instructionIndexes.map((id, index) => id.value -> index)
                )
              )
            }
        }
      }.map { candidates =>
        val best = candidates.tail.foldLeft(candidates.head) { (current, candidate) =>
          if candidate.score > current.score then candidate else current
        }
        OptimizationReport(
          bestProgram = best.program,
          candidates = candidates.sortBy(candidate => -candidate.score),
          metadata = Map(
            "optimizer"              -> "mipro_v2",
            "num_candidates"         -> candidates.size,
            "num_trials"             -> plans.size,
            "num_demo_candidates"    -> demos.assignments.size,
            "num_bootstrap_failures" -> demos.failures,
            "num_proposal_failures"  -> instructions.failures,
            "best_score"             -> best.score,
            "optimizable_leaves"     -> student.program.parameters.size
          )
        )
      }
    }

  private def applyTrial[I, O](
      student     : RecordProgram[I, O],
      demos       : DemoAssignment,
      instructions: Instructions,
      plan        : TrialPlan
  ): Either[DspyError, RecordProgram[I, O]] =
    val replacements: Map[ParameterId, OptimizableParameters] = student.program.parameters.all.map { binding =>
      val instruction = instructions(binding.id)(plan.instructionIndexes(binding.id))
      val parameters  = binding.value.copy(
        instructions = instruction,
        demos = demos.getOrElse(binding.id, binding.value.demos)
      )
      binding.id -> parameters
    }.toMap
    student.replaceParameters(replacements)

  private def emptyReport[I, O](student: RecordProgram[I, O]): OptimizationReport[RecordProgram[I, O]] =
    OptimizationReport(
      bestProgram = student,
      candidates = Vector.empty,
      metadata = Map(
        "optimizer"          -> "mipro_v2",
        "num_candidates"     -> 0,
        "best_score"         -> 0.0,
        "optimizable_leaves" -> 0
      )
    )
