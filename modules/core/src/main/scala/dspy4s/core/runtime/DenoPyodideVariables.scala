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

  /** Prepend `name = json.loads("<json>")` assignments for each variable (plus `import json`). One uniform JSON-based
    * mechanism vs upstream's literal/file split; same JSON-compatible value semantics.
    */
  def inject(code: String, variables: Map[String, DynamicValue]): Either[DspyError, String] =
    if variables.isEmpty then Right(code)
    else
      val invalid = variables.keys.find(k => !IdentifierPattern.matches(k) || PythonKeywords.contains(k) || k == "json")
      invalid match
        case Some(k) => Left(RuntimeError(CodeInterpreterErrors.Interpreter, s"Invalid variable name: '$k'"))
        case None    =>
          val assignments = variables.toVector.map { case (name, value) =>
            // Double JSON-encoding: the inner JSON text becomes a valid Python string literal.
            s"$name = json.loads(${encodeJson(DynamicValues.fromAny(encodeJson(value)))})"
          }
          Right((("import json" +: assignments) :+ code).mkString("\n"))
