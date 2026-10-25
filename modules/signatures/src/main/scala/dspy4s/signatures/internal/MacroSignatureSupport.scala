package dspy4s.signatures.internal

import dspy4s.core.contracts.TypeMarker
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.Modifier

import scala.deriving.Mirror
import scala.quoted.*
import MacroTypeSupport.{namedTupleParts, namedTupleType, unnamedTupleParts}

/** The signature that the signature macros emit, and the compile-time checks that they share. */
private[signatures] object MacroSignatureSupport:

  /** A call of `Signature.fromShapes` with the canonical shapes of `inputType` and `outputType`. */
  def signatureExpr(using
      quotes: Quotes
  )(
      nameExpr        : Expr[String],
      instructionsExpr: Expr[String],
      inputType       : quotes.reflect.TypeRepr,
      outputType      : quotes.reflect.TypeRepr
  ): Expr[Any] =
    inputType.asType match
      case '[i] => outputType.asType match
          case '[o] => '{
              Signature.fromShapes[i, o](
                $nameExpr,
                $instructionsExpr,
                Shape.canonicalDerived[i],
                Shape.canonicalDerived[o]
              )
            }

  /** The signature of a function with the parameters `inputs` and the return type `returnType`. `owner` names the
    * function in the compile errors.
    *
    * The return type gives the outputs:
    *   - a named tuple keeps its names, and a tuple gets the names `_1`, `_2`, and so on
    *   - a case class or another product keeps the names of its fields
    *   - a single-value type (see [[isSingleValue]]) and each type that is not a product become one output field with
    *     the name `result`
    */
  def functionSignatureExpr(using
      quotes: Quotes
  )(
      owner           : String,
      nameExpr        : Expr[String],
      instructionsExpr: Expr[String],
      inputs          : List[(String, quotes.reflect.TypeRepr)],
      returnType      : quotes.reflect.TypeRepr
  ): Expr[Any] =
    import quotes.reflect.*
    if inputs.isEmpty then report.errorAndAbort(s"$owner requires at least one input")
    if returnType =:= TypeRepr.of[Unit] then report.errorAndAbort(s"$owner requires an output type, not Unit")

    val (outputType, outputNames) = namedTupleParts(returnType).orElse(unnamedTupleParts(returnType)) match
      case Some(Nil)   => report.errorAndAbort(s"$owner requires at least one output field")
      case Some(parts) => (returnType, parts.map(_._1))
      case None        =>
        val isProduct = returnType.asType match
          case '[o] => Expr.summon[Mirror.ProductOf[o]].isDefined
        if isProduct && !isSingleValue(returnType) then (returnType, returnType.typeSymbol.caseFields.map(_.name))
        else (namedTupleType(List("result" -> returnType)), List("result"))
    requireUniqueNames(owner, inputs.map(_._1), outputNames)
    signatureExpr(nameExpr, instructionsExpr, namedTupleType(inputs), outputType)

  /** True for a case class that the framework reads as one value, not as a record of fields: a type whose marker
    * ([[TypeMarker]]) is in [[TypeMarker.singleValue]], for example `Noul`, `Score`, `Choice`, or `History`. The macro
    * reads the marker from the `Modifier.config` annotations of the type symbol, as `Schema.derived` does. A function
    * that returns such a type has one `result` output.
    */
  private def isSingleValue(using quotes: Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    markerOf(tpe).exists(TypeMarker.singleValue.contains)

  /** The marker of the type `tpe`: the value of its `Modifier.config` annotation with the key [[TypeMarker.Key]]. */
  private def markerOf(using quotes: Quotes)(tpe: quotes.reflect.TypeRepr): Option[String] =
    import quotes.reflect.*
    // An annotation argument is a literal, or a reference to a constant: its type is then a constant type.
    def text(argument: Term): Option[String] =
      argument.tpe.widenTermRefByName match
        case ConstantType(StringConstant(value)) => Some(value)
        case _                                   => None
    tpe.dealias.typeSymbol.annotations
      .filter(_.tpe <:< TypeRepr.of[Modifier.config])
      .collectFirst { case Apply(_, List(key, value)) if text(key).contains(TypeMarker.Key) => text(value) }
      .flatten

  /** Stop the compilation when two field names are the same: two inputs, two outputs, or an input and an output.
    * `owner` names the declaration in the compile error.
    */
  def requireUniqueNames(using quotes: Quotes)(owner: String, inputs: List[String], outputs: List[String]): Unit =
    val names      = inputs ++ outputs
    val duplicates = names.diff(names.distinct).distinct
    if duplicates.nonEmpty then
      quotes.reflect.report.errorAndAbort(s"$owner has duplicate field names: ${duplicates.mkString(", ")}")
