package dspy4s.typed.internal

import dspy4s.core.contracts.{FieldRole, FieldSpec, SignatureSpec}
import dspy4s.typed.{InputField, OutputField, Shape, Spec, TypedSignature, ValueDecoder}
import scala.quoted.*

private[typed] object SpecMacro:

  /** Implementation of `TypedSignature.of[T <: Spec]`. Inspects T's
    * abstract methods at compile time, validates each returns
    * `InputField[X]` or `OutputField[X]`, summons a `ValueDecoder[X]`,
    * and emits a `TypedSignature[I, O]` whose `I` and `O` are named
    * tuples carrying the spec's input and output fields.
    *
    * Compile errors:
    *   - concrete (non-abstract) methods on the spec trait
    *   - methods that don't return `InputField[X]` or `OutputField[X]`
    *   - methods with parameters
    *   - duplicate field names
    *   - missing `ValueDecoder[X]` for any wrapped type */
  def ofImpl[T <: Spec : Type](using Quotes): Expr[Any] =
    import quotes.reflect.*

    val tpe        = TypeRepr.of[T]
    val typeSym    = tpe.typeSymbol
    val sigName    = typeSym.name

    val inputFieldSym  = TypeRepr.of[InputField[Any]].typeSymbol
    val outputFieldSym = TypeRepr.of[OutputField[Any]].typeSymbol

    // Methods declared on the trait itself (excluding synthetic /
    // compiler-generated ones). Inherited methods from Spec / Any are
    // not in declaredMethods.
    val allDeclared = typeSym.declaredMethods.filter(m => !m.flags.is(Flags.Synthetic))

    // Reject concrete (non-abstract) methods on the spec trait. Spec
    // traits must be purely declarative -- every member is a field
    // declaration.
    val concrete = allDeclared.filterNot(_.flags.is(Flags.Deferred))
    if concrete.nonEmpty then
      report.errorAndAbort(
        s"Spec trait '$sigName' must declare only abstract field methods; " +
        s"found concrete method(s): ${concrete.map(_.name).mkString(", ")}"
      )

    val methods = allDeclared  // all deferred by construction

    if methods.isEmpty then
      report.errorAndAbort(
        s"Spec trait '$sigName' must declare at least one InputField or OutputField method"
      )

    // For each method: (name, isInput, inner type, FieldSpec-Expr,
    // ValueDecoder-Expr).
    // The decoder expression is cast to ValueDecoder[Any] so we can carry
    // a uniform list-of-decoders type into the macro output.
    val fieldData: List[(String, Boolean, TypeRepr, Expr[FieldSpec], Expr[ValueDecoder[Any]])] =
      methods.map { m =>
        val name = m.name

        // Reject methods that take parameters -- spec methods must be
        // parameterless field declarations.
        if m.paramSymss.exists(_.nonEmpty) then
          report.errorAndAbort(
            s"Spec method '$sigName.$name' must be parameterless (got parameters: ${m.paramSymss})"
          )

        // Read the declared return type directly from the DefDef. This
        // sidesteps the NullaryMethodType / ByNameType wrappers that
        // `tpe.memberType(m)` produces for parameterless `def`s.
        val returnType = m.tree match
          case dd: DefDef => dd.returnTpt.tpe
          case _ =>
            report.errorAndAbort(
              s"Spec member '$sigName.$name' must be a `def` declaration"
            )

        val (isInput, innerType) = returnType match
          case AppliedType(tc, List(arg)) if tc.typeSymbol == inputFieldSym  => (true,  arg)
          case AppliedType(tc, List(arg)) if tc.typeSymbol == outputFieldSym => (false, arg)
          case other =>
            report.errorAndAbort(
              s"Spec method '$sigName.$name' must return InputField[X] or OutputField[X], got: ${other.show}"
            )

        // Summon a ValueDecoder[X] at the macro expansion site, then cast
        // to ValueDecoder[Any] so the runtime decoder map can carry
        // heterogeneous types.
        val decoderExpr: Expr[ValueDecoder[Any]] = innerType.asType match
          case '[t] =>
            Expr.summon[ValueDecoder[t]] match
              case Some(d) => '{ ${ d }.asInstanceOf[ValueDecoder[Any]] }
              case None =>
                report.errorAndAbort(
                  s"No ValueDecoder[${innerType.show}] in scope for spec field '$sigName.$name'"
                )

        val nameExpr = Expr(name)
        val roleExpr =
          if isInput then '{ FieldRole.Input } else '{ FieldRole.Output }
        val fieldSpecExpr = '{
          FieldSpec(
            name     = ${ nameExpr },
            role     = ${ roleExpr },
            typeRef  = ${ decoderExpr }.typeRef,
            metadata = ${ decoderExpr }.metadata
          )
        }

        (name, isInput, innerType, fieldSpecExpr, decoderExpr)
      }

    // Reject duplicate field names.
    val duplicates = fieldData.groupBy(_._1).collect {
      case (n, occurrences) if occurrences.size > 1 => n
    }
    if duplicates.nonEmpty then
      report.errorAndAbort(
        s"Spec trait '$sigName' has duplicate field names: ${duplicates.mkString(", ")}"
      )

    def buildDecoderMapExpr(items: List[(String, Boolean, TypeRepr, Expr[FieldSpec], Expr[ValueDecoder[Any]])])
        : Expr[Map[String, ValueDecoder[Any]]] =
      val pairs: List[Expr[(String, ValueDecoder[Any])]] = items.map { case (n, _, _, _, dec) =>
        val nameExpr = Expr(n)
        '{ ${ nameExpr } -> ${ dec } }
      }
      '{ Map(${ Varargs(pairs) }*) }

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

    val inputData  = fieldData.filter(_._2)
    val outputData = fieldData.filterNot(_._2)

    val inputFieldExprs:  List[Expr[FieldSpec]] = inputData.map(_._4)
    val outputFieldExprs: List[Expr[FieldSpec]] = outputData.map(_._4)
    val inputDecodersExpr  = buildDecoderMapExpr(fieldData.filter(_._2))
    val outputDecodersExpr = buildDecoderMapExpr(fieldData.filterNot(_._2))
    val sigNameExpr        = Expr(sigName)

    val inputType  = namedTupleType(inputData.map { case (n, _, tpe, _, _) => n -> tpe })
    val outputType = namedTupleType(outputData.map { case (n, _, tpe, _, _) => n -> tpe })

    (inputType.asType, outputType.asType) match
      case ('[i], '[o]) =>
        '{
          val inFields:  Vector[FieldSpec] = Vector(${ Varargs(inputFieldExprs)  }*)
          val outFields: Vector[FieldSpec] = Vector(${ Varargs(outputFieldExprs) }*)
          val sig = SignatureSpec
            .create(
              name   = ${ sigNameExpr },
              fields = inFields ++ outFields
            )
            .fold(
              err => throw new IllegalStateException(
                s"Internal error materializing spec trait '${${ sigNameExpr }}': ${err.message}"
              ),
              identity
            )
          TypedSignature[i, o](
            name        = ${ sigNameExpr },
            untyped     = sig,
            inputShape  = new Shape.TupleShape[i](inFields,  ${ inputDecodersExpr }),
            outputShape = new Shape.TupleShape[o](outFields, ${ outputDecodersExpr })
          )
        }
