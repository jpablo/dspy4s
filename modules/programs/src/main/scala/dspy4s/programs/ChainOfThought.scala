package dspy4s.programs

import dspy4s.core.contracts.{
  DspyError, DynamicValues, Example, FieldRole, FieldSpec, NotFoundError, RuntimeContext,
  SignatureLayout, TypeRef, ValidationError
}
import dspy4s.programs.contracts.{Module, ProgramRuntime, TypedCall}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{Prediction, Shape, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}
import scala.NamedTuple

/** ChainOfThought, defined as a small signature transformation on top of
  * [[Predict]]. Wraps a `Signature[I, O]` whose output is a named tuple
  * (typically produced by `Signature.of[T <: Spec]` or
  * `Signature.fromType[F]("...")`) and produces a `Prediction[Out]` whose
  * output named tuple has `reasoning: String` prepended to O's fields.
  *
  * Inputs flow through `Predict` unchanged. CoT contributes only the augmented
  * signature: a leading `reasoning` output field in the runtime layout and an
  * output `Shape` that decodes the underlying prediction as
  * `(reasoning = ..., <base outputs...>)`.
  *
  * **Scope**: named-tuple outputs only. Case-class outputs from
  * `Signature.derived[I, O <: Product]` would need an augmented
  * synthesized case class at the call site; for those, define the reasoning
  * field in the output case class and use [[Predict]] directly.
  *
  * Like Python's `ChainOfThought` (which is `self.predict = Predict(extended_signature)` and a one-line
  * `forward` that calls it), this is a `Module` that *contains* an inner [[Predict]] and delegates to it. So a
  * call emits a `chain_of_thought` module event wrapping the inner `predict` event, mirroring Python's
  * `CoT.__call__` → `forward` → `self.predict(**kwargs)` nesting. Because the typed decode now lives inside the
  * inner `Predict`'s wrapped `forward`, a decode failure returns `Left` *and* records no trace entry — the
  * earlier "trace says success while the call returns `Left`" divergence is gone.
  */
