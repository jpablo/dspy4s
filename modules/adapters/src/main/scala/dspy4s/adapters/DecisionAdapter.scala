package dspy4s.adapters

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.{JsonDynamic, PythonText, StrictJsonSchema}
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  FieldSpec,
  ParseError,
  RuntimeContext,
  SignatureLayout,
  TypeRef,
  ValidationError,
  updated
}
import dspy4s.core.decisions.{DecisionEvidence, DecisionKind, DecisionPlan}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Experimental. The adapter that asks a generative model for the evidence of the decision outputs of `plan`, and
  * decodes it (upstream `DecisionAdapter` for a model that is not a System One model, in `dspy/adapters/decision.py`).
  * Each other part of the request comes from `inner`, so the same rules hold for the Chat, JSON, and XML adapters.
  *
  * The request (upstream `DecisionAdapter._prepare`):
  *   - Each decision output of `plan` becomes an evidence field ([[DecisionAdapter.evidenceLayout]]): the same name,
  *     the type [[DecisionAdapter.NoulEvidence]], [[DecisionAdapter.ScoreEvidence]], or
  *     [[DecisionAdapter.ChoiceEvidence]], and the description `"\n"` plus the question of the plan as indented JSON
  *     (`json.dumps(question, ensure_ascii=False, indent=2)`). Each other field stays as it is.
  *   - The output JSON schema of the request has the evidence schema ([[DecisionAdapter.evidenceSchema]]) as the
  *     property of each evidence field, so the structure note of the field shows that schema, and the JSON adapter
  *     sends it in its `response_format`.
  *   - The demos do not go as demo turns: evidence must not be invented for a labeled demo. When the invocation has
  *     demos, the instructions get `"\n\nTask examples (labels or evidence):\n"` and the JSON text of the demos
  *     (`json.dumps(examples)`, with ASCII escapes), after the effective instructions of the layout. Each example keeps
  *     only the fields of the layout, in the order of the demo.
  *
  * The parse (upstream `DecisionAdapter._decode`): `inner` parses the reply with the evidence layout. The value of each
  * evidence field (a JSON object, or its JSON text) gives the evidence ([[DecisionPlan.evidence]]), and
  * [[DecisionPlan.decode]] gives the output values, which replace the evidence values. Each other value stays. A
  * malformed evidence value is a `Left`: a [[dspy4s.core.contracts.ParseError]], so that the predictor can try the
  * fallback adapter (upstream: the evidence model of the field rejects it in the parse of the adapter). An error of the
  * decode (for example a distribution with a total of zero) is not a parse error: upstream `_decode` raises it after
  * the adapter call.
  */
