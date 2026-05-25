package dspy4s.typed

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

private[typed] final case class EnumInfo[A](
    displayName: String,
    caseNames: List[String],
    cases: List[A]
):
  val byName: Map[String, A] = caseNames.zip(cases).toMap
  val byValue: Map[A, String] = cases.zip(caseNames).toMap

private[typed] object EnumInfo:

  inline def derived[A <: scala.reflect.Enum](using m: Mirror.SumOf[A]): EnumInfo[A] =
    EnumInfo(
      displayName = constValue[m.MirroredLabel & String],
      caseNames   = summonLabels[m.MirroredElemLabels],
      cases       = summonCases[A, m.MirroredElemTypes]
    )

  private inline def summonLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h & String] :: summonLabels[t]

  /** Materializes each parameterless enum case by summoning its singleton
    * mirror and reading the `fromProduct(EmptyTuple)` value. Compile-error
    * if any case has parameters (the inline match leaves no fallback). */
  private inline def summonCases[A, T <: Tuple]: List[A] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t) =>
        val m = summonInline[Mirror.ProductOf[h & A]]
        val v = m.fromProduct(EmptyTuple).asInstanceOf[A]
        v :: summonCases[A, t]
