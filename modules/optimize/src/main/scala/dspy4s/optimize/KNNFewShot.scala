package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.Embedder
import dspy4s.programs.predictors.Predictors
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.retrievers.KNN
import dspy4s.programs.retrievers.NeighborCount
import dspy4s.programs.retrievers.NonEmptyTrainset
import zio.blocks.schema.DynamicValue

/** KNNFewShot — dynamic per-query few-shot selection (a port of `dspy.teleprompt.KNNFewShot`, PORT_GAPS G-10). At each
  * forward call, the [[KNN]] retriever finds the `k` trainset examples nearest to THAT input, runs [[BootstrapFewShot]]
  * over just those neighbors to attach them as demos, and executes the compiled program — so every query gets
  * demonstrations tailored to it, instead of one fixed demo set.
  *
  * ==Delta from Python==
  * Upstream's `compile` returns a copy of the student with a monkey-patched `forward`. dspy4s programs are immutable
  * case classes, so this is NOT a [[dspy4s.optimize.contracts.Teleprompter]] (whose `compile` must return an optimized
  * `P`): [[compile]] instead returns a [[KNNFewShotProgram]] — a wrapper [[DynamicModule]] with the same per-call
  * behavior.
  */
final class KNNFewShot[P: {Predictors, ProgramRunner}](
    k: NeighborCount,
    trainset: NonEmptyTrainset,
    embedder: Embedder,
    bootstrapConfig: BootstrapFewShotConfig = BootstrapFewShotConfig()
):
  /** Embed the trainset (eager, like upstream's `KNN.__init__`) and wrap the student. */
  def compile(student: P, teacher: Option[P] = None)(using RuntimeContext): Either[DspyError, KNNFewShotProgram[P]] =
    KNN.create(k, trainset, embedder).map(knn => new KNNFewShotProgram(student, teacher, knn, bootstrapConfig))

/** The compiled artifact of [[KNNFewShot]]: a module that, per call, retrieves the query's nearest trainset neighbors,
  * bootstraps them onto the student as demos, and runs the result.
  */
final class KNNFewShotProgram[P: {Predictors, ProgramRunner}] private[optimize] (
    val student: P,
    teacher: Option[P],
    knn: KNN,
    bootstrapConfig: BootstrapFewShotConfig
) extends DynamicModule:

  override val moduleName: String = "knn_few_shot"

  override protected def forward(call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, DynamicPrediction] =
    for
      neighbors <- knn.retrieve(call.input)
      report    <- new BootstrapFewShot[P](bootstrapConfig).compile(student, neighbors, teacher)
      result    <- summon[ProgramRunner[P]].run(report.bestProgram, call.input)
    yield result
