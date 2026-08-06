package dspy4s.core.runtime

import dspy4s.core.contracts.{CodeInterpreterErrors, DspyError, DynamicValues, RuntimeError}
import zio.blocks.schema.DynamicValue

/** Validates and serializes host values injected into the sandbox as Python variables. */
private[runtime] object DenoPyodideVariables:
  import DenoPyodideProtocol.encodeJson

  private val PythonKeywords: Set[String] = Set(
    "False",
    "None",
    "True",
    "and",
    "as",
    "assert",
    "async",
    "await",
    "break",
    "class",
    "continue",
    "def",
    "del",
    "elif",
    "else",
    "except",
    "finally",
    "for",
    "from",
    "global",
    "if",
    "import",
    "in",
    "is",
    "lambda",
    "nonlocal",
    "not",
    "or",
    "pass",
    "raise",
    "return",
    "try",
    "while",
    "with",
    "yield"
  )
  private val IdentifierPattern = "^[A-Za-z_][A-Za-z0-9_]*$".r

  /** Build the `name = json.loads("<json>")` assignment prelude for `variables` (plus `import json`); the empty string
    * when there are none. One uniform JSON-based mechanism vs upstream's literal/file split; same JSON-compatible value
    * semantics.
    *
    * The caller prepends this to EVERY executed code block (upstream parity: variables are re-assigned at the top of
    * each block, so a sandbox-side mutation of an injected variable does not survive to the next `execute`). The built
    * text depends only on `variables`, so callers may cache it across calls -- the per-variable JSON encoding is
    * proportional to total value size, which for RLM is the whole input context.
    */
  def prelude(variables: Map[String, DynamicValue]): Either[DspyError, String] =
    if variables.isEmpty then Right("")
    else
      val invalid = variables.keys.find(k => !IdentifierPattern.matches(k) || PythonKeywords.contains(k) || k == "json")
      invalid match
        case Some(k) => Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Invalid variable name: '$k'"))
        case None    =>
          val assignments = variables.toVector.map { case (name, value) =>
            // Double JSON-encoding: the inner JSON text becomes a valid Python string literal.
            s"$name = json.loads(${encodeJson(DynamicValues.fromAny(encodeJson(value)))})"
          }
          Right(("import json" +: assignments).mkString("\n"))
