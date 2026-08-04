package dspy4s.programs.algebra

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.algebra.{AnyObject, CategoryFunctor, Lens}
import dspy4s.core.collections.SizedVector
import dspy4s.programs.AndThen
import dspy4s.programs.Both
import dspy4s.programs.Compose
import dspy4s.programs.andThen
import dspy4s.programs.&&&
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableView
import dspy4s.programs.ProgramRunner
import dspy4s.programs.optimization.OptimizableTraversal
import dspy4s.programs.RecordCodec
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import zio.blocks.schema.DynamicValue

import scala.compiletime.ops.int.+

/** A packaged, existentially typed addressable program: the hom-set of the parameterized category.
  *
  * The package hides a concrete module representation while retaining its fixed-arity [[OptimizableTraversal]]
  * evidence, so the binary type `Program[I, O]` supports parameter projection and reparameterization without knowing
  * the representation. Construction through [[Program.of]] is the only gate, and it requires evidence at BOTH slots:
  * [[OptimizableTraversal]] for the morphism (no addressability, no program) and [[RecordCodec]] for the domain OBJECT
  * (no codec, no object).
  *
  * Decoding is a property of the object, not the morphism: nothing decode-related is packaged, and identity plus
  * record-boundary evaluation resolve the sealed canonical `RecordCodec[I]` for that object. The old morphism-specific
  * `ProgramInput` capability and its coherence law are GONE; an incoherent per-morphism decoder is no longer
  * representable. Runtime-string signatures participate through [[dspy4s.programs.DynamicSignature]], whose parse mints
  * fresh codec-equipped types.
  *
  * The category laws use the complete prediction, parameters, and lifecycle as their observation. Sequential raw
  * evidence has an associative accumulator with the empty envelope as identity, so structural identity preserves the
  * same public result observed by [[ProgramRunner]]. Equality is observational rather than structural equality of this
  * existential package.
  */
