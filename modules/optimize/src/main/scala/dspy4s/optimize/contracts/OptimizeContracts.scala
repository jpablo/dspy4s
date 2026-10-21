package dspy4s.optimize.contracts

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
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
  * (upstream `_with_compile_callbacks`). That helper also installs the context that `compile` gets, so the given
  * context wins for the whole compile (UPGRADE-3.2.1-to-3.4.0, decision D8).
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
    *
    * The scope and `thunk` run with `context`, the context of the `compile` call, as the installed context
    * ([[RuntimeEnvironment.withInstalled]], decision D8: the given context wins). `thunk` gets the effective context of
    * the call as its `using` value. A call site such as `withCompileCallbacks(this, trainset)(compileInScope(...))`
    * thus gives that context to `compileInScope`, because the parameter of the context function is the innermost given.
    */
  def withCompileCallbacks[A](
      optimizer: AnyRef,
      trainset : Vector[Example],
      valset   : Option[Vector[Example]] = None
  )(thunk: RuntimeContext ?=> Either[DspyError, A])(using context: RuntimeContext): Either[DspyError, A] =
    val inputs = DynamicValues.recordFromEntries(
      Vector("trainset_size" := trainset.size) ++ valset.map(set => "valset_size" := set.size)
    )
    RuntimeEnvironment.withInstalled(context) { effective ?=>
      CallbackDispatcher.withCompile(optimizer, optimizer.getClass.getSimpleName, inputs)(thunk(using effective))
    }
