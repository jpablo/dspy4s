package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.CodeAct
import dspy4s.programs.ChainOfThought
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Predict
import dspy4s.programs.ProgramRunner
import dspy4s.programs.ProgramOfThought
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.DynamicModule
import zio.blocks.schema.DynamicValue

/** What [[Streamify]] needs of a program in order to stream it, captured as a typeclass so that both dynamic
  * `Module[DynamicValue.Record, DynamicValue.Record]` values and statically typed `Module[I, O]` values can be
  * streamed through the same entry point. `Streamify` requires exactly two things of a program:
  *
  *   1. a shared [[ProgramRunner]] — invoke the program from a record of inputs, yielding its `RawPrediction`
  *      for the final `PredictionEvent`. Token streaming itself is orthogonal: it's driven by the wrapped
  *      `StreamingLanguageModel` consulting `ActivePredictContext`, which each `PredictEngine` execution scopes for
  *      both typed and dynamic prediction modules — independent of how the outer program is invoked. 2.
  *      [[knownSignatures]] — best-effort `(predictName, signature)` pairs used *only* for stream-listener validation
  *      (warnings). An opaque program returns empty and validation is skipped.
  */
trait Streamable[P]:
  protected def runner: ProgramRunner[P]
  def knownSignatures(program: P): Vector[(String, SignatureLayout)]

  final def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, RawPrediction] =
    runner.run(program, inputs)

object Streamable:

  private def from[P](known: P => Vector[(String, SignatureLayout)])(using
      programRunner: ProgramRunner[P]
  ): Streamable[P] =
    new Streamable[P]:
      protected val runner: ProgramRunner[P]                             = programRunner
      def knownSignatures(program: P): Vector[(String, SignatureLayout)] = known(program)

  /** Any dynamic program: invoke via a `ProgramCall`; surface a leaf `DynamicPredict`'s signature for validation. */
  given dynamicModule[P <: DynamicModule](using ProgramRunner[P]): Streamable[P] =
    from { program =>
      program match
        case p: DynamicPredict => Vector((p.moduleName, p.layout))
        case _                 => Vector.empty
    }

  /** A typed `Predict` has one engine-visible signature. */
  given predict[I, O](using ProgramRunner[Predict[I, O]]): Streamable[Predict[I, O]] =
    from(program => Vector(program.moduleName -> program.signature.layout))

  /** `ChainOfThought` delegates to an inner `Predict` whose runtime signature contains the reasoning field. */
  given chainOfThought[I, O](using ProgramRunner[ChainOfThought[I, O]]): Streamable[ChainOfThought[I, O]] =
    from { program =>
      val layout = ChainOfThought.augmentLayout(program.baseSignature.layout)
      Vector("predict" -> layout)
    }

  /** Typed `ReAct`: decode the record into the typed input, run it, and emit the raw prediction. Its two sub-predicts
    * (the per-step react predict and the final extractor) are the stream-listener targets.
    */
  given reAct[I, O](using ProgramRunner[ReAct[I, O]]): Streamable[ReAct[I, O]] =
    from { program =>
      Vector(
        (program.reactProgramName, program.reactSignature),
        // The extractor runs the CoT-AUGMENTED layout (a `reasoning` output prepended). Report that — not the
        // un-augmented extractorSignature — so a `reasoning` stream listener isn't wrongly told it'll never fire.
        (program.extractorProgramName, program.extractorPredict.signature.layout)
      )
    }

  /** Typed `CodeAct`: decode the record into the typed input, run it, and emit the raw prediction. Its two sub-predicts
    * (the per-iteration code generator and the final extractor) are the stream-listener targets.
    */
  given codeAct[I, O](using ProgramRunner[CodeAct[I, O]]): Streamable[CodeAct[I, O]] =
    from { program =>
      Vector(
        (program.codeActProgramName, program.codeActSignature),
        // The extractor runs the CoT-AUGMENTED layout (a `reasoning` output prepended). Report that — not the
        // un-augmented extractorSignature — so a `reasoning` stream listener isn't wrongly told it'll never fire.
        (program.extractorProgramName, program.extractorPredict.signature.layout)
      )
    }

  /** Typed `ProgramOfThought`: decode the record into the typed input, run it, and emit the raw prediction. Its three
    * stable inner predictors are the stream-listener targets.
    */
  given programOfThought[I, O](using ProgramRunner[ProgramOfThought[I, O]]): Streamable[ProgramOfThought[I, O]] =
    from { program =>
      Vector(
        program.generatorPredict.moduleName   -> program.generatorPredict.signature.layout,
        program.regeneratorPredict.moduleName -> program.regeneratorPredict.signature.layout,
        program.answererPredict.moduleName    -> program.answererPredict.signature.layout
      )
    }
