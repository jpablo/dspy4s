package dspy4s.signatures.internal

import scala.quoted.*

/** The resolution of a method reference, for the macros that read a method from a term (`Signature.from` and
  * `ToolFunction.fromMethod`). The call site can eta-expand the method into a closure.
  */
private[dspy4s] object MethodResolution:

  private def unwrap(using q: Quotes)(term: q.reflect.Term): q.reflect.Term =
    import q.reflect.*
    term match
      case Inlined(_, _, inner) => unwrap(inner)
      case Typed(inner, _)      => unwrap(inner)
      case Block(Nil, inner)    => unwrap(inner)
      case other                => other

  /** The method that `term` calls or names, when it is not a compiler-generated closure method. */
  def calledMethod(using q: Quotes)(term: q.reflect.Term): Option[q.reflect.Symbol] =
    import q.reflect.*
    unwrap(term) match
      case Apply(fn, _) if fn.symbol.isDefDef && !fn.symbol.name.startsWith("$anonfun")  => Some(fn.symbol)
      case Apply(fn, _)                                                                  => calledMethod(fn)
      case TypeApply(fn, _)                                                              => calledMethod(fn)
      case id: Ident if id.symbol.isDefDef && !id.symbol.name.startsWith("$anonfun")     => Some(id.symbol)
      case sel: Select if sel.symbol.isDefDef && !sel.symbol.name.startsWith("$anonfun") => Some(sel.symbol)
      case _                                                                             => None

  /** The method that `term` refers to. Another term is a compile error with the text `<errorPrefix>; got: <term>`. */
  def referencedMethod(using q: Quotes)(term: q.reflect.Term, errorPrefix: String): q.reflect.Symbol =
    import q.reflect.*
    given CanEqual[Symbol, Symbol] = CanEqual.derived
    unwrap(term) match
      case id: Ident if id.symbol.isDefDef                        => id.symbol
      case sel: Select if sel.symbol.isDefDef                     => sel.symbol
      case Block(stats, Closure(meth, _)) if meth.symbol.isDefDef =>
        stats.collectFirst { case dd: DefDef if dd.symbol == meth.symbol => dd }
          .flatMap(_.rhs.flatMap(calledMethod)).getOrElse(meth.symbol)
      case Closure(meth, _) if meth.symbol.isDefDef => meth.symbol
      case other                                    => report.errorAndAbort(s"$errorPrefix; got: ${other.show}")
