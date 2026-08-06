package dspy4s.programs.strategies

import dspy4s.core.contracts.DynamicValues
import dspy4s.programs.{LlmCallLimit, OutputCharLimit}
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** Text and data protocol shared by the RLM prompt, persistent REPL history, and schema-validated SUBMIT boundary. */
private[programs] object RLMReplProtocol:
  private val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Upstream's `ACTION_INSTRUCTIONS_TEMPLATE`, ported verbatim with the same placeholders. */
  def actionInstructionsTemplate(
      inputs          : String,
      outputFields    : String,
      finalOutputNames: String,
      maxLlmCalls     : LlmCallLimit
  ): String =
    s"""You are tasked with producing the following outputs given the inputs $inputs:
       |$outputFields
       |
       |You have access to a Python REPL environment. Write Python code and it will be executed. You will see the output, then write more code based on what you learned. This is an iterative process.
       |
       |Available:
       |- Variables: $inputs (your input data)
       |- `llm_query(prompt)` - query a sub-LLM (~500K char capacity) for semantic analysis
       |- `llm_query_batched(prompts)` - query multiple prompts concurrently (much faster for multiple queries)
       |- `print()` - ALWAYS print to see results
       |- `SUBMIT($finalOutputNames)` - submit final output when done
       |- Standard libraries: re, json, collections, math, etc.
       |
       |IMPORTANT: This is ITERATIVE. Each code block you write will execute, you'll see the output, then you decide what to do next. Do NOT try to solve everything in one step.
       |
       |1. EXPLORE FIRST - Look at your data before processing it. Print samples, check types/lengths, understand the structure.
       |2. ITERATE - Write small code snippets, observe outputs, then decide next steps. State persists between iterations.
       |3. VERIFY BEFORE SUBMITTING - If results seem wrong (zeros, empty, unexpected), reconsider your approach.
       |4. USE llm_query FOR SEMANTICS - String matching finds WHERE things are; llm_query understands WHAT things mean.
       |5. MINIMIZE RETYPING (INPUTS & OUTPUTS) - When values are long, precise, or error-prone (IDs, numbers, code, quotes), re-access them via variables and parse/compute in code instead of retyping. Use small, targeted prints to sanity-check, but avoid manual copying when variables can carry the exact value.
       |6. SUBMIT ONLY AFTER SEEING OUTPUTS - SUBMIT ends the current run immediately. If you need to inspect printed output, run it in one step, review the result, then call SUBMIT in a later step.
       |
       |You have max $maxLlmCalls sub-LLM calls. When done, call SUBMIT() with your output.""".stripMargin

  /** Upstream `REPLHistory.format`. */
  def renderHistory(entries: Vector[RLM.ReplEntry], maxOutputChars: OutputCharLimit): String =
    if entries.isEmpty then "You have not interacted with the REPL environment yet."
    else entries.zipWithIndex.map { case (entry, i) => entry.format(i, maxOutputChars) }.mkString("\n")

  /** Upstream `REPLEntry.format_output`: head+tail truncation with the true length in the header. */
  def formatOutputBlock(output: String, maxOutputChars: OutputCharLimit): String =
    val rawLen = output.length
    val body   =
      if rawLen > maxOutputChars then
        val half    = maxOutputChars / 2
        val omitted = rawLen - maxOutputChars
        output.take(half) + s"\n\n... (${groupDigits(omitted)} characters omitted) ...\n\n" + output.takeRight(half)
      else output
    s"Output (${groupDigits(rawLen)} chars):\n$body"

  def formatOutput(output: String): String =
    if output.isEmpty then "(no output - did you forget to print?)" else output

  /** Parse a SUBMIT payload (the interpreter's `finalOutput` JSON) and verify every output field is present. Returns
    * the upstream-style `[Error] …` message on a problem.
    */
  def parseSubmitted(
      finalJson       : String,
      outputFieldNames: Vector[String]
  ): Either[String, DynamicValue.Record] =
    DynamicValues.parseJsonRecord(finalJson) match
      case Some(record) =>
        val present = DynamicValues.recordKeys(record).toSet
        val missing = outputFieldNames.filterNot(present.contains)
        if missing.isEmpty then Right(record)
        else
          Left(
            s"[Error] Missing output fields: ${missing.sorted.mkString("[", ", ", "]")}. Use SUBMIT(${outputFieldNames.mkString(", ")})"
          )
      case None => Left(
          s"[Error] FINAL returned a non-dict payload, expected dict with fields: ${outputFieldNames.mkString(", ")}"
        )

  /** Upstream `_strip_code_fences`: strip decorative outer fences, accept ```python/```py/bare fences, REJECT an
    * explicit non-Python language tag (the error becomes an `[Error]` observation).
    */
  def stripCodeFences(raw: String): Either[String, String] =
    var code = raw.trim
    if !code.contains("```") then Right(code)
    else
      // Strip outer decorative fence pairs (e.g. ```\n```python\n...\n```\n```).
      var lines = code.linesIterator.toVector
      while lines.size >= 2 && lines.head.trim == "```" && lines.last.trim == "```" do
        lines = lines.drop(1).dropRight(1)
      code = lines.mkString("\n").trim
      if !code.contains("```") then Right(code)
      else
        val fenceStart = code.indexOf("```")
        val afterFence = code.drop(fenceStart + 3)
        val newline    = afterFence.indexOf('\n')
        if newline < 0 then Right(code)
        else
          val langLine = afterFence.take(newline).trim
          val lang     = if langLine.isEmpty then "" else langLine.split("\\s+", 2)(0).toLowerCase
          if !Set("python", "py", "python3", "py3", "").contains(lang) then
            Left(s"Expected Python code but got ```$lang fence. Write Python code, not $lang.")
          else
            val remainder = afterFence.drop(newline + 1)
            val blockEnd  = remainder.indexOf("```")
            if blockEnd < 0 then Right(remainder.trim) else Right(remainder.take(blockEnd).trim)

  /** Python-style type name for the variable metadata (upstream `type(value).__name__`). */
  def pythonTypeName(value: DynamicValue): String =
    value match
      case DynamicValue.Primitive(p) => p match
          case _: PrimitiveValue.String  => "str"
          case _: PrimitiveValue.Boolean => "bool"
          case _: PrimitiveValue.Int | _: PrimitiveValue.Long | _: PrimitiveValue.Short | _: PrimitiveValue.Byte |
              _: PrimitiveValue.BigInt => "int"
          case _: PrimitiveValue.Double | _: PrimitiveValue.Float | _: PrimitiveValue.BigDecimal => "float"
          case _                                                                                 => "str"
      case _: DynamicValue.Sequence  => "list"
      case _: DynamicValue.Record    => "dict"
      case _: DynamicValue.Map       => "dict"
      case _: DynamicValue.Null.type => "NoneType"
      case _                         => "str"

  /** Render a variable's value for length/preview: primitives as text, records/sequences as JSON. */
  def renderValue(value: DynamicValue): String =
    value match
      case DynamicValue.Primitive(_) => DynamicValues.renderText(value)
      case _                         => new String(dynamicJsonCodec.encode(value), StandardCharsets.UTF_8)

  /** Digit grouping like Python's `{:,}` (locale-independent). */
  def groupDigits(n: Int): String =
    String.format(java.util.Locale.US, "%,d", n)
