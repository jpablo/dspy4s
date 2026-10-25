package dspy4s.signatures.internal

import dspy4s.signatures.{InputField, OutputField, Spec}
import scala.quoted.*
import MacroSignatureSupport.{requireUniqueNames, signatureExpr}
import MacroTypeSupport.namedTupleType

private[signatures] object SpecMacro:

  /** Implementation of `Signature.of[T <: Spec]`. The macro reads the abstract methods of `T` and of each parent trait
    * of `T` that extends `Spec`. Each method must give `InputField[X]` or `OutputField[X]`. The macro emits a
    * `Signature[I, O]`, where `I` and `O` are named tuples with the input fields and the output fields of the spec. The
    * canonical schema of each named tuple gives the field specs (the `TypeRef` of each field) and the codec.
    *
    * The fields of the parents come first, in the reverse order of the linearization: the most basic parent first, and
    * `T` last. The fields of one trait are in their source order. A subtrait can declare a field of a parent again with
    * the same role and the same type. Then the two declarations are one field, at the position of the parent field.
    *
    * These are compile errors:
    *   - a concrete (not abstract) method on a spec trait
    *   - a method that does not give `InputField[X]` or `OutputField[X]`
    *   - a method with parameters
    *   - two fields with the same name: two inputs, two outputs, an input and an output, or a field that a subtrait
    *     declares again with a different role or type
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

    val specSym        = TypeRepr.of[Spec].typeSymbol
    val inputFieldSym  = TypeRepr.of[InputField[Any]].typeSymbol
    val outputFieldSym = TypeRepr.of[OutputField[Any]].typeSymbol

    // The traits that declare fields: each class of the linearization of `T` that extends `Spec`, but not `Spec`. Thus
    // the members of `Spec`, `Any`, and `AnyRef` are not fields. The reverse order puts the parents before `T`.
    val fieldOwners = tpe.baseClasses.reverse.filter(cls => cls != specSym && tpe.baseType(cls) <:< TypeRepr.of[Spec])

    // The methods that `owner` itself declares, with no synthetic methods, in their source order. A method with no
    // source position keeps its place after the methods with a position.
    def ownMethods(owner: Symbol): List[Symbol] =
      owner.declaredMethods
        .filter(m => !m.flags.is(Flags.Synthetic))
        .sortBy(_.pos.fold(Int.MaxValue)(_.start))

    // A spec trait only declares fields, thus each method must be abstract.
    fieldOwners.foreach { owner =>
      val concrete = ownMethods(owner).filterNot(_.flags.is(Flags.Deferred))
      if concrete.nonEmpty then
        report.errorAndAbort(
          s"Spec trait '${owner.name}' must declare only abstract field methods; " +
            s"found concrete method(s): ${concrete.map(_.name).mkString(", ")}"
        )
    }

    val methods = fieldOwners.flatMap(owner => ownMethods(owner).map(owner -> _))
    if methods.isEmpty then
      report.errorAndAbort(
        s"Spec trait '$specName' must declare at least one InputField or OutputField method"
      )

    // For each method: the name, `true` for an input, and the type `X` of `InputField[X]` or `OutputField[X]`.
    val declared: List[(String, Boolean, TypeRepr)] = methods.map { (owner, m) =>
      val name = m.name

      // A spec method declares a field, thus it has no parameters.
      if m.paramSymss.exists(_.nonEmpty) then
        report.errorAndAbort(
          s"Spec method '${owner.name}.$name' must be parameterless (got parameters: ${m.paramSymss})"
        )

      // The type of a `def` with no parameters is a `ByNameType`, and the type of a `def` with an empty parameter list
      // is a `MethodType`. The result type is inside each of them.
      val returnType = tpe.memberType(m) match
        case ByNameType(result)       => result
        case MethodType(_, _, result) => result
        case other                    => other

      val (isInput, innerType) = returnType match
        case AppliedType(tc, List(arg)) if tc.typeSymbol == inputFieldSym  => (true, arg)
        case AppliedType(tc, List(arg)) if tc.typeSymbol == outputFieldSym => (false, arg)
        case other                                                         => report.errorAndAbort(
            s"Spec method '${owner.name}.$name' must return InputField[X] or OutputField[X], got: ${other.show}"
          )

      (name, isInput, innerType)
    }

    // A field that a subtrait declares again with the same role and the same type is one field. Each other field with
    // the same name stays, thus `requireUniqueNames` stops the compilation.
    val fieldData = declared.foldLeft(Vector.empty[(String, Boolean, TypeRepr)]) { (fields, field) =>
      val (fieldName, isInput, innerType) = field
      val again                           = fields.exists { (n, in, t) => n == fieldName && in == isInput && t =:= innerType }
      if again then fields else fields :+ field
    }
    val inputs  = fieldData.collect { case (n, true, t) => n -> t }.toList
    val outputs = fieldData.collect { case (n, false, t) => n -> t }.toList

    requireUniqueNames(s"Spec trait '$specName'", inputs.map(_._1), outputs.map(_._1))

    val sigNameExpr = '{
      val explicitName = ${ name }
      if explicitName.isEmpty then ${ Expr(specName) } else explicitName
    }

    signatureExpr(sigNameExpr, instructions, namedTupleType(inputs), namedTupleType(outputs))
