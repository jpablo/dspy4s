package dspy4s.signatures.internal

import dspy4s.core.contracts.{FieldSpec, History, SignatureLayout, TypeRef}
import dspy4s.signatures.{Shape, Signature}
import scala.quoted.*
import MacroSignatureSupport.functionSignatureExpr
import MacroTypeSupport.namedTupleType

private[signatures] object FunctionMacro:

  /** Implementation of `Signature.from(method)`. The macro does not call the method. The names and the types of its
    * parameters become the inputs, and its return type gives the outputs.
    */
  def fromImpl[F: Type](fn: Expr[F])(using Quotes): Expr[Any] =
    import quotes.reflect.*
    given CanEqual[Symbol, Symbol] = CanEqual.derived

    def unwrap(term: Term): Term =
      term match
        case Inlined(_, _, inner) => unwrap(inner)
        case Typed(inner, _)      => unwrap(inner)
        case Block(Nil, inner)    => unwrap(inner)
        case other                => other

    def referencedMethod(term: Term): Symbol =
      unwrap(term) match
        case id: Ident if id.symbol.isDefDef                        => id.symbol
        case sel: Select if sel.symbol.isDefDef                     => sel.symbol
        case Block(stats, Closure(meth, _)) if meth.symbol.isDefDef =>
          stats.collectFirst {
            case dd: DefDef if dd.symbol == meth.symbol => dd
          }.flatMap(_.rhs.flatMap(calledMethod)).getOrElse(meth.symbol)
        case Closure(meth, _) if meth.symbol.isDefDef => meth.symbol
        case other                                    => report.errorAndAbort(
            "Signature.from expects a method reference, e.g. " +
              "`Signature.from(emotionsSig)`; got: " + other.show
          )

    def methodDef(sym: Symbol): DefDef =
      sym.tree match
        case dd: DefDef => dd
        case _          => report.errorAndAbort(
            s"Signature.from could not inspect method '${sym.name}'"
          )

    def calledMethod(term: Term): Option[Symbol] =
      unwrap(term) match
        case Apply(fn, _) if fn.symbol.isDefDef && !fn.symbol.name.startsWith("$anonfun")  => Some(fn.symbol)
        case Apply(fn, _)                                                                  => calledMethod(fn)
        case TypeApply(fn, _)                                                              => calledMethod(fn)
        case id: Ident if id.symbol.isDefDef && !id.symbol.name.startsWith("$anonfun")     => Some(id.symbol)
        case sel: Select if sel.symbol.isDefDef && !sel.symbol.name.startsWith("$anonfun") => Some(sel.symbol)
        case _                                                                             => None

    def dealiasedMethod(sym: Symbol): Symbol =
      if sym.name.startsWith("$anonfun") then
        methodDef(sym).rhs.flatMap(calledMethod).getOrElse(sym)
      else sym

    val sym     = dealiasedMethod(referencedMethod(fn.asTerm))
    val dd      = methodDef(sym)
    val sigName = sym.name

    if dd.paramss.exists {
        case _: TypeParamClause => true
        case _                  => false
      }
    then
      report.errorAndAbort(s"Signature.from does not support polymorphic method '$sigName'")

    val termParamClauses = dd.paramss.collect { case clause: TermParamClause => clause }
    if termParamClauses.size != 1 then
      report.errorAndAbort(
        s"Signature.from expects method '$sigName' to have exactly one parameter list"
      )

    functionSignatureExpr(
      owner = s"Signature.from (method '$sigName')",
      nameExpr = Expr(sigName),
      instructionsExpr = Expr(""),
      inputs = termParamClauses.head.params.map(vd => vd.name -> vd.tpt.tpe),
      returnType = dd.returnTpt.tpe
    )

  /** Implementation of `Signature.fromType[F]`. It reads a function type, not a method term. */
  def fromTypeImpl[F: Type](
      name        : Expr[String],
      instructions: Expr[String]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*

    def isFunction(tc: TypeRepr): Boolean = tc.typeSymbol.fullName.startsWith("scala.Function")

    def functionParts(tpe: TypeRepr): (List[(Option[String], TypeRepr)], TypeRepr) =
      tpe.dealias match
        case Refinement(parent, "apply", MethodType(paramNames, paramTypes, returnType)) =>
          val parentParts = parent.dealias match
            case AppliedType(tc, args) if isFunction(tc) => args
            case other                                   => report.errorAndAbort(s"Signature.fromType expects a function type, got: ${other.show}")
          val parentInputs = parentParts.dropRight(1)
          val inputs       =
            if paramTypes.size == parentInputs.size then paramTypes
            else parentInputs
          paramNames.map(Some(_)).zip(inputs) -> returnType
        case AppliedType(tc, args) if isFunction(tc) => args.dropRight(1).map(None -> _) -> args.last
        case other                                   => report.errorAndAbort(s"Signature.fromType expects a function type, got: ${other.show}")

    def inputName(index: Int, total: Int, explicit: Option[String]): String =
      explicit.getOrElse(if total == 1 then "input" else s"input${index + 1}")

    val (rawInputs, returnType) = functionParts(TypeRepr.of[F])
    functionSignatureExpr(
      owner = "Signature.fromType",
      nameExpr = '{
        val explicitName = ${ name }
        if explicitName.isEmpty then "Signature" else explicitName
      },
      instructionsExpr = instructions,
      inputs = rawInputs.zipWithIndex.map { case ((maybeName, tpe), index) =>
        inputName(index, rawInputs.size, maybeName) -> tpe
      },
      returnType = returnType
    )

  /** Implementation of `Signature.fromString("a -> b")`. The DSL text must be a literal that the compiler knows. The
    * macro parses it with the runtime `SignatureLayout.parse`, and each field becomes an element of a named tuple. A
    * field with no type is a `String`, and `int`, `float`, and `bool` give the matching scalar. A `Literal[...]` field
    * is a `String`. An optional field (`Optional[T]` or `T | None`) is an `Option[T]`. An input field with the type
    * `History` becomes a `dspy4s.core.contracts.History` element. An invalid DSL text, or a field type that the
    * `Signature` API does not support, is a compile error.
    *
    * The layout comes from the parsed DSL, thus it keeps the attributes of the text, for example the enum values of a
    * `Literal` field. The canonical shapes of the named tuples give only the codec.
    */
  def fromStringImpl(dsl: Expr[String], instructions: Expr[String])(using Quotes): Expr[Any] =
    import quotes.reflect.*

    val literal = dsl.value.getOrElse(
      report.errorAndAbort(
        "Signature.fromString requires a string literal known at compile time. For a signature built from a " +
          "runtime string, use Signature.fromStringDynamic."
      )
    )

    val layout = SignatureLayout.parse(literal) match
      case Right(parsed) => parsed
      case Left(err)     => report.errorAndAbort(s"""Invalid signature DSL "$literal": ${err.message}""")

    def valueType(field: FieldSpec, isInput: Boolean): TypeRepr =
      field.typeRef.repr match
        case "string"             => TypeRepr.of[String]
        case "int"                => TypeRepr.of[Int]
        case "double"             => TypeRepr.of[Double]
        case "bool"               => TypeRepr.of[Boolean]
        case "history" if isInput => TypeRepr.of[History]
        case other                => report.errorAndAbort(
            s"Signature.fromString: field '${field.name}' has type '$other', which the string DSL does " +
              "not support (supported: str, int, float, bool, and History for an input field). For richer field " +
              "types use Signature.of[Spec], a case class, or Signature.fromStringDynamic."
          )

    def scalaType(field: FieldSpec, isInput: Boolean): TypeRepr =
      val value = valueType(field, isInput)
      if field.optional then TypeRepr.of[Option].appliedTo(value) else value

    val inputItems  = layout.inputFields.toList.map(field => field.name -> scalaType(field, isInput = true))
    val outputItems = layout.outputFields.toList.map(field => field.name -> scalaType(field, isInput = false))
    if inputItems.isEmpty then
      report.errorAndAbort(s"""Signature.fromString "$literal" declares no input fields""")
    if outputItems.isEmpty then
      report.errorAndAbort(s"""Signature.fromString "$literal" declares no output fields""")

    layoutSignatureExpr(layout, instructions, namedTupleType(inputItems).asType, namedTupleType(outputItems).asType)

  /** A call of `Signature.fromFieldSpecs` with the field specs of `layout` and the canonical shapes of `inputType` and
    * `outputType`.
    */
  private def layoutSignatureExpr(
      layout      : SignatureLayout,
      instructions: Expr[String],
      inputType   : Type[?],
      outputType  : Type[?]
  )(using Quotes): Expr[Any] =
    inputType match
      case '[i] => outputType match
          case '[o] => '{
              Signature.fromFieldSpecs[i, o](
                ${ Expr(layout.name) },
                $instructions,
                ${ fieldSpecsExpr(layout.inputFields) },
                ${ fieldSpecsExpr(layout.outputFields) },
                Shape.canonicalDerived[i],
                Shape.canonicalDerived[o]
              )
            }

  /** The code that makes the field specs `fields` again at run time. It keeps each attribute that the DSL parse gives:
    * the name, the type, the description, the prefix, the enum values, and the optional flag. The parse gives no
    * default value and no constraint.
    */
  private def fieldSpecsExpr(fields: Vector[FieldSpec])(using Quotes): Expr[Vector[FieldSpec]] =
    val specs = fields.toList.map { field =>
      '{
        FieldSpec(
          name = ${ Expr(field.name) },
          typeRef = TypeRef(${ Expr(field.typeRef.repr) }),
          description = ${ Expr(field.description) },
          prefix = ${ Expr(field.prefix) },
          enumValues = Vector(${ Varargs(field.enumValues.map(Expr(_))) }*),
          optional = ${ Expr(field.optional) }
        )
      }
    }
    '{ Vector(${ Varargs(specs) }*) }
