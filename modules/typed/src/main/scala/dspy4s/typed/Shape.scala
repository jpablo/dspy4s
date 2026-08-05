package dspy4s.typed

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, NotFoundError}
import dspy4s.core.algebra.{IsEq, Law, <->}
import zio.blocks.schema.{DynamicValue, Schema}

/** A schema-aware view of a user type `A`, used as the input or output of a `Signature`. Lists fields in declaration
  * order, converts each to a `FieldSpec` for the untyped `SignatureLayout`, and encodes / decodes typed values against
  * the `DynamicValue.Record` spine carried through `ProgramCall`, adapters, and `RawPrediction`.
  *
  * The codec spine is `DynamicValue.Record` end-to-end: `Shape.encode` produces one, `Shape.decode` consumes one, and
  * adapters speak the same intermediate. There is no `Map[String, Any]` round-trip.
  */
trait Shape[A]:
  def fieldSpecs: Vector[FieldSpec]

  /** Encode a typed value to a `DynamicValue.Record` (the codec-spine intermediate). */
  def encode(value: A): DynamicValue.Record

  /** Decode a typed value from a `DynamicValue.Record` produced by an adapter or supplied by user code. */
  def decode(raw: DynamicValue.Record): Either[DspyError, A]

  /** Render this shape as a JSON Schema string suitable for prompt instructions to LMs that follow structured-output
    * hints. Returns `None` for shapes that don't have a backing `zio.blocks.schema.Schema` (e.g. `MapShape` from
    * `Signature.fromStringDynamic`); adapters that use this fall back to their default natural-language instruction in
    * that case.
    */
  def jsonSchemaString: Option[String] = None

/** A [[Shape]] whose typed carrier contains only values accepted by its decoder. Schema-backed product shapes have this
  * property; [[Shape.MapShape]] deliberately does not, because a plain `DynamicValue.Record` can omit fields that its
  * runtime layout requires.
  */
trait RoundTripShape[A] extends Shape[A]:

  /** Encoding followed by decoding recovers the typed value. There is deliberately no law in the opposite direction:
    * decoding may normalize LM-produced wire values and may accept records with fields outside this shape.
    */
  @Law("decoding an encoded value recovers the typed value")
  final def decodeEncode(value: A): IsEq[Either[DspyError, A]] =
    decode(encode(value)) <-> Right(value)

