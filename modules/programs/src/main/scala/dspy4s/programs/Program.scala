package dspy4s.programs

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.CodeResult
import dspy4s.core.data.Example
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.programs.contracts.{Prediction, ToolCallRequest, ToolCallResult}
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
type Program[I, O] = ProgramWithEnv[I, O, PredictionBackend]

final class ProgramWithEnv[I, O, R] private[programs] (
    private[programs] val root: Program.Node[I, O],
    val parameters            : ParameterStore
):

  infix def >>>[B, R2](next: ProgramWithEnv[O, B, R2]): ProgramWithEnv[I, B, R & R2] =
    Program.apply[I, B, R & R2](Program.Node.AndThen(root, next.root), parameters.merge(next.parameters))

  infix def &&&[B, R2](other: ProgramWithEnv[I, B, R2]): ProgramWithEnv[I, (O, B), R & R2] =
    Program.apply[I, (O, B), R & R2](Program.Node.Fanout(root, other.root), parameters.merge(other.parameters))

  infix def ***[J, B, R2](other: ProgramWithEnv[J, B, R2]): ProgramWithEnv[(I, J), (O, B), R & R2] =
    Program.apply[(I, J), (O, B), R & R2](Program.Node.Split(root, other.root), parameters.merge(other.parameters))

  /** Select one of two visible branches from a typed `Either` input. */
  infix def |||[J, R2](other: ProgramWithEnv[J, O, R2]): ProgramWithEnv[Either[I, J], O, R & R2] =
    Program.apply[Either[I, J], O, R & R2](Program.Node.Choice(root, other.root), parameters.merge(other.parameters))

  def map[B](f: O => B): ProgramWithEnv[I, B, R] =
    Program.apply[I, B, R](Program.Node.MapOutput(root, f), parameters)

  def contramap[J](f: J => I): ProgramWithEnv[J, O, R] =
    Program.apply[J, O, R](Program.Node.ContramapInput(root, f), parameters)

  def local(f: RunOptions => RunOptions): ProgramWithEnv[I, O, R] =
    Program.apply[I, O, R](Program.Node.Local(root, f), parameters)

  /** Change run options from the current typed input. */
  def localWithInput(f: (I, RunOptions) => RunOptions): ProgramWithEnv[I, O, R] =
    Program.apply[I, O, R](Program.Node.LocalWithInput(root, f), parameters)

  /** Derive run-local parameter values from one visible program without changing the static program value. */
  def localParametersWith[C, R2](configurator: ProgramWithEnv[I, C, R2])(
      update: (ParameterStore, C) => Either[DspyError, ParameterStore]
  ): ProgramWithEnv[I, O, R & R2] =
    Program.apply[I, O, R & R2](
      Program.Node.LocalParameters(root, configurator.root, update),
      parameters.merge(configurator.parameters)
    )

  def recoverWith[R2](fallback: ProgramWithEnv[I, O, R2])(when: DspyError => Boolean)
      : ProgramWithEnv[I, O, R & R2] =
    Program.apply[I, O, R & R2](Program.Node.Recover(root, fallback.root, when), parameters.merge(fallback.parameters))

  /** Convert a program failure into typed output data. */
  def attempt: ProgramWithEnv[I, Either[DspyError, O], R] =
    Program.apply[I, Either[DspyError, O], R](Program.Node.Attempt(root), parameters)

  /** Make the typed output and its raw evidence available to later program structure. */
  def withEvidence: ProgramWithEnv[I, Prediction[O], R] =
    Program.apply[I, Prediction[O], R](Program.Node.WithEvidence(root), parameters)

  /** Add an explicit observable scope. Structural nodes are otherwise invisible to execution events. */
  def observed(name: String, encodeInput: I => DynamicValue.Record): ProgramWithEnv[I, O, R] =
    Program.apply[I, O, R](Program.Node.Observe(name, encodeInput, root), parameters)

  def updatedParameter(id: ParameterId, value: OptimizableParameters): Either[DspyError, ProgramWithEnv[I, O, R]] =
    parameters.updated(id, value).map(store => Program.apply[I, O, R](root, store))

  def updatedParameter(ref: ParameterRef, value: OptimizableParameters): Either[DspyError, ProgramWithEnv[I, O, R]] =
    updatedParameter(ref.id, value)

  def modifyParameter(id: ParameterId)(
      update: OptimizableParameters => OptimizableParameters
  ): Either[DspyError, ProgramWithEnv[I, O, R]] =
    parameters
      .get(id)
      .toRight(dspy4s.core.contracts.NotFoundError("program_parameter", s"Unknown parameter id '${id.value}'"))
      .flatMap(value => updatedParameter(id, update(value)))

  def modifyParameter(ref: ParameterRef)(
      update: OptimizableParameters => OptimizableParameters
  ): Either[DspyError, ProgramWithEnv[I, O, R]] =
    modifyParameter(ref.id)(update)

  def replaceParameters(values: Map[ParameterId, OptimizableParameters])
      : Either[DspyError, ProgramWithEnv[I, O, R]] =
    parameters.replace(values).map(store => Program.apply[I, O, R](root, store))

  def loadParameterState(state: DynamicValue.Record): Either[DspyError, ProgramWithEnv[I, O, R]] =
    parameters.loadState(state).map(store => Program.apply[I, O, R](root, store))

  /** Add the explicit decoder needed by record-based evaluation and optimization. */
  def fromRecords(inputShape: Shape[I]): RecordProgramWithEnv[I, O, R] = RecordProgramWithEnv(this, inputShape)

