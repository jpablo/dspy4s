package dspy4s.adapters.internal

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.JsonSchemaRefs
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import scala.collection.mutable

/** The JSON schema of the outputs as OpenAI strict structured outputs take it. The rules come from upstream
  * `_strict_json_schema` (`dspy/clients/legacy_requests.py`) and `_get_structured_outputs_response_format`
  * (`dspy/adapters/json_adapter.py`).
  */
private[adapters] object StrictJsonSchema:

  /** The schema name of a structured output request (upstream: the name of the generated pydantic model). */
  val Name: String = "DSPyProgramOutputs"

  /** `schema` in strict shape. The rules apply at each level: in `$defs` and `definitions`, in `properties`, in
    * `items`, in `anyOf`, and in `allOf`.
    *
    *   - An object schema (its `type` is `object`, or a list that has `object`) with no `additionalProperties` gets
    *     `additionalProperties: false`.
    *   - A schema with `properties` gets `required` with each property name, in the order of `properties`.
    *   - An `allOf` with one schema is merged into its parent schema, and the keys of that one schema win.
    *   - A `default` of `null` is removed.
    *   - A `oneOf` becomes an `anyOf`, because OpenAI strict mode does not take `oneOf` (a port rule: zio-blocks writes
    *     `oneOf` for a sealed trait whose cases have fields, and upstream pydantic writes `anyOf` for a union).
    *   - A `$ref` with other keys is replaced by the schema that it names (an internal `#/...` path from the root of
    *     `schema`), in strict shape, and the other keys win. A `$ref` with no other keys stays.
    *   - Each top-level property in `optionalFields` whose schema does not permit `null` becomes
    *     `{"anyOf": [<its schema>, {"type": "null"}]}`. A schema permits `null` when its `type` is `null` or a list
    *     that has `null`, or when its `anyOf` has such a schema.
    *
    * The result is a `Left` when strict mode cannot take the schema:
    *   - an open map, which is an object schema with an `additionalProperties` that is a schema or `true` (upstream
    *     sends JSON mode for an open map);
    *   - a `$ref` with other keys that does not resolve (upstream raises a `ValueError`).
    *
    * @param optionalFields
    *   the names of the optional output fields ([[FieldSpec.optional]])
    */
  def shape(
      schema        : DynamicValue.Record,
      optionalFields: Set[String] = Set.empty
  ): Either[DspyError, DynamicValue.Record] =
    shapeAt(schema, Shaping(schema), refs = Set.empty).map(permitNull(_, optionalFields))

  /** The JSON schema of the output fields `fields` of a signature that has no typed schema (upstream builds a pydantic
    * model from the annotations). The schema is an object with one property for each field, in the order of `fields`.
    * It is not in strict shape: [[shape]] does that.
    *
    *   - `string` gives `{"type": "string"}`. With [[FieldSpec.enumValues]], it is `{"type": "string", "enum": [...]}`.
    *   - `int` gives `{"type": "integer"}`, `double` gives `{"type": "number"}`, and `bool` gives
    *     `{"type": "boolean"}`.
    *   - `list` gives `{"type": "array", "items": {}}`. `list[T]` gives `{"type": "array", "items": <schema of T>}` for
    *     each `T` in these rules.
    *   - A field with a [[FieldSpec.defaultValue]] has the JSON value of that value under `default`: the value of its
    *     JSON text ([[PythonText.json]], then [[PythonText.parseJson]]). Thus an enum case is its name, and an integer
    *     keeps its exact value. A value with no JSON text (a float that is not finite) stays as it is.
    *   - A field that `known` has takes its schema from `known`, as it is. The decision adapter gives the evidence
    *     schemas this way.
    *
    * The result is `None` when a field has a type with no schema in these rules: `json` (an open map, upstream `dict`),
    * `tool_calls`, `history`, and each opaque type. Upstream sends JSON mode in these cases.
    */
  def fromFields(
      fields: Vector[FieldSpec],
      known : Map[String, DynamicValue.Record] = Map.empty
  ): Option[DynamicValue.Record] =
    Traverse
      .option(fields)(field => known.get(field.name).orElse(fieldSchema(field)).map(field.name -> _))
      .map(entries =>
        DynamicValues.record("type" := "object", "properties" -> DynamicValues.recordFromEntries(entries))
      )

  // ── Strict shape ──────────────────────────────────────────────────────────────────────────────────────────────

  /** The keys whose value is a record of named schemas. */
  private val DefinitionKeys: Vector[String] = Vector("$defs", "definitions")

  /** The state of one [[shape]] call: `root` is the schema that a `$ref` names a path in, and `resolved` holds the
    * target of each `$ref` with other keys in strict shape, by its reference, after the first use of the reference. A
    * target in strict shape does not change with the place of its use, so the call shapes each target one time.
    */
  private final class Shaping(val root: DynamicValue.Record):
    val resolved: mutable.Map[String, DynamicValue.Record] = mutable.Map.empty

  /** `schema` in strict shape, in the order of upstream `_strict_json_schema`. `refs` are the references that the call
    * resolves now: a reference to one of them is a cycle.
    */
  private def shapeAt(
      schema : DynamicValue.Record,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    for
      defined <- DefinitionKeys.foldLeft[Either[DspyError, DynamicValue.Record]](Right(schema))((acc, key) =>
                   acc.flatMap(shapeNamed(_, key, shaping, refs))
                 )
      closed    <- close(defined)
      withProps <- shapeProperties(closed, shaping, refs)
      withItems <- shapeItems(withProps, shaping, refs)
      withAnyOf <- shapeEach(oneOfAsAnyOf(withItems), "anyOf", shaping, refs)
      withAllOf <- mergeAllOf(withAnyOf, shaping, refs)
      resolved  <- resolveRef(dropNullDefault(withAllOf), shaping, refs)
    yield resolved

  /** Each schema in the record under `key` (`$defs` or `definitions`) in strict shape. */
  private def shapeNamed(
      schema : DynamicValue.Record,
      key    : String,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, key) match
      case Some(named: DynamicValue.Record) =>
        shapeEntries(named, shaping, refs).map(shaped => schema.updated(key, shaped))
      case _ => Right(schema)

  /** An object schema with no `additionalProperties` gets `additionalProperties: false`. An open map is a `Left`. */
  private def close(schema: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
    if isOpenMap(schema) then
      Left(ValidationError(s"Strict mode does not take an open map: ${PythonText.json(schema)}"))
    else if hasType(schema, "object") && DynamicValues.recordGet(schema, "additionalProperties").isEmpty then
      Right(schema.updated("additionalProperties", DynamicValue.Primitive(PrimitiveValue.Boolean(false))))
    else Right(schema)

  /** `true` when `value` has an open map at some level ([[isOpenMap]]). [[shape]] gives a `Left` for such a schema. */
  private def hasOpenMap(value: DynamicValue): Boolean =
    value match
      case schema: DynamicValue.Record => isOpenMap(schema) || schema.fields.exists((_, inner) => hasOpenMap(inner))
      case list: DynamicValue.Sequence => list.elements.exists(hasOpenMap)
      case _                           => false

  /** `true` when the output schema `schema` has an open map ([[isOpenMap]]) below its root: in a property, in an item,
    * or in a definition. This is the open map of an output field. The JSON adapter then sends JSON mode with no
    * warning, as upstream does for an open map (`_has_open_ended_mapping` in `dspy/adapters/json_adapter.py` tests the
    * type of each output field). The root is the object of the output fields, so an open root is not the open map of a
    * field: the decision adapter writes an open root when a field has no schema. [[shape]] gives a `Left` for an open
    * root.
    */
  def hasFieldOpenMap(schema: DynamicValue.Record): Boolean =
    schema.fields.exists((_, inner) => hasOpenMap(inner))

  /** The schema of an open map: an object with any properties (upstream `dict`). */
  val OpenMapSchema: DynamicValue.Record = DynamicValues.record("type" := "object", "additionalProperties" := true)

  /** `true` for an object schema whose `additionalProperties` is a schema or `true`. */
  private def isOpenMap(schema: DynamicValue.Record): Boolean =
    hasType(schema, "object") &&
      (DynamicValues.recordGet(schema, "additionalProperties") match
        case Some(_: DynamicValue.Record) | Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))) => true
        case _                                                                                         => false)

  /** Each property in strict shape, and each property name in `required`. */
  private def shapeProperties(
      schema : DynamicValue.Record,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "properties") match
      case Some(properties: DynamicValue.Record) =>
        val required = DynamicValues.sequence(DynamicValues.recordKeys(properties).map(DynamicValues.string))
        shapeEntries(properties, shaping, refs).map(shaped =>
          schema.updated("required", required).updated("properties", shaped)
        )
      case _ => Right(schema)

  private def shapeItems(
      schema : DynamicValue.Record,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "items") match
      case Some(items: DynamicValue.Record) =>
        shapeAt(items, shaping, refs).map(shaped => schema.updated("items", shaped))
      case _ => Right(schema)

  /** Each schema in the list under `key` (`anyOf` or `allOf`) in strict shape. */
  private def shapeEach(
      schema : DynamicValue.Record,
      key    : String,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, key) match
      case Some(list: DynamicValue.Sequence) => Traverse.either(list.elements)(shapeValue(_, shaping, refs))
          .map(shaped => schema.updated(key, DynamicValues.sequence(shaped)))
      case _ => Right(schema)

  /** An `allOf` with one schema is merged into `schema`, and the keys of that one schema win. */
  private def mergeAllOf(
      schema : DynamicValue.Record,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "allOf") match
      case Some(list: DynamicValue.Sequence) => list.elements.toVector match
          case Vector(single: DynamicValue.Record) =>
            shapeAt(single, shaping, refs).map(shaped => DynamicValues.mergeRecords(without(schema, "allOf"), shaped))
          case _ => shapeEach(schema, "allOf", shaping, refs)
      case _ => Right(schema)

  /** A `oneOf` becomes an `anyOf` (OpenAI strict mode takes `anyOf` only). The schemas of a `oneOf` come after the
    * schemas of an `anyOf` that the schema has already.
    */
  private def oneOfAsAnyOf(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "oneOf") match
      case Some(options: DynamicValue.Sequence) =>
        val before = DynamicValues.recordGet(schema, "anyOf") match
          case Some(existing: DynamicValue.Sequence) => existing.elements
          case _                                     => Chunk.empty
        without(schema, "oneOf").updated("anyOf", DynamicValue.Sequence(before ++ options.elements))
      case _ => schema

  private def dropNullDefault(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "default") match
      case Some(_: DynamicValue.Null.type) => without(schema, "default")
      case _                               => schema

  /** A `$ref` with other keys is replaced by the schema that it names, in strict shape, and the other keys win. The
    * call shapes the target of a reference at its first use only ([[Shaping.resolved]]).
    */
  private def resolveRef(
      schema : DynamicValue.Record,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "$ref") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(ref))) if schema.fields.length > 1 =>
        if refs.contains(ref) then Left(ValidationError(s"The $$ref '$ref' is in a cycle"))
        else
          val target = shaping.resolved.get(ref) match
            case Some(shaped) => Right(Some(shaped))
            case None         => JsonSchemaRefs.lookup(shaping.root, ref).flatMap {
                case found: DynamicValue.Record => shapeAt(found, shaping, refs + ref).map { shaped =>
                    shaping.resolved.update(ref, shaped)
                    Some(shaped)
                  }
                case _ => Right(None)
              }
          target.map(_.fold(schema)(shaped => DynamicValues.mergeRecords(shaped, without(schema, "$ref"))))
      case _ => Right(schema)

  /** Each top-level property in `optionalFields` that does not permit `null` gets a `null` option. */
  private def permitNull(schema: DynamicValue.Record, optionalFields: Set[String]): DynamicValue.Record =
    DynamicValues.recordGet(schema, "properties") match
      case Some(properties: DynamicValue.Record) if optionalFields.nonEmpty =>
        val widened = properties.fields.map {
          case (name, property: DynamicValue.Record) if optionalFields.contains(name) && !permitsNull(property) =>
            name -> DynamicValues.record("anyOf" -> DynamicValues.sequence(Vector(property, typed("null"))))
          case other => other
        }
        schema.updated("properties", DynamicValue.Record(widened))
      case _ => schema

  private def permitsNull(schema: DynamicValue.Record): Boolean =
    hasType(schema, "null") ||
      (DynamicValues.recordGet(schema, "anyOf") match
        case Some(options: DynamicValue.Sequence) => options.elements.exists {
            case option: DynamicValue.Record => permitsNull(option)
            case _                           => false
          }
        case _ => false)

  /** True when the `type` of `schema` is `name`, or a list that has `name`. */
  private def hasType(schema: DynamicValue.Record, name: String): Boolean =
    DynamicValues.recordGet(schema, "type") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(single))) => single == name
      case Some(types: DynamicValue.Sequence)                          => types.elements.exists {
          case DynamicValue.Primitive(PrimitiveValue.String(member)) => member == name
          case _                                                     => false
        }
      case _ => false

  /** A record of schemas with each schema in strict shape. A value that is not a schema object stays. */
  private def shapeEntries(
      entries: DynamicValue.Record,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    Traverse.either(entries.fields)((name, value) => shapeValue(value, shaping, refs).map(name -> _))
      .map(DynamicValues.recordFromEntries)

  private def shapeValue(
      value  : DynamicValue,
      shaping: Shaping,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue] =
    value match
      case schema: DynamicValue.Record => shapeAt(schema, shaping, refs)
      case other                       => Right(other)

  /** `schema` with no field `key`. */
  private def without(schema: DynamicValue.Record, key: String): DynamicValue.Record =
    DynamicValues.recordFilterKeys(schema, _ != key)

  // ── A schema from the field types ─────────────────────────────────────────────────────────────────────────────

  private def fieldSchema(field: FieldSpec): Option[DynamicValue.Record] =
    typeSchema(field.typeRef).map { schema =>
      val withEnum =
        if field.enumValues.nonEmpty && field.typeRef == TypeRef.string then
          schema.updated("enum", DynamicValues.sequence(field.enumValues.map(DynamicValues.string)))
        else schema
      field.defaultValue.fold(withEnum)(value =>
        withEnum.updated("default", PythonText.parseJson(PythonText.json(value)).getOrElse(value))
      )
    }

  /** The schema of a field type. `json` has no schema: it is an open map. `list` and `list[T]` give an array, and
    * `string`, `int`, `double`, and `bool` give their JSON type ([[TypeRef.jsonType]]).
    */
  private def typeSchema(typeRef: TypeRef): Option[DynamicValue.Record] =
    typeRef match
      case TypeRef.json => None
      case TypeRef.list => Some(array(DynamicValue.Record.empty))
      case _            => typeRef.collection match
          case Some((TypeRef.Collection.List, item)) => typeSchema(TypeRef.fromToken(item)).map(array)
          case Some(_)                               => None
          case None                                  => typeRef.jsonType.map(typed)

  private def typed(name: String): DynamicValue.Record = DynamicValues.record("type" := name)

  private def array(items: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.record("type" := "array", "items" -> items)
