package dspy4s.typed

import dspy4s.core.contracts.{DspyError, FieldMetadata, TypeRef, ValidationError}
import scala.compiletime.{constValue, erasedValue, summonInline}
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
  * the case name. Other shapes (such as kyo-schema's discriminated
  * `{"case":{}}` wrapper) must be normalized at the adapter layer before
  * reaching the decoder. */
trait ValueDecoder[A]:
  def typeRef: TypeRef
  def decode(raw: Any): Either[DspyError, A]
  def encode(value: A): Any

  /** Optional per-field metadata surfaced into `FieldSpec.metadata` at
    * `Shape` derivation time. Well-known keys live in
    * `dspy4s.core.contracts.FieldMetadata` so adapter readers and decoder
    * writers share one contract. Defaults to empty. */
  def metadata: Map[String, String] = Map.empty

object ValueDecoder:

  // ── Primitive instances ──────────────────────────────────────────────────

  given ValueDecoder[String] with
    val typeRef: TypeRef = TypeRef.string
    def decode(raw: Any): Either[DspyError, String] = raw match
      case s: String                                       => Right(s)
      case b: Boolean                                      => Right(b.toString)
      case n @ (_: Int | _: Long | _: Float | _: Double)   => Right(n.toString)
      case other =>
        Left(ValidationError(s"Cannot decode as String: $other"))
    def encode(value: String): Any = value

  given ValueDecoder[Int] with
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

  given ValueDecoder[Double] with
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

  given ValueDecoder[Boolean] with
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

  // ── Scala enum derivation ────────────────────────────────────────────────
  //
  // Decodes enums from their `.toString` form (which equals the case name
  // for parameterless cases). Phase 0 finding: kyo-schema's default enum
  // wire form uses `{"caseName":{}}`. The typed-layer boundary accepts
  // raw strings instead — adapters that produce discriminated objects can
  // transform before handoff, or callers can pass the typed enum value
  // directly.

  /** Enables `derives ValueDecoder` on Scala enums. Decodes case names from
    * strings or accepts already-typed enum values. */
  inline def derived[A <: scala.reflect.Enum](using m: Mirror.SumOf[A]): ValueDecoder[A] =
    new EnumDecoder[A](
      caseNames   = summonEnumLabels[m.MirroredElemLabels],
      cases       = summonEnumCases[A, m.MirroredElemTypes],
      displayName = constValue[m.MirroredLabel & String]
    )

  /** Extracted from `derived` so the inline def doesn't duplicate the
    * anonymous class at each call site. Package-private so inline expansion
    * sites outside `ValueDecoder` (but inside `dspy4s.typed`) can reach it.
    *
    * The wire form for enum fields is a plain `String` — `typeRef =
    * TypeRef.string` reflects that honestly. The allowed-case constraint
    * is surfaced via `metadata(FieldMetadata.EnumCases)` so adapters can
    * render it into the prompt. */
  private[typed] final class EnumDecoder[A](
      caseNames: List[String],
      cases: List[A],
      displayName: String
  ) extends ValueDecoder[A]:

    private val byName:  Map[String, A] = caseNames.zip(cases).toMap
    private val byValue: Map[A, String] = cases.zip(caseNames).toMap

    val typeRef: TypeRef = TypeRef.string

    override val metadata: Map[String, String] = Map(
      FieldMetadata.EnumCases -> caseNames.mkString(","),
      FieldMetadata.EnumName  -> displayName
    )

    def decode(raw: Any): Either[DspyError, A] = raw match
      case a if cases.contains(a) => Right(a.asInstanceOf[A])
      case s: String =>
        byName.get(s) match
          case Some(c) => Right(c)
          case None =>
            Left(ValidationError(
              s"Cannot decode as $displayName: '$s' is not one of ${caseNames.mkString(", ")}"
            ))
      case other =>
        Left(ValidationError(s"Cannot decode as $displayName: $other"))

    /** Encode via the matched case name rather than `value.toString` so a
      * user-overridden `toString` can't drift away from the decode contract
      * or the prompt-rendered allowed set. */
    def encode(value: A): Any =
      byValue.getOrElse(value, value.toString)

  private inline def summonEnumLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h & String] :: summonEnumLabels[t]

  /** Materializes each parameterless enum case by summoning its singleton
    * mirror and reading the `fromProduct(EmptyTuple)` value. Compile-error
    * if any case has parameters (the inline match leaves no fallback). */
  private inline def summonEnumCases[A, T <: Tuple]: List[A] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   =>
        val m  = summonInline[Mirror.ProductOf[h & A]]
        val v  = m.fromProduct(EmptyTuple).asInstanceOf[A]
        v :: summonEnumCases[A, t]
