package dspy4s.typed

import dspy4s.core.contracts.{DspyError, TypeRef, ValidationError}
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
  * extracting raw field values. This deliberately sidesteps the kyo-schema
  * enum wire-format mismatch surfaced in Phase 0 (flat string `"joy"` vs.
  * discriminated `{"joy":{}}`); the typed layer accepts both shapes
  * depending on which the adapter produces.
  */
trait ValueDecoder[A]:
  def typeRef: TypeRef
  def decode(raw: Any): Either[DspyError, A]
  def encode(value: A): Any

  /** Optional per-field metadata surfaced into `FieldSpec.metadata` at
    * `Shape` derivation time. Adapters can read well-known keys to enrich
    * prompts (e.g. listing allowed enum case names). Defaults to empty. */
  def metadata: Map[String, String] = Map.empty

object ValueDecoder:

  /** Well-known `metadata` keys produced by built-in decoders. Adapters
    * that understand these may surface them to the LM (e.g. by rendering
    * the allowed enum cases in a prompt). */
  object Meta:
    /** Comma-separated case names for fields backed by a Scala enum.
      * Example: `"sadness,joy,love,anger,fear,surprise"`. */
    val EnumCases: String = "enum.cases"

    /** The original Scala display name of an enum type (e.g. `"Sentiment"`).
      * Useful for adapter prompt rendering when the type label is more
      * informative than the lowercased `TypeRef`. */
    val EnumName: String = "enum.name"

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
    * is surfaced via `metadata(Meta.EnumCases)` so adapters can render it
    * into the prompt. */
  private[typed] final class EnumDecoder[A](
      caseNames: List[String],
      cases: List[A],
      displayName: String
  ) extends ValueDecoder[A]:

    private val byName: Map[String, A] = caseNames.zip(cases).toMap
    val typeRef: TypeRef               = TypeRef.string

    override val metadata: Map[String, String] = Map(
      Meta.EnumCases -> caseNames.mkString(","),
      Meta.EnumName  -> displayName
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

    def encode(value: A): Any = value.toString

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
