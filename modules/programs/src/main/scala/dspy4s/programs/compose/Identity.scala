package dspy4s.programs.compose

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.TransparentModule
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.contracts.Prediction

/** `id[I]` — the Category unit: a pure passthrough that returns its input as the output, with an empty raw envelope.
  * Sequential composition accumulates envelopes through [[RawPrediction.followedBy]], for which the empty envelope is
  * an identity, so both `id >>> p` and `p >>> id` preserve the complete prediction.
  */
final case class Identity[I]() extends TransparentModule[I, I]:
  override val moduleName: String                                                                              = "id"
  override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[I]] =
    Right(Prediction(call.input, RawPrediction.empty))

object Identity:
  given identityOptimizableStructure[I]: OptimizableStructure.WithArity[Identity[I], 0] =
    OptimizableStructure.empty
