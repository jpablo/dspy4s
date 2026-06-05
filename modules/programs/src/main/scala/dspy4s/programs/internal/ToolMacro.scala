package dspy4s.programs.internal

import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, RuntimeContext, ValidationError}
import dspy4s.core.contracts.TypeRef as DspyTypeRef
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.typed.SchemaInterop
import zio.blocks.schema.{DynamicValue, Schema}

import scala.quoted.*

/** Runtime support for [[dspy4s.programs.contracts.ToolFunction.fromMethod]]: decode one named argument from the
  * tool's call record, coercing LM-shaped values (string primitives → the field's type) the same way the typed
  * predict path does. */
object ToolArgs:
  def decode[A](record: DynamicValue.Record, name: String)(using Schema[A]): Either[DspyError, A] =
    DynamicValues.recordGet(record, name) match
      case Some(dv) =>
        SchemaInterop.decodeValue[A](dv).left.map(err => ValidationError(s"tool argument '$name': ${err.message}"))
      case None =>
        Left(NotFoundError("tool_argument", s"missing required tool argument '$name'"))

object ToolMacro:

  /** Implementation of `ToolFunction.fromMethod(method)`: read the method's name + `@description` + typed
    * parameters, and emit a `ToolFunction` that decodes each argument from the call record, applies the method,
    * and lifts the result via its `Schema`. */
  def fromMethodImpl[F: Type](method: Expr[F])(using Quotes): Expr[ToolFunction] =
    import quotes.reflect.*
    given CanEqual[Symbol, Symbol] = CanEqual.derived

    def unwrap(t: Term): Term = t match
      case Inlined(_, _, inner) => unwrap(inner)
      case Typed(inner, _)      => unwrap(inner)
      case Block(Nil, inner)    => unwrap(inner)
      case other                => other

    def calledMethod(term: Term): Option[Symbol] =
      unwrap(term) match
        case Apply(fn, _) if fn.symbol.isDefDef && !fn.symbol.name.startsWith("$anonfun") => Some(fn.symbol)
        case Apply(fn, _)     => calledMethod(fn)
        case TypeApply(fn, _) => calledMethod(fn)
        case id: Ident if id.symbol.isDefDef && !id.symbol.name.startsWith("$anonfun")  => Some(id.symbol)
        case sel: Select if sel.symbol.isDefDef && !sel.symbol.name.startsWith("$anonfun") => Some(sel.symbol)
        case _ => None

    // Resolve the referenced method symbol (the call site eta-expands the method into a closure).
    val methodSym: Symbol = unwrap(method.asTerm) match
      case id: Ident if id.symbol.isDefDef    => id.symbol
      case sel: Select if sel.symbol.isDefDef => sel.symbol
      case Block(stats, Closure(meth, _)) if meth.symbol.isDefDef =>
        stats.collectFirst { case dd: DefDef if dd.symbol == meth.symbol => dd }
          .flatMap(_.rhs.flatMap(calledMethod)).getOrElse(meth.symbol)
      case Closure(meth, _) if meth.symbol.isDefDef => meth.symbol
      case other =>
        report.errorAndAbort(
          "ToolFunction.fromMethod expects a method reference, e.g. `ToolFunction.fromMethod(getWeather)`; got: " +
          other.show
        )

    val toolName = methodSym.name

    val toolDesc: String =
      methodSym.getAnnotation(TypeRepr.of[description].typeSymbol) match
        case Some(Apply(_, List(Literal(StringConstant(s))))) => s
        case _                                                => ""

    val defdef: DefDef = methodSym.tree match
      case dd: DefDef => dd
      case _          => report.errorAndAbort(s"ToolFunction.fromMethod could not inspect method '$toolName'")

    val paramClauses = defdef.paramss.collect { case tpc: TermParamClause => tpc }
    val params: List[(String, TypeRepr)] = paramClauses match
      case Nil       => Nil
      case List(tpc) => tpc.params.map(p => p.name -> p.tpt.tpe)
      case _ =>
        report.errorAndAbort(
          s"ToolFunction.fromMethod supports a single parameter list (no `using` clauses); '$toolName' has " +
          s"${paramClauses.size}. Tools needing the RuntimeContext use the ToolFunction(...) / .of(...) factories."
        )

    val returnType: TypeRepr = defdef.returnTpt.tpe

    // argSchema: Vector[(name, TypeRef)]
    val argSchemaExprs: List[Expr[(String, DspyTypeRef)]] = params.map { (name, tpe) =>
      tpe.asType match
        case '[t] =>
          val s = Expr.summon[Schema[t]].getOrElse(report.errorAndAbort(s"No Schema for '$name: ${tpe.show}'"))
          '{ (${ Expr(name) }, SchemaInterop.typeRef[t](using $s)) }
    }
    val argSchemaExpr: Expr[Vector[(String, DspyTypeRef)]] = '{ Vector(${ Varargs(argSchemaExprs) }*) }

    // invoke body: decode each arg, apply the method, lift the result via its Schema
    def buildBody(record: Expr[DynamicValue.Record], remaining: List[(String, TypeRepr)], decoded: List[Term])
        : Expr[Either[DspyError, DynamicValue]] =
      remaining match
        case Nil =>
          val applied: Term = if decoded.isEmpty then Ref(methodSym) else Apply(Ref(methodSym), decoded)
          returnType.asType match
            case '[r] =>
              val schemaR = Expr.summon[Schema[r]]
                .getOrElse(report.errorAndAbort(s"No zio.blocks.schema.Schema for the tool return type '${returnType.show}'"))
              '{ Right(ToolFunction.result[r](${ applied.asExprOf[r] })(using $schemaR)) }
        case (name, tpe) :: rest =>
          tpe.asType match
            case '[t] =>
              val schemaT = Expr.summon[Schema[t]].get
              '{
                ToolArgs.decode[t]($record, ${ Expr(name) })(using $schemaT).flatMap { (a: t) =>
                  ${ buildBody(record, rest, decoded :+ '{ a }.asTerm) }
                }
              }

    '{
      new ToolFunction:
        override val name: String                       = ${ Expr(toolName) }
        override val description: String                = ${ Expr(toolDesc) }
        override val argSchema: Vector[(String, DspyTypeRef)] = $argSchemaExpr
        override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
          ${ buildBody('args, params, Nil) }
    }
