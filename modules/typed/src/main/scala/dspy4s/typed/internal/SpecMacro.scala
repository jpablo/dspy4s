package dspy4s.typed.internal

import dspy4s.core.contracts.{FieldRole, FieldSpec, SignatureSpec}
import dspy4s.typed.{InputField, OutputField, Shape, Spec, TypedSignature, ValueDecoder}
import scala.quoted.*

private[typed] object SpecMacro:

  /** Implementation of `TypedSignature.of[T <: Spec]`. Inspects T's
    * abstract methods at compile time, validates each returns
    * `InputField[X]` or `OutputField[X]`, summons a `ValueDecoder[X]`,
    * and emits a `TypedSignature[Map[String, Any], Map[String, Any]]`
    * whose `inputShape` / `outputShape` are `MapShape`s carrying the
    * derived `FieldSpec` vectors.
    *
    * Compile errors:
    *   - concrete (non-abstract) methods on the spec trait
    *   - methods that don't return `InputField[X]` or `OutputField[X]`
    *   - methods with parameters
    *   - duplicate field names
    *   - missing `ValueDecoder[X]` for any wrapped type */
  def ofImpl[T <: Spec : Type](using Quotes)
      : Expr[TypedSignature[Map[String, Any], Map[String, Any]]] =
    import quotes.reflect.*

    val tpe        = TypeRepr.of[T]
    val typeSym    = tpe.typeSymbol
    val sigName    = typeSym.name

    val inputFieldSym  = TypeRepr.of[InputField[Any]].typeSymbol
    val outputFieldSym = TypeRepr.of[OutputField[Any]].typeSymbol

    // Abstract methods declared on the trait, in declaration order.
    val methods = typeSym.declaredMethods.filter { m =>
      val flags = m.flags
      flags.is(Flags.Deferred) && !flags.is(Flags.Synthetic)
    }

    if methods.isEmpty then
      report.errorAndAbort(
        s"Spec trait '$sigName' must declare at least one InputField or OutputField method"
      )

    // For each method: (name, isInput, innerType-Repr, FieldSpec-Expr)
    val fieldData: List[(String, Boolean, TypeRepr, Expr[FieldSpec])] =
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

        // Summon a ValueDecoder[X] at the macro expansion site.
        val decoderExpr = innerType.asType match
          case '[t] =>
            Expr.summon[ValueDecoder[t]] match
              case Some(d) => d
              case None =>
                report.errorAndAbort(
                  s"No ValueDecoder[${innerType.show}] in scope for spec field '$sigName.$name'"
                )

        // Build: FieldSpec(name, role, decoder.typeRef, metadata = decoder.metadata)
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

        (name, isInput, innerType, fieldSpecExpr)
      }

    // Reject duplicate field names.
    val duplicates = fieldData.groupBy(_._1).collect {
      case (n, occurrences) if occurrences.size > 1 => n
    }
    if duplicates.nonEmpty then
      report.errorAndAbort(
        s"Spec trait '$sigName' has duplicate field names: ${duplicates.mkString(", ")}"
      )

    val inputFieldExprs:  List[Expr[FieldSpec]] = fieldData.collect { case (_, true,  _, e) => e }
    val outputFieldExprs: List[Expr[FieldSpec]] = fieldData.collect { case (_, false, _, e) => e }
    val sigNameExpr = Expr(sigName)

    '{
      val inFields: Vector[FieldSpec]  = Vector(${ Varargs(inputFieldExprs)  }*)
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
      TypedSignature[Map[String, Any], Map[String, Any]](
        name        = ${ sigNameExpr },
        untyped     = sig,
        inputShape  = new Shape.MapShape(inFields),
        outputShape = new Shape.MapShape(outFields)
      )
    }
