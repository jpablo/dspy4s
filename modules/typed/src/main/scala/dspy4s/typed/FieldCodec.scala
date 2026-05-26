package dspy4s.typed

import dspy4s.core.contracts.{DspyError, FieldMetadata, TypeRef, ValidationError}
import zio.blocks.schema.Schema
import scala.deriving.Mirror

/** Field-level codec at the typed-layer boundary. Maps a Scala type to a
  * `TypeRef` for adapter metadata, decodes raw prediction values (already
  * deserialized by the adapter — typically `String`, `Int`, `Boolean`,
  * `Double`, or an existing typed value) into the target Scala type, and
  * encodes typed inputs back into the untyped `Map[String, Any]` carried
  * through `ProgramCall`.
  *
  * Decodes from `Any`, not from JSON bytes — adapters are responsible for
  * extracting raw field values from the LM response. For enums, the
  * built-in `EnumDecoder` accepts either an already-typed enum value (e.g.
  * when an adapter has pre-resolved the case) or a flat string carrying
  * the case name. For structured product values, the low-priority
  * schema-backed decoder delegates to `kyo-schema` through
  * `Structure.Value`. */
trait FieldCodec[A]:
  def typeRef: TypeRef
  def decode(raw: Any): Either[DspyError, A]
  def encode(value: A): Any

  /** Optional per-field metadata surfaced into `FieldSpec.metadata` at
    * `Shape` derivation time. Well-known keys live in
    * `dspy4s.core.contracts.FieldMetadata` so adapter readers and decoder
    * writers share one contract. Defaults to empty. */
  def metadata: Map[String, String] = Map.empty