object Shape:

  /** Derive the structural schema determined by `A` itself, without allowing ambient `Schema` instances to replace the
    * schema of `A` or any nested field. This is the canonical path used wherever a Scala type is treated as an object
    * at the record boundary.
    *
    * zio-blocks' `Schema.derived` deliberately reuses schemas found in the expansion scope. The two ambiguous generic
    * blockers below make those open-world searches fail, causing the derivation macro to recurse structurally instead.
    * Exact schemas for the closed primitive leaf set remain available; the `DynamicValue.Record` exception is the
    * framework's canonical narrowing of zio-blocks' dynamic schema. Array-like types are deliberately excluded because
    * their schema derivation consumes an open `ClassTag`; custom array semantics belong behind an explicitly branded
    * shape. Consequently ambient evidence cannot change the meaning of the same object type.
    */
  transparent inline def canonicalSchema[A]: Schema[A] =
    @annotation.unused
    given Schema[DynamicValue] = Schema.dynamic
    @annotation.unused
    given Schema[DynamicValue.Record] = dspy4s.core.contracts.recordSchema
    @annotation.unused
    given Schema[Unit] = Schema.unit
    @annotation.unused
    given Schema[Boolean] = Schema.boolean
    @annotation.unused
    given Schema[Byte] = Schema.byte
    @annotation.unused
    given Schema[Short] = Schema.short
    @annotation.unused
    given Schema[Int] = Schema.int
    @annotation.unused
    given Schema[Long] = Schema.long
    @annotation.unused
    given Schema[Float] = Schema.float
    @annotation.unused
    given Schema[Double] = Schema.double
    @annotation.unused
    given Schema[Char] = Schema.char
    @annotation.unused
    given Schema[String] = Schema.string
    @annotation.unused
    given Schema[BigInt] = Schema.bigInt
    @annotation.unused
    given Schema[BigDecimal] = Schema.bigDecimal
    @annotation.unused
    given Schema[java.time.DayOfWeek] = Schema.dayOfWeek
    @annotation.unused
    given Schema[java.time.Duration] = Schema.duration
    @annotation.unused
    given Schema[java.time.Instant] = Schema.instant
    @annotation.unused
    given Schema[java.time.LocalDate] = Schema.localDate
    @annotation.unused
    given Schema[java.time.LocalDateTime] = Schema.localDateTime
    @annotation.unused
    given Schema[java.time.LocalTime] = Schema.localTime
    @annotation.unused
    given Schema[java.time.Month] = Schema.month
    @annotation.unused
    given Schema[java.time.MonthDay] = Schema.monthDay
    @annotation.unused
    given Schema[java.time.OffsetDateTime] = Schema.offsetDateTime
    @annotation.unused
    given Schema[java.time.OffsetTime] = Schema.offsetTime
    @annotation.unused
    given Schema[java.time.Period] = Schema.period
    @annotation.unused
    given Schema[java.time.Year] = Schema.year
    @annotation.unused
    given Schema[java.time.YearMonth] = Schema.yearMonth
    @annotation.unused
    given Schema[java.time.ZoneId] = Schema.zoneId
    @annotation.unused
    given Schema[java.time.ZoneOffset] = Schema.zoneOffset
    @annotation.unused
    given Schema[java.time.ZonedDateTime] = Schema.zonedDateTime
    @annotation.unused
    given Schema[java.util.Currency] = Schema.currency
    @annotation.unused
    given Schema[java.util.UUID] = Schema.uuid

    // Both definitions are intentionally applicable. An ambient Schema[T] is therefore not selected by
    // zio-blocks' nested implicit search; its macro falls back to structural derivation for T instead.
    @annotation.unused
    given blockAmbientSchema1[T]: Schema[T] =
      throw new IllegalStateException("canonical schema blocker must never be evaluated")
    @annotation.unused
    given blockAmbientSchema2[T]: Schema[T] =
      throw new IllegalStateException("canonical schema blocker must never be evaluated")
    @annotation.unused
    given blockAmbientClassTag1[T]: scala.reflect.ClassTag[T] =
      throw new IllegalStateException("canonical class-tag blocker must never be evaluated")
    @annotation.unused
    given blockAmbientClassTag2[T]: scala.reflect.ClassTag[T] =
      throw new IllegalStateException("canonical class-tag blocker must never be evaluated")

    Schema.derived[A]

  /** Canonical case-class/product shape. This path ignores ambient schemas and is closed over structural derivation,
    * making it suitable for type-indexed record-boundary objects.
    */
  inline def canonicalDerived[A]: RoundTripShape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](using canonicalSchema[A])

  /** A `Shape[DynamicValue.Record]` for the dynamic path (`Signature.fromStringDynamic`), where the DSL carries no
    * static schema so the "typed" value stays at the spine type. `encode` is the identity; `decode` only validates that
    * every field listed in `fieldSpecs` is present in the raw record (no per-field coercion -- that happens upstream in
    * the adapter / `ZioSchemaCodec.normalize` for schema-backed shapes).
    */
  final class MapShape(
      override val fieldSpecs: Vector[FieldSpec]
  ) extends Shape[DynamicValue.Record]:

    def encode(value: DynamicValue.Record): DynamicValue.Record = value

    def decode(raw: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
      val present = DynamicValues.recordKeys(raw).toSet
      val missing = fieldSpecs.iterator.map(_.name).filterNot(present.contains).toList
      if missing.nonEmpty then
        Left(NotFoundError(
          resource = "prediction_field",
          message = s"Missing required fields: ${missing.mkString(", ")}"
        ))
      else Right(raw)

  /** A `Shape` for a (named-)tuple type `A`, fully backed by a zio-blocks `Schema[A]` derived for that tuple. Used by
    * the `Signature.of[Spec]` / `from` / `fromType` macros, which hand callers a named-tuple type, e.g.
    * `(sentence: String)` for inputs and `(sentiment: Emotion)` for outputs. zio-blocks bridges named-tuple <-> tuple
    * internally (`NamedTuple.toTuple` + register construction), so there is no reflective `productIterator` /
    * `Tuple.fromArray` cast. `fieldSpecs` (names and wire `typeRef`s) are derived from the schema's `Reflect`; the
    * enclosing `SignatureLayout` determines whether they belong to its input or output cohort. The decode path reuses
    * [[ZioSchemaCodec]]'s LM-string coercion, the same path the case-class shapes use.
    */
  final class SchemaTupleShape[A](
      schema: Schema[A]
  ) extends RoundTripShape[A]:
    private val delegate: RoundTripShape[A]                      = ZioSchemaCodec.derivedFromZioSchema[A](using schema)
    val fieldSpecs: Vector[FieldSpec]                            = delegate.fieldSpecs
    def encode(value: A): DynamicValue.Record                    = delegate.encode(value)
    def decode(raw  : DynamicValue.Record): Either[DspyError, A] = delegate.decode(raw)
    override def jsonSchemaString: Option[String]                = delegate.jsonSchemaString

  /** Derives a `Shape[A]` from any case class / product type with a `zio.blocks.schema.Schema[A]` in scope. zio-blocks
    * owns product encode/decode; dspy4s derives the DSPy-facing field metadata from the same structural Reflect
    * description (see [[ZioSchemaCodec]] for the metadata story).
    */
  inline def derived[A <: Product](using schema: Schema[A]): RoundTripShape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A]
