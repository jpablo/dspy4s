package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.Module
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter

final case class LabeledFewShotConfig(
    k: Int = 16,
    sample: Boolean = true,
    seed: Long = 0L
):
  require(k >= 0, "k must be non-negative")

final class LabeledFewShot[P <: Module[dspy4s.programs.contracts.ProgramCall, Prediction]: PredictOps](
    config: LabeledFewShotConfig = LabeledFewShotConfig()
) extends Teleprompter[P]:

  override val name: String = "labeled_few_shot"

  override def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val ops = summon[PredictOps[P]]

    val demos: Vector[Example] =
      if trainset.isEmpty then Vector.empty
      else if !config.sample then Vector.from(trainset.take(config.k))
      else
        val rng = new scala.util.Random(config.seed)
        Vector.from(rng.shuffle(trainset).take(config.k))

    val compiled = ops.withDemos(student, demos)
    Right(
      OptimizationReport(
        bestProgram = compiled,
        candidates = Vector(
          CandidateProgram(
            program = compiled,
            score = 0.0,
            metadata = Map(
              "optimizer" -> name,
              "num_demos" -> demos.size,
              "trainset_size" -> trainset.size
            )
          )
        ),
        metadata = Map(
          "k" -> config.k,
          "sample" -> config.sample,
          "seed" -> config.seed
        )
      )
    )