object FieldCodec extends LowPriorityFieldCodecs:

  // ── Primitive instances ──────────────────────────────────────────────────

  given FieldCodec[String] with
    val typeRef: TypeRef = TypeRef.string
    def decode(raw: Any): Either[DspyError, String] = raw match
      case s: String                                       => Right(s)
      case b: Boolean                                      => Right(b.toString)
      case n @ (_: Int | _: Long | _: Float | _: Double)   => Right(n.toString)
      case other =>
        Left(ValidationError(s"Cannot decode as String: $other"))
    def encode(value: String): Any = value

  given FieldCodec[Int] with
    val typeRef: TypeRef = TypeRef.int
    def decode(raw: Any): Either[DspyError, Int] = raw match
      case n: Int                                            => Right(n)
      case n: Long if n >= Int.MinValue && n <= Int.MaxValue => Right(n.toInt)
      case s: String =>
        s.trim.toIntOption.toRight(
          ValidationError(s"Cannot decode as Int: '$s'")
        )
      case other => Left(ValidationError(s"Cannot decode as Int: $other"))
    def encode(value: Int): Any = value

  given FieldCodec[Double] with
    val typeRef: TypeRef = TypeRef.double
    def decode(raw: Any): Either[DspyError, Double] = raw match
      case n: Double => Right(n)
      case n: Float  => Right(n.toDouble)
      case n: Int    => Right(n.toDouble)
      case n: Long   => Right(n.toDouble)
      case s: String =>
        s.trim.toDoubleOption.toRight(
          ValidationError(s"Cannot decode as Double: '$s'")
        )
      case other => Left(ValidationError(s"Cannot decode as Double: $other"))
    def encode(value: Double): Any = value

  given FieldCodec[Boolean] with
    val typeRef: TypeRef = TypeRef.bool
    def decode(raw: Any): Either[DspyError, Boolean] = raw match
      case b: Boolean => Right(b)
      case s: String =>
        s.trim.toLowerCase match
          case "true"  => Right(true)
          case "false" => Right(false)
          case _       =>
            Left(ValidationError(s"Cannot decode as Boolean: '$s'"))
      case other => Left(ValidationError(s"Cannot decode as Boolean: $other"))
    def encode(value: Boolean): Any = value

  // ── zio-blocks Schema interop ───────────────────────────────────────────

  /** Builds a field decoder from a `zio.blocks.schema.Schema[A]`. This is the bridge for
    * structured/nested values: adapter-produced `Map` / `Seq` / primitive values flow through
    * [[ZioSchemaCodec]] which normalizes them to `DynamicValue` and decodes via the schema. */
  inline def fromSchema[A](
      typeRef: TypeRef = TypeRef.json,
      metadata: Map[String, String] = Map.empty
  )(using schema: Schema[A]): FieldCodec[A] =
    val capturedTypeRef = typeRef
    val capturedMetadata = metadata
    new FieldCodec[A]:
      val typeRef: TypeRef = capturedTypeRef
      override val metadata: Map[String, String] = capturedMetadata
      def encode(value: A): Any =
        ZioSchemaCodec.dynamicToAny(schema.toDynamicValue(value))
      def decode(raw: Any): Either[DspyError, A] =
        val dyn = ZioSchemaCodec.anyToDynamic(raw, schema.reflect)
        schema.fromDynamicValue(dyn).left.map(err => ValidationError(err.toString))

  /** One-line companion helper for DSPy-style flat enum fields.
    *
    * {{{
    * enum Emotion:
    *   case joy, sadness
    *
    * object Emotion extends FieldCodec.FlatEnum[Emotion]
    * }}}
    *
    * The companion provides both `FieldCodec[Emotion]` (for the typed-layer field-metadata + decoding
    * surface) and `Schema[Emotion]` (zio-blocks-derived; produces a `Variant` reflect that
    * [[ZioSchemaCodec.dynamicToAny]] flattens to a case-name string at the adapter boundary).
    */
  trait FlatEnum[A <: scala.reflect.Enum]:
    inline given fieldCodec(using Mirror.SumOf[A]): FieldCodec[A] =
      FieldCodec.derived[A]

    inline given schema: Schema[A] = Schema.derived

  // ── Scala enum derivation ────────────────────────────────────────────────
  //
  // Decodes enums from their `.toString` form (which equals the case name
  // for parameterless cases). Phase 0 finding: kyo-schema's default enum
  // wire form uses `{"caseName":{}}`. The typed-layer boundary accepts
  // raw strings instead — adapters that produce discriminated objects can
  // transform before handoff, or callers can pass the typed enum value
  // directly.

  /** Enables `derives FieldCodec` on Scala enums. Decodes case names from
    * strings or accepts already-typed enum values. */
  inline def derived[A <: scala.reflect.Enum](using m: Mirror.SumOf[A]): FieldCodec[A] =
    new EnumDecoder[A](EnumInfo.derived[A])

  /** Extracted from `derived` so the inline def doesn't duplicate the
    * anonymous class at each call site. Package-private so inline expansion
    * sites outside `FieldCodec` (but inside `dspy4s.typed`) can reach it.
    *
    * The wire form for enum fields is a plain `String` — `typeRef =
    * TypeRef.string` reflects that honestly. The allowed-case constraint
    * is surfaced via `metadata(FieldMetadata.EnumCases)` so adapters can
    * render it into the prompt. */
  private[typed] final class EnumDecoder[A](
      info: EnumInfo[A]
  ) extends FieldCodec[A]:

    val typeRef: TypeRef = TypeRef.string

    override val metadata: Map[String, String] = Map(
      FieldMetadata.EnumCases -> info.caseNames.mkString(","),
      FieldMetadata.EnumName  -> info.displayName
    )

    def decode(raw: Any): Either[DspyError, A] = raw match
      case a if info.cases.contains(a) => Right(a.asInstanceOf[A])
      case s: String =>
        info.byName.get(s) match
          case Some(c) => Right(c)
          case None =>
            Left(ValidationError(
              s"Cannot decode as ${info.displayName}: '$s' is not one of ${info.caseNames.mkString(", ")}"
            ))
      case other =>
        Left(ValidationError(s"Cannot decode as ${info.displayName}: $other"))

    /** Encode via the matched case name rather than `value.toString` so a
      * user-overridden `toString` can't drift away from the decode contract
      * or the prompt-rendered allowed set. */
    def encode(value: A): Any =
      info.byValue.getOrElse(value, value.toString)

