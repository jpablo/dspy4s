package dspy4s.streaming

import dspy4s.core.contracts.{CodeInterpreter, CodeResult, DspyError}
import dspy4s.programs.ProgramOfThought
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

private final case class StreamablePotInput(question: String) derives Schema
private final case class StreamablePotOutput(answer: String) derives Schema

class ProgramOfThoughtStreamableSuite extends FunSuite:

  private object Interpreter extends CodeInterpreter:
    def execute(code: String): Either[DspyError, CodeResult] =
      Right(CodeResult(stdout = "", stderr = "", exitCode = 0))
    def close(): Unit = ()

  test("Streamable exposes the three stable executable predictor signatures") {
    val program = ProgramOfThought(
      baseSignature = Signature.derived[StreamablePotInput, StreamablePotOutput]("StreamablePoT"),
      interpreter = Interpreter
    )
    val known = summon[Streamable[ProgramOfThought[StreamablePotInput, StreamablePotOutput]]]
      .knownSignatures(program)

    assertEquals(
      known,
      Vector(
        program.generatorPredict.moduleName   -> program.generatorPredict.layout,
        program.regeneratorPredict.moduleName -> program.regeneratorPredict.layout,
        program.answererPredict.moduleName    -> program.answererPredict.layout
      )
    )
  }
