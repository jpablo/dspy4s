package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, FieldSpec, NotFoundError}
import dspy4s.algebra.{IsEq, Law, <->}
import dspy4s.core.decisions.Score
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

/** A schema-aware view of a user type `A`, used as the input or output of a `Signature`. Lists fields in declaration
  * order, converts each to a `FieldSpec` for the runtime `SignatureLayout`, and encodes / decodes values against the
  * `DynamicValue.Record` interchange format carried through `ProgramCall`, adapters, and `RawPrediction`.
  *
  * The interchange format is `DynamicValue.Record` end-to-end: `Shape.encode` produces one, `Shape.decode` consumes
  * one, and adapters speak the same intermediate. There is no `Map[String, Any]` round-trip.
  */
trait Shape[A]:
  def fieldSpecs: Vector[FieldSpec]

  /** Encode a value to a `DynamicValue.Record` (the interchange format). */
  def encode(value: A): DynamicValue.Record

  /** Decode a value from a `DynamicValue.Record` produced by an adapter or supplied by user code. */
  def decode(raw: DynamicValue.Record): Either[DspyError, A]

  /** The JSON schema of this shape, as text, for the prompt of an LM that follows a structured output. `None` for a
    * shape that has no `zio.blocks.schema.Schema` (for example, the `MapShape` of `Signature.fromStringDynamic`). For
    * such a shape, an adapter uses its default instruction in natural language.
    */
  def jsonSchemaString: Option[String] = None

  /** Experimental. The names of the fields whose type holds a `Noul`, a `Score`, or a `Choice` inside an `Option`, a
    * collection, or a map, for example `List[Noul]` (upstream `extract_custom_type_from_annotation`). Such a field is
    * not a decision output, thus it gets no evidence decoding. The default is empty: a shape with no schema cannot
    * tell.
    */
  def nestedDecisionFields: Vector[String] = Vector.empty

/** A [[Shape]] whose carrier contains only values accepted by its decoder. Schema-backed product shapes have this
  * property; [[Shape.MapShape]] deliberately does not, because a plain `DynamicValue.Record` can omit fields that its
  * runtime layout requires.
  */
trait RoundTripShape[A] extends Shape[A]:

  /** Encoding followed by decoding recovers the value. There is deliberately no law in the opposite direction: decoding
    * may normalize LM-produced wire values and may accept records with fields outside this shape.
    */
  @Law("decoding an encoded value recovers the value")
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
    *
    * A `Score[L]` (experimental) has no value of `L`, thus its structural schema does not hold the levels of `L`. The
    * given for `Score[L]` below derives the schema of `L` structurally, and keeps its case names as the levels.
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
    @annotation.unused
    given canonicalScoreSchema[L](using levels: StructuralSchema[L]): Schema[Score[L]] = Score.schemaOf(levels.schema)

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

  /** The structural schema of `A`: the schema that `Schema.derived` makes where the canonical blockers are in scope.
    * The inline given expands where the search finds it, thus in [[canonicalSchema]] the derivation of `A` is also
    * canonical. [[canonicalSchema]] uses it for the levels of a `Score[L]`.
    *
    * The class is public because [[canonicalSchema]] expands in the package of the caller, and the implicit search
    * there must find the given.
    */
  final class StructuralSchema[A](val schema: Schema[A])

  object StructuralSchema:
    inline given derived[A]: StructuralSchema[A] = StructuralSchema(Schema.derived[A])

  /** The canonical shape of a case class, a tuple, or a named tuple. It ignores the ambient schemas and uses only the
    * structural derivation, thus each type has one meaning at the record boundary. The signature macros use it for
    * their named tuples, for example `(sentence: String)` for the inputs and `(sentiment: Emotion)` for the outputs.
    */
  inline def canonicalDerived[A]: RoundTripShape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A](using canonicalSchema[A])

  /** A `Shape[DynamicValue.Record]` for the dynamic path (`Signature.fromStringDynamic`). The DSL has no static schema,
    * thus the value stays a record. `encode` gives the value as it is. `decode` checks that the record has each
    * required field of `fieldSpecs` (see [[requiredFieldsCheck]]), and adds the default value of each absent field that
    * has one. It does not coerce a field: for a shape with a schema, the adapter and `ZioSchemaCodec.normalize` do
    * that.
    */
  final class MapShape(
      override val fieldSpecs: Vector[FieldSpec]
  ) extends Shape[DynamicValue.Record]:
    private val requireFields = requiredFieldsCheck(fieldSpecs)

    def encode(value: DynamicValue.Record): DynamicValue.Record = value

    def decode(raw: DynamicValue.Record): Either[DspyError, DynamicValue.Record] =
      requireFields(raw).map { _ =>
        val absent = fieldSpecs.flatMap { field =>
          field.defaultValue.filter(_ => !raw.fields.exists(_._1 == field.name)).map(field.name -> _)
        }
        if absent.isEmpty then raw else DynamicValue.Record(raw.fields ++ Chunk.from(absent))
      }

  /** Derives a `Shape[A]` from a case class or another product type with a `zio.blocks.schema.Schema[A]` in scope.
    * zio-blocks encodes and decodes the product. dspy4s gets the field specs from the same `Reflect` (see
    * [[ZioSchemaCodec]]).
    */
  inline def derived[A <: Product](using schema: Schema[A]): RoundTripShape[A] =
    ZioSchemaCodec.derivedFromZioSchema[A]

  /** The check that a record has each required field of `fieldSpecs`. The wire does not require an optional field or a
    * field with a default value: the decode gives the default to such an absent field. The check finds the names of the
    * required fields one time, before it reads a record. For a missing field, the result is a `NotFoundError` that
    * names each missing field.
    */
  private[signatures] def requiredFieldsCheck(
      fieldSpecs: Vector[FieldSpec]
  ): DynamicValue.Record => Either[DspyError, Unit] =
    val required = fieldSpecs.filterNot(field => field.optional || field.defaultValue.isDefined).map(_.name)
    raw =>
      val present = raw.fields.iterator.map(_._1).toSet
      val missing = required.filterNot(present.contains)
      if missing.isEmpty then Right(())
      else
        Left(NotFoundError(
          resource = "prediction_field",
          message = s"Missing required fields: ${missing.mkString(", ")}"
        ))