final case class DecisionAdapter(inner: Adapter, plan: DecisionPlan) extends Adapter:

  override def name: String = inner.name

  /** The fallback of `inner`, with the same plan. */
  override def fallback: Option[Adapter] = inner.fallback.map(DecisionAdapter(_, plan))

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    DecisionAdapter.evidenceLayout(invocation.layout, plan).flatMap { evidence =>
      inner.format(
        invocation.copy(
          layout = withTaskExamples(invocation, evidence),
          demos = Vector.empty,
          outputJsonSchema = Some(PythonText.json(outputSchema(invocation, evidence)))
        )
      )
    }

  override def parse(layout: SignatureLayout, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    for
      evidence <- DecisionAdapter.evidenceLayout(layout, plan)
      parsed   <- inner.parse(evidence, output)
      answers  <- answersOf(parsed.values, output.text)
      decoded  <- plan.decode(answers)
    yield parsed.copy(values =
      decoded.fields.foldLeft(parsed.values)((values, entry) => values.updated(entry._1, entry._2))
    )

  override def formatUserMessageContent(layout: SignatureLayout, values: DynamicValue.Record): String =
    inner.formatUserMessageContent(layout, values)

  /** `evidence` with the task examples of the demos of `invocation` after the effective instructions of its layout.
    * With no demos, `evidence` stays as it is.
    */
  private def withTaskExamples(invocation: AdapterInvocation, evidence: SignatureLayout): SignatureLayout =
    if invocation.demos.isEmpty then evidence
    else
      val names    = invocation.layout.fields.map(_.name).toSet
      val examples = invocation.demos.map(demo =>
        DynamicValue.Record(demo.values.fields.filter((name, _) => names.contains(name)))
      )
      evidence.withInstructions(
        Some(
          invocation.layout.effectiveInstructions + DecisionAdapter.TaskExamplesHeader +
            PythonText.jsonAscii(DynamicValue.Sequence(Chunk.from(examples)))
        )
      )

  /** The output JSON schema of the request: the output schema of `invocation` with the evidence schema as the property
    * of each evidence field. When the root of the output schema is a `$ref` (a recursive output type), the schema that
    * it names replaces it first ([[StrictJsonSchema.inlineRootRef]]), so that the other properties stay. With no output
    * schema, the schema of the fields of `evidence` ([[StrictJsonSchema.fromFields]]), with the evidence schemas. When
    * a field has a type with no schema there, the result has only the evidence properties and
    * `"additionalProperties": true`: the JSON adapter then sends JSON mode, as it does for that field with no decision
    * outputs.
    */
  private def outputSchema(invocation: AdapterInvocation, evidence: SignatureLayout): DynamicValue.Record =
    val schemas = plan.kinds.map((name, kind) => name -> DecisionAdapter.evidenceSchema(kind))
    invocation.outputJsonSchema.flatMap(PythonText.parseJson) match
      case Some(parsed: DynamicValue.Record) =>
        val typed      = StrictJsonSchema.inlineRootRef(parsed)
        val properties = DynamicValues.recordGet(typed, "properties") match
          case Some(record: DynamicValue.Record) => record
          case _                                 => DynamicValue.Record.empty
        typed.updated("properties", schemas.foldLeft(properties)((acc, entry) => acc.updated(entry._1, entry._2)))
      case _ =>
        val fields = evidence.outputFields.filterNot(NativeFunctionCalling.isToolCallsField)
        StrictJsonSchema.fromFields(fields, schemas.toMap).getOrElse(
          DynamicValue.Record(
            "type"                 -> DecisionAdapter.text("object"),
            "properties"           -> DynamicValue.Record(Chunk.from(schemas)),
            "additionalProperties" -> DynamicValue.Primitive(PrimitiveValue.Boolean(true))
          )
        )

  /** The evidence of each decision output in the parsed `values`. A value that is not evidence is a [[ParseError]] that
    * names the field and has the raw response `raw`.
    */
  private def answersOf(values: DynamicValue.Record, raw: String): Either[DspyError, Map[String, DecisionEvidence]] =
    plan.kinds.foldLeft[Either[DspyError, Map[String, DecisionEvidence]]](Right(Map.empty)) {
      case (acc, (name, _)) =>
        val answer =
          for
            value    <- evidenceValue(name, DynamicValues.recordGet(values, name))
            evidence <- plan.evidence(name, value)
          yield evidence
        for
          found    <- acc
          evidence <- answer.left.map(error =>
                        ParseError("adapter", s"Failed to parse field $name: ${error.message}", raw = Some(raw))
                      )
        yield found.updated(name, evidence)
    }

  /** The JSON value of an evidence field: a JSON object as it is, or the JSON value of a text. A chat reply gives the
    * text of the object.
    */
  private def evidenceValue(name: String, value: Option[DynamicValue]): Either[DspyError, DynamicValue] =
    value.toRight(ValidationError(s"The reply has no evidence for '$name'")).flatMap {
      case DynamicValue.Primitive(PrimitiveValue.String(text)) => JsonDynamic.parse(text)
      case other                                               => Right(other)
    }

object DecisionAdapter:

  /** The type of the evidence field of a Noul output. The prompt shows this name, as upstream. */
  val NoulEvidence: TypeRef = TypeRef("NoulEvidence")

  /** The type of the evidence field of a Score output. */
  val ScoreEvidence: TypeRef = TypeRef("ScoreEvidence")

  /** The type of the evidence field of a Choice output. */
  val ChoiceEvidence: TypeRef = TypeRef("ChoiceEvidence")

  /** The text between the instructions and the task examples (upstream `DecisionAdapter._prepare`). */
  private val TaskExamplesHeader: String = "\n\nTask examples (labels or evidence):\n"

  /** The JSON schema of the evidence of `kind`, in the form of the port (upstream `evidence_type` writes the pydantic
    * form, with title-cased labels; the boundary rule of decision D3 keeps the port form):
    *   - Noul: `{"type": "object", "properties": {"noul": {"type": "number", "minimum": 0, "maximum": 1, "description":
    *     "Probability that the answer is true."}}, "required": ["noul"], "additionalProperties": false}`;
    *   - Score and Choice: `{"type": "object", "properties": {"probabilities": {"type": "object", "properties":
    *     {<label>: {"type": "number", "minimum": 0, "maximum": 1}, ...}, "required": [<labels>],
    *     "additionalProperties": false, "description": "Probability of each option; sum to one."}, "confidence":
    *     {"type": "number", "minimum": 0, "maximum": 1, "description": "Confidence in the decision, from 0 to 1."}},
    *     "required": ["probabilities", "confidence"], "additionalProperties": false}`. The labels of a Score are `"0"`
    *     to `"N-1"`; the labels of a Choice are its options, in the order of declaration.
    *
    * The integers 0 and 1 are JSON integers.
    */
  def evidenceSchema(kind: DecisionKind): DynamicValue.Record =
    kind match
      case DecisionKind.Noul           => closedObject(Vector("noul" -> probability(Some(NoulDescription))), None)
      case DecisionKind.Score(levels)  => distribution(levels.indices.map(_.toString).toVector)
      case DecisionKind.Choice(labels) => distribution(labels)

  /** `layout` with an evidence field in place of each decision output of `plan` (the first rule of the request above).
    * The instructions do not change. A question that the plan cannot give (no description and no instructions) is a
    * `Left`.
    *
    * An evidence field has no default value and is not optional: the evidence must come from the model. Its other
    * properties stay.
    */
  def evidenceLayout(layout: SignatureLayout, plan: DecisionPlan): Either[DspyError, SignatureLayout] =
    plan.kinds
      .foldLeft[Either[DspyError, Map[String, FieldSpec]]](Right(Map.empty)) { case (acc, (name, kind)) =>
        for
          fields   <- acc
          question <- plan.question(name)
        yield fields ++ layout.outputFields.find(_.name == name).map(field =>
          name -> field.copy(
            typeRef = evidenceType(kind),
            description = Some("\n" + PythonText.json(question, indent = Some(2))),
            defaultValue = None,
            enumValues = Vector.empty,
            optional = false
          )
        )
      }
      .map(fields => layout.withOutputFields(layout.outputFields.map(field => fields.getOrElse(field.name, field))))

  private def evidenceType(kind: DecisionKind): TypeRef =
    kind match
      case DecisionKind.Noul      => NoulEvidence
      case DecisionKind.Score(_)  => ScoreEvidence
      case DecisionKind.Choice(_) => ChoiceEvidence

  // The descriptions of upstream `evidence_type`.
  private val NoulDescription: String         = "Probability that the answer is true."
  private val DistributionDescription: String = "Probability of each option; sum to one."
  private val ConfidenceDescription: String   = "Confidence in the decision, from 0 to 1."

  /** The evidence schema of a Score or a Choice with the labels `labels`. */
  private def distribution(labels: Vector[String]): DynamicValue.Record =
    closedObject(
      Vector(
        "probabilities" -> closedObject(labels.map(_ -> probability(None)), Some(DistributionDescription)),
        "confidence"    -> probability(Some(ConfidenceDescription))
      ),
      None
    )

  /** An object schema with `properties`, each property required, and no other property. */
  private def closedObject(
      properties : Vector[(String, DynamicValue)],
      description: Option[String]
  ): DynamicValue.Record =
    DynamicValue.Record(
      Chunk.from(
        Vector(
          "type"                 -> text("object"),
          "properties"           -> DynamicValue.Record(Chunk.from(properties)),
          "required"             -> DynamicValue.Sequence(Chunk.from(properties.map((name, _) => text(name)))),
          "additionalProperties" -> DynamicValue.Primitive(PrimitiveValue.Boolean(false))
        ) ++ description.map("description" -> text(_))
      )
    )

  /** The schema of a number in [0, 1] (upstream `Probability`). */
  private def probability(description: Option[String]): DynamicValue.Record =
    DynamicValue.Record(
      Chunk.from(
        Vector(
          "type"    -> text("number"),
          "minimum" -> DynamicValue.Primitive(PrimitiveValue.Int(0)),
          "maximum" -> DynamicValue.Primitive(PrimitiveValue.Int(1))
        ) ++ description.map("description" -> text(_))
      )
    )

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))
