package dspy4s.programs

import dspy4s.core.contracts.{
  DspyError, DynamicValues, Example, FieldRole, FieldSpec, RuntimeContext,
  SignatureLayout, TypeRef
}
import dspy4s.core.contracts.SignatureOps.*
import dspy4s.programs.contracts.{Module, ProgramRuntime, TypedCall}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.{OutputAugmentation, Prediction, Shape, Signature}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

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
  * `DynamicValue.Record` from `Signature.fromStringDynamic`, which carries no static fields.
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

    // Reuse the base output's JSON schema so the structured (nested) field shapes still reach the adapter
    // (and thus the LM). The prepended `reasoning` field is a plain String already covered by the adapter's
    // field markers, so omitting it from the schema is harmless; the schema's only job here is to convey the
    // nested shapes of the structured output fields.
    override lazy val jsonSchemaString: Option[String] = signature.outputShape.jsonSchemaString

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
      // `prepend` builds the augmented named tuple via `NamedTuple.build` for products (named tuples and
      // case classes); only the fieldless `DynamicValue.Record` output yields a structured error.
      OutputAugmentation.decodePrepended(raw, signature.outputShape, "reasoning", "ChainOfThought", signature.name)

object ChainOfThought:

  // dspy 3.2.1 alignment (item P3): no hardcoded prefix. `FieldSpec.normalize`
  // derives the marker from the field name in the augment path -- inferPrefix
  // yields "Reasoning:", identical to the old literal (a true no-op on the wire).
  private[programs] val reasoningField: FieldSpec = FieldSpec.normalize(FieldSpec(
    name        = "reasoning",
    role        = FieldRole.Output,
    typeRef     = TypeRef.string,
    description = Some("${reasoning}")
  ))

  private[dspy4s] def augmentLayout(layout: SignatureLayout): Either[DspyError, SignatureLayout] =
    Right(layout.prependOutput(reasoningField))

  /** The augmented output type — `reasoning: String` prepended to `O`'s named-tuple view, idempotently. A thin
    * alias over the shared [[dspy4s.typed.OutputAugmentation.WithField]]; see there for the full semantics. */
  type WithReasoning[O] = OutputAugmentation.WithField[O, "reasoning", String]
