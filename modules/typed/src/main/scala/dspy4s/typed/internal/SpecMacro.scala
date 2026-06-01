package dspy4s.typed.internal

import dspy4s.core.contracts.{FieldRole, SignatureLayout}
import dspy4s.typed.{InputField, OutputField, Shape, Spec, Signature as TypedSig}
import zio.blocks.schema.Schema
import scala.quoted.*

private[typed] object SpecMacro:

  /** Implementation of `Signature.of[T <: Spec]`. Inspects T's
    * abstract methods at compile time, validates each returns
    * `InputField[X]` or `OutputField[X]`, confirms a `Schema[X]` is in
    * scope, and emits a `Signature[I, O]` whose `I` and `O` are named
    * tuples carrying the spec's input and output fields. Field metadata
    * (`TypeRef`) and the value codec are both derived from the named
    * tuple's `Schema` -- there is no per-field `FieldCodec`.
    *
    * Compile errors:
    *   - concrete (non-abstract) methods on the spec trait
    *   - methods that don't return `InputField[X]` or `OutputField[X]`
    *   - methods with parameters
    *   - duplicate field names
    *   - missing `Schema[X]` for any wrapped type */
  def ofImpl[T <: Spec : Type](
      name: Expr[String],
      instructions: Expr[String]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*
    given CanEqual[Symbol, Symbol] = CanEqual.derived

    val tpe        = TypeRepr.of[T]
    val typeSym    = tpe.typeSymbol
    val specName   = typeSym.name

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
        s"Spec trait '$specName' must declare only abstract field methods; " +
        s"found concrete method(s): ${concrete.map(_.name).mkString(", ")}"
      )

    val methods = allDeclared  // all deferred by construction

    if methods.isEmpty then
      report.errorAndAbort(
        s"Spec trait '$specName' must declare at least one InputField or OutputField method"
      )

    // For each method: (name, isInput, inner type X from InputField[X] / OutputField[X]).
    // Field metadata (typeRef) and the value codec are both derived later from the
    // zio-blocks `Schema` of the input / output named tuple -- no per-field FieldCodec.
    val fieldData: List[(String, Boolean, TypeRepr)] =
      methods.map { m =>
        val name = m.name

        // Reject methods that take parameters -- spec methods must be
        // parameterless field declarations.
        if m.paramSymss.exists(_.nonEmpty) then
          report.errorAndAbort(
            s"Spec method '$specName.$name' must be parameterless (got parameters: ${m.paramSymss})"
          )

        // Read the declared return type directly from the DefDef. This
        // sidesteps the NullaryMethodType / ByNameType wrappers that
        // `tpe.memberType(m)` produces for parameterless `def`s.
        val returnType = m.tree match
          case dd: DefDef => dd.returnTpt.tpe
          case _ =>
            report.errorAndAbort(
              s"Spec member '$specName.$name' must be a `def` declaration"
            )

        val (isInput, innerType) = returnType match
          case AppliedType(tc, List(arg)) if tc.typeSymbol == inputFieldSym  => (true,  arg)
          case AppliedType(tc, List(arg)) if tc.typeSymbol == outputFieldSym => (false, arg)
          case other =>
            report.errorAndAbort(
              s"Spec method '$specName.$name' must return InputField[X] or OutputField[X], got: ${other.show}"
            )

        // Validate up front that the field type has a zio-blocks Schema, so a missing one
        // gives a per-field error here rather than a derivation failure on the whole tuple.
        innerType.asType match
          case '[t] =>
            if Expr.summon[Schema[t]].isEmpty then
              report.errorAndAbort(
                s"No Schema[${innerType.show}] in scope for spec field '$specName.$name'. Spec field " +
                "types must have a zio-blocks Schema (a primitive, an enum, or a type that `derives Schema`)."
              )

        (name, isInput, innerType)
      }

    // Reject duplicate field names.
    val duplicates = fieldData.groupBy(_._1).collect {
      case (n, occurrences) if occurrences.size > 1 => n
    }
    if duplicates.nonEmpty then
      report.errorAndAbort(
        s"Spec trait '$specName' has duplicate field names: ${duplicates.mkString(", ")}"
      )

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

    val sigNameExpr = '{
      val explicitName = ${ name }
      if explicitName.isEmpty then ${ Expr(specName) } else explicitName
    }

    val inputType  = namedTupleType(inputData.map { case (n, _, tpe) => n -> tpe })
    val outputType = namedTupleType(outputData.map { case (n, _, tpe) => n -> tpe })

    (inputType.asType, outputType.asType) match
      case ('[i], '[o]) =>
        '{
          // Shapes are fully schema-backed; their `fieldSpecs` (names, wire typeRefs) come from the
          // derived `Reflect`, with the role stamped per side. The layout is assembled from them.
          val inputShape  = new Shape.SchemaTupleShape[i](FieldRole.Input,  Schema.derived[i])
          val outputShape = new Shape.SchemaTupleShape[o](FieldRole.Output, Schema.derived[o])
          val sig = SignatureLayout
            .create(
              name         = ${ sigNameExpr },
              fields       = inputShape.fieldSpecs ++ outputShape.fieldSpecs,
              instructions = Option(${ instructions }).filter(_.nonEmpty)
            )
            .fold(
              err => throw new IllegalStateException(
                s"Internal error materializing spec trait '${${ sigNameExpr }}': ${err.message}"
              ),
              identity
            )
          TypedSig[i, o](
            name        = ${ sigNameExpr },
            layout      = sig,
            inputShape  = inputShape,
            outputShape = outputShape
          )
        }
      case _ =>
        report.errorAndAbort(s"Internal error materializing spec trait '$specName'")
