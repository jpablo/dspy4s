package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.AndThen
import dspy4s.programs.Both
import dspy4s.programs.Compose
import dspy4s.programs.andThen
import dspy4s.programs.&&&
import dspy4s.programs.predictors.PredictorState
import dspy4s.programs.predictors.PredictorView
import dspy4s.programs.ProgramInput
import dspy4s.programs.ProgramRunner
import dspy4s.programs.predictors.Predictors
import dspy4s.programs.RecordCodec
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** A packaged, existentially typed addressable program: the hom-set of the Para-shaped category.
  *
  * The package hides a concrete module representation while retaining its [[Predictors]] evidence and input decoder.
  * Consequently, the binary type `Program[I, O]` supports parameter projection, reparameterization, and record-based
  * evaluation without knowing the representation. Construction through [[Program.of]] is the only public gate: it
  * requires [[ProgramInput]] evidence, so the decoder-coherence obligation sits on the instance (the `ProgramInput`
  * law), the conventional typeclass shape. A caller needing a custom decoder supplies its own instance explicitly
  * and thereby assumes that law. No constructor admits a representation without `Predictors` evidence.
  *
  * The category laws use typed output, parameters, coherent record decoding, and lifecycle as their observation. They
  * do not use structural equality of this existential package or final `Prediction.raw`: right identity preserves the
  * semantic output and lifecycle but identity supplies an empty final raw envelope.
  */
sealed trait Program[I, O]:
  type Rep <: Module[ProgramCall[I], Prediction[O]]
  val program: Rep
  val addressable: Predictors[Rep]

  /** The input decoder captured at packaging time and threaded through composition. */
  val decodeInput: DynamicValue.Record => Either[DspyError, I] // why not require a ProgramInput instead?

  /** Run the packaged program through the module's wrapped `apply`. */
  def apply(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program.apply(call)

object Program:

  private def packageWith[I, O, F <: Module[ProgramCall[I], Prediction[O]]](
      f: F,
      decode: DynamicValue.Record => Either[DspyError, I]
  )(using ev: Predictors[F]): Program[I, O] { type Rep = F } =
    new Program[I, O]:
      type Rep = F
      val program: F                                               = f
      val addressable: Predictors[F]                               = ev
      val decodeInput: DynamicValue.Record => Either[DspyError, I] = decode

  /** Package a program whose input decoder comes from [[ProgramInput]] evidence (derived for the framework's
    * signature-backed programs and codec-equipped input types; supplied explicitly, under the `ProgramInput`
    * coherence law, by anything else). */
  def of[I, O, F <: Module[ProgramCall[I], Prediction[O]]](f: F)(using
      ev: Predictors[F],
      codec: ProgramInput[F, I]
  ): Program[I, O] { type Rep = F } =
    packageWith(f, codec.decoder(f))

  /** Addressability for packaged programs delegates to the evidence retained by the package. */
  given programPredictors[I, O]: Predictors[Program[I, O]] with
    def inspect(program: Program[I, O]): Vector[PredictorView] =
      program.addressable.inspect(program.program)

    def replace(program: Program[I, O], updates: Vector[PredictorState]): Program[I, O] =
      Program.packageWith(program.addressable.replace(program.program, updates), program.decodeInput)(using
        program.addressable
      )

    override def inspectNamed(program: Program[I, O]): Vector[(String, PredictorView)] =
      program.addressable.inspectNamed(program.program)

  /** Uniform record-boundary execution uses the decoder captured by the existential package. */
  given programRunner[I, O]: ProgramRunner[Program[I, O]] with
    def run(program: Program[I, O], call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, dspy4s.core.data.DynamicPrediction] =
      program.decodeInput(call.input).flatMap(input => program.apply(call.mapInput(_ => input)).map(_.raw))

  /** The Para category over packaged programs.
    *
    * Identity synthesizes a decoder from the object's [[RecordCodec]]. Composition and fan-out retain the structural
    * `Predictors` evidence of their children and thread the shared input decoder from the first leg. Decoder equality
    * holds given LAWFUL [[ProgramInput]] instances (the trait's coherence law): the category is lawful conditional on
    * its instances, the standard typeclass contract. The law suite pins the counterexample an unlawful instance
    * produces.
    */
  given paraCategoryProgram: ParaCategory[RecordCodec, Program] with
    def id[A: RecordCodec]: Program[A, A] =
      Program.packageWith(Compose.id[A], summon[RecordCodec[A]].decode)

    def fanout[I, A, B](f: Program[I, A], g: Program[I, B]): Program[I, (A, B)] =
      Program.packageWith(f.program &&& g.program, f.decodeInput)(using
        Both.bothPredictors[I, A, B, f.Rep, g.Rep](using f.addressable, g.addressable)
      )

    extension [A, B](f: Program[A, B])
      infix def >>>[C](g: Program[B, C]): Program[A, C] =
        Program.packageWith(f.program.andThen(g.program), f.decodeInput)(using
          AndThen.andThenPredictors[A, B, C, f.Rep, g.Rep](using f.addressable, g.addressable)
        )

      def params: Vector[PredictorState] = f.addressable.read(f.program)

      def reparam(ps: Vector[PredictorState]): Program[A, B] =
        Program.packageWith(f.addressable.replace(f.program, ps), f.decodeInput)(using f.addressable)

/** Parameter projection as a functor from packaged programs into the delooped parameter monoid. */
object ReadFunctor extends CategoryFunctor[RecordCodec, Program, AnyObject, ParamsHom]:
  def map[A, B](f: Program[A, B]): ParamsHom[A, B] = f.params
