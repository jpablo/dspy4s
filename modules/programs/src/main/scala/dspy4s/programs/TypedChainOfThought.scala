package dspy4s.programs

import dspy4s.core.contracts.{
  DspyError, Example, NotFoundError, RuntimeContext, ValidationError
}
import dspy4s.programs.contracts.{ProgramCall, ProgramRuntime}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{TypedPrediction, TypedSignature}
import scala.NamedTuple

/** Typed counterpart to `DynamicChainOfThought`. Wraps a `TypedSignature[I, O]`
  * whose output is a named tuple (typically produced by
  * `TypedSignature.of[T <: Spec]` or `TypedSignature.fromType[F]("...")`)
  * and produces a `TypedPrediction[Out]` whose output named tuple has
  * `reasoning: String` prepended to O's fields.
  *
  * Inputs flow through `signature.inputShape.encode` unchanged. The
  * runtime CoT augmentation (inserting the reasoning field, formatting,
  * parsing, callbacks) is delegated to the existing `DynamicChainOfThought`
  * program — this typed wrapper only inserts the encode → decode
  * boundary and prepends the reasoning value to the decoded tuple.
  *
  * **Scope**: named-tuple outputs only. Case-class outputs from
  * `TypedSignature.derived[I, O <: Product]` would need an augmented
  * synthesized case class at the call site; for those, use the untyped
  * `DynamicChainOfThought` directly and read `raw.value("reasoning")`.
  *
  * **Known limitation** (inherited from `TypedPredict`): when the inner
  * `DynamicChainOfThought` succeeds but the typed decode fails, the trace still
  * records a successful module call while `run` returns `Left`. The
  * underlying CoT really did succeed; consolidating the typed boundary's
  * tracing is an open design decision.
  */
final case class TypedChainOfThought[I, O](
    signature: TypedSignature[I, O],
    demos: Vector[Example] = Vector.empty,
    runtime: ProgramRuntime = new SettingsProgramRuntime {}
):

  /** The augmented output type: `reasoning: String` prepended to O's
    * named-tuple fields. Only reduces when O is a named tuple; case-class
    * outputs leave this match type stuck. */
  type Out = TypedChainOfThought.WithReasoning[O]

  def run(
      input: I,
      config: Map[String, Any] = Map.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, TypedPrediction[Out]] =
    val inputMap = signature.inputShape.encode(input)

    // Same defensive missing-input check as TypedPredict.run.
    val requiredInputs = signature.untyped.inputFields.iterator.map(_.name).toSet
    val missing        = requiredInputs.diff(inputMap.keySet)
    if missing.nonEmpty then
      Left(NotFoundError(
        resource = "program_input",
        message  =
          s"Missing required inputs for '${signature.name}': ${missing.toVector.sorted.mkString(", ")}"
      ))
    else
      val program = DynamicChainOfThought(
        baseSignature = signature.untyped,
        demos         = demos,
        runtime       = runtime
      )
      program
        .run(ProgramCall(inputs = inputMap, config = config, traceEnabled = traceEnabled))
        .flatMap { raw =>
          for
            reasoning <- extractReasoning(raw.values)
            baseOut   <- signature.outputShape.decode(raw.values)
          yield
            // Named tuples erase to plain tuples at runtime, so we can
            // cons the reasoning string onto the base tuple to materialize
            // the augmented named tuple.
            val baseTuple = baseOut.asInstanceOf[Tuple]
            val augmented = (reasoning *: baseTuple).asInstanceOf[Out]
            TypedPrediction(augmented, raw)
        }

  private def extractReasoning(values: Map[String, Any]): Either[DspyError, String] =
    values.get("reasoning") match
      case Some(s: String) => Right(s)
      case Some(other) =>
        Left(ValidationError(
          s"CoT reasoning field must be a String, got: $other"
        ))
      case None =>
        Left(NotFoundError(
          resource = "prediction_field",
          message  = "Required field 'reasoning' is missing from the DynamicChainOfThought prediction"
        ))

object TypedChainOfThought:

  /** Match type prepending `reasoning: String` to a named-tuple output. */
  type WithReasoning[O] = O match
    case NamedTuple.NamedTuple[n, v] =>
      NamedTuple.NamedTuple["reasoning" *: n, String *: v]
