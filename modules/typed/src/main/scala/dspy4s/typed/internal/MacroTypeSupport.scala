package dspy4s.typed.internal

import scala.quoted.*

/** Quote-dependent tuple construction and inspection shared by the typed signature macros. */
private[typed] object MacroTypeSupport:

  private def sameSymbol(using
      quotes: Quotes
  )(
      left: quotes.reflect.Symbol,
      right: quotes.reflect.Symbol
  ): Boolean =
    given CanEqual[quotes.reflect.Symbol, quotes.reflect.Symbol] = CanEqual.derived
    left == right

  def tupleType(using quotes: Quotes)(parts: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr =
    import quotes.reflect.*
    parts.foldRight(TypeRepr.of[EmptyTuple]) { (head, tail) =>
      TypeRepr.of[*:].appliedTo(List(head, tail))
    }

  def namedTupleType(using
      quotes: Quotes
  )(
      items: List[(String, quotes.reflect.TypeRepr)]
  ): quotes.reflect.TypeRepr =
    import quotes.reflect.*
    val nameTypes  = items.map { (name, _) => ConstantType(StringConstant(name)) }
    val valueTypes = items.map(_._2)
    TypeRepr.of[NamedTuple.NamedTuple].appliedTo(List(tupleType(nameTypes), tupleType(valueTypes)))

  def tupleParts(using quotes: Quotes)(tpe: quotes.reflect.TypeRepr): List[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    tpe.dealias match
      case AppliedType(tc, List(head, tail)) if sameSymbol(tc.typeSymbol, TypeRepr.of[*:].typeSymbol) =>
        head :: tupleParts(tail)
      case AppliedType(tc, args) if tc.typeSymbol.fullName.startsWith("scala.Tuple") =>
        args
      case other if other =:= TypeRepr.of[EmptyTuple] => Nil
      case other                                      =>
        report.errorAndAbort(s"Expected tuple type, got: ${other.show}")

  def namedTupleParts(using
      quotes: Quotes
  )(
      tpe: quotes.reflect.TypeRepr
  ): Option[List[(String, quotes.reflect.TypeRepr)]] =
    import quotes.reflect.*
    tpe.dealias match
      case AppliedType(tc, List(names, values))
          if sameSymbol(tc.typeSymbol, TypeRepr.of[NamedTuple.NamedTuple].typeSymbol) =>
        val nameParts = tupleParts(names).map {
          case ConstantType(StringConstant(name)) => name
          case other                              =>
            report.errorAndAbort(s"Expected named-tuple label, got: ${other.show}")
        }
        Some(nameParts.zip(tupleParts(values)))
      case _ => None

  def unnamedTupleParts(using
      quotes: Quotes
  )(
      tpe: quotes.reflect.TypeRepr
  ): Option[List[(String, quotes.reflect.TypeRepr)]] =
    import quotes.reflect.*

    // Flatten first, then assign positional labels. Assigning indexes recursively from the tail reverses labels for
    // `*:`-spelled tuples (`Int *: Boolean *: EmptyTuple` would become `[_2: Int, _1: Boolean]`).
    def elements(t: TypeRepr): Option[List[TypeRepr]] =
      t.dealias match
        case AppliedType(tc, List(head, tail)) if sameSymbol(tc.typeSymbol, TypeRepr.of[*:].typeSymbol) =>
          elements(tail).map(head :: _)
        case AppliedType(tc, args) if tc.typeSymbol.fullName.startsWith("scala.Tuple") && args.nonEmpty =>
          Some(args)
        case other if other =:= TypeRepr.of[EmptyTuple] => Some(Nil)
        case _                                          => None

    elements(tpe).map(_.zipWithIndex.map { case (element, index) => s"_${index + 1}" -> element })
