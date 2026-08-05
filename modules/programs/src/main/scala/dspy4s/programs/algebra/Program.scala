package dspy4s.programs.algebra

import dspy4s.algebra.{AnyObject, Category, Functor, Id, Lens, NatGradedCategory, OrderedFanout}
import dspy4s.core.collections.SizedVector
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.ProgramRunner
import dspy4s.programs.RecordCodec
import dspy4s.programs.compose.AndThen
import dspy4s.programs.compose.Both
import dspy4s.programs.compose.Compose
import dspy4s.programs.compose.andThen
import dspy4s.programs.compose.&&&
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.OptimizableView
import dspy4s.programs.contracts.Prediction
import zio.blocks.schema.DynamicValue

import scala.compiletime.ops.int.+

/** An optimizable module packaged with the exact number of writable parameter leaves.
  *
  * `Rep` hides the concrete module representation while `N` remains visible as the natural-number grade. Construction
  * through [[Program.of]] requires complete optimizable-structure evidence for the representation. Decoding from a
  * dynamic record is a separate boundary capability supplied by [[ProgramRunner]].
  */
sealed trait Program[I, O, N <: Int]:
  type Rep <: Module[I, O]

  val program: Rep
  val optimizableParameters: OptimizableStructure.WithArity[Rep, N]

  /** Run the packaged program through the module's wrapped `apply`. */
  def apply(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
    program(call)

/** A packaged program whose parameter grade is intentionally hidden. */
type SomeProgram[I, O] = Program[I, O, ?]

object Program:

  private def packageModule[I, O, M <: Module[I, O]](
      module: M
  )(using structure: OptimizableStructure[M]): Program[I, O, structure.Arity] { type Rep = M } =
    new Program[I, O, structure.Arity]:
      type Rep = M
      val program               = module
      val optimizableParameters = structure

  /** Package a module while retaining its exact parameter grade. */
  def of[I, O, M <: Module[I, O]](module: M)(using
      structure: OptimizableStructure[M]
  ): Program[I, O, structure.Arity] { type Rep = M } =
    packageModule(module)

  /** Naturals grade program composition: identity contributes zero leaves and composition adds leaf counts. */
  given gradedProgramCategory: NatGradedCategory[AnyObject, Program] with
    def id[A: AnyObject]: Program[A, A, 0] =
      Program.packageModule(Compose.id[A])

    def compose[A, B, C, N <: Int, M <: Int](
        f: Program[A, B, N],
        g: Program[B, C, M]
    ): Program[A, C, N + M] =
      Program.packageModule(f.program.andThen(g.program))(using
        AndThen.andThenOptimizableStructure[A, B, C, f.Rep, g.Rep, N, M](using
          f.optimizableParameters,
          g.optimizableParameters
        )
      )

  /** Shared-input pairing remains an ordered effectful operation rather than categorical product structure. */
  given programFanout: OrderedFanout[Program] with
    def fanout[I, A, B, N <: Int, M <: Int](
        f: Program[I, A, N],
        g: Program[I, B, M]
    ): Program[I, (A, B), N + M] =
      Program.packageModule(f.program &&& g.program)(using
        Both.bothOptimizableStructure[I, A, B, f.Rep, g.Rep, N, M](using
          f.optimizableParameters,
          g.optimizableParameters
        )
      )

  /** The complete parameter vector is a lawful lens whose size is the program's grade. */
  given programParameterization: Parameterization[AnyObject, Program] with
    val category: NatGradedCategory[AnyObject, Program] = gradedProgramCategory

    def read[A, B, N <: Int](f: Program[A, B, N]): SizedVector[OptimizableParameters, N] =
      f.optimizableParameters.readSized(f.program)

    def replace[A, B, N <: Int](
        f         : Program[A, B, N],
        parameters: SizedVector[OptimizableParameters, N]
    ): Program[A, B, N] =
      Program.packageModule(f.optimizableParameters.replaceSized(f.program, parameters))(using f.optimizableParameters)

    def replaceUnsized[A, B, N <: Int](
        f         : Program[A, B, N],
        parameters: Vector[OptimizableParameters]
    ): Program[A, B, N] =
      Program.packageModule(f.optimizableParameters.replace(f.program, parameters))(using f.optimizableParameters)

  /** The parameterization supplies the canonical fixed-grade lens. */
  given programParameterLens[I, O, N <: Int]
      : Lens[Program[I, O, N], SizedVector[OptimizableParameters, N]] =
    programParameterization.parameterLens[I, O, N]

  /** Optimizable structure delegates to the packaged representation while preserving `N`. */
  given programOptimizableStructure[I, O, N <: Int]: OptimizableStructure.Of[Program[I, O, N], N] with
    def arity(program: Program[I, O, N]): Int =
      program.optimizableParameters.arity(program.program)

    def inspect(program: Program[I, O, N]): Vector[OptimizableView] =
      program.optimizableParameters.inspect(program.program)

    def replace(
        program: Program[I, O, N],
        updates: Vector[OptimizableParameters]
    ): Program[I, O, N] =
      programParameterization.replaceUnsized(program, updates)

    override def inspectNamed(program: Program[I, O, N]): Vector[(String, OptimizableView)] =
      program.optimizableParameters.inspectNamed(program.program)

  /** Record-boundary execution for a program whose grade is known. */
  given programRunner[I, O, N <: Int](using codec: RecordCodec[I]): ProgramRunner[Program[I, O, N]] with
    def run(program: Program[I, O, N], call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, dspy4s.core.data.RawPrediction] =
      codec.decode(call.input).flatMap(input => program(call.mapInput(_ => input)).map(_.raw))

  /** Record-boundary execution does not require the parameter grade. */
  given someProgramRunner[I, O](using codec: RecordCodec[I]): ProgramRunner[SomeProgram[I, O]] with
    def run(program: SomeProgram[I, O], call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, dspy4s.core.data.RawPrediction] =
      codec.decode(call.input).flatMap(input => program(call.mapInput(_ => input)).map(_.raw))

  /** The ordinary category obtained by existentially forgetting program grades.
    *
    * This value is explicit rather than a `given`, so exact `Program[I, O, N]` composition always selects the graded
    * operation and retains `N + M`.
    */
  val erasedCategory: Category[AnyObject, SomeProgram] = new Category[AnyObject, SomeProgram]:
    def id[A: AnyObject]: SomeProgram[A, A] = gradedProgramCategory.id[A]

    extension [A, B](f: SomeProgram[A, B])
      infix def >>>[C](g: SomeProgram[B, C]): SomeProgram[A, C] =
        gradedProgramCategory.compose(f, g)

/** Optimizer-view projection from arity-erased programs into the delooped ordered view monoid. */
object InspectFunctor
    extends Functor[Id, AnyObject, SomeProgram, AnyObject, ViewsHom](using
      Program.erasedCategory,
      viewsDeloop
    ):
  def mapObject[A](using AnyObject[A]): AnyObject[A]  = summon
  def map[A, B](f: SomeProgram[A, B]): ViewsHom[A, B] =
    f.optimizableParameters.inspect(f.program)

/** Parameter projection from arity-erased programs into the delooped ordered parameter monoid. */
object ReadFunctor
    extends Functor[Id, AnyObject, SomeProgram, AnyObject, ParamsHom](using
      Program.erasedCategory,
      paramsDeloop
    ):
  def mapObject[A](using AnyObject[A]): AnyObject[A]   = summon
  def map[A, B](f: SomeProgram[A, B]): ParamsHom[A, B] =
    ForgetMetadataFunctor.map(InspectFunctor.map(f))
