package dspy4s.core.runtime

import dspy4s.core.contracts.{CodeInterpreterErrors, DspyError}
import dspy4s.core.runtime.InterpreterSupport.tryEncodeJson
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** Validates and serializes host values injected into the sandbox as Python variables. */
private[runtime] object DenoPyodideVariables:

  /** The name that the prelude uses for the `json` module, so a variable cannot have it. */
  private val ReservedName = "json"

  /** Build the `name = json.loads("<json>")` assignment prelude for `variables` (plus `import json`); the empty string
    * when there are none. One uniform JSON-based mechanism vs upstream's literal/file split; same JSON-compatible value
    * semantics. A value that the JSON writer cannot encode, for example a text with a lone UTF-16 surrogate, is a
    * `Left` that names the variable.
    *
    * A variable name must be a Python identifier that is not a keyword (upstream `str.isidentifier` and
    * `keyword.iskeyword`), so Unicode letters are valid. The name `json` is not valid.
    *
    * The caller prepends this to EVERY executed code block (upstream parity: variables are re-assigned at the top of
    * each block, so a sandbox-side mutation of an injected variable does not survive to the next `execute`). The built
    * text depends only on `variables`, so callers may cache it across calls. The JSON encoding of each variable is
    * proportional to the size of its value, which for RLM is the whole input context.
    */
  def prelude(variables: Map[String, DynamicValue]): Either[DspyError, String] =
    if variables.isEmpty then Right("")
    else
      val invalid = variables.keys.find(k => !PythonNames.isBindable(k) || k == ReservedName)
      invalid match
        case Some(k) => CodeInterpreterErrors.failure(s"Invalid variable name: '$k'")
        case None    =>
          val assignments = variables.toVector.foldLeft[Either[DspyError, Vector[String]]](Right(Vector.empty)) {
            case (acc, (name, value)) => acc.flatMap(lines => assignment(name, value).map(lines :+ _))
          }
          assignments.map(lines => ("import json" +: lines).mkString("\n"))

  /** The line `name = json.loads("<json>")`, or a `Left` when the JSON writer cannot encode `value`. */
  private def assignment(name: String, value: DynamicValue): Either[DspyError, String] =
    // Double JSON-encoding: the inner JSON text becomes a valid Python string literal.
    val literal =
      tryEncodeJson(value).flatMap(json => tryEncodeJson(DynamicValue.Primitive(PrimitiveValue.String(json))))
    literal match
      case Right(text) => Right(s"$name = json.loads($text)")
      case Left(cause) => CodeInterpreterErrors.failure(s"Unable to encode the variable '$name' as JSON: $cause")
