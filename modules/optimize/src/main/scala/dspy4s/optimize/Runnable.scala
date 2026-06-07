package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.ChainOfThought
import dspy4s.programs.CodeAct
import dspy4s.programs.Predict
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.DynamicModule
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.OutputAugmentation
import zio.blocks.schema.DynamicValue

/** Uniformly runs an arbitrary program `P` on an example's inputs, yielding the untyped
  * [[DynamicPrediction]] the optimizers and [[dspy4s.evaluate.Evaluate]] consume.
  *
  * This is the P5 "spine unification": [[dspy4s.evaluate.Evaluate.apply]] already takes a plain
  * `Example => Either[DspyError, DynamicPrediction]` (not a [[DynamicModule]]), so the only thing the
  * optimizers needed in order to target *typed* programs was a uniform way to RUN `P` on a record of
  * inputs. `Runnable` supplies exactly that and nothing more.
  *
  *   - The untyped spine ([[DynamicModule]]) runs through `apply(ProgramCall(inputs = inputs))`.
  *   - A typed single program runs by decoding `inputs` to its input type `I` via its signature's
  *     `inputShape`, invoking the typed `apply(i)`, and returning `prediction.raw`.
  *
  * No `asInstanceOf` is used; each instance is keyed to a concrete program type whose decode/apply
  * surface is known statically.
  */
trait Runnable[P]:
  def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction]

object Runnable:

  /** The untyped spine. Every engine program (`DynamicPredict`, `ReAct`/`CodeAct`'s inner predicts, and
    * user data-bag programs) is a [[DynamicModule]] and runs by calling `apply(ProgramCall(...))`. This is
    * the instance that keeps the existing DynamicModule-based optimizer tests green. */
  given fromDynamicModule[P <: DynamicModule]: Runnable[P] with
    def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      program.apply(ProgramCall(inputs = inputs))

  /** Typed single program [[Predict]]: decode `inputs` to `I` via the signature's bidirectional input
    * shape, run the typed `apply(i)`, and hand back the underlying [[DynamicPrediction]] (`prediction.raw`). */
  given fromPredict[I, O]: Runnable[Predict[I, O]] with
    def run(program: Predict[I, O], inputs: DynamicValue.Record)(using RuntimeContext)
        : Either[DspyError, DynamicPrediction] =
      program.signature.inputShape.decode(inputs).flatMap(i => program.apply(i).map(_.raw))

  /** Typed single program [[ChainOfThought]]. Same shape as [[fromPredict]]; the `prepend` evidence is part
    * of `ChainOfThought`'s parameter list and is required for the type to be well-formed at this use site. */
  given fromChainOfThought[I, O](using
      @annotation.unused prepend: OutputAugmentation.PrependField.Aux[
        "reasoning", String, O, ChainOfThought.WithReasoning[O]
      ]
  ): Runnable[ChainOfThought[I, O]] with
    def run(program: ChainOfThought[I, O], inputs: DynamicValue.Record)(using RuntimeContext)
        : Either[DspyError, DynamicPrediction] =
      program.signature.inputShape.decode(inputs).flatMap(i => program.apply(i).map(_.raw))

  /** Typed composite [[ReAct]]: inputs decode through `baseSignature.inputShape`; the typed `apply(i)` runs
    * the full react/extract loop and we return the raw prediction. */
  given fromReAct[I, O]: Runnable[ReAct[I, O]] with
    def run(program: ReAct[I, O], inputs: DynamicValue.Record)(using RuntimeContext)
        : Either[DspyError, DynamicPrediction] =
      program.baseSignature.inputShape.decode(inputs).flatMap(i => program.apply(i).map(_.raw))

  /** Typed composite [[CodeAct]]: same as [[fromReAct]] over `baseSignature`. */
  given fromCodeAct[I, O]: Runnable[CodeAct[I, O]] with
    def run(program: CodeAct[I, O], inputs: DynamicValue.Record)(using RuntimeContext)
        : Either[DspyError, DynamicPrediction] =
      program.baseSignature.inputShape.decode(inputs).flatMap(i => program.apply(i).map(_.raw))

  /** Summon helper. */
  def apply[P](using r: Runnable[P]): Runnable[P] = r

  // MultiChainComparison is deliberately NOT given a Runnable instance: it does not take a bare input `I`
  // but a `MultiChainCall` (a set of candidate completions to compare), so there is no `inputs`-only run
  // shape that matches the `Runnable` contract. Optimizing it requires a different call protocol; left for
  // a later phase.
  //
  // Arbitrary USER composites typed as `Module[TypedCall[I], Prediction[O]]` are also NOT covered by a
  // generic given: a bare `Module` does not expose its signature, so there is no sound way to obtain the
  // `inputShape` decoder generically. User composites therefore supply their own `Runnable` (typically a
  // one-liner delegating to a contained typed program's signature). See `TypedProgramOptimizeSuite`
  // (`TwoStageQA`) for a worked example.
