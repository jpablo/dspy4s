package dspy4s.adapters.internal

import dspy4s.core.contracts.{DynamicValues, FieldSpec, JsonSchemaRefs, PythonText, SignatureLayout, ToolCall, TypeRef}
import zio.blocks.schema.DynamicValue

/** The prompt texts that the Chat, JSON, XML, and TwoStep adapters share (decision D3). The adapters compute each text
  * from the data of the port: the [[TypeRef]], the enum values, the optional flag, the description, and the constraints
  * of a field. The upstream source of each rule is `dspy/adapters/utils.py`, unless a function names an other source.
  *
  * The type text of a field (the type slots):
  *   - The type name is `str`, `int`, `float`, `bool`, `dict` ([[TypeRef.json]]), `list` ([[TypeRef.list]]), `History`,
  *     `list[Tool]` ([[TypeRef.tools]]), or `ToolCalls`. An other [[TypeRef]] shows its `repr`. A field with enum
  *     values shows `str`. An optional field shows the name of its type.
  *   - The note of an output field: `True or False` for a bool, `a single int value` or `a single float value` for a
  *     number, the `Literal` note for a field with enum values, and a JSON schema for a structured field and for a tool
  *     calls field. A field of an other type (an opaque [[TypeRef]], for example the evidence type of a decision
  *     output) has the JSON schema note when the output schema has a property for it. Each other field has no note.
  */
