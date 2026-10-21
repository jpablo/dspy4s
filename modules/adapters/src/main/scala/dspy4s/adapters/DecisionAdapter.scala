package dspy4s.adapters

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, NativeFunctionCalling, ParsedOutput}
import dspy4s.adapters.internal.{AdapterTextSupport, JsonDynamic, PromptText, PythonText, StrictJsonSchema}
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
import dspy4s.core.data.Example
import dspy4s.core.decisions.{DecisionEvidence, DecisionKind, DecisionPlan}
import dspy4s.lm.contracts.LmOutput
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Experimental. The adapter that asks a generative model for the evidence of the decision outputs of `plan`, and
  * decodes it (upstream `DecisionAdapter` for a model that is not a System One model, in `dspy/adapters/decision.py`).
  * Each other part of the request comes from `inner`, so the same rules hold for the Chat, JSON, and XML adapters. A
  * System One model gets no prompt: `Predict` sends it the state and the questions of the companion functions
  * [[DecisionAdapter.systemOneState]] and [[DecisionAdapter.systemOneQuestions]].
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
  * [[DecisionPlan.decode]] gives the output values, which replace the evidence values. Each other value stays. The
  * result keeps the evidence of each decision output in [[ParsedOutput.evidence]], so that the predictor can record it
  * (upstream `record_evidence`). A malformed evidence value is a `Left`: a [[dspy4s.core.contracts.ParseError]], so
  * that the predictor can try the fallback adapter (upstream: the evidence model of the field rejects it in the parse
  * of the adapter). An error of the decode (for example a distribution with a total of zero) is not a parse error:
  * upstream `_decode` raises it after the adapter call.
  */
final case class DecisionAdapter(inner: Adapter, plan: DecisionPlan) extends Adapter:

  override def name: String = inner.name

  /** The fallback of `inner`, with the same plan. */
  override def fallback: Option[Adapter] = inner.fallback.map(DecisionAdapter(_, plan))

  override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
    DecisionAdapter.evidenceLayout(invocation.layout, plan).flatMap { evidence =>
      inner.format(evidenceInvocation(invocation, evidence).copy(layout = withTaskExamples(invocation, evidence)))
    }

  override def parse(layout: SignatureLayout, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    decodeWith(layout, output)(evidence => inner.parse(evidence, output))

  /** The parse of `inner` gets the invocation of the request: the evidence layout, and the output schema with the
    * evidence schemas.
    */
  override def parse(invocation: AdapterInvocation, output: LmOutput)(using
      RuntimeContext
  ): Either[DspyError, ParsedOutput] =
    decodeWith(invocation.layout, output)(evidence => inner.parse(evidenceInvocation(invocation, evidence), output))

  /** The invocation of `inner` for the evidence layout `evidence`: no demos, and the output schema with the evidence
    * schemas ([[outputSchema]]).
    */
  private def evidenceInvocation(invocation: AdapterInvocation, evidence: SignatureLayout): AdapterInvocation =
    invocation.copy(
      layout = evidence,
      demos = Vector.empty,
      outputJsonSchema = Some(PythonText.json(outputSchema(invocation, evidence)))
    )

  /** The output values of `output`: `parseEvidence` parses it with the evidence layout of `layout`, then the evidence
    * gives the decisions.
    */
  private def decodeWith(layout: SignatureLayout, output: LmOutput)(
      parseEvidence: SignatureLayout => Either[DspyError, ParsedOutput]
  ): Either[DspyError, ParsedOutput] =
    for
      evidence <- DecisionAdapter.evidenceLayout(layout, plan)
      parsed   <- parseEvidence(evidence)
      answers  <- answersOf(parsed.values, output.text)
      decoded  <- plan.decode(answers)
    yield parsed.copy(
      values = decoded.fields.foldLeft(parsed.values)((values, entry) => values.updated(entry._1, entry._2)),
      evidence = plan.kinds.flatMap((name, _) => answers.get(name).map(name -> _))
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

  /** The questions of a System One request (upstream `DecisionAdapter._prepare` with `system_one`): the question of
    * each decision output of `plan` ([[DecisionPlan.question]]), by output name, in the order of the layout. A question
    * that the plan cannot give is a `Left`.
    */
  def systemOneQuestions(plan: DecisionPlan): Either[DspyError, DynamicValue.Record] =
    plan.kinds
      .foldLeft[Either[DspyError, Vector[(String, DynamicValue)]]](Right(Vector.empty)) { case (acc, (name, _)) =>
        for
          questions <- acc
          question  <- plan.question(name)
        yield questions :+ (name -> question)
      }
      .map(entries => DynamicValue.Record(Chunk.from(entries)))

  /** The state of a System One request (upstream `DecisionAdapter._prepare` with `system_one`). Its keys, in this
    * order:
    *   - `instructions`: the effective instructions of `layout`;
    *   - `input_fields`: the numbered list of the input fields, as the adapters write it in the system message
    *     (upstream `get_field_description_string`, which strips the text);
    *   - `inputs`: the values of the input fields of `layout` in `inputs`, in the order of `inputs`, then the default
    *     value of each input field that `inputs` does not have (upstream `Predict._forward_preprocess`);
    *   - `demos`: only when `demos` is not empty. Each demo keeps only the fields of `layout`, in the order of the
    *     demo.
    */
  def systemOneState(
      layout: SignatureLayout,
      demos : Vector[Example],
      inputs: DynamicValue.Record
  ): DynamicValue.Record =
    val inputNames = layout.inputFields.map(_.name).toSet
    val fieldNames = layout.fields.map(_.name).toSet
    val declared   = DynamicValues.recordFilterKeys(
      AdapterTextSupport.withInputDefaults(layout.inputFields, inputs),
      inputNames.contains
    )
    val examples = Option.when(demos.nonEmpty)(
      "demos" -> DynamicValue.Sequence(
        Chunk.from(demos.map(demo => DynamicValues.recordFilterKeys(demo.values, fieldNames.contains)))
      )
    )
    DynamicValue.Record(
      Chunk.from(
        Vector[(String, DynamicValue)](
          "instructions" -> text(layout.effectiveInstructions),
          "input_fields" -> text(PromptText.descriptions(layout.inputFields)),
          "inputs"       -> declared
        ) ++ examples
      )
    )

  /** The evidence of each decision output of `plan` in the answers of a System One model, by question name, in the
    * order of the layout (upstream `DecisionAdapter._decode`, with [[DecisionPlan.evidence]]). [[DecisionPlan.decode]]
    * then gives the output values. A decision output with no answer is a `Left`.
    */
  def systemOneEvidence(
      plan   : DecisionPlan,
      answers: DynamicValue.Record
  ): Either[DspyError, Vector[(String, DecisionEvidence)]] =
    plan.kinds.foldLeft[Either[DspyError, Vector[(String, DecisionEvidence)]]](Right(Vector.empty)) {
      case (acc, (name, _)) =>
        for
          found  <- acc
          answer <- DynamicValues.recordGet(answers, name)
                      .toRight(ValidationError(s"The reply of the System One model has no answer for '$name'"))
          evidence <- plan.evidence(name, answer)
        yield found :+ (name -> evidence)
    }

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
