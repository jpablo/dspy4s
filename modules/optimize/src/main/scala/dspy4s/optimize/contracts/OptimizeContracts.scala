package dspy4s.optimize.contracts

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.evaluate.contracts.EvaluationResult

final case class CandidateProgram[P](
    program   : P,
    score     : Double,
    evaluation: Option[EvaluationResult] = None,
    metadata  : Map[String, Any]         = Map.empty
)

final case class OptimizationReport[P](
    bestProgram: P,
    candidates : Vector[CandidateProgram[P]],
    metadata   : Map[String, Any] = Map.empty
)

/** An optimizer that compiles a student program (upstream `dspy.teleprompt.Teleprompter`).
  *
  * Each implementation of [[compile]] runs its work in a compile scope with [[Teleprompter.withCompileCallbacks]]
  * (upstream `_with_compile_callbacks`).
  */
trait Teleprompter[P]:
  def name: String

  def compile(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]               = None,
      valset  : Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]]

object Teleprompter:

  /** Run one `compile` call of `optimizer` in a compile scope (upstream `_with_compile_callbacks`). The `optimizerName`
    * is the simple class name of `optimizer`. The inputs are `trainset_size`, and `valset_size` when the call has a
    * validation set. A `compile` of the same `optimizer` instance inside the scope opens no second scope.
    */
  def withCompileCallbacks[A](
      optimizer: AnyRef,
      trainset : Vector[Example],
      valset   : Option[Vector[Example]] = None
  )(thunk: => Either[DspyError, A]): Either[DspyError, A] =
    val inputs = DynamicValues.recordFromEntries(
      Vector("trainset_size" := trainset.size) ++ valset.map(set => "valset_size" := set.size)
    )
    CallbackDispatcher.withCompile(optimizer, optimizer.getClass.getSimpleName, inputs)(thunk)
