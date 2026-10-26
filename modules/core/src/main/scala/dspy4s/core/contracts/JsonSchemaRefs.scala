package dspy4s.core.contracts

import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The internal references of a JSON schema: a `$ref` to a path from the root of the schema, for example
  * `{"$ref": "#/$defs/Node"}`. The signatures module writes such a reference for a recursive type, and the adapters
  * read it, so the two modules use these rules.
  */
private[dspy4s] object JsonSchemaRefs:

  /** The value at the internal path `ref` (`#/a/b`) of `root` (upstream `_resolve_ref`). A reference that does not
    * start with `#/`, or a path to no value or to a null, is a `Left`.
    */
  def lookup(root: DynamicValue.Record, ref: String): Either[DspyError, DynamicValue] =
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

  /** `schema` with the schema that its root `$ref` names in place of the `$ref`. The keys of the named schema come
    * first, then each other key of `schema` (for example `$defs`): a key that the named schema has takes the value of
    * `schema` in its position. The root of the schema of a recursive type is a `$ref` to its definition, thus a change
    * to the root `properties` must come after this step. A schema with no root `$ref`, or with a `$ref` that does not
    * name an object, stays as it is.
    */
  def inlineRoot(schema: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.recordGet(schema, "$ref") match
      case Some(DynamicValue.Primitive(PrimitiveValue.String(ref))) => lookup(schema, ref) match
          case Right(target: DynamicValue.Record) =>
            schema.fields.filterNot((key, _) => key == "$ref").foldLeft(target)((acc, entry) =>
              acc.updated(entry._1, entry._2)
            )
          case _ => schema
      case _ => schema
