package dspy4s.typed.internal

import dspy4s.core.contracts.SignatureLayout
import dspy4s.typed.{Shape, Signature}

import scala.quoted.*

/** Runtime signature assembly emitted by the typed signature macros. */
private[typed] object MacroSignatureSupport:

  def materialize[I: Type, O: Type](
      nameExpr: Expr[String],
      instructionsExpr: Expr[String],
      errorContext: String,
      inputShapeExpr: Expr[Shape[I]],
      outputShapeExpr: Expr[Shape[O]]
  )(using Quotes): Expr[Signature[I, O]] =
    val errorContextExpr = Expr(errorContext)
    '{
      val name        = ${ nameExpr }
      val inputShape  = ${ inputShapeExpr }
      val outputShape = ${ outputShapeExpr }
      val layout = SignatureLayout
        .create(
          name = name,
          inputFields = inputShape.fieldSpecs,
          outputFields = outputShape.fieldSpecs,
          instructions = Option(${ instructionsExpr }).filter(_.nonEmpty)
        )
        .fold(
          err =>
            throw new IllegalStateException(
              s"Internal error materializing ${${ errorContextExpr }}: ${err.message}"
            ),
          identity
        )
      Signature[I, O](name, layout, inputShape, outputShape)
    }
