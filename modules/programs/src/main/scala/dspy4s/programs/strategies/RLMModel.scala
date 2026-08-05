package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, TypeRef}
import dspy4s.programs.OutputCharLimit
import dspy4s.signatures.Shape
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** Data exchanged between an [[RLM]] strategy, its action predictor, and its persistent Python REPL. */
object RLMModel:
  /** The action predict's typed input. Base inputs reach the LM only as REPL variable metadata. */
  final case class ActionInputs(variables_info: String, repl_history: String, iteration: String) derives Schema

  /** The extract-fallback predict's typed input. */
  final case class ExtractInputs(variables_info: String, repl_history: String) derives Schema

  /** The typed output of one action step. */
  final case class ActionStep(reasoning: String, code: String)

  /** A parsed Python action ready for the per-call REPL. */
  final case class ReplAction(reasoning: String, code: String)

  /** Post-execution control signal: ordinary observations continue the loop; a validated SUBMIT carries terminal
    * outputs back to the enclosing RLM program.
    */
  enum ReplExecution:
    case Observed(entry: ReplEntry)
    case Submitted(entry: ReplEntry, outputs: DynamicValue.Record)

  /** Metadata about a REPL variable, shown to the LM instead of the value itself. */
  final case class ReplVariable(
      name       : String,
      typeName   : String,
      desc       : String,
      totalLength: Int,
      preview    : String
  ):
    def format: String =
      val lines = Vector.newBuilder[String]
      lines += s"Variable: `$name` (access it in your code)"
      lines += s"Type: $typeName"
      if desc.nonEmpty then lines += s"Description: $desc"
      lines += s"Total length: ${RLMReplProtocol.groupDigits(totalLength)} characters"
      lines += s"Preview:\n```\n$preview\n```"
      lines.result().mkString("\n")

  object ReplVariable:
    /** Build metadata using a head+tail preview over the rendered value. */
    def fromValue(name: String, value: DynamicValue, field: Option[FieldSpec], previewChars: Int = 1000): ReplVariable =
      val rendered = RLMReplProtocol.renderValue(value)
      val preview  =
        if rendered.length > previewChars then
          val half = previewChars / 2
          rendered.take(half) + "..." + rendered.takeRight(half)
        else rendered
      ReplVariable(
        name = name,
        typeName = RLMReplProtocol.pythonTypeName(value),
        desc = field.flatMap(_.description).filterNot(_.startsWith("${")).getOrElse(""),
        totalLength = rendered.length,
        preview = preview
      )

  /** One persistent REPL interaction. */
  final case class ReplEntry(reasoning: String, code: String, output: String):
    def format(index: Int, maxOutputChars: OutputCharLimit): String =
      val reasoningLine = if reasoning.nonEmpty then s"Reasoning: $reasoning\n" else ""
      s"=== Step ${index + 1} ===\n${reasoningLine}Code:\n```python\n$code\n```\n${RLMReplProtocol.formatOutputBlock(output, maxOutputChars)}"

  private[programs] val reasoningField: FieldSpec = FieldSpec(
    "reasoning",
    typeRef = TypeRef.string,
    description = Some("Think step-by-step: what do you know? What remains? Plan your next action.")
  )

  private[programs] val codeField: FieldSpec = FieldSpec(
    "code",
    typeRef = TypeRef.string,
    description = Some("Python code to execute. Use markdown code block format: ```python\\n<code>\\n```")
  )

  /** Lenient action output shape: absent reasoning or code becomes an empty string and remains a loop observation. */
  private[programs] val actionStepShape: Shape[ActionStep] = new Shape[ActionStep]:
    val fieldSpecs: Vector[FieldSpec] = Vector(reasoningField, codeField)

    def encode(value: ActionStep): DynamicValue.Record =
      DynamicValue.Record(Chunk.from(Seq(
        "reasoning" -> DynamicValue.Primitive(PrimitiveValue.String(value.reasoning)),
        "code"      -> DynamicValue.Primitive(PrimitiveValue.String(value.code))
      )))

    def decode(raw: DynamicValue.Record): Either[DspyError, ActionStep] =
      Right(ActionStep(
        reasoning = DynamicValues.recordGet(raw, "reasoning").map(DynamicValues.renderText).getOrElse(""),
        code = DynamicValues.recordGet(raw, "code").map(DynamicValues.renderText).getOrElse("")
      ))
