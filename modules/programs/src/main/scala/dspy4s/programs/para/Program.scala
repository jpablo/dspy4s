package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.AndThen
import dspy4s.programs.Both
import dspy4s.programs.PredictorState
import dspy4s.programs.PredictorView
import dspy4s.programs.Identity
import dspy4s.programs.Predictors
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** A packaged, existentially typed addressable program: the hom-set of the Para-shaped category.
  *
  * The package hides a concrete module representation while retaining its [[Predictors]] evidence and input decoder.
  * Consequently, the binary type `Program[I, O]` supports parameter projection, reparameterization, and record-based
  * evaluation without knowing the representation. Construction through [[Program.of]] is the coherent gate;
  * [[Program.unsafeOf]] is the explicitly named custom-decoder escape hatch. Neither admits a representation without
  * `Predictors` evidence.
  *
  * The category laws use typed output, parameters, coherent record decoding, and lifecycle as their observation. They
  * do not use structural equality of this existential package or final `Prediction.raw`: right identity preserves the
  * semantic output and lifecycle but identity supplies an empty final raw envelope.
  */
sealed trait Program[I, O]:
  type Rep <: Module[TypedCall[I], Prediction[O]]
  val program: Rep
  val addressable: Predictors[Rep]

  /** The input decoder captured at packaging time and threaded through composition. */
  val decodeInput: DynamicValue.Record => Either[DspyError, I]

  /** Run the packaged program through the module's wrapped `apply`. */
  def apply(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program.apply(call)

object Program:

  private def packageWith[I, O, F <: Module[TypedCall[I], Prediction[O]]](
      f: F,
      decode: DynamicValue.Record => Either[DspyError, I]
  )(using ev: Predictors[F]): Program[I, O] { type Rep = F } =
    new Program[I, O]:
      type Rep = F
      val program: F                                               = f
      val addressable: Predictors[F]                               = ev
      val decodeInput: DynamicValue.Record => Either[DspyError, I] = decode

  /** Package a module with an explicitly supplied input decoder.
    *
    * Prefer [[of]]: this escape hatch cannot prove that `decode` agrees with the module's typed-call boundary or with
    * the source object's [[RecordCodec]]. An incoherent decoder remains runnable, but falls outside the observational
    * equality under which the category laws hold.
    */
  def unsafeOf[I, O, F <: Module[TypedCall[I], Prediction[O]]](
      f: F,
      decode: DynamicValue.Record => Either[DspyError, I]
  )(using ev: Predictors[F]): Program[I, O] { type Rep = F } =
    packageWith(f, decode)

  /** Package a program whose input decoder is derivable from [[ProgramInput]]. */
  def of[I, O, F <: Module[TypedCall[I], Prediction[O]]](f: F)(using
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

  /** The Para category over packaged programs.
    *
    * Identity synthesizes a decoder from the object's [[RecordCodec]]. Composition and fan-out retain the structural
    * `Predictors` evidence of their children and thread the shared input decoder from the first leg. Decoder equality
    * assumes packages were built through coherent [[ProgramInput]] evidence; [[unsafeOf]] documents the escape hatch.
    */
  given paraCategoryProgram: ParaCategory[RecordCodec, Program] with
    def id[A: RecordCodec]: Program[A, A] =
      Program.packageWith(Identity[A](), summon[RecordCodec[A]].decode)

    def fanout[I, A, B](f: Program[I, A], g: Program[I, B]): Program[I, (A, B)] =
      Program.packageWith(Both[I, A, B, f.Rep, g.Rep](f.program, g.program), f.decodeInput)(using
        Both.bothPredictors[I, A, B, f.Rep, g.Rep](using f.addressable, g.addressable)
      )

    extension [A, B](f: Program[A, B])
      infix def >>>[C](g: Program[B, C]): Program[A, C] =
        Program.packageWith(AndThen[A, B, C, f.Rep, g.Rep](f.program, g.program), f.decodeInput)(using
          AndThen.andThenPredictors[A, B, C, f.Rep, g.Rep](using f.addressable, g.addressable)
        )

      def params: Vector[PredictorState] = f.addressable.read(f.program)

      def reparam(ps: Vector[PredictorState]): Program[A, B] =
        Program.packageWith(f.addressable.replace(f.program, ps), f.decodeInput)(using f.addressable)

/** Parameter projection as a functor from packaged programs into the delooped parameter monoid. */
object ReadFunctor extends CategoryFunctor[RecordCodec, Program, AnyObject, ParamsHom]:
  def map[A, B](f: Program[A, B]): ParamsHom[A, B] = f.params
