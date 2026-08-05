package dspy4s.programs.runtime

import scala.util.matching.Regex

/** Normalizes Python source emitted by a language model into code suitable for an interpreter.
  *
  * This is shared infrastructure for code-generating strategies rather than part of any one strategy's protocol.
  */
private[programs] object GeneratedPython:
  /** Matches a fenced code block, optionally tagged `python` or `py`, and captures its body. */
  private val FencedBlock: Regex = """(?s)```(?:python|py)?\s*\n?(.*?)```""".r

  private val LastLineAssignment: Regex = """^(\w+)\s*=""".r

  /** Port of DSPy's shared `_parse_code`: truncate trailing prose, remove a Python fence, reject malformed or empty
    * code, and make a final multi-line assignment observable by appending its variable as an expression.
    */
  def parse(raw: String): Either[String, String] =
    val pre       = raw.split("---", 2)(0).split("\n\n\n", 2)(0).trim
    val codeBlock = FencedBlock.findFirstMatchIn(pre).map(_.group(1).trim).getOrElse(pre)
    if codeBlock.isEmpty then Left("Empty code after parsing.")
    else if !codeBlock.contains("\n") && codeBlock.count(_ == '=') > 1 then Left("Code format is not correct.")
    else
      val lines = codeBlock.split("\n", -1)
      LastLineAssignment.findPrefixMatchOf(lines.last.trim) match
        case Some(m) if lines.length > 1 => Right(codeBlock + "\n" + m.group(1))
        case _                           => Right(codeBlock)
