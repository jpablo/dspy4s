package dspy4s.typed.internal

import dspy4s.core.contracts.{FieldRole, FieldSpec, SignatureLayout}
import dspy4s.typed.{Shape, Signature as TypedSig, FieldCodec}
import kyo.Schema
import scala.deriving.Mirror
import scala.quoted.*

private[typed] object FunctionMacro:

  case class FieldData(
      name: String,
      fieldSpec: Expr[FieldSpec],
      decoder: Expr[FieldCodec[Any]]
  )

  private def materialize[I : Type, O : Type](
      sigNameExpr: Expr[String],
      instructionsExpr: Expr[String],
      errorName: String,
      inputFields: List[FieldData],
      outputFieldsExpr: Expr[Vector[FieldSpec]],
      inputShapeExpr: Expr[Shape[I]],
      outputShapeExpr: Expr[Shape[O]]
  )(using Quotes): Expr[TypedSig[I, O]] =
    val inputFieldExprs = inputFields.map(_.fieldSpec)
    val errorNameExpr = Expr(errorName)
    '{
      val name: String = ${ sigNameExpr }
      val inFields: Vector[FieldSpec] = Vector(${ Varargs(inputFieldExprs) }*)
      val outFields: Vector[FieldSpec] = ${ outputFieldsExpr }
      val sig = SignatureLayout
        .create(
          name         = name,
          fields       = inFields ++ outFields,
          instructions = Option(${ instructionsExpr }).filter(_.nonEmpty)
        )
        .fold(
          err => throw new IllegalStateException(
            s"Internal error materializing function signature '${${ errorNameExpr }}': ${err.message}"
          ),
          identity
        )
      TypedSig[I, O](
        name        = name,
        untyped     = sig,
        inputShape  = ${ inputShapeExpr },
        outputShape = ${ outputShapeExpr }
      )
    }

  /** Implementation of `Signature.from(method)`. The method itself is
    * never called; it is a declaration surface whose parameter names/types
    * become inputs and whose return type becomes outputs.
    */
  def fromImpl[F : Type](fn: Expr[F])(using Quotes): Expr[Any] =
    import quotes.reflect.*

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
            "Signature.from expects a method reference, e.g. " +
            "`Signature.from(emotionsSig)`; got: " + other.show
          )

    def methodDef(sym: Symbol): DefDef =
      sym.tree match
        case dd: DefDef => dd
        case _ =>
          report.errorAndAbort(
            s"Signature.from could not inspect method '${sym.name}'"
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

    def decoderExpr(owner: String, fieldName: String, fieldTpe: TypeRepr): Expr[FieldCodec[Any]] =
      fieldTpe.asType match
        case '[t] =>
          Expr.summon[FieldCodec[t]] match
            case Some(d) => '{ ${ d }.asInstanceOf[FieldCodec[Any]] }
            case None =>
              report.errorAndAbort(
                s"No FieldCodec[${fieldTpe.show}] in scope for field '$owner.$fieldName'"
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
        FieldData(name, fieldSpecExpr, dec)
      }

    def decoderMapExpr(items: List[FieldData]): Expr[Map[String, FieldCodec[Any]]] =
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
    ): Expr[TypedSig[I, O]] =
      materialize[I, O](
        sigNameExpr = Expr(sigName),
        instructionsExpr = Expr(""),
        errorName = sigName,
        inputFields = inputFields,
        outputFieldsExpr = outputFieldsExpr,
        inputShapeExpr = inputShapeExpr,
        outputShapeExpr = outputShapeExpr
      )

    val sym = dealiasedMethod(referencedMethod(fn.asTerm))
    val dd = methodDef(sym)
    val sigName = sym.name

    if dd.paramss.exists {
        case _: TypeParamClause => true
        case _ => false
      }
    then
      report.errorAndAbort(s"Signature.from does not support polymorphic method '$sigName'")

    val termParamClauses = dd.paramss.collect { case clause: TermParamClause => clause }
    if termParamClauses.size != 1 then
      report.errorAndAbort(
        s"Signature.from expects method '$sigName' to have exactly one parameter list"
      )

    val params = termParamClauses.head.params.map { vd =>
      vd.name -> vd.tpt.tpe
    }

    if params.isEmpty then
      report.errorAndAbort(s"Signature.from expects method '$sigName' to declare at least one input parameter")

    val duplicateInputs = params.groupBy(_._1).collect {
      case (name, occurrences) if occurrences.size > 1 => name
    }
    if duplicateInputs.nonEmpty then
      report.errorAndAbort(
        s"Method '$sigName' has duplicate parameter names: ${duplicateInputs.mkString(", ")}"
      )

    val returnType = dd.returnTpt.tpe
    if returnType =:= TypeRepr.of[Unit] then
      report.errorAndAbort(s"Signature.from requires method '$sigName' to return an output type, not Unit")

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
          report.errorAndAbort(s"Signature.from requires method '$sigName' to declare at least one output field")
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
                  Expr.summon[Schema[o]] match
                    case Some(schema) =>
                      inputType.asType match
                        case '[i] =>
                          signatureExpr[i, o](
                            sigName = sigName,
                            inputFields = inputData,
                            outputFieldsExpr = '{
                              Shape
                                .derivedProductWithRole[o](FieldRole.Output)(using ${ mirror }, ${ schema })
                                .fieldSpecs
                            },
                            inputShapeExpr = '{ new Shape.TupleShape[i](Vector(${ Varargs(inputData.map(_.fieldSpec)) }*), ${ decoderMapExpr(inputData) }) },
                            outputShapeExpr = '{
                              Shape
                                .derivedProductWithRole[o](FieldRole.Output)(using ${ mirror }, ${ schema })
                            }
                          )
                    case None =>
                      report.errorAndAbort(s"No kyo.Schema for product output type '${returnType.show}'")
                case None =>
                  scalarOutputExpr(returnType)
        else scalarOutputExpr(returnType)

  /** Implementation of `Signature.fromType[F]`. Inspects a function
    * type rather than a method term.
    */
  def fromTypeImpl[F : Type](
      name: Expr[String],
      instructions: Expr[String]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*

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

    def decoderExpr(owner: String, fieldName: String, fieldTpe: TypeRepr): Expr[FieldCodec[Any]] =
      fieldTpe.asType match
        case '[t] =>
          Expr.summon[FieldCodec[t]] match
            case Some(d) => '{ ${ d }.asInstanceOf[FieldCodec[Any]] }
            case None =>
              report.errorAndAbort(
                s"No FieldCodec[${fieldTpe.show}] in scope for field '$owner.$fieldName'"
              )

    def fieldData(owner: String, role: FieldRole, items: List[(String, TypeRepr)]): List[FieldData] =
      items.map { (fieldName, tpe) =>
        val dec = decoderExpr(owner, fieldName, tpe)
        val nameExpr = Expr(fieldName)
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
        FieldData(fieldName, fieldSpecExpr, dec)
      }

    def decoderMapExpr(items: List[FieldData]): Expr[Map[String, FieldCodec[Any]]] =
      val pairs = items.map { item =>
        val nameExpr = Expr(item.name)
        '{ ${ nameExpr } -> ${ item.decoder } }
      }
      '{ Map(${ Varargs(pairs) }*) }

    def functionParts(tpe: TypeRepr): (List[(Option[String], TypeRepr)], TypeRepr) =
      tpe.dealias match
        case Refinement(parent, "apply", MethodType(paramNames, paramTypes, returnType)) =>
          val parentParts = parent.dealias match
            case AppliedType(tc, args) if tc.typeSymbol.fullName.startsWith("scala.Function") =>
              args
            case other =>
              report.errorAndAbort(s"Signature.fromType expects a function type, got: ${other.show}")
          val parentInputs = parentParts.dropRight(1)
          val inputs =
            if paramTypes.size == parentInputs.size then paramTypes
            else parentInputs
          paramNames.map(Some(_)).zip(inputs) -> returnType
        case AppliedType(tc, args) if tc.typeSymbol.fullName.startsWith("scala.Function") =>
          if args.size < 2 then
            report.errorAndAbort("Signature.fromType requires at least one input type")
          val inputTypes = args.dropRight(1)
          val returnType = args.last
          inputTypes.map(None -> _) -> returnType
        case other =>
          report.errorAndAbort(s"Signature.fromType expects a function type, got: ${other.show}")

    def inputName(index: Int, total: Int, explicit: Option[String]): String =
      explicit.getOrElse(if total == 1 then "input" else s"input${index + 1}")

    val sigName = name.value.filter(_.nonEmpty).getOrElse("Signature")
    val sigNameExpr = '{
      val explicitName = ${ name }
      if explicitName.isEmpty then "Signature" else explicitName
    }
    val (rawInputs, returnType) = functionParts(TypeRepr.of[F])

    if rawInputs.isEmpty then
      report.errorAndAbort("Signature.fromType expects at least one input type")
    if returnType =:= TypeRepr.of[Unit] then
      report.errorAndAbort("Signature.fromType requires an output type, not Unit")

    val inputItems = rawInputs.zipWithIndex.map { case ((maybeName, tpe), index) =>
      inputName(index, rawInputs.size, maybeName) -> tpe
    }
    val duplicateInputs = inputItems.groupBy(_._1).collect {
      case (fieldName, occurrences) if occurrences.size > 1 => fieldName
    }
    if duplicateInputs.nonEmpty then
      report.errorAndAbort(
        s"Signature.fromType has duplicate input names: ${duplicateInputs.mkString(", ")}"
      )

    val inputData = fieldData(sigName, FieldRole.Input, inputItems)
    val inputType = namedTupleType(inputItems)

    def signatureExpr[I : Type, O : Type](
        outputFieldsExpr: Expr[Vector[FieldSpec]],
        outputShapeExpr: Expr[Shape[O]]
    ): Expr[TypedSig[I, O]] =
      materialize[I, O](
        sigNameExpr = sigNameExpr,
        instructionsExpr = instructions,
        errorName = sigName,
        inputFields = inputData,
        outputFieldsExpr = outputFieldsExpr,
        inputShapeExpr = '{ new Shape.TupleShape[I](Vector(${ Varargs(inputData.map(_.fieldSpec)) }*), ${ decoderMapExpr(inputData) }) },
        outputShapeExpr = outputShapeExpr
      )

    def scalarOutputExpr(returnType: TypeRepr): Expr[Any] =
      val outputItems = List("result" -> returnType)
      val outputData = fieldData(sigName, FieldRole.Output, outputItems)
      val outputType = namedTupleType(outputItems)
      (inputType.asType, outputType.asType) match
        case ('[i], '[o]) =>
          val outFieldExprs = outputData.map(_.fieldSpec)
          signatureExpr[i, o](
            outputFieldsExpr = '{ Vector(${ Varargs(outFieldExprs) }*) },
            outputShapeExpr = '{ new Shape.TupleShape[o](Vector(${ Varargs(outFieldExprs) }*), ${ decoderMapExpr(outputData) }) }
          )
        case _ =>
          report.errorAndAbort("Internal error materializing scalar output for function type")

    namedTupleParts(returnType) match
      case Some(outputItems) =>
        if outputItems.isEmpty then
          report.errorAndAbort("Signature.fromType requires at least one output field")
        val outputData = fieldData(sigName, FieldRole.Output, outputItems)
        (inputType.asType, returnType.asType) match
          case ('[i], '[o]) =>
            val outFieldExprs = outputData.map(_.fieldSpec)
            signatureExpr[i, o](
              outputFieldsExpr = '{ Vector(${ Varargs(outFieldExprs) }*) },
              outputShapeExpr = '{ new Shape.TupleShape[o](Vector(${ Varargs(outFieldExprs) }*), ${ decoderMapExpr(outputData) }) }
            )
          case _ =>
            report.errorAndAbort("Internal error materializing named-tuple output for function type")
      case None =>
        if returnType.typeSymbol.flags.is(Flags.Case) || returnType <:< TypeRepr.of[Product] then
          returnType.asType match
            case '[o] =>
              Expr.summon[Mirror.ProductOf[o]] match
                case Some(mirror) =>
                  Expr.summon[Schema[o]] match
                    case Some(schema) =>
                      inputType.asType match
                        case '[i] =>
                          signatureExpr[i, o](
                            outputFieldsExpr = '{
                              Shape
                                .derivedProductWithRole[o](FieldRole.Output)(using ${ mirror }, ${ schema })
                                .fieldSpecs
                            },
                            outputShapeExpr = '{
                              Shape
                                .derivedProductWithRole[o](FieldRole.Output)(using ${ mirror }, ${ schema })
                            }
                          )
                    case None =>
                      report.errorAndAbort(s"No kyo.Schema for product output type '${returnType.show}'")
                case None =>
                  scalarOutputExpr(returnType)
        else scalarOutputExpr(returnType)
