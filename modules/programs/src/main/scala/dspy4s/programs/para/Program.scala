package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.AndThen
import dspy4s.programs.Both
import dspy4s.programs.Compose
import dspy4s.programs.andThen
import dspy4s.programs.&&&
import dspy4s.programs.predictors.OptimizableParameters
import dspy4s.programs.predictors.PredictorView
import dspy4s.programs.ProgramRunner
import dspy4s.programs.predictors.PredictorTraversal
import dspy4s.programs.RecordCodec
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

/** A packaged, existentially typed addressable program: the hom-set of the Para-shaped category.
  *
  * The package hides a concrete module representation while retaining its [[PredictorTraversal]] evidence, so the binary
  * type `Program[I, O]` supports parameter projection and reparameterization without knowing the representation.
  * Construction through [[Program.of]] is the only gate, and it requires evidence at BOTH slots: `PredictorTraversal` for
  * the morphism (no addressability, no program) and [[RecordCodec]] for the domain OBJECT (no codec, no object).
  *
  * Decoding is a property of the object, not the morphism: nothing decode-related is packaged, and identity plus
  * record-boundary evaluation resolve the sealed canonical `RecordCodec[I]` for that object. The old
  * morphism-specific `ProgramInput` capability and its coherence law are GONE; an incoherent per-morphism decoder is
  * no longer representable. Runtime-string signatures participate through
  * [[dspy4s.programs.DynamicSignature]], whose parse mints fresh codec-equipped types.
  *
  * The category laws use the complete prediction, parameters, and lifecycle as their observation. Sequential raw
  * evidence has an associative accumulator with the empty envelope as identity, so structural identity preserves the
  * same public result observed by [[ProgramRunner]]. Equality is observational rather than structural equality of this
  * existential package.
  */
sealed trait Program[I, O]:
  type Rep <: Module[I, O]
  val program: Rep
  val predictors: PredictorTraversal[Rep]

  /** Run the packaged program through the module's wrapped `apply`. */
  def apply(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program.apply(call)

object Program:

  private def packageWith[I, O, F <: Module[I, O]](
      f: F
  )(using ev: PredictorTraversal[F]): Program[I, O] { type Rep = F } =
    new Program[I, O]:
      type Rep = F
      val program: F                        = f
      val predictors: PredictorTraversal[F] = ev

  /** Package a program at a codec-equipped object. The `RecordCodec[I]` requirement is the categorical gate:
    * every object reachable through `of` / `id` has a canonical decoder, which makes the unit laws unconditional. */
  def of[I, O, F <: Module[I, O]](f: F)(using
      ev: PredictorTraversal[F],
      @annotation.unused codec: RecordCodec[I]
  ): Program[I, O] { type Rep = F } =
    packageWith(f)

  /** Addressability for packaged programs delegates to the evidence retained by the package. */
  given programPredictorTraversal[I, O]: PredictorTraversal[Program[I, O]] with
    def inspect(program: Program[I, O]): Vector[PredictorView] =
      program.predictors.inspect(program.program)

    def replace(program: Program[I, O], updates: Vector[OptimizableParameters]): Program[I, O] =
      Program.packageWith(program.predictors.replace(program.program, updates))(using program.predictors)

    override def inspectNamed(program: Program[I, O]): Vector[(String, PredictorView)] =
      program.predictors.inspectNamed(program.program)

  /** Record-boundary execution resolves the sealed canonical codec for the domain object. */
  given programRunner[I, O](using codec: RecordCodec[I]): ProgramRunner[Program[I, O]] with
    def run(program: Program[I, O], call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, dspy4s.core.data.RawPrediction] =
      codec.decode(call.input).flatMap(input => program.apply(call.mapInput(_ => input)).map(_.raw))

  /** The Para category over packaged programs.
    *
    * Identity exists at codec-equipped objects; composition and fan-out retain only the structural `PredictorTraversal`
    * evidence of their children. Decoder equality between `id` and any program is definitional on the sealed
    * canonical object codec, so the unit laws hold with no morphism-specific coherence condition.
    */
  given paraCategoryProgram: ParaCategory[RecordCodec, Program] with
    def id[A](using @annotation.unused codec: RecordCodec[A]): Program[A, A] =
      Program.packageWith(Compose.id[A])

    def fanout[I, A, B](f: Program[I, A], g: Program[I, B]): Program[I, (A, B)] =
      Program.packageWith(f.program &&& g.program)(using
        Both.bothPredictorTraversal[I, A, B, f.Rep, g.Rep](using f.predictors, g.predictors)
      )

    extension [A, B](f: Program[A, B])
      infix def >>>[C](g: Program[B, C]): Program[A, C] =
        Program.packageWith(f.program.andThen(g.program))(using
          AndThen.andThenPredictorTraversal[A, B, C, f.Rep, g.Rep](using f.predictors, g.predictors)
        )

      def params: Vector[OptimizableParameters] = f.predictors.read(f.program)

      def reparam(ps: Vector[OptimizableParameters]): Program[A, B] =
        Program.packageWith(f.predictors.replace(f.program, ps))(using f.predictors)

/** Parameter projection as a functor from packaged programs into the delooped parameter monoid. */
object ReadFunctor extends CategoryFunctor[RecordCodec, Program, AnyObject, ParamsHom]:
  def map[A, B](f: Program[A, B]): ParamsHom[A, B] = f.params