trait LowPriorityFieldCodecs:

  /** Compositional codecs for standard container fields. These are kept
    * explicit rather than making every `Schema[A]` a `FieldCodec[A]`, so
    * arbitrary unsupported classes still fail with a clear "No
    * FieldCodec" error while JSON-like collection shapes work out of the
    * box.
    */
  given list[A](using item: FieldCodec[A]): FieldCodec[List[A]] with
    val typeRef: TypeRef = TypeRef.json
    def decode(raw: Any): Either[DspyError, List[A]] =
      decodeIterable(raw, "List")(item).map(_.toList)
    def encode(value: List[A]): Any =
      value.map(item.encode)

  given seq[A](using item: FieldCodec[A]): FieldCodec[Seq[A]] with
    val typeRef: TypeRef = TypeRef.json
    def decode(raw: Any): Either[DspyError, Seq[A]] =
      decodeIterable(raw, "Seq")(item)
    def encode(value: Seq[A]): Any =
      value.map(item.encode)

  given vector[A](using item: FieldCodec[A]): FieldCodec[Vector[A]] with
    val typeRef: TypeRef = TypeRef.json
    def decode(raw: Any): Either[DspyError, Vector[A]] =
      decodeIterable(raw, "Vector")(item).map(_.toVector)
    def encode(value: Vector[A]): Any =
      value.map(item.encode)

  given set[A](using item: FieldCodec[A]): FieldCodec[Set[A]] with
    val typeRef: TypeRef = TypeRef.json
    def decode(raw: Any): Either[DspyError, Set[A]] =
      decodeIterable(raw, "Set")(item).map(_.toSet)
    def encode(value: Set[A]): Any =
      value.map(item.encode)

  given map[K, V](using
      key: FieldCodec[K],
      itemValue: FieldCodec[V]
  ): FieldCodec[Map[K, V]] with
    val typeRef: TypeRef = TypeRef.json
    def decode(raw: Any): Either[DspyError, Map[K, V]] =
      raw match
        case map: collection.Map[?, ?] =>
          map.iterator.foldLeft(Right(Map.empty[K, V]): Either[DspyError, Map[K, V]]) {
            case (Left(err), _) => Left(err)
            case (Right(acc), (rawKey, rawValue)) =>
              for
                decodedKey <- key.decode(rawKey).left.map(err =>
                  ValidationError(s"Cannot decode Map key '$rawKey': ${err.message}")
                )
                decodedValue <- itemValue.decode(rawValue).left.map(err =>
                  ValidationError(s"Cannot decode Map value for key '$rawKey': ${err.message}")
                )
              yield acc.updated(decodedKey, decodedValue)
          }
        case other =>
          Left(ValidationError(s"Cannot decode as Map: $other"))
    def encode(value: Map[K, V]): Any =
      value.map { (k, v) => key.encode(k) -> itemValue.encode(v) }

  given option[A](using item: FieldCodec[A]): FieldCodec[Option[A]] with
    val typeRef: TypeRef = TypeRef.json
    def decode(raw: Any): Either[DspyError, Option[A]] =
      raw match
        case null => Right(None)
        case value =>
          item.decode(value).map(Some(_))
    def encode(value: Option[A]): Any =
      value.map(item.encode).orNull

  private def decodeIterable[A](
      raw: Any,
      typeName: String
  )(item: FieldCodec[A]): Either[DspyError, Seq[A]] =
    raw match
      case values: Iterable[?] =>
        values.zipWithIndex.foldLeft(Right(Vector.empty[A]): Either[DspyError, Vector[A]]) {
          case (Left(err), _) => Left(err)
          case (Right(acc), (rawItem, index)) =>
            item.decode(rawItem).left
              .map(err => ValidationError(s"Cannot decode $typeName element $index: ${err.message}"))
              .map(acc :+ _)
        }
      case other =>
        Left(ValidationError(s"Cannot decode as $typeName: $other"))

  /** Fallback decoder for product types that have a `zio.blocks.schema.Schema[A]` but no more specific
    * dspy4s `FieldCodec[A]`. Restricted to product types so an arbitrary undecodable class still reports
    * "No FieldCodec" instead of triggering Schema's generic derivation error. Kept low-priority so
    * primitive and enum decoders above continue to define the default flat-field behavior. */
  inline given schemaBackedProduct[A <: Product](using schema: Schema[A]): FieldCodec[A] =
    FieldCodec.fromSchema[A]()(using schema)
