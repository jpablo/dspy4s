package dspy4s.programs

import dspy4s.core.contracts.{
  DspyError, DynamicValues, Example, FieldRole, FieldSpec, NotFoundError, RuntimeContext,
  SignatureLayout, TypeRef, ValidationError
}
import dspy4s.programs.contracts.{Module, ProgramRuntime, TypedCall}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{OutputAugmentation, Prediction, Shape, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** ChainOfThought, defined as a small signature transformation on top of
  * [[Predict]]. Wraps a `Signature[I, O]` and produces a `Prediction[Out]` whose output is the base output with
  * a `reasoning: String` field — **always a named tuple** ([[ChainOfThought.WithReasoning]]).
  *
  * Inputs flow through `Predict` unchanged. CoT contributes only the augmented
  * signature: a leading `reasoning` output field in the runtime layout and an
  * output `Shape` that decodes the underlying prediction as
  * `(reasoning = ..., <base outputs...>)`.
  *
  * **Output type.** `O` is normalized to its named-tuple view (`NamedTuple.From`: identity for named tuples, the
  * field tuple for case classes), then `reasoning` is prepended — *unless* the output already has a `reasoning`
  * field, in which case it is kept as-is (idempotent: never a second `reasoning`). The result is therefore
  * always a named tuple, even when `O` is a case class. A case-class output is **not** echoed back as the same
  * nominal type — Scala can't synthesize "case class plus a field" — so you get the structural named tuple;
  * convert it back yourself if you want the nominal type (`Mirror.fromProduct` for an exact-field match, or a
  * name-based mapper like Chimney to project / drop `reasoning`). The only unsupported output is the
  * `DynamicValue.Record` from `Signature.fromString`, which carries no static fields.
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
)(using prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, ChainOfThought.WithReasoning[O]])
    extends Module[TypedCall[I], Prediction[ChainOfThought.WithReasoning[O]]]:

  /** The augmented output type — always a named tuple. See [[ChainOfThought.WithReasoning]]. */
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
        // `prepend` builds the augmented named tuple via `NamedTuple.build` for products (named tuples and case
        // classes) with no `asInstanceOf`; only the fieldless `DynamicValue.Record` output yields `None`.
        augmented <- prepend.prepend(reasoning, baseOut).toRight(unsupportedOutputShape(baseOut))
      yield augmented

  /** The structured error for an `O` that is neither a named tuple nor a case class — i.e. the
    * `DynamicValue.Record` output of a `Signature.fromString`, which has no static fields to augment. The
    * `WithReasoning[O]` match type also fails to reduce for it. */
  private def unsupportedOutputShape(baseOut: O): DspyError =
    ValidationError(
      s"ChainOfThought requires a product output (named tuple or case class); the string-DSL signature " +
      s"'${signature.name}' has a fieldless DynamicValue.Record output (got ${baseOut.getClass.getSimpleName}). " +
      s"Use a typed signature (Signature.of / Signature.derived / Signature.fromType)."
    )

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

  /** The augmented output type — `reasoning: String` prepended to `O`'s named-tuple view, idempotently. A thin
    * alias over the shared [[dspy4s.typed.OutputAugmentation.WithField]]; see there for the full semantics. */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]
