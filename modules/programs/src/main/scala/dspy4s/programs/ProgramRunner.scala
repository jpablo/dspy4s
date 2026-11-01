package dspy4s.programs

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.contracts.{DynamicModule, Module, Prediction, ProgramCall}
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue

/** Capability for evaluating a program from the dynamic record boundary.
  *
  * Optimizers, evaluation, and streaming all consume this same boundary: a record-valued [[ProgramCall]] goes in and
  * the program's [[RawPrediction]] comes out. The capability is external to `Module` because record decoding is not
  * available on every module representation; third-party programs can supply their own instance.
  *
  * For BARE modules the program's own signature is the canonical decode boundary (no identity morphism is in sight, so
  * no coherence question arises); the framework leaves and composites carry signature-backed instances below. For a
  * packaged `Program`, decoding is likewise resolved canonically from [[RecordCodec]] at this boundary rather than
  * stored in the morphism or required by the program algebra.
  */
trait ProgramRunner[P]:
  def run(program: P, call: ProgramCall[DynamicValue.Record])(using
      RuntimeContext
  ): Either[DspyError, RawPrediction]

  final def run(program: P, inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, RawPrediction] =
    run(program, ProgramCall(inputs))

/** A framework program that decodes its record input through its own (base) signature. */
private[programs] trait SignatureInput[I]:
  private[programs] def baseSignature: Signature[I, ?]

private[programs] trait LowPriorityProgramRunner:
  /** Any module whose input type carries a [[RecordCodec]] (user composites without a hand-written runner). Lower
    * priority than the signature-backed instances in the companion.
    */
  given fromRecordCodec[I, O, P <: Module[I, O]](using
      codec: RecordCodec[I]
  ): ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      ProgramRunner.decodeThenRun(codec.decode, call)(program(_))

object ProgramRunner extends LowPriorityProgramRunner:

  /** Decode the record input of `call`, run `program` on the decoded call, and keep the raw prediction. */
  private[programs] def decodeThenRun[I, O](
      decode: DynamicValue.Record => Either[DspyError, I],
      call  : ProgramCall[DynamicValue.Record]
  )(program: ProgramCall[I] => Either[DspyError, Prediction[O]]): Either[DspyError, RawPrediction] =
    decode(call.input).flatMap(decoded => program(call.mapInput(_ => decoded)).map(_.raw))

  /** Dynamic modules already inhabit the record boundary. */
  given fromDynamicModule[P <: DynamicModule]: ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program(call).map(_.raw)

  /** The framework leaves and composites, decoded through their own (base) signature. */
  given fromSignature[I, O, P <: Module[I, O] & SignatureInput[I]]: ProgramRunner[P] with
    def run(program: P, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      decodeThenRun(program.baseSignature.inputShape.decode, call)(program(_))

  def apply[P](using runner: ProgramRunner[P]): ProgramRunner[P] = runner
