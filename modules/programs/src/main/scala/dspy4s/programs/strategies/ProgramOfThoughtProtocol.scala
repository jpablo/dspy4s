package dspy4s.programs.strategies

import dspy4s.core.contracts.{DspyError, DynamicValues, FieldSpec, TypeRef, ValidationError}
import dspy4s.typed.Shape
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** ProgramOfThought's generated-code field contract, predictor names, and lenient step decoder. */
private[programs] object ProgramOfThoughtProtocol:
  val generatorModuleName: String = "program_of_thought_generate"
  val regeneratorModuleName: String = "program_of_thought_regenerate"
  val answererModuleName: String = "program_of_thought_answer"

  val generatedCodeField: FieldSpec = FieldSpec.normalize(
    FieldSpec(
      name = "generated_code",
      typeRef = TypeRef.string,
      description = Some("Python code that, when executed, computes the answer and prints it as JSON.")
    )
  )

  val previousCodeField: FieldSpec = FieldSpec.normalize(
    FieldSpec(
      name = "previous_code",
      typeRef = TypeRef.string,
      description = Some("The Python code from the previous attempt that errored.")
    )
  )

  val errorField: FieldSpec = FieldSpec.normalize(
    FieldSpec(
      name = "error",
      typeRef = TypeRef.string,
      description = Some("Error message produced by the previous Python code.")
    )
  )

  val finalGeneratedCodeField: FieldSpec = FieldSpec.normalize(
    FieldSpec(
      name = "final_generated_code",
      typeRef = TypeRef.string,
      description = Some("The final Python code that produced the answer.")
    )
  )

  val codeOutputField: FieldSpec = FieldSpec.normalize(
    FieldSpec(
      name = "code_output",
      typeRef = TypeRef.string,
      description = Some("The printed output of the final Python code.")
    )
  )

  val codeOutShape: Shape[ProgramOfThought.CodeOut] =
    new Shape[ProgramOfThought.CodeOut]:
      val fieldSpecs: Vector[FieldSpec] = Vector(generatedCodeField)

      def encode(value: ProgramOfThought.CodeOut): DynamicValue.Record =
        value.generatedCode match
          case Some(code) =>
            DynamicValue.Record(Chunk("generated_code" -> DynamicValue.Primitive(PrimitiveValue.String(code))))
          case None =>
            DynamicValue.Record.empty

      def decode(raw: DynamicValue.Record): Either[DspyError, ProgramOfThought.CodeOut] =
        DynamicValues.recordGet(raw, "generated_code") match
          case None =>
            Right(ProgramOfThought.CodeOut(None))
          case Some(DynamicValue.Primitive(PrimitiveValue.String(code))) =>
            Right(ProgramOfThought.CodeOut(Some(code)))
          case Some(other) =>
            Left(ValidationError(s"ProgramOfThought generated_code must be a String, got: $other"))