object Program:

  /** Start a namespace for stable prediction declarations. */
  def namespace(value: String): ParameterNamespace = ParameterNamespace(value)

  def identity[A]: ProgramWithEnv[A, A, Any] = apply[A, A, Any](Node.Identity[A](), ParameterStore.empty)

  def lift[I, O](f: I => O): ProgramWithEnv[I, O, Any] = apply[I, O, Any](Node.Lift(f), ParameterStore.empty)

  def liftEither[I, O](f: I => Either[DspyError, O]): ProgramWithEnv[I, O, Any] =
    apply[I, O, Any](Node.LiftEither(f), ParameterStore.empty)

  /** Declare generated-code execution as an explicit service requirement. */
  val executeCode: ProgramWithEnv[String, CodeExecutionResult, CodeExecutionBackend] =
    apply(Node.ExecuteCode(), ParameterStore.empty)

  /** Declare host-tool invocation as an explicit service requirement. */
  val invokeTool: ProgramWithEnv[ToolCallRequest, ToolCallResult, ToolBackend] =
    apply(Node.InvokeTool(), ParameterStore.empty)

  /** Declare one step in a persistent REPL session as an explicit service requirement. */
  val executeRepl: ProgramWithEnv[ReplExecutionRequest, CodeResult, ReplExecutionBackend] =
    apply(Node.ExecuteRepl(), ParameterStore.empty)

  /** Repeat one visible step program until it returns `Done` or the bound is exhausted. */
  def iterate[S, O, R](step: ProgramWithEnv[S, LoopDecision[S, O], R], maxSteps: Int)
      : ProgramWithEnv[S, O, R] =
    require(maxSteps > 0, "Program.iterate maxSteps must be positive")
    apply[S, O, R](Node.Iterate(step.root, maxSteps), step.parameters)

  /** Run homogeneous visible members from left to right on one shared input. */
  def collectAll[I, O, R](members: Vector[ProgramWithEnv[I, O, R]]): ProgramWithEnv[I, Vector[O], R] =
    val parameters = members.foldLeft(ParameterStore.empty)((store, member) => store.merge(member.parameters))
    apply[I, Vector[O], R](Node.CollectAll(members.map(_.root)), parameters)

  /** Run homogeneous visible members concurrently and retain member order in the result. */
  def collectAllPar[I, O, R](
      members    : Vector[ProgramWithEnv[I, O, R]],
      parallelism: Int
  ): ProgramWithEnv[I, Vector[O], R] =
    require(parallelism > 0, "Program.collectAllPar parallelism must be positive")
    val parameters = members.foldLeft(ParameterStore.empty)((store, member) => store.merge(member.parameters))
    apply[I, Vector[O], R](Node.CollectAllPar(members.map(_.root), parallelism), parameters)

  /** Use a prediction carried as typed output as the final output and raw evidence. */
  def fromEvidence[I, O, R](inner: ProgramWithEnv[I, Prediction[O], R]): ProgramWithEnv[I, O, R] =
    apply[I, O, R](Node.FromEvidence(inner.root), inner.parameters)

  /** Run a visible program several times and retain its highest-scoring successful result. */
  def bestOfN[I, O, R](
      inner    : ProgramWithEnv[I, O, R],
      attempts : Int,
      threshold: Option[Double] = None,
      failAfter: Int            = 1
  )(
      reward: (I, Prediction[O]) => Either[DspyError, Double]
  ): ProgramWithEnv[I, O, R] =
    require(attempts > 0, "Program.bestOfN attempts must be positive")
    require(failAfter > 0, "Program.bestOfN failAfter must be positive")
    apply[I, O, R](Node.BestOfN(inner.root, attempts, threshold, failAfter, reward), inner.parameters)

  /** Repeat one visible program. Acceptance and feedback can inspect its complete prediction evidence. */
  def repeatUntil[I, O, R](
      inner   : ProgramWithEnv[I, O, R],
      maxSteps: Int
  )(
      accept   : (I, Prediction[O]) => Either[DspyError, Boolean],
      nextInput: (I, Prediction[O]) => Either[DspyError, I]
  ): ProgramWithEnv[I, O, R] =
    val attempt = identity[I] &&& inner.withEvidence
    val decide  = liftEither[(I, Prediction[O]), LoopDecision[I, O]] { case (input, prediction) =>
      accept(input, prediction).flatMap { accepted =>
        if accepted then Right(LoopDecision.Done(prediction.output))
        else nextInput(input, prediction).map(LoopDecision.Continue(_))
      }
    }
    iterate(attempt >>> decide, maxSteps)

  /** Declare one typed LM prediction with an anonymous optimizer slot.
    *
    * Anonymous slots receive deterministic ordinal IDs in declaration order. Reuse the returned program value to share
    * one slot. Use [[namespace]] and [[ParameterNamespace.declare]] when state needs a stable semantic name.
    */
  def predict[I, O](
      signature: Signature[I, O],
      demos    : Vector[Example]     = Vector.empty,
      config   : DynamicValue.Record = DynamicValue.Record.empty,
      name     : String              = "predict",
      tools    : Vector[ToolSpec]    = Vector.empty
  ): ProgramWithEnv[I, O, PredictionBackend] =
    predictWithKey(ParameterKey.anonymous(), signature, demos, config, name, tools)

  /** Declare one typed LM prediction with an explicit stable optimizer slot. */
  def predictStable[I, O](
      id       : ParameterId,
      signature: Signature[I, O],
      demos    : Vector[Example]     = Vector.empty,
      config   : DynamicValue.Record = DynamicValue.Record.empty,
      name     : String              = "predict",
      tools    : Vector[ToolSpec]    = Vector.empty
  ): ProgramWithEnv[I, O, PredictionBackend] =
    predictWithKey(ParameterKey.stable(id), signature, demos, config, name, tools)

  private def predictWithKey[I, O](
      key      : ParameterKey,
      signature: Signature[I, O],
      demos    : Vector[Example],
      config   : DynamicValue.Record,
      name     : String,
      tools    : Vector[ToolSpec]
  ): ProgramWithEnv[I, O, PredictionBackend] =
    val staticSignature = signature.withInstructions(None)
    val metadata        = OptimizableMetadata.from(staticSignature.layout, name)
    val parameters      = OptimizableParameters(signature.instructions, demos, config)
    apply[I, O, PredictionBackend](
      Node.Predict(PredictSpec(key, staticSignature, name, tools)),
      ParameterStore.single(key, metadata, parameters)
    )

  private[programs] def apply[I, O, R](node: Node[I, O], parameters: ParameterStore): ProgramWithEnv[I, O, R] =
    new ProgramWithEnv(node, parameters)

  private[programs] final case class PredictSpec[I, O](
      parameterKey: ParameterKey,
      signature   : Signature[I, O],
      name        : String,
      tools       : Vector[ToolSpec]
  )

  private[programs] sealed trait Node[I, O]

  private[programs] object Node:
    final case class Identity[A]()                                                    extends Node[A, A]
    final case class Lift[I, O](run: I => O)                                          extends Node[I, O]
    final case class LiftEither[I, O](run: I => Either[DspyError, O])                 extends Node[I, O]
    final case class Predict[I, O](spec: PredictSpec[I, O])                           extends Node[I, O]
    final case class ExecuteCode()                                                    extends Node[String, CodeExecutionResult]
    final case class InvokeTool()                                                     extends Node[ToolCallRequest, ToolCallResult]
    final case class ExecuteRepl()                                                    extends Node[ReplExecutionRequest, CodeResult]
    final case class AndThen[I, A, O](first: Node[I, A], second: Node[A, O])          extends Node[I, O]
    final case class Fanout[I, A, B](left: Node[I, A], right: Node[I, B])             extends Node[I, (A, B)]
    final case class Split[I, J, A, B](left: Node[I, A], right: Node[J, B])           extends Node[(I, J), (A, B)]
    final case class Choice[I, J, O](left: Node[I, O], right: Node[J, O])             extends Node[Either[I, J], O]
    final case class MapOutput[I, A, B](inner: Node[I, A], map: A => B)               extends Node[I, B]
    final case class ContramapInput[J, I, O](inner: Node[I, O], contramap: J => I)    extends Node[J, O]
    final case class Local[I, O](inner: Node[I, O], update: RunOptions => RunOptions) extends Node[I, O]
    final case class LocalWithInput[I, O](inner: Node[I, O], update: (I, RunOptions) => RunOptions)
        extends Node[I, O]
    final case class LocalParameters[I, O, C](
        inner       : Node[I, O],
        configurator: Node[I, C],
        update      : (ParameterStore, C) => Either[DspyError, ParameterStore]
    ) extends Node[I, O]
    final case class Recover[I, O](
        primary : Node[I, O],
        fallback: Node[I, O],
        when    : DspyError => Boolean
    ) extends Node[I, O]
    final case class Attempt[I, O](inner: Node[I, O])                  extends Node[I, Either[DspyError, O]]
    final case class WithEvidence[I, O](inner: Node[I, O])             extends Node[I, Prediction[O]]
    final case class FromEvidence[I, O](inner: Node[I, Prediction[O]]) extends Node[I, O]
    final case class CollectAll[I, O](members: Vector[Node[I, O]])     extends Node[I, Vector[O]]
    final case class CollectAllPar[I, O](members: Vector[Node[I, O]], parallelism: Int)
        extends Node[I, Vector[O]]
    final case class Iterate[S, O](step: Node[S, LoopDecision[S, O]], maxSteps: Int) extends Node[S, O]
    final case class BestOfN[I, O](
        inner    : Node[I, O],
        attempts : Int,
        threshold: Option[Double],
        failAfter: Int,
        reward   : (I, Prediction[O]) => Either[DspyError, Double]
    ) extends Node[I, O]
    final case class Observe[I, O](name: String, encodeInput: I => DynamicValue.Record, inner: Node[I, O])
        extends Node[I, O]
