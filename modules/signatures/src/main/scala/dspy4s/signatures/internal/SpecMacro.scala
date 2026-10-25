package dspy4s.signatures.internal

import dspy4s.signatures.{InputField, OutputField, Spec}
import scala.quoted.*
import MacroSignatureSupport.{requireUniqueNames, signatureExpr}
import MacroTypeSupport.namedTupleType

private[signatures] object SpecMacro:

  /** Implementation of `Signature.of[T <: Spec]`. The macro reads the abstract methods of `T`. Each method must give
    * `InputField[X]` or `OutputField[X]`. The macro emits a `Signature[I, O]`, where `I` and `O` are named tuples with
    * the input fields and the output fields of the spec. The canonical schema of each named tuple gives the field specs
    * (the `TypeRef` of each field) and the codec.
    *
    * These are compile errors:
    *   - a concrete (not abstract) method on the spec trait
    *   - a method that does not give `InputField[X]` or `OutputField[X]`
    *   - a method with parameters
    *   - two fields with the same name
    *   - a field type with no structural schema
    */
  def ofImpl[T <: Spec: Type](
      name        : Expr[String],
      instructions: Expr[String]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*
    given CanEqual[Symbol, Symbol] = CanEqual.derived

    val tpe      = TypeRepr.of[T]
    val typeSym  = tpe.typeSymbol
    val specName = typeSym.name

    val inputFieldSym  = TypeRepr.of[InputField[Any]].typeSymbol
    val outputFieldSym = TypeRepr.of[OutputField[Any]].typeSymbol

    // The methods that the trait itself declares, with no synthetic methods. `declaredMethods` does not include the
    // methods that the trait gets from `Spec` or `Any`.
    val methods = typeSym.declaredMethods.filter(m => !m.flags.is(Flags.Synthetic))

    // A spec trait only declares fields, thus each method must be abstract.
    val concrete = methods.filterNot(_.flags.is(Flags.Deferred))
    if concrete.nonEmpty then
      report.errorAndAbort(
        s"Spec trait '$specName' must declare only abstract field methods; " +
          s"found concrete method(s): ${concrete.map(_.name).mkString(", ")}"
      )

    if methods.isEmpty then
      report.errorAndAbort(
        s"Spec trait '$specName' must declare at least one InputField or OutputField method"
      )

    // For each method: the name, `true` for an input, and the type `X` of `InputField[X]` or `OutputField[X]`.
    val fieldData: List[(String, Boolean, TypeRepr)] = methods.map { m =>
      val name = m.name

      // A spec method declares a field, thus it has no parameters.
      if m.paramSymss.exists(_.nonEmpty) then
        report.errorAndAbort(
          s"Spec method '$specName.$name' must be parameterless (got parameters: ${m.paramSymss})"
        )

      // Read the declared return type from the DefDef. `tpe.memberType(m)` wraps the type of a `def` with no
      // parameters in a `NullaryMethodType` or a `ByNameType`, and the DefDef does not.
      val returnType = m.tree match
        case dd: DefDef => dd.returnTpt.tpe
        case _          => report.errorAndAbort(
            s"Spec member '$specName.$name' must be a `def` declaration"
          )

      val (isInput, innerType) = returnType match
        case AppliedType(tc, List(arg)) if tc.typeSymbol == inputFieldSym  => (true, arg)
        case AppliedType(tc, List(arg)) if tc.typeSymbol == outputFieldSym => (false, arg)
        case other                                                         => report.errorAndAbort(
            s"Spec method '$specName.$name' must return InputField[X] or OutputField[X], got: ${other.show}"
          )

      (name, isInput, innerType)
    }

    requireUniqueNames(s"Spec trait '$specName'", fieldData.map(_._1))

    val sigNameExpr = '{
      val explicitName = ${ name }
      if explicitName.isEmpty then ${ Expr(specName) } else explicitName
    }

    signatureExpr(
      sigNameExpr,
      instructions,
      namedTupleType(fieldData.collect { case (n, true, tpe) => n -> tpe }),
      namedTupleType(fieldData.collect { case (n, false, tpe) => n -> tpe })
    )
