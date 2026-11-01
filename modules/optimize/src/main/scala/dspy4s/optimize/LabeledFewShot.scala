package dspy4s.optimize

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter

final case class LabeledFewShotConfig(
    k     : DemoCount = DemoCount(16),
    sample: Boolean   = true,
    seed  : Long      = 0L
)

/** LabeledFewShot gives each predictor labeled demos from the trainset (upstream `dspy/teleprompt/vanilla.py`). With
  * `sample`, one random source gives a new sample of `k` examples to each predictor, in read order. Without `sample`,
  * each predictor gets the same first `k` examples.
  */
final class LabeledFewShot[P: OptimizableStructure](
    config: LabeledFewShotConfig = LabeledFewShotConfig()
) extends Teleprompter[P]:

  override val name: String = "labeled_few_shot"

  override protected[optimize] def compileInScope(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      valset  : Option[Vector[Example]]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val ps = summon[OptimizableStructure[P]]

    val rng = new scala.util.Random(config.seed)

    def demos(): Vector[Example] =
      if config.sample then rng.shuffle(trainset).take(config.k) else trainset.take(config.k)

    val compiled = ps.replace(student, ps.read(student).map(_.copy(demos = demos())))
    Right(
      OptimizationReport(
        bestProgram = compiled,
        candidates = Vector(
          CandidateProgram(
            program = compiled,
            score = 0.0,
            metadata = Map(
              "optimizer"     -> name,
              "num_demos"     -> math.min(config.k, trainset.size),
              "trainset_size" -> trainset.size
            )
          )
        ),
        metadata = Map(
          "k"      -> config.k,
          "sample" -> config.sample,
          "seed"   -> config.seed
        )
      )
    )
