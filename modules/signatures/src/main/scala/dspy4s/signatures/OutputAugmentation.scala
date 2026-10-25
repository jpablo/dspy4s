package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, FieldSpec, ValidationError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json
import zio.blocks.schema.{DynamicValue, Schema}

import scala.NamedTuple
import scala.deriving.Mirror

/** Output augmentation for programs, from the types: put one named field `Name: T` before the fields of the output `O`
  * of a program. The result is a named tuple. When `O` already has a `Name` field, `O` stays the same. The augmentation
  * uses no cast.
  *
  * `NamedTuple.From` gives the named-tuple view of `O`: a named tuple stays the same, and a case class gives the tuple
  * of its fields. Thus a case-class output is supported, and the result is always a named tuple, not the case class: a
  * nominal type cannot get an extra field. The only `O` that is not supported is an `O` with no static fields (for
  * example, the `DynamicValue.Record` of `Signature.fromStringDynamic`). For it, the low-priority
  * [[PrependField.fallback]] gives `None`.
  *
  * [[Signature.prependOutput]] uses it. [[dspy4s.programs.strategies.ChainOfThought]] (`reasoning: String`), `ReAct`,
  * and `MultiChainComparison` (`rationale: String`) call that method.
  */
object OutputAugmentation:

  /** `true` when `X` is one of the names in the tuple `T`. The check that `O` already has the field uses it. */
  type Contains[T <: Tuple, X] <: Boolean = T match
    case X *: _     => true
    case _ *: rest  => Contains[rest, X]
    case EmptyTuple => false

  /** The augmented output type, always a named tuple. It is the named-tuple view of `O` (`NamedTuple.From`), with
    * `Name: T` first. When `O` already has a `Name` field, it is the view of `O`.
    */
  type WithField[O, Name <: String & Singleton, T] = NamedTuple.From[O] match
    case NamedTuple.NamedTuple[n, v] => Contains[n, Name] match
        case true  => NamedTuple.NamedTuple[n, v]
        case false => NamedTuple.NamedTuple[Name *: n, T *: v]

  /** Makes a [[WithField]] value from a base output value, and gets the base output value back, from the types and with
    * no `asInstanceOf`. With the abstract `Out` member, each instance gives the exact type that it makes, thus the
    * match type and the `Mirror` do not have to agree. A caller sets it with `WithOutput[Name, T, O, WithField[O, Name,
    * T]]`. The compiler finds an instance where `O` is concrete (the call site of the program). For an abstract `O`,
    * only the fallback matches, and its `Out` is `Nothing`. Thus code with an abstract `O` must get the evidence from
    * its caller.
    */
  trait PrependField[Name <: String & Singleton, T, O]:
    type Out
    def prepend(value: T, base: O): Option[Out]

    /** The inverse of [[prepend]]: the prepended value and the base value of `out`. The prepended value is `None` when
      * `O` already has the field, because then the base value holds it.
      */
    def split(out: Out): (Option[T], O)

  trait LowPriorityPrependField:
    /** The fallback for an `O` that is not a named tuple and not a product. Such an `O` is not supported: [[prepend]]
      * gives `None`. Thus no augmented value exists, and `Out` is `Nothing`.
      */
    given fallback[Name <: String & Singleton, T, O]: (PrependField[Name, T, O] { type Out = Nothing }) =
      new PrependField[Name, T, O]:
        type Out = Nothing
        def prepend(value: T, base: O): Option[Out] = None
        def split(out    : Out): (Option[T], O)     = out

  object PrependField extends LowPriorityPrependField:
    type WithOutput[Name <: String & Singleton, T, O, O2] = PrependField[Name, T, O] { type Out = O2 }

    /** The evidence for the canonical result [[WithField]]. Use [[WithOutput]] only for an output with a different type
      * member.
      */
    type Of[Name <: String & Singleton, T, O] = WithOutput[Name, T, O, WithField[O, Name, T]]

    /** A named-tuple output with no `Name` field: put the field first with `NamedTuple.build`, which builds the full
      * tuple.
      */
    given ntAbsent[Name <: String & Singleton, T, N <: Tuple, V <: Tuple](using
        Contains[N, Name] =:= false
    ): WithOutput[Name, T, NamedTuple.NamedTuple[N, V], NamedTuple.NamedTuple[Name *: N, T *: V]] =
      new PrependField[Name, T, NamedTuple.NamedTuple[N, V]]:
        type Out = NamedTuple.NamedTuple[Name *: N, T *: V]
        def prepend(value: T, base: NamedTuple.NamedTuple[N, V]): Option[Out] =
          Some(NamedTuple.build[Name *: N]()(value *: NamedTuple.toTuple(base)))
        def split(out: Out): (Option[T], NamedTuple.NamedTuple[N, V]) =
          val values = NamedTuple.toTuple(out)
          (Some(values.head), NamedTuple.build[N]()(values.tail))

    /** A named-tuple output that already has a `Name` field: the output stays the same. */
    given ntPresent[Name <: String & Singleton, T, N <: Tuple, V <: Tuple](using
        Contains[N, Name] =:= true
    ): WithOutput[Name, T, NamedTuple.NamedTuple[N, V], NamedTuple.NamedTuple[N, V]] =
      new PrependField[Name, T, NamedTuple.NamedTuple[N, V]]:
        type Out = NamedTuple.NamedTuple[N, V]
        def prepend(value: T, base: NamedTuple.NamedTuple[N, V]): Option[Out] = Some(base)
        def split(out    : Out): (Option[T], NamedTuple.NamedTuple[N, V])     = (None, out)

    /** A product (a case class): the `Mirror` gives its named-tuple view, and the named-tuple instances do the rest.
      * Thus a case-class output is supported, and the result is always a named tuple.
      */
    given product[Name <: String & Singleton, T, O <: Product, N <: Tuple, V <: Tuple](using
        m: Mirror.ProductOf[O] { type MirroredElemLabels = N; type MirroredElemTypes = V },
        inner: PrependField[Name, T, NamedTuple.NamedTuple[N, V]]
    ): WithOutput[Name, T, O, inner.Out] =
      new PrependField[Name, T, O]:
        type Out = inner.Out
        def prepend(value: T, base: O): Option[Out] =
          inner.prepend(value, NamedTuple.build[N]()(Tuple.fromProductTyped(base)(using m)))
        def split(out: Out): (Option[T], O) =
          val (value, fields) = inner.split(out)
          (value, m.fromTuple(NamedTuple.toTuple(fields)))

  /** The output [[Shape]] of [[Signature.prependOutput]]: the `String` field `field` first, and then the fields of
    * `base`. The shape puts `field` before the specs of `base` only when `base` has no field with that name, as the
    * type-level [[WithField]] and the `prependOutput` of the layout do.
    *
    * The encode gets the base value back with [[PrependField.split]], encodes it with `base`, and puts the `String`
    * field first. Thus each base field has the encoding of its schema (an `Option`, a nested record).
    *
    * The decode reads `field` as a `String`, decodes the base output with `base`, and puts the field first. When the
    * output of `base` has no static fields (the `DynamicValue.Record` output of `Signature.fromStringDynamic`), the
    * decode gives a `ValidationError`. `signatureName` names the signature in the errors.
    *
    * The JSON schema is the schema of `base` with the property of `field` first, in the order of the layout (upstream
    * builds the schema from the augmented signature). The name of `field` is first in `required` when the layout
    * requires the field. The JSON adapter sends this schema as a strict `response_format`. If the schema does not have
    * the property, a model in strict mode cannot write the field. When `base` already has the field, its schema stays
    * as it is. When the schema of `base` has no root `properties`, the shape has no schema, and the adapter builds one
    * from the layout.
    */
  private[signatures] def prepended[Name <: String & Singleton, O, Out](
      base         : Shape[O],
      field        : FieldSpec,
      signatureName: String
  )(using prepend: PrependField.WithOutput[Name, String, O, Out]): Shape[Out] =
    val baseHasField = base.fieldSpecs.exists(_.name == field.name)
    val withField    = Shape.pair(Shape.string(field, signatureName), base)
    new Shape[Out]:
      val fieldSpecs: Vector[FieldSpec] = if baseHasField then base.fieldSpecs else withField.fieldSpecs

      override lazy val jsonSchemaString: Option[String] =
        if baseHasField then base.jsonSchemaString
        else
          base.jsonSchemaString.flatMap(
            withLeadingProperty(_, field.name, StringProperty, field.defaultValue.isEmpty && !field.optional)
          )

      override def nestedDecisionFields: Vector[String] = base.nestedDecisionFields

      // `base` encodes the base value with its schema. When `base` has the field, the base value holds it.
      def encode(value: Out): DynamicValue.Record =
        prepend.split(value) match
          case (Some(text), baseValue) => withField.encode((text, baseValue))
          case (None, baseValue)       => base.encode(baseValue)

      def decode(raw: DynamicValue.Record): Either[DspyError, Out] =
        withField.decode(raw).flatMap { (text, baseOut) =>
          prepend.prepend(text, baseOut).toRight(productOutputRequired(field.name, signatureName, baseOut))
        }

  /** The JSON schema of a `String` field, as zio-blocks writes it for the field of a record. */
  private lazy val StringProperty: Json = Schema.string.toJsonSchema.toJson

  private val DefsPrefix: String = "#/$defs/"

  /** The JSON schema text `schema` with the property `name: property` first in `properties`. When `required` is true,
    * `name` is also first in `required`. A root `$ref` to an entry of `$defs` (the schema of a recursive type) becomes
    * that entry, and `$defs` stays: the augmented output is not the recursive type. The text has the form of
    * [[ZioSchemaCodec.jsonSchemaForModel]]. The result is `None` when `schema` is not a JSON object with `properties`.
    */
  private def withLeadingProperty(schema: String, name: String, property: Json, required: Boolean): Option[String] =
    for
      root       <- Json.parse(schema).toOption.collect { case root: Json.Object => inlinedRoot(root) }
      properties <- member(root, "properties").collect { case properties: Json.Object => properties }
    yield
      val others       = properties.value.filterNot((key, _) => key == name)
      val withProperty = updated(root, "properties", Json.Object((name -> property) +: others))
      val augmented    =
        if !required then withProperty
        else
          val names = member(root, "required").collect { case names: Json.Array => names.value }
            .getOrElse(Chunk.empty)
            .filterNot {
              case Json.String(other) => other == name
              case _                  => false
            }
          updated(withProperty, "required", Json.Array(Json.String(name) +: names))
      augmented.toString

  /** `root` with the entry of `$defs` that its `$ref` names in place of the `$ref`. Each other key of `root` comes
    * after the keys of the entry. A root with no such `$ref` stays as it is.
    */
  private def inlinedRoot(root: Json.Object): Json.Object =
    val entry =
      for
        ref         <- member(root, "$ref").collect { case Json.String(ref) if ref.startsWith(DefsPrefix) => ref }
        definitions <- member(root, "$defs").collect { case definitions: Json.Object => definitions }
        found       <- member(definitions, ref.stripPrefix(DefsPrefix)).collect { case found: Json.Object => found }
      yield found
    entry.fold(root)(found => Json.Object(found.value ++ root.value.filterNot((key, _) => key == "$ref")))

  private def member(obj: Json.Object, key: String): Option[Json] =
    obj.value.find((name, _) => name == key).map((_, value) => value)

  /** `obj` with `value` at `key`. An existing key keeps its position, and a new key goes at the end. */
  private def updated(obj: Json.Object, key: String, value: Json): Json.Object =
    if obj.value.exists((name, _) => name == key) then
      Json.Object(obj.value.map((name, old) => if name == key then name -> value else name -> old))
    else Json.Object(obj.value :+ (key -> value))

  /** The error for an output with no static fields. The output of a `Signature` with no static fields (the
    * `DynamicValue.Record` output of `Signature.fromStringDynamic`) cannot hold a prepended field.
    */
  private def productOutputRequired(fieldName: String, signatureName: String, baseOut: Any): DspyError =
    ValidationError(
      s"The output '$fieldName' requires a product output (named tuple or case class); the signature " +
        s"'$signatureName' has a fieldless output (got ${baseOut.getClass.getSimpleName}). Use a signature " +
        s"(Signature.of / Signature.derived / Signature.fromType)."
    )
