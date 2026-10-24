package dspy4s.core.runtime

import dspy4s.core.contracts.{CodeInterpreterErrors, DspyError}
import dspy4s.core.contracts.DynamicValues.encodeJson
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Validates and serializes host values injected into the sandbox as Python variables. */
private[runtime] object DenoPyodideVariables:

  private val IdentifierPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

  /** Build the `name = json.loads("<json>")` assignment prelude for `variables` (plus `import json`); the empty string
    * when there are none. One uniform JSON-based mechanism vs upstream's literal/file split; same JSON-compatible value
    * semantics.
    *
    * The caller prepends this to EVERY executed code block (upstream parity: variables are re-assigned at the top of
    * each block, so a sandbox-side mutation of an injected variable does not survive to the next `execute`). The built
    * text depends only on `variables`, so callers may cache it across calls. The JSON encoding of each variable is
    * proportional to the size of its value, which for RLM is the whole input context.
    */
  def prelude(variables: Map[String, DynamicValue]): Either[DspyError, String] =
    if variables.isEmpty then Right("")
    else
      val invalid =
        variables.keys.find(k => !IdentifierPattern.matches(k) || PythonNames.Keywords.contains(k) || k == "json")
      invalid match
        case Some(k) => CodeInterpreterErrors.failure(s"Invalid variable name: '$k'")
        case None    =>
          val assignments = variables.toVector.map { case (name, value) =>
            // Double JSON-encoding: the inner JSON text becomes a valid Python string literal.
            val literal = encodeJson(DynamicValue.Primitive(PrimitiveValue.String(encodeJson(value))))
            s"$name = json.loads($literal)"
          }
          Right(("import json" +: assignments).mkString("\n"))
