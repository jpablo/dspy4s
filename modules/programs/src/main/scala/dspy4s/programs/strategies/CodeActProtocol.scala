package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, TypeRef}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.typed.Shape
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** CodeAct's model-facing step schema and its textual tool and trajectory protocol. */
private[programs] object CodeActProtocol:
  val loopTrajectoryField: FieldSpec = FieldSpec(
    name = "trajectory",
    typeRef = TypeRef.string,
    description = Some("History of generated code and observations so far.")
  )

  val extractTrajectoryField: FieldSpec = FieldSpec(
    name = "trajectory",
    typeRef = TypeRef.string,
    description = Some("History of generated code and observations.")
  )

  val generatedCodeField: FieldSpec = FieldSpec(
    name = "generated_code",
    typeRef = TypeRef.string,
    description = Some("Python code that, when executed, produces output relevant to answering the question.")
  )

  val finishedField: FieldSpec = FieldSpec(
    name = "finished",
    typeRef = TypeRef.bool,
    description = Some("Set to true once enough information has been collected to produce the final outputs.")
  )

  /** Leniently decode missing code as empty and Boolean or string completion flags. */
  val codeStepShape: Shape[CodeAct.CodeStep] = new Shape[CodeAct.CodeStep]:
    val fieldSpecs: Vector[FieldSpec] = Vector(generatedCodeField, finishedField)

    def encode(value: CodeAct.CodeStep): DynamicValue.Record =
      DynamicValue.Record(Chunk.from(Seq(
        "generated_code" -> DynamicValue.Primitive(PrimitiveValue.String(value.generatedCode)),
        "finished"       -> DynamicValue.Primitive(PrimitiveValue.Boolean(value.finished))
      )))

    def decode(raw: DynamicValue.Record): Either[DspyError, CodeAct.CodeStep] =
      val finished = DynamicValues.recordGet(raw, "finished") match
        case Some(DynamicValue.Primitive(PrimitiveValue.Boolean(b))) => b
        case Some(DynamicValue.Primitive(PrimitiveValue.String(s)))  => s.trim.equalsIgnoreCase("true")
        case _                                                       => false
      Right(CodeAct.CodeStep(
        generatedCode = DynamicValues.recordGet(raw, "generated_code").map(DynamicValues.renderText).getOrElse(""),
        finished = finished
      ))

  /** Render one host tool for the model's numbered function list. */
  def renderTool(tool: ToolFunction): String =
    val desc =
      if tool.description.nonEmpty then s", whose description is <desc>${tool.description.replace("\n", "  ")}</desc>."
      else "."
    val args = tool.argSchema.map { case (name, typeRef) => s"$name: ${typeRef.repr}" }.mkString("{", ", ", "}")
    s"${tool.name}$desc It takes arguments $args."

  def renderTrajectory(entries: Vector[CodeAct.TrajectoryEntry]): String =
    if entries.isEmpty then "(empty)"
    else
      entries.iterator.map { entry =>
        val codeBlock =
          if entry.code.isEmpty then "(no code)"
          else s"```python\n${entry.code}\n```"
        val obsLabel = if entry.isError then "observation" else "code_output"
        s"## Iteration ${entry.iteration + 1}\n$codeBlock\n${obsLabel}_${entry.iteration}: ${entry.observation}"
      }.mkString("\n\n")
