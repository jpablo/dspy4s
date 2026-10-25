package dspy4s.signatures.internal

import dspy4s.signatures.{Shape, Signature}

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
    *   - each other type becomes one output field with the name `result`
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
    requireUniqueNames(owner, inputs.map(_._1))

    val outputType = namedTupleParts(returnType).orElse(unnamedTupleParts(returnType)) match
      case Some(Nil) => report.errorAndAbort(s"$owner requires at least one output field")
      case Some(_)   => returnType
      case None      =>
        val isProduct = returnType.asType match
          case '[o] => Expr.summon[Mirror.ProductOf[o]].isDefined
        if isProduct then returnType else namedTupleType(List("result" -> returnType))
    signatureExpr(nameExpr, instructionsExpr, namedTupleType(inputs), outputType)

  /** Stop the compilation when two of `names` are the same. `owner` names the declaration in the compile error. */
  def requireUniqueNames(using quotes: Quotes)(owner: String, names: List[String]): Unit =
    val duplicates = names.diff(names.distinct).distinct
    if duplicates.nonEmpty then
      quotes.reflect.report.errorAndAbort(s"$owner has duplicate field names: ${duplicates.mkString(", ")}")