sealed trait Program[I, O]:
  type Rep <: Module[I, O]
  type ParameterArity <: Int
  val program: Rep
  val optimizableParameters: OptimizableTraversal.WithArity[Rep, ParameterArity]

  /** Run the packaged program through the module's wrapped `apply`. */
  def apply(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program(call)

object Program:

  /** A packaged program whose parameter arity remains visible to the type system. */
  type WithArity[I, O, N <: Int] = Program[I, O] { type ParameterArity = N }

  private def packageWith[I, O, F <: Module[I, O]](
      f: F
  )(using ev: OptimizableTraversal[F]): Program[I, O] { type Rep = F; type ParameterArity = ev.Arity } =
    new Program[I, O]:
      type Rep            = F
      type ParameterArity = ev.Arity
      val program: F                                                               = f
      val optimizableParameters: OptimizableTraversal.WithArity[F, ParameterArity] = ev

  /** Package a program at a codec-equipped object. The `RecordCodec[I]` requirement is the categorical gate: every
    * object reachable through `of` / `id` has a canonical decoder, which makes the unit laws unconditional.
    */
  def of[I, O, F <: Module[I, O]](f: F)(using
      ev: OptimizableTraversal[F],
      @annotation.unused codec: RecordCodec[I]
  ): Program[I, O] { type Rep = F; type ParameterArity = ev.Arity } =
    packageWith(f)

  extension [I, O](program: Program[I, O])
    /** Read all writable parameters while retaining this packaged program's hidden arity. */
    def sizedParams: SizedVector[OptimizableParameters, program.ParameterArity] =
      program.optimizableParameters.readSized(program.program)

    /** Reparameterize with a vector whose type already proves it has the right number of leaves. */
    def reparamSized(
        parameters: SizedVector[OptimizableParameters, program.ParameterArity]
    ): WithArity[I, O, program.ParameterArity] =
      packageWith(program.optimizableParameters.replaceSized(program.program, parameters))(using
        program.optimizableParameters
      )

  /** The additive lawful lens requested by the parameter algebra: existing `params` / `reparam` methods remain the
    * compatibility surface, while this instance makes their exact total domain explicit.
    */
  given parameterLens[I, O, N <: Int]: Lens[WithArity[I, O, N], SizedVector[OptimizableParameters, N]] with
    def get(program: WithArity[I, O, N]): SizedVector[OptimizableParameters, N] = program.sizedParams

    def set(
        program: WithArity[I, O, N],
        parameters: SizedVector[OptimizableParameters, N]
    ): WithArity[I, O, N] = program.reparamSized(parameters)

  /** Optimizer traversal for a package whose parameter arity has not been erased. */
  given programOptimizableTraversal[I, O, N <: Int]: OptimizableTraversal.Of[WithArity[I, O, N], N] with
    def arity(program: WithArity[I, O, N]): Int =
      program.optimizableParameters.arity(program.program)

    def inspect(program: WithArity[I, O, N]): Vector[OptimizableView] =
      program.optimizableParameters.inspect(program.program)

    def replace(program: WithArity[I, O, N], updates: Vector[OptimizableParameters]): WithArity[I, O, N] =
      Program.packageWith(program.optimizableParameters.replace(program.program, updates))(using
        program.optimizableParameters
      )

    override def inspectNamed(program: WithArity[I, O, N]): Vector[(String, OptimizableView)] =
      program.optimizableParameters.inspectNamed(program.program)

  /** Record-boundary execution resolves the sealed canonical codec for the domain object. */
  given programRunner[I, O](using codec: RecordCodec[I]): ProgramRunner[Program[I, O]] with
    def run(program: Program[I, O], call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, dspy4s.core.data.RawPrediction] =
      codec.decode(call.input).flatMap(input => program(call.mapInput(_ => input)).map(_.raw))

  /** Preserve the refined package type when an optimizer requires both traversal and record-running evidence. */
  given programWithArityRunner[I, O, N <: Int](using codec: RecordCodec[I]): ProgramRunner[WithArity[I, O, N]] with
    def run(program: WithArity[I, O, N], call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, dspy4s.core.data.RawPrediction] =
      codec.decode(call.input).flatMap(input => program(call.mapInput(_ => input)).map(_.raw))

  /** The parameterized category over packaged programs.
    *
    * Identity exists at codec-equipped objects; composition and fan-out retain the fixed parameter arities of their
    * children. Decoder equality between `id` and any program is definitional on the sealed canonical object codec, so
    * the unit laws hold with no morphism-specific coherence condition.
    */
  given parameterizedCategoryProgram: ParameterizedCategory[RecordCodec, Program] with
    def id[A](using @annotation.unused codec: RecordCodec[A]): WithArity[A, A, 0] =
      Program.packageWith(Compose.id[A])

    def fanout[I, A, B](
        f: Program[I, A],
        g: Program[I, B]
    ): WithArity[I, (A, B), f.ParameterArity + g.ParameterArity] =
      Program.packageWith(f.program &&& g.program)(using
        Both.bothOptimizableTraversal[I, A, B, f.Rep, g.Rep, f.ParameterArity, g.ParameterArity](using
          f.optimizableParameters,
          g.optimizableParameters
        )
      )

    extension [A, B](f: Program[A, B])
      infix def >>>[C](g: Program[B, C]): WithArity[A, C, f.ParameterArity + g.ParameterArity] =
        Program.packageWith(f.program.andThen(g.program))(using
          AndThen.andThenOptimizableTraversal[A, B, C, f.Rep, g.Rep, f.ParameterArity, g.ParameterArity](using
            f.optimizableParameters,
            g.optimizableParameters
          )
        )

      def params: Vector[OptimizableParameters] = f.optimizableParameters.read(f.program)

      def reparam(ps: Vector[OptimizableParameters]): Program[A, B] =
        Program.packageWith(f.optimizableParameters.replace(f.program, ps))(using f.optimizableParameters)

/** Parameter projection as a functor from packaged programs into the delooped parameter monoid. */
object ReadFunctor extends CategoryFunctor[RecordCodec, Program, AnyObject, ParamsHom]:
  def map[A, B](f: Program[A, B]): ParamsHom[A, B] = f.params
