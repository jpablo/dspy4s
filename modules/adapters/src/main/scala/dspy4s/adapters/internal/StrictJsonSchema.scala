package dspy4s.adapters.internal

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.TypeRef
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.updated
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.PrimitiveValue

import scala.util.matching.Regex

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
    shapeAt(schema, root = schema, refs = Set.empty).map(permitNull(_, optionalFields))

  /** The JSON schema of the output fields `fields` of a signature that has no typed schema (upstream builds a pydantic
    * model from the annotations). The schema is an object with one property for each field, in the order of `fields`.
    * It is not in strict shape: [[shape]] does that.
    *
    *   - `string` gives `{"type": "string"}`. With [[FieldSpec.enumValues]], it is `{"type": "string", "enum": [...]}`.
    *   - `int` gives `{"type": "integer"}`, `double` gives `{"type": "number"}`, and `bool` gives
    *     `{"type": "boolean"}`.
    *   - `list` gives `{"type": "array", "items": {}}`. `list[T]` gives `{"type": "array", "items": <schema of T>}` for
    *     each `T` in these rules.
    *   - A field with a [[FieldSpec.defaultValue]] has the JSON of that value under `default`.
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
    val properties = fields.foldLeft(Option(Vector.empty[(String, DynamicValue)])) { (acc, field) =>
      for
        done     <- acc
        property <- known.get(field.name).orElse(fieldSchema(field))
      yield done :+ (field.name -> property)
    }
    properties.map(entries =>
      DynamicValue.Record(Chunk("type" -> str("object"), "properties" -> DynamicValue.Record(Chunk.from(entries))))
    )

  /** `schema` with the schema that its root `$ref` names in place of the `$ref`. The keys of the named schema come
    * first, then each other key of `schema`. The root of the schema of a recursive type is a `$ref` (see
    * `ZioSchemaCodec.jsonSchemaForModel`), thus a change to the root `properties` must come after this step. A schema
    * with no root `$ref`, or with a `$ref` that does not resolve, stays as it is.
    */
  def inlineRootRef(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "$ref") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(ref))) => lookup(schema, ref) match
          case Right(target: DynamicValue.Record) =>
            overlay(target, schema.fields.toVector.filterNot((key, _) => key == "$ref"))
          case _ => schema
      case _ => schema

  // ── Strict shape ──────────────────────────────────────────────────────────────────────────────────────────────

  /** The keys whose value is a record of named schemas. */
  private val DefinitionKeys: Vector[String] = Vector("$defs", "definitions")

  /** `schema` in strict shape, in the order of upstream `_strict_json_schema`. `root` is the schema that a `$ref` names
    * a path in. `refs` are the references that the call resolves now: a reference to one of them is a cycle.
    */
  private def shapeAt(
      schema: DynamicValue.Record,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    for
      defined <- DefinitionKeys.foldLeft[Either[DspyError, DynamicValue.Record]](Right(schema))((acc, key) =>
                   acc.flatMap(shapeNamed(_, key, root, refs))
                 )
      closed    <- close(defined)
      withProps <- shapeProperties(closed, root, refs)
      withItems <- shapeItems(withProps, root, refs)
      withAnyOf <- shapeEach(oneOfAsAnyOf(withItems), "anyOf", root, refs)
      withAllOf <- mergeAllOf(withAnyOf, root, refs)
      resolved  <- resolveRef(dropNullDefault(withAllOf), root, refs)
    yield resolved

  /** Each schema in the record under `key` (`$defs` or `definitions`) in strict shape. */
  private def shapeNamed(
      schema: DynamicValue.Record,
      key   : String,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, key) match
      case Some(named: DynamicValue.Record) =>
        shapeEntries(named, root, refs).map(shaped => schema.updated(key, shaped))
      case _ => Right(schema)

  /** An object schema with no `additionalProperties` gets `additionalProperties: false`. An open map is a `Left`. */
  private def close(schema: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
    if isOpenMap(schema) then
      Left(ValidationError(s"Strict mode does not take an open map: ${JsonDynamic.toUjson(schema)}"))
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
  val OpenMapSchema: DynamicValue.Record = DynamicValue.Record(
    Chunk("type" -> str("object"), "additionalProperties" -> DynamicValue.Primitive(PrimitiveValue.Boolean(true)))
  )

  /** `true` for an object schema whose `additionalProperties` is a schema or `true`. */
  private def isOpenMap(schema: DynamicValue.Record): Boolean =
    hasType(schema, "object") &&
      (DynamicValues.recordGet(schema, "additionalProperties") match
        case Some(_: DynamicValue.Record) | Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))) => true
        case _                                                                                         => false)

  /** Each property in strict shape, and each property name in `required`. */
  private def shapeProperties(
      schema: DynamicValue.Record,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "properties") match
      case Some(properties: DynamicValue.Record) =>
        val required = DynamicValue.Sequence(Chunk.from(properties.fields.toVector.map((name, _) => str(name))))
        shapeEntries(properties, root, refs).map(shaped =>
          schema.updated("required", required).updated("properties", shaped)
        )
      case _ => Right(schema)

  private def shapeItems(
      schema: DynamicValue.Record,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "items") match
      case Some(items: DynamicValue.Record) => shapeAt(items, root, refs).map(shaped => schema.updated("items", shaped))
      case _                                => Right(schema)

  /** Each schema in the list under `key` (`anyOf` or `allOf`) in strict shape. */
  private def shapeEach(
      schema: DynamicValue.Record,
      key   : String,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, key) match
      case Some(list: DynamicValue.Sequence) => Traverse.either(list.elements.toVector)(shapeValue(_, root, refs))
          .map(shaped => schema.updated(key, DynamicValue.Sequence(Chunk.from(shaped))))
      case _ => Right(schema)

  /** An `allOf` with one schema is merged into `schema`, and the keys of that one schema win. */
  private def mergeAllOf(
      schema: DynamicValue.Record,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "allOf") match
      case Some(list: DynamicValue.Sequence) => list.elements.toVector match
          case Vector(single: DynamicValue.Record) =>
            shapeAt(single, root, refs).map(shaped => overlay(removed(schema, "allOf"), shaped.fields.toVector))
          case _ => shapeEach(schema, "allOf", root, refs)
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
        removed(schema, "oneOf").updated("anyOf", DynamicValue.Sequence(before ++ options.elements))
      case _ => schema

  private def dropNullDefault(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "default") match
      case Some(_: DynamicValue.Null.type) => removed(schema, "default")
      case _                               => schema

  /** A `$ref` with other keys is replaced by the schema that it names, in strict shape, and the other keys win. */
  private def resolveRef(
      schema: DynamicValue.Record,
      root  : DynamicValue.Record,
      refs  : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    DynamicValues.recordGet(schema, "$ref") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(ref))) if schema.fields.length > 1 =>
        if refs.contains(ref) then Left(ValidationError(s"The $$ref '$ref' is in a cycle"))
        else
          lookup(root, ref).flatMap {
            case target: DynamicValue.Record => shapeAt(target, root, refs + ref).map(shaped =>
                overlay(shaped, schema.fields.toVector.filterNot((key, _) => key == "$ref"))
              )
            case _ => Right(schema)
          }
      case _ => Right(schema)

  /** The value at the internal path `ref` (`#/a/b`) of `root` (upstream `_resolve_ref`). */
  private def lookup(root: DynamicValue.Record, ref: String): Either[DspyError, DynamicValue] =
    if !ref.startsWith("#/") then
      Left(ValidationError(s"Unexpected $$ref format '$ref'; only internal references are supported"))
    else
      ref.drop(2).split("/").toVector.foldLeft[Either[DspyError, DynamicValue]](Right(root)) { (acc, part) =>
        acc.flatMap {
          case node: DynamicValue.Record => DynamicValues.recordGet(node, part) match
              case Some(_: DynamicValue.Null.type) | None => Left(ValidationError(s"Could not resolve $$ref '$ref'"))
              case Some(value)                            => Right(value)
          case _ => Left(ValidationError(s"Could not resolve $$ref '$ref'"))
        }
      }

  /** Each top-level property in `optionalFields` that does not permit `null` gets a `null` option. */
  private def permitNull(schema: DynamicValue.Record, optionalFields: Set[String]): DynamicValue.Record =
    DynamicValues.recordGet(schema, "properties") match
      case Some(properties: DynamicValue.Record) if optionalFields.nonEmpty =>
        val widened = properties.fields.toVector.map {
          case (name, property: DynamicValue.Record) if optionalFields.contains(name) && !permitsNull(property) =>
            name -> DynamicValue.Record(Chunk.single(
              "anyOf" ->
                DynamicValue.Sequence(Chunk(property, DynamicValue.Record(Chunk.single("type" -> str("null")))))
            ))
          case other => other
        }
        schema.updated("properties", DynamicValue.Record(Chunk.from(widened)))
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
      root   : DynamicValue.Record,
      refs   : Set[String]
  ): Either[DspyError, DynamicValue.Record] =
    Traverse.either(entries.fields.toVector)((name, value) => shapeValue(value, root, refs).map(name -> _))
      .map(shaped => DynamicValue.Record(Chunk.from(shaped)))

  private def shapeValue(
      value: DynamicValue,
      root : DynamicValue.Record,
      refs : Set[String]
  ): Either[DspyError, DynamicValue] =
    value match
      case schema: DynamicValue.Record => shapeAt(schema, root, refs)
      case other                       => Right(other)

  /** `base` with each entry of `entries`: a key of `base` takes the new value in place, and a new key goes at the end.
    */
  private def overlay(base: DynamicValue.Record, entries: Vector[(String, DynamicValue)]): DynamicValue.Record =
    entries.foldLeft(base)((acc, entry) => acc.updated(entry._1, entry._2))

  private def removed(schema: DynamicValue.Record, key: String): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(schema.fields.toVector.filterNot((name, _) => name == key)))

  // ── A schema from the field types ─────────────────────────────────────────────────────────────────────────────

  /** A `list[T]` type: the group is `T`. */
  private val ListOf: Regex = """(?i)list\[(.+)\]""".r

  private def fieldSchema(field: FieldSpec): Option[DynamicValue.Record] =
    typeSchema(field.typeRef).map { schema =>
      val withEnum =
        if field.enumValues.nonEmpty && field.typeRef == TypeRef.string then
          schema.updated("enum", DynamicValue.Sequence(Chunk.from(field.enumValues.map(str))))
        else schema
      field.defaultValue.fold(withEnum)(value =>
        withEnum.updated("default", JsonDynamic.fromUjson(JsonDynamic.toUjson(value)))
      )
    }

  private def typeSchema(typeRef: TypeRef): Option[DynamicValue.Record] =
    typeRef match
      case TypeRef.string         => Some(typed("string"))
      case TypeRef.int            => Some(typed("integer"))
      case TypeRef.double         => Some(typed("number"))
      case TypeRef.bool           => Some(typed("boolean"))
      case TypeRef.list           => Some(array(DynamicValue.Record.empty))
      case TypeRef(ListOf(inner)) => typeSchema(TypeRef.fromToken(inner)).map(array)
      case _                      => None

  private def typed(name: String): DynamicValue.Record = DynamicValue.Record(Chunk.single("type" -> str(name)))

  private def array(items: DynamicValue.Record): DynamicValue.Record =
    DynamicValue.Record(Chunk("type" -> str("array"), "items" -> items))

  private def str(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))