final case class ChainOfThought[I, O](
    signature: Signature[I, O],
    demos: Vector[Example] = Vector.empty,
    runtime: ProgramRuntime = new SettingsProgramRuntime {},
    name: Option[String] = None
) extends Module[TypedCall[I], Prediction[ChainOfThought.WithReasoning[O]]]:

  /** The augmented output type: `reasoning: String` prepended to O's
    * named-tuple fields. Only reduces when O is a named tuple; case-class
    * outputs leave this match type stuck. */
  type Out = ChainOfThought.WithReasoning[O]

  override val moduleName: String = name.getOrElse("chain_of_thought")

  override protected def callInputs(call: TypedCall[I]): DynamicValue.Record =
    signature.inputShape.encode(call.input)

  override protected def callTraceEnabled(call: TypedCall[I]): Boolean = call.traceEnabled

  override protected def tracePayload(prediction: Prediction[Out]): DynamicValue.Record =
    prediction.raw.values

  override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    predictor.flatMap(_.apply(call))

  /** Convenience entry mirroring the prior caller signature; builds a [[TypedCall]] and dispatches through the
    * wrapped [[apply]]. */
  def apply(
      input: I,
      config: DynamicValue.Record = DynamicValue.Record.empty,
      traceEnabled: Boolean = true
  )(using RuntimeContext): Either[DspyError, Prediction[Out]] =
    apply(TypedCall(input, config, traceEnabled))

  /** The inner predictor, built once (memoized) — the analog of Python's `self.predict =
    * Predict(extended_signature)`. Left unnamed so it surfaces as a nested `predict` event under this
    * program's `chain_of_thought` event. */
  private lazy val predictor: Either[DspyError, Predict[I, Out]] =
    augmentedSignature.map { sig =>
      Predict(sig, demos, None, runtime)
    }

  private def augmentedSignature: Either[DspyError, Signature[I, Out]] =
    augmentedLayout.map { layout =>
      Signature(
        name        = signature.name,
        layout      = layout,
        inputShape  = signature.inputShape,
        outputShape = augmentedOutputShape
      )
    }

  private def augmentedLayout: Either[DspyError, SignatureLayout] =
    ChainOfThought.augmentLayout(signature.layout)

  private def augmentedOutputShape: Shape[Out] = new Shape[Out]:
    val fieldSpecs: Vector[FieldSpec] =
      ChainOfThought.reasoningField +: signature.outputShape.fieldSpecs

    def encode(value: Out): DynamicValue.Record =
      val product: Product = value match
        case p: Product => p
        case _ =>
          throw new IllegalArgumentException(
            "ChainOfThought output must be a named-tuple value (a Product); got a non-Product. " +
            "This shape is built only from the augmented Signature, which supplies named tuples."
          )
      val values = product.productIterator.toVector
      val entries = fieldSpecs.zip(values).map { (field, raw) =>
        field.name -> DynamicValues.fromAny(raw)
      }
      DynamicValue.Record(Chunk.from(entries))

    def decode(raw: DynamicValue.Record): Either[DspyError, Out] =
      for
        reasoning <- extractReasoning(raw)
        baseOut   <- signature.outputShape.decode(raw)
        augmented <- prependReasoning(reasoning, baseOut)
      yield augmented

  /** Cons `reasoning` onto the base output to materialize the augmented
    * named tuple. Named tuples erase to plain tuples at runtime, so the
    * cons is well-defined only when `baseOut` is a `Tuple` -- which holds
    * for `Signature.of[Spec]` / `Signature.fromType[F]` outputs (backed
    * by `Shape.SchemaTupleShape`) but not for case-class outputs from
    * `Signature.derived[I, O <: Product]` (backed by a Schema-derived
    * `Shape` from `ZioSchemaCodec`, which decodes into a non-tuple case
    * class) or `Map[String, Any]`
    * outputs from `Signature.fromString` (backed by `MapShape`).
    *
    * Surfaces the unsupported-shape case as a `ValidationError` instead
    * of letting an `asInstanceOf` `ClassCastException` escape the
    * `Either` boundary. The `WithReasoning[O]` match type already
    * fails to reduce for these shapes, so anything that hits this
    * branch is misuse of the public constructor. */
  private def prependReasoning(reasoning: String, baseOut: O): Either[DspyError, Out] =
    baseOut match
      case tuple: Tuple =>
        Right((reasoning *: tuple).asInstanceOf[Out])
      case other =>
        Left(ValidationError(
          s"ChainOfThought requires a named-tuple output signature " +
          s"(Signature.of[Spec] or Signature.fromType[F]); got " +
          s"${other.getClass.getSimpleName} from '${signature.name}'. " +
          s"For case-class outputs, include reasoning in the output type and use Predict directly."
        ))

  private def extractReasoning(values: DynamicValue.Record): Either[DspyError, String] =
    DynamicValues.recordGet(values, "reasoning") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(s))) => Right(s)
      case Some(other) =>
        Left(ValidationError(
          s"CoT reasoning field must be a String, got: $other"
        ))
      case None =>
        Left(NotFoundError(
          resource = "prediction_field",
          message  = "Required field 'reasoning' is missing from the ChainOfThought prediction"
        ))

object ChainOfThought:

  private[programs] val reasoningField: FieldSpec = FieldSpec(
    name        = "reasoning",
    role        = FieldRole.Output,
    typeRef     = TypeRef.string,
    description = Some("${reasoning}"),
    prefix      = Some("Reasoning:")
  )

  private[dspy4s] def augmentLayout(layout: SignatureLayout): Either[DspyError, SignatureLayout] =
    if layout.outputFields.exists(_.name == reasoningField.name) then Right(layout)
    else layout.insert(index = 0, field = reasoningField)

  /** Match type prepending `reasoning: String` to a named-tuple output. */
  type WithReasoning[O] = O match
    case NamedTuple.NamedTuple[n, v] =>
      NamedTuple.NamedTuple["reasoning" *: n, String *: v]