private[adapters] object PromptText:

  /** The first text of the structure of each adapter (upstream `format_field_structure`). */
  val StructureHeader: String =
    "All interactions will be structured in the following way, with the appropriate values filled in."

  /** The prompt text of a tool calls field (upstream `ToolCalls.description` and the reminder of `ChatAdapter`). */
  private val ToolCallsTypeName: String    = "ToolCalls"
  private val ToolCallsDescription: String = "Tool calls must be a JSON object with `tool_calls`, a list of calls. " +
    "Each call must include `name` and `args`. " +
    """Example: {"tool_calls": [{"name": "search", "args": {"query": "cats"}}]}"""
  private val ToolCallsReminder: String =
    """ (must be a JSON object like {"tool_calls": [{"name": "...", "args": {...}}]})"""

  /** The JSON schema of a tool calls field: the schema of upstream `ToolCalls`, in the order of `_get_json_schema`. */
  private val ToolCallsSchema: String =
    """{"type": "object", "$defs": {"ToolCall": {"type": "object", "properties": """ +
      """{"args": {"type": "object", "additionalProperties": true, "title": "Args"}, "name": {"type": "string", """ +
      """"title": "Name"}}, "required": ["name", "args"], "title": "ToolCall"}}, "properties": {"tool_calls": """ +
      """{"type": "array", "items": {"$ref": "#/$defs/ToolCall"}, "title": "Tool Calls"}}, "required": """ +
      """["tool_calls"], "title": "ToolCalls"}"""

  /** The start of a reference to a definition of the output schema. */
  private val DefsPrefix: String = "#/$defs/"

  /** The schema of a list field and of a dict field that have no property in the output schema. */
  private val ListSchema: String = """{"type": "array", "items": {}}"""
  private val DictSchema: String = """{"type": "object", "additionalProperties": true}"""

  // ── The system message ─────────────────────────────────────────────────────────────────────────────────────────

  /** The system message of the Chat, JSON, and XML adapters (upstream `Adapter.format_system_message` in
    * `dspy/adapters/base.py`): the field description of `shown`, the structure of the adapter, and the task description
    * with the instructions of `layout`.
    *
    * The instructions are the instructions of `layout`, else its default instructions
    * ([[SignatureLayout.effectiveInstructions]], upstream `_default_instructions` in `dspy/signatures/signature.py`).
    * Upstream computes the default instructions when it makes the signature, so an adapter takes them from the full
    * layout, also when the prompt does not show each field of the layout.
    *
    * @param layout
    *   the full layout of the signature
    * @param shown
    *   the layout that the prompt shows: with native function calling active, it has no tool calls field
    */
  def systemMessage(layout: SignatureLayout, shown: SignatureLayout, structure: String): String =
    s"${fieldDescription(shown)}\n$structure\n${taskDescription(layout.effectiveInstructions)}"

  /** The task description of the Chat, JSON, and XML adapters (upstream `ChatAdapter.format_task_description`): each
    * line of the dedented instructions on a new line, after eight spaces.
    */
  private def taskDescription(instructions: String): String =
    "In adhering to this structure, your objective is: " +
      PythonText.splitLines(PythonText.dedent(instructions)).map("\n        " + _).mkString

  // ── Field descriptions ─────────────────────────────────────────────────────────────────────────────────────────

  /** The field description of the Chat, JSON, and XML adapters (upstream `ChatAdapter.format_field_description`). */
  private def fieldDescription(layout: SignatureLayout): String =
    s"Your input fields are:\n${descriptions(layout.inputFields)}\n" +
      s"Your output fields are:\n${descriptions(layout.outputFields)}"

  /** The numbered list of `fields` (upstream `get_field_description_string`): the name, the type name, the description,
    * the type description of a tool calls field, and the constraints on a line of their own.
    */
  def descriptions(fields: Vector[FieldSpec]): String =
    PythonText.strip(
      fields.zipWithIndex.map { (field, index) =>
        val typeDescription = Option.when(field.typeRef == TypeRef.toolCalls)(
          s"\n    Type description of $ToolCallsTypeName: $ToolCallsDescription"
        )
        val constraints = Option.when(field.constraints.nonEmpty)(
          s"\nConstraints: ${field.constraints.map(_.render).mkString(", ")}"
        )
        s"${index + 1}. `${field.name}` (${typeName(field)}): ${description(field)}" +
          typeDescription.getOrElse("") + constraints.getOrElse("")
      }.mkString("\n")
    )

  /** The description of `field`. The placeholder `${name}` of [[FieldSpec.normalize]] is no description. */
  private def description(field: FieldSpec): String =
    field.description.filter(_ != s"$${${field.name}}").getOrElse("")

  /** The type name of `field` (upstream `get_annotation_name`, with the type text of the port). */
  def typeName(field: FieldSpec): String =
    if field.enumValues.nonEmpty then "str"
    else if field.typeRef == TypeRef.toolCalls then ToolCallsTypeName
    else field.typeRef.pythonTypeName.getOrElse(field.typeRef.repr)

  // ── Structure ──────────────────────────────────────────────────────────────────────────────────────────────────

  /** The placeholder of `field` in the structure (upstream `translate_field_type`): `{name}`, with the note of an
    * output field. An input field has no note.
    */
  def placeholder(field: FieldSpec, output: Boolean, schema: OutputSchema): String =
    val note = if output then outputNote(field, schema) else None
    s"{${field.name}}" + note.fold("")(text => s"        # note: the value you produce $text")

  private def outputNote(field: FieldSpec, schema: OutputSchema): Option[String] =
    if field.enumValues.nonEmpty then
      Some(s"must exactly match (no extra characters) one of: ${field.enumValues.mkString("; ")}")
    else
      field.typeRef match
        case TypeRef.string               => None
        case TypeRef.bool                 => Some("must be True or False")
        case TypeRef.int                  => Some("must be a single int value")
        case TypeRef.double               => Some("must be a single float value")
        case TypeRef.toolCalls            => Some(s"must adhere to the JSON schema: $ToolCallsSchema")
        case other if isStructured(other) => Some(s"must adhere to the JSON schema: ${schema.textOf(field)}")
        // Upstream `translate_field_type` gives each other type its JSON schema. The port has that schema only when
        // the output schema has a property for the field.
        case _ => schema.propertyText(field).map(text => s"must adhere to the JSON schema: $text")

  /** `true` for a dict, a list, and a list with an item type (`list[str]`, [[TypeRef.collection]]). */
  private def isStructured(typeRef: TypeRef): Boolean =
    typeRef == TypeRef.json || typeRef == TypeRef.list ||
      typeRef.collection.exists((kind, _) => kind == TypeRef.Collection.List)

  /** The type hint of `field` in the output reminder of the Chat and JSON adapters (upstream
    * `user_message_output_requirements`): empty for a `str` field.
    */
  def reminderHint(field: FieldSpec): String =
    if field.typeRef == TypeRef.toolCalls then ToolCallsReminder
    else
      typeName(field) match
        case "str" => ""
        case name  => s" (must be formatted as a valid Python $name)"

  // ── Values ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** The text of the value of `field` (upstream `format_field_value`). A list value of a `str` field is a numbered
    * list. A list, a dict, or a record is JSON text. Each other value is its Python `str` text.
    */
  def fieldValue(field: FieldSpec, value: DynamicValue): String =
    jsonable(field, value) match
      case sequence: DynamicValue.Sequence if isStr(field) => numberedList(sequence.elements.toVector)
      case other                                           => PythonText.str(other)

  /** `true` when the type of `field` is `str` in upstream: a text field with no enum values that is not optional. */
  def isStr(field: FieldSpec): Boolean =
    field.typeRef == TypeRef.string && field.enumValues.isEmpty && !field.optional

  /** The value of `field` in the form that upstream serializes (`serialize_for_json`). The value of a tool calls field
    * is the object of upstream `ToolCalls`: `{"tool_calls": [{"name": ..., "args": ...}]}`, with no call ids. Each
    * other value stays as it is.
    */
  def jsonable(field: FieldSpec, value: DynamicValue): DynamicValue =
    if field.typeRef != TypeRef.toolCalls then value
    else
      ToolCall.fromDynamic(value).fold(value) { found =>
        DynamicValues.record(ToolCall.ToolCallsKey -> DynamicValues.sequence(found.map(_.copy(id = None).toRecord)))
      }

  /** A list value of a `str` field (upstream `_format_input_list_field_value`): `N/A` for an empty list, one blob for
    * one item, and else one numbered blob on each line.
    */
  private def numberedList(items: Vector[DynamicValue]): String =
    items match
      case Vector()     => "N/A"
      case Vector(item) => blob(PythonText.str(item))
      case _            => items.zipWithIndex.map(numbered).mkString("\n")

  private def numbered(entry: (DynamicValue, Int)): String =
    s"[${entry._2 + 1}] ${blob(PythonText.str(entry._1))}"

  /** One item of a numbered list (upstream `_format_blob`). */
  private def blob(text: String): String =
    if !text.contains('\n') && !text.contains('«') && !text.contains('»') then s"«$text»"
    else s"«««\n    ${text.replace("\n", "\n    ")}\n»»»"

  // ── The output schema ──────────────────────────────────────────────────────────────────────────────────────────

  /** The output JSON schema `root` of an invocation, for the schema notes of the structured fields. With no schema, no
    * field has a property.
    */
  final class OutputSchema(root: Option[DynamicValue.Record]):

    /** The JSON text of the schema of `field`: its property in the output schema, with the `$defs` entries that the
      * property uses at the end. With no such property, the schema of a list or of a dict.
      */
    def textOf(field: FieldSpec): String =
      propertyText(field).getOrElse(if field.typeRef == TypeRef.json then DictSchema else ListSchema)

    /** The JSON text of the property of `field` in the output schema, with the `$defs` entries that the property uses
      * at the end. `None` when the output schema has no property for `field`.
      */
    def propertyText(field: FieldSpec): Option[String] =
      propertySchema(field.name).map(PythonText.json(_))

    /** The schema of the property `name` of the output schema, with the `$defs` entries that the property uses at the
      * end. `None` when the output schema has no such property.
      */
    def propertySchema(name: String): Option[DynamicValue] =
      property(name).map(withDefinitions)

    private def property(name: String): Option[DynamicValue] =
      root.flatMap(DynamicValues.recordGet(_, "properties")).flatMap(DynamicValues.recordGet(_, name))

    /** The `$defs` entries of the output schema, in its order. The schema of each structured field reads them, so the
      * object computes them one time.
      */
    private lazy val definitions: Vector[(String, DynamicValue)] =
      root.flatMap(DynamicValues.recordGet(_, "$defs")).flatMap(DynamicValues.asRecord)
        .fold(Vector.empty)(DynamicValues.recordEntries)

    /** The names of the definitions that each definition references, by name, computed one time. */
    private lazy val definitionReferences: Map[String, Vector[String]] =
      definitions.map((name, schema) => name -> references(schema)).toMap

    /** `schema` with a `$defs` key at the end that holds each definition that `schema` uses, also through other
      * definitions, in the order of the output schema.
      */
    private def withDefinitions(schema: DynamicValue): DynamicValue =
      def closure(found: Set[String], next: Vector[String]): Set[String] =
        next.filterNot(found.contains) match
          case Vector() => found
          case fresh    => closure(found ++ fresh, fresh.flatMap(definitionReferences.getOrElse(_, Vector.empty)))
      val used = closure(Set.empty, references(schema))
      schema match
        case record: DynamicValue.Record if used.nonEmpty =>
          val entries = definitions.filter((name, _) => used.contains(name))
          DynamicValue.Record(record.fields :+ ("$defs" -> DynamicValues.recordFromEntries(entries)))
        case other => other

    /** The names of the definitions that `schema` references with `{"$ref": "#/$defs/<name>"}`. The name has its JSON
      * pointer escapes decoded ([[JsonSchemaRefs.unescape]]).
      */
    private def references(schema: DynamicValue): Vector[String] =
      schema match
        case record: DynamicValue.Record => record.fields.toVector.flatMap {
            case ("$ref", value) => DynamicValues.asString(value).filter(_.startsWith(DefsPrefix))
                .map(ref => JsonSchemaRefs.unescape(ref.stripPrefix(DefsPrefix))).toVector
            case (_, value) => references(value)
          }
        case sequence: DynamicValue.Sequence => sequence.elements.toVector.flatMap(references)
        case _                               => Vector.empty
