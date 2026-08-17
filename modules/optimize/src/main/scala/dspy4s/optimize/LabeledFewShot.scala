package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport}
import dspy4s.programs.RecordProgramWithEnv
import zio.{IO, ZIO}

final case class LabeledFewShotConfig(
    k     : DemoCount = DemoCount(16),
    sample: Boolean   = true,
    seed  : Long      = 0L
)

/** Add labeled demonstrations to every parameter slot without running the program. */
object LabeledFewShot:

  def apply[I, O, R](
      student : RecordProgramWithEnv[I, O, R],
      trainset: Vector[Example],
      config  : LabeledFewShotConfig = LabeledFewShotConfig()
  ): IO[DspyError, OptimizationReport[RecordProgramWithEnv[I, O, R]]] =
    val demos =
      if trainset.isEmpty then Vector.empty
      else if !config.sample then Vector.from(trainset.take(config.k))
      else
        val random = new scala.util.Random(config.seed)
        Vector.from(random.shuffle(trainset).take(config.k))

    val replacements = student.program.parameters.all.map { binding =>
      binding.id -> binding.value.copy(demos = demos)
    }.toMap

    ZIO.fromEither(student.replaceParameters(replacements)).map { compiled =>
      OptimizationReport(
        bestProgram = compiled,
        candidates = Vector(CandidateProgram(
          program = compiled,
          score = 0.0,
          metadata = Map(
            "optimizer"     -> "labeled_few_shot",
            "num_demos"     -> demos.size,
            "trainset_size" -> trainset.size
          )
        )),
        metadata = Map(
          "k"      -> config.k,
          "sample" -> config.sample,
          "seed"   -> config.seed
        )
      )
    }
