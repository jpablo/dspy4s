package dspy4s.typed.internal

import dspy4s.core.contracts.{FieldRole, FieldSpec, SignatureSpec}
import dspy4s.typed.{Shape, TypedSignature, ValueDecoder}
import scala.deriving.Mirror
import scala.quoted.*

private[typed] object FunctionMacro:

  /** Implementation of `TypedSignature.from(method)`. The method itself is
    * never called; it is a declaration surface whose parameter names/types
    * become inputs and whose return type becomes outputs.
    */
  def fromImpl[F : Type](fn: Expr[F])(using Quotes): Expr[Any] =
    import quotes.reflect.*

    case class FieldData(
        name: String,
        tpe: TypeRepr,
        fieldSpec: Expr[FieldSpec],
        decoder: Expr[ValueDecoder[Any]]
    )

    def unwrap(term: Term): Term = term match
      case Inlined(_, _, inner) => unwrap(inner)
      case Typed(inner, _)      => unwrap(inner)
      case Block(Nil, inner)    => unwrap(inner)
      case other                => other

    def referencedMethod(term: Term): Symbol =
      unwrap(term) match
        case id: Ident if id.symbol.isDefDef => id.symbol
        case sel: Select if sel.symbol.isDefDef => sel.symbol
        case Block(stats, Closure(meth, _)) if meth.symbol.isDefDef =>
          stats.collectFirst {
            case dd: DefDef if dd.symbol == meth.symbol => dd
          }.flatMap(_.rhs.flatMap(calledMethod)).getOrElse(meth.symbol)
        case Closure(meth, _) if meth.symbol.isDefDef => meth.symbol
        case other =>
          report.errorAndAbort(
            "TypedSignature.from expects a method reference, e.g. " +
            "`TypedSignature.from(emotionsSig)`; got: " + other.show
          )

    def methodDef(sym: Symbol): DefDef =
      sym.tree match
        case dd: DefDef => dd
        case _ =>
          report.errorAndAbort(
            s"TypedSignature.from could not inspect method '${sym.name}'"
          )

    def calledMethod(term: Term): Option[Symbol] =
      unwrap(term) match
        case Apply(fn, _) if fn.symbol.isDefDef && !fn.symbol.name.startsWith("$anonfun") =>
          Some(fn.symbol)
        case Apply(fn, _) => calledMethod(fn)
        case TypeApply(fn, _) => calledMethod(fn)
        case id: Ident if id.symbol.isDefDef && !id.symbol.name.startsWith("$anonfun") =>
          Some(id.symbol)
        case sel: Select if sel.symbol.isDefDef && !sel.symbol.name.startsWith("$anonfun") =>
          Some(sel.symbol)
        case _ => None

    def dealiasedMethod(sym: Symbol): Symbol =
      if sym.name.startsWith("$anonfun") then
        methodDef(sym).rhs.flatMap(calledMethod).getOrElse(sym)
      else sym

    def tupleType(parts: List[TypeRepr]): TypeRepr =
      parts.foldRight(TypeRepr.of[EmptyTuple]) { (head, tail) =>
        TypeRepr.of[*:].appliedTo(List(head, tail))
      }

    def namedTupleType(items: List[(String, TypeRepr)]): TypeRepr =
      val nameTypes = items.map { (name, _) => ConstantType(StringConstant(name)) }
      val valueTypes = items.map(_._2)
      val namesTuple = tupleType(nameTypes)
      val valuesTuple = tupleType(valueTypes)
      TypeRepr.of[NamedTuple.NamedTuple].appliedTo(List(namesTuple, valuesTuple))

    def tupleParts(tpe: TypeRepr): List[TypeRepr] =
      tpe.dealias match
        case AppliedType(tc, List(head, tail)) if tc.typeSymbol == TypeRepr.of[*:].typeSymbol =>
          head :: tupleParts(tail)
        case AppliedType(tc, args) if tc.typeSymbol.fullName.startsWith("scala.Tuple") =>
          args
        case other if other =:= TypeRepr.of[EmptyTuple] => Nil
        case other =>
          report.errorAndAbort(s"Expected tuple type, got: ${other.show}")

    def namedTupleParts(tpe: TypeRepr): Option[List[(String, TypeRepr)]] =
      tpe.dealias match
        case AppliedType(tc, List(names, values))
            if tc.typeSymbol == TypeRepr.of[NamedTuple.NamedTuple].typeSymbol =>
          val nameParts = tupleParts(names).map {
            case ConstantType(StringConstant(name)) => name
            case other =>
              report.errorAndAbort(s"Expected named-tuple label, got: ${other.show}")
          }
          val valueParts = tupleParts(values)
          Some(nameParts.zip(valueParts))
        case _ => None

    def decoderExpr(owner: String, fieldName: String, fieldTpe: TypeRepr): Expr[ValueDecoder[Any]] =
      fieldTpe.asType match
        case '[t] =>
          Expr.summon[ValueDecoder[t]] match
            case Some(d) => '{ ${ d }.asInstanceOf[ValueDecoder[Any]] }
            case None =>
              report.errorAndAbort(
                s"No ValueDecoder[${fieldTpe.show}] in scope for field '$owner.$fieldName'"
              )

    def fieldData(owner: String, role: FieldRole, items: List[(String, TypeRepr)]): List[FieldData] =
      items.map { (name, tpe) =>
        val dec = decoderExpr(owner, name, tpe)
        val nameExpr = Expr(name)
        val roleExpr =
          if role == FieldRole.Input then '{ FieldRole.Input } else '{ FieldRole.Output }
        val fieldSpecExpr = '{
          FieldSpec(
            name     = ${ nameExpr },
            role     = ${ roleExpr },
            typeRef  = ${ dec }.typeRef,
            metadata = ${ dec }.metadata
          )
        }
        FieldData(name, tpe, fieldSpecExpr, dec)
      }

    def decoderMapExpr(items: List[FieldData]): Expr[Map[String, ValueDecoder[Any]]] =
      val pairs = items.map { item =>
        val nameExpr = Expr(item.name)
        '{ ${ nameExpr } -> ${ item.decoder } }
      }
      '{ Map(${ Varargs(pairs) }*) }

    def signatureExpr[I : Type, O : Type](
        sigName: String,
        inputFields: List[FieldData],
        outputFieldsExpr: Expr[Vector[FieldSpec]],
        inputShapeExpr: Expr[Shape[I]],
        outputShapeExpr: Expr[Shape[O]]
    ): Expr[TypedSignature[I, O]] =
      val inputFieldExprs = inputFields.map(_.fieldSpec)
      val sigNameExpr = Expr(sigName)
      '{
        val inFields: Vector[FieldSpec] = Vector(${ Varargs(inputFieldExprs) }*)
        val outFields: Vector[FieldSpec] = ${ outputFieldsExpr }
        val sig = SignatureSpec
          .create(
            name   = ${ sigNameExpr },
            fields = inFields ++ outFields
          )
          .fold(
            err => throw new IllegalStateException(
              s"Internal error materializing function signature '${${ sigNameExpr }}': ${err.message}"
            ),
            identity
          )
        TypedSignature[I, O](
          name        = ${ sigNameExpr },
          untyped     = sig,
          inputShape  = ${ inputShapeExpr },
          outputShape = ${ outputShapeExpr }
        )
      }

    val sym = dealiasedMethod(referencedMethod(fn.asTerm))
    val dd = methodDef(sym)
    val sigName = sym.name

    if dd.paramss.exists {
        case _: TypeParamClause => true
        case _ => false
      }
    then
      report.errorAndAbort(s"TypedSignature.from does not support polymorphic method '$sigName'")

    val termParamClauses = dd.paramss.collect { case clause: TermParamClause => clause }
    if termParamClauses.size != 1 then
      report.errorAndAbort(
        s"TypedSignature.from expects method '$sigName' to have exactly one parameter list"
      )

    val params = termParamClauses.head.params.map { vd =>
      vd.name -> vd.tpt.tpe
    }

    if params.isEmpty then
      report.errorAndAbort(s"TypedSignature.from expects method '$sigName' to declare at least one input parameter")

    val duplicateInputs = params.groupBy(_._1).collect {
      case (name, occurrences) if occurrences.size > 1 => name
    }
    if duplicateInputs.nonEmpty then
      report.errorAndAbort(
        s"Method '$sigName' has duplicate parameter names: ${duplicateInputs.mkString(", ")}"
      )

    val returnType = dd.returnTpt.tpe
    if returnType =:= TypeRepr.of[Unit] then
      report.errorAndAbort(s"TypedSignature.from requires method '$sigName' to return an output type, not Unit")

    val inputData = fieldData(sigName, FieldRole.Input, params)
    val inputType = namedTupleType(params)

    def scalarOutputExpr(returnType: TypeRepr): Expr[Any] =
      val outputItems = List("result" -> returnType)
      val outputData = fieldData(sigName, FieldRole.Output, outputItems)
      val outputType = namedTupleType(outputItems)
      (inputType.asType, outputType.asType) match
        case ('[i], '[o]) =>
          val outFieldExprs = outputData.map(_.fieldSpec)
          signatureExpr[i, o](
            sigName = sigName,
            inputFields = inputData,
            outputFieldsExpr = '{ Vector(${ Varargs(outFieldExprs) }*) },
            inputShapeExpr = '{ new Shape.TupleShape[i](Vector(${ Varargs(inputData.map(_.fieldSpec)) }*), ${ decoderMapExpr(inputData) }) },
            outputShapeExpr = '{ new Shape.TupleShape[o](Vector(${ Varargs(outFieldExprs) }*), ${ decoderMapExpr(outputData) }) }
          )
        case _ =>
          report.errorAndAbort(s"Internal error materializing scalar output for method '$sigName'")

    namedTupleParts(returnType) match
      case Some(outputItems) =>
        if outputItems.isEmpty then
          report.errorAndAbort(s"TypedSignature.from requires method '$sigName' to declare at least one output field")
        val outputData = fieldData(sigName, FieldRole.Output, outputItems)
        (inputType.asType, returnType.asType) match
          case ('[i], '[o]) =>
            val outFieldExprs = outputData.map(_.fieldSpec)
            signatureExpr[i, o](
              sigName = sigName,
              inputFields = inputData,
              outputFieldsExpr = '{ Vector(${ Varargs(outFieldExprs) }*) },
              inputShapeExpr = '{ new Shape.TupleShape[i](Vector(${ Varargs(inputData.map(_.fieldSpec)) }*), ${ decoderMapExpr(inputData) }) },
              outputShapeExpr = '{ new Shape.TupleShape[o](Vector(${ Varargs(outFieldExprs) }*), ${ decoderMapExpr(outputData) }) }
            )
          case _ =>
            report.errorAndAbort(s"Internal error materializing named-tuple output for method '$sigName'")
      case None =>
        if returnType.typeSymbol.flags.is(Flags.Case) || returnType <:< TypeRepr.of[Product] then
          returnType.asType match
            case '[o] =>
              Expr.summon[Mirror.ProductOf[o]] match
                case Some(mirror) =>
                  inputType.asType match
                    case '[i] =>
                      signatureExpr[i, o](
                        sigName = sigName,
                        inputFields = inputData,
                        outputFieldsExpr = '{
                          Shape
                            .derivedProductWithRole[o](FieldRole.Output)(using ${ mirror })
                            .fieldSpecs
                        },
                        inputShapeExpr = '{ new Shape.TupleShape[i](Vector(${ Varargs(inputData.map(_.fieldSpec)) }*), ${ decoderMapExpr(inputData) }) },
                        outputShapeExpr = '{
                          Shape
                            .derivedProductWithRole[o](FieldRole.Output)(using ${ mirror })
                        }
                      )
                case None =>
                  scalarOutputExpr(returnType)
        else scalarOutputExpr(returnType)
