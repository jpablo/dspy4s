package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json
import zio.blocks.schema.{DynamicValue, Schema}

import scala.NamedTuple
import scala.deriving.Mirror

/** Reusable, type-directed output augmentation for programs: prepend a single named field `Name: T` to a program's
  * output `O`, producing a named tuple — **idempotently** (skipped if `O` already declares `Name`) and **cast-free**.
  *
  * `O` is normalized to its named-tuple view via `NamedTuple.From` (identity for named tuples, the field tuple for case
  * classes), so case-class outputs are supported and the result is always a named tuple. A case-class output is
  * therefore *not* echoed back as the same nominal type — that type can't be synthesized with an extra field. The only
  * unsupported `O` is one with no static fields (e.g. `DynamicValue.Record` from `Signature.fromStringDynamic`),
  * handled by the low-priority [[PrependField.fallback]] returning `None`.
  *
  * Used by [[dspy4s.programs.strategies.ChainOfThought]] (`reasoning: String`) and available to any other program that
  * adds an output field (e.g. a future `MultiChainComparison` with `rationale: String`).
  */
object OutputAugmentation:

  /** Type-level membership: is `X` one of the names in tuple `T`? Drives the idempotence check. */
  type Contains[T <: Tuple, X] <: Boolean = T match
    case X *: _     => true
    case _ *: rest  => Contains[rest, X]
    case EmptyTuple => false

  /** The augmented output type — **always a named tuple**. Normalizes `O` to its named-tuple view (`NamedTuple.From`),
    * then prepends `Name: T` unless a `Name` field is already present (idempotent).
    */
  type WithField[O, Name <: String & Singleton, T] = NamedTuple.From[O] match
    case NamedTuple.NamedTuple[n, v] => Contains[n, Name] match
        case true  => NamedTuple.NamedTuple[n, v]
        case false => NamedTuple.NamedTuple[Name *: n, T *: v]

  /** Type-directed construction of [[WithField]] from a base output value, with no `asInstanceOf`. The abstract `Out`
    * member lets each instance pin the exact type it builds (sidestepping match-type/`Mirror` alignment); consumers
    * constrain it via `WithOutput[Name, T, O, WithField[O, Name, T]]`. Instances resolve where `O` is concrete (the
    * program's call site), never against an abstract `O` — there only the fallback would match.
    */
  trait PrependField[Name <: String & Singleton, T, O]:
    type Out
    def prepend(value: T, base: O): Option[Out]

  trait LowPriorityPrependField:
    /** Fallback for an `O` that is neither a named tuple nor a product — e.g. the `DynamicValue.Record` output of
      * `Signature.fromStringDynamic`, which has no static fields. Unsupported: yields `None`.
      */
    given fallback[Name <: String & Singleton, T, O]
        : (PrependField[Name, T, O] { type Out = WithField[O, Name, T] }) =
      new PrependField[Name, T, O]:
        type Out = WithField[O, Name, T]
        def prepend(value: T, base: O): Option[Out] = None

  object PrependField extends LowPriorityPrependField:
    type WithOutput[Name <: String & Singleton, T, O, O2] = PrependField[Name, T, O] { type Out = O2 }

    /** Evidence for the canonical augmentation result [[WithField]]. Use [[WithOutput]] only when the output is
      * intentionally pinned to some other type member.
      */
    type Of[Name <: String & Singleton, T, O] = WithOutput[Name, T, O, WithField[O, Name, T]]

    /** Named-tuple output without a `Name` field: prepend it via the supported whole-tuple constructor
      * `NamedTuple.build`.
      */
    given ntAbsent[Name <: String & Singleton, T, N <: Tuple, V <: Tuple](using
        Contains[N, Name] =:= false
    ): WithOutput[Name, T, NamedTuple.NamedTuple[N, V], NamedTuple.NamedTuple[Name *: N, T *: V]] =
      new PrependField[Name, T, NamedTuple.NamedTuple[N, V]]:
        type Out = NamedTuple.NamedTuple[Name *: N, T *: V]
        def prepend(value: T, base: NamedTuple.NamedTuple[N, V]): Option[Out] =
          Some(NamedTuple.build[Name *: N]()(value *: NamedTuple.toTuple(base)))

    /** Named-tuple output that already declares `Name`: idempotent — kept unchanged. */
    given ntPresent[Name <: String & Singleton, T, N <: Tuple, V <: Tuple](using
        Contains[N, Name] =:= true
    ): WithOutput[Name, T, NamedTuple.NamedTuple[N, V], NamedTuple.NamedTuple[N, V]] =
      new PrependField[Name, T, NamedTuple.NamedTuple[N, V]]:
        type Out = NamedTuple.NamedTuple[N, V]
        def prepend(value: T, base: NamedTuple.NamedTuple[N, V]): Option[Out] = Some(base)

    /** Any product (case class): normalize to its named-tuple view through the `Mirror` and delegate to the named-tuple
      * instances, so case-class outputs are supported and the result is always a named tuple.
      */
    given product[Name <: String & Singleton, T, O <: Product, N <: Tuple, V <: Tuple](using
        m: Mirror.ProductOf[O] { type MirroredElemLabels = N; type MirroredElemTypes = V },
        inner: PrependField[Name, T, NamedTuple.NamedTuple[N, V]]
    ): WithOutput[Name, T, O, inner.Out] =
      new PrependField[Name, T, O]:
        type Out = inner.Out
        def prepend(value: T, base: O): Option[Out] =
          inner.prepend(value, NamedTuple.build[N]()(Tuple.fromProductTyped(base)(using m)))

  /** The general opening-position output augmentation decode (the realizable part of the `Thought`-shaped form): read
    * the augment field `Name` as `T` via `readField`, decode the base output `O` via `shape`, prepend the field
    * (idempotent, cast-free), mapping the fieldless-output case (a string-DSL `Signature`, no static fields) to a
    * structured error, then run an optional post-decode `hook`.
    *
    * [[decodePrepended]] is the `T = String`, identity-hook instance every composite uses today. The one piece of the
    * `Thought` ceiling left additive is the **closing position** (append a self-check field), which needs an
    * `AppendField` dual and currently has no consumer (see `docs/refactor/algebra-2-program-composition.md`). `label`
    * names the producing component in errors.
    */
  def decodeAugmented[O, Name <: String & Singleton, T, Out](
      raw          : DynamicValue.Record,
      shape        : Shape[O],
      fieldName    : Name,
      label        : String,
      signatureName: String,
      readField    : (DynamicValue.Record, Name, String) => Either[DspyError, T],
      hook         : Out => Either[DspyError, Out] = (out: Out) => Right(out)
  )(using prepend: PrependField.WithOutput[Name, T, O, Out]): Either[DspyError, Out] =
    for
      value     <- readField(raw, fieldName, label)
      baseOut   <- shape.decode(raw)
      prepended <- prepend.prepend(value, baseOut).toRight(productOutputRequired(label, signatureName, baseOut))
      augmented <- hook(prepended)
    yield augmented

  /** The shared decode for an opening-`String` output augmentation: the `T = String`, identity-hook instance of
    * [[decodeAugmented]]. The value-level body shared by `ChainOfThought` / `ReAct` / `MultiChainComparison`.
    */
  def decodePrepended[O, Name <: String & Singleton, Out](
      raw          : DynamicValue.Record,
      shape        : Shape[O],
      fieldName    : Name,
      label        : String,
      signatureName: String
  )(using prepend: PrependField.WithOutput[Name, String, O, Out]): Either[DspyError, Out] =
    decodeAugmented[O, Name, String, Out](
      raw,
      shape,
      fieldName,
      label,
      signatureName,
      readField = (record, field, lbl) => DynamicValues.requireString(record, field, lbl)
    )

  /** The reusable output [[Shape]] for an opening-position `String` augmentation — the shape a composite hands its
    * inner `Predict` (`ChainOfThought`'s `reasoning` and the extractor pass of `ReAct`). `field` is prepended to
    * `base`'s specs idempotently by name (matching the type-level [[WithField]] and the layout's `prependOutput` — an
    * unconditional prepend would desync `encode`'s `fieldSpecs.zip(values)`); decode runs [[decodePrepended]].
    *
    * The JSON schema is the schema of `base` with the property of `field` first, in the order of the layout (upstream
    * builds the schema from the augmented signature). The name of `field` is first in `required` when the layout
    * requires the field. The JSON adapter sends this schema as a strict `response_format`. If the schema does not have
    * the property, a model in strict mode cannot write the field. When `base` already has the field, its schema stays
    * as it is. When the schema of `base` has no root `properties`, the shape has no schema, and the adapter builds one
    * from the layout.
    */
  def prependedStringShape[Name <: String & Singleton, O, Out](
      base         : Shape[O],
      field        : dspy4s.core.contracts.FieldSpec,
      fieldName    : Name,
      label        : String,
      signatureName: String
  )(using prepend: PrependField.WithOutput[Name, String, O, Out]): Shape[Out] =
    new Shape[Out]:
      val fieldSpecs: Vector[dspy4s.core.contracts.FieldSpec] =
        if base.fieldSpecs.exists(_.name == field.name) then base.fieldSpecs
        else field +: base.fieldSpecs

      override lazy val jsonSchemaString: Option[String] =
        if base.fieldSpecs.exists(_.name == field.name) then base.jsonSchemaString
        else
          base.jsonSchemaString.flatMap(
            withLeadingProperty(_, field.name, StringProperty, field.defaultValue.isEmpty && !field.optional)
          )

      override def nestedDecisionFields: Vector[String] = base.nestedDecisionFields

      def encode(value: Out): DynamicValue.Record =
        val product: Product = value match
          case p: Product => p
          case _          => throw new IllegalArgumentException(
              s"$label output must be a named-tuple value (a Product); got a non-Product. " +
                "This shape is built only from an augmented Signature, which supplies named tuples."
            )
        val values  = product.productIterator.toVector
        val entries = fieldSpecs.zip(values).map { (spec, raw) =>
          spec.name -> DynamicValues.fromAny(raw)
        }
        DynamicValue.Record(zio.blocks.chunk.Chunk.from(entries))

      def decode(raw: DynamicValue.Record): Either[DspyError, Out] =
        decodePrepended[O, Name, Out](raw, base, fieldName, label, signatureName)(using prepend)

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

  /** The fieldless-output error shared by [[decodePrepended]]'s call sites: a `Signature` whose output has no static
    * fields (the `DynamicValue.Record` output of `Signature.fromStringDynamic`) cannot carry a prepended field, so the
    * augmentation is unsupported.
    */
  private def productOutputRequired(label: String, signatureName: String, baseOut: Any): DspyError =
    ValidationError(
      s"$label requires a product output (named tuple or case class); the signature '$signatureName' has a " +
        s"fieldless output (got ${baseOut.getClass.getSimpleName}). Use a signature " +
        s"(Signature.of / Signature.derived / Signature.fromType)."
    )
