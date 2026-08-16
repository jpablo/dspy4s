package dspy4s.programs.plan

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.programs.optimization.{OptimizableMetadata, OptimizableParameters}
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.DynamicValue

/** One transition of a visible bounded program loop. */
enum LoopDecision[S, O]:
  case Continue(state: S)
  case Done(output: O)

/** Pure, typed program syntax.
  *
  * A `Program` describes work but cannot execute itself. [[ProgramRunner]] supplies execution. Structural combinators
  * build an internal typed tree, while optimizer values remain in a separate immutable [[ParameterStore]].
  */
final class Program[I, O] private[plan] (
    private[plan] val root: Program.Node[I, O],
    val parameters             : ParameterStore
):

  infix def >>>[B](next: Program[O, B]): Program[I, B] =
    Program(Program.Node.AndThen(root, next.root), parameters.merge(next.parameters))

  infix def &&&[B](other: Program[I, B]): Program[I, (O, B)] =
    Program(Program.Node.Fanout(root, other.root), parameters.merge(other.parameters))

  infix def ***[J, B](other: Program[J, B]): Program[(I, J), (O, B)] =
    Program(Program.Node.Split(root, other.root), parameters.merge(other.parameters))

  def map[B](f: O => B): Program[I, B] =
    Program(Program.Node.MapOutput(root, f), parameters)

  def contramap[J](f: J => I): Program[J, O] =
    Program(Program.Node.ContramapInput(root, f), parameters)

  def local(f: RunOptions => RunOptions): Program[I, O] =
    Program(Program.Node.Local(root, f), parameters)

  def recoverWith(fallback: Program[I, O])(when: DspyError => Boolean): Program[I, O] =
    Program(Program.Node.Recover(root, fallback.root, when), parameters.merge(fallback.parameters))

  /** Add an explicit observable scope. Structural nodes are otherwise invisible to execution events. */
  def observed(name: String, encodeInput: I => DynamicValue.Record): Program[I, O] =
    Program(Program.Node.Observe(name, encodeInput, root), parameters)

  def updatedParameter(id: ParameterId, value: OptimizableParameters): Either[DspyError, Program[I, O]] =
    parameters.updated(id, value).map(store => Program(root, store))

  def replaceParameters(values: Map[ParameterId, OptimizableParameters]): Either[DspyError, Program[I, O]] =
    parameters.replace(values).map(store => Program(root, store))

  def loadParameterState(state: DynamicValue.Record): Either[DspyError, Program[I, O]] =
    parameters.loadState(state).map(store => Program(root, store))

  /** Add the explicit decoder needed by record-based evaluation and optimization. */
  def fromRecords(inputShape: Shape[I]): RecordProgram[I, O] = RecordProgram(this, inputShape)

object Program:

  def identity[A]: Program[A, A] = Program(Node.Identity[A](), ParameterStore.empty)

  def lift[I, O](f: I => O): Program[I, O] = Program(Node.Lift(f), ParameterStore.empty)

  def liftEither[I, O](f: I => Either[DspyError, O]): Program[I, O] =
    Program(Node.LiftEither(f), ParameterStore.empty)

  /** Repeat one visible step program until it returns `Done` or the bound is exhausted. */
  def iterate[S, O](step: Program[S, LoopDecision[S, O]], maxSteps: Int): Program[S, O] =
    require(maxSteps > 0, "Program.iterate maxSteps must be positive")
    Program(Node.Iterate(step.root, maxSteps), step.parameters)

  /** Declare one typed LM prediction and its stable optimizer slot. */
  def predict[I, O](
      id       : ParameterId,
      signature: Signature[I, O],
      demos    : Vector[Example] = Vector.empty,
      config   : DynamicValue.Record = DynamicValue.Record.empty,
      name     : String = "predict",
      tools    : Vector[ToolSpec] = Vector.empty
  ): Program[I, O] =
    val staticSignature = signature.withInstructions(None)
    val metadata        = OptimizableMetadata.from(staticSignature.layout, name)
    val parameters      = OptimizableParameters(signature.instructions, demos, config)
    Program(
      Node.Predict(PredictSpec(id, staticSignature, name, tools)),
      ParameterStore.single(ParameterBinding(id, metadata, parameters))
    )

  private def apply[I, O](node: Node[I, O], parameters: ParameterStore): Program[I, O] =
    new Program(node, parameters)

  private[plan] final case class PredictSpec[I, O](
      parameterId: ParameterId,
      signature  : Signature[I, O],
      name       : String,
      tools      : Vector[ToolSpec]
  )

  private[plan] sealed trait Node[I, O]

  private[plan] object Node:
    final case class Identity[A]() extends Node[A, A]
    final case class Lift[I, O](run: I => O) extends Node[I, O]
    final case class LiftEither[I, O](run: I => Either[DspyError, O]) extends Node[I, O]
    final case class Predict[I, O](spec: PredictSpec[I, O]) extends Node[I, O]
    final case class AndThen[I, A, O](first: Node[I, A], second: Node[A, O]) extends Node[I, O]
    final case class Fanout[I, A, B](left: Node[I, A], right: Node[I, B]) extends Node[I, (A, B)]
    final case class Split[I, J, A, B](left: Node[I, A], right: Node[J, B]) extends Node[(I, J), (A, B)]
    final case class MapOutput[I, A, B](inner: Node[I, A], map: A => B) extends Node[I, B]
    final case class ContramapInput[J, I, O](inner: Node[I, O], contramap: J => I) extends Node[J, O]
    final case class Local[I, O](inner: Node[I, O], update: RunOptions => RunOptions) extends Node[I, O]
    final case class Recover[I, O](
        primary : Node[I, O],
        fallback: Node[I, O],
        when    : DspyError => Boolean
    ) extends Node[I, O]
    final case class Iterate[S, O](step: Node[S, LoopDecision[S, O]], maxSteps: Int) extends Node[S, O]
    final case class Observe[I, O](name: String, encodeInput: I => DynamicValue.Record, inner: Node[I, O])
        extends Node[I, O]
