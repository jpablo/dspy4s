package dspy4s.streaming

import dspy4s.core.contracts.{CodeInterpreter, CodeResult, DspyError}
import dspy4s.programs.strategies.{ChainOfThought, Predict, ProgramOfThought}
import dspy4s.signatures.Signature
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
        program.generatorPredict.moduleName   -> program.generatorPredict.signature.layout,
        program.regeneratorPredict.moduleName -> program.regeneratorPredict.signature.layout,
        program.answererPredict.moduleName    -> program.answererPredict.signature.layout
      )
    )
  }

  test("Streamable derives typed Predict and ChainOfThought signatures from the shared runner") {
    val signature = Signature.derived[StreamablePotInput, StreamablePotOutput]("StreamableTyped")
    val predict   = Predict(signature)
    val cot       = ChainOfThought(signature)

    val predictKnown = summon[Streamable[Predict[StreamablePotInput, StreamablePotOutput]]]
      .knownSignatures(predict)
    val cotKnown = summon[Streamable[ChainOfThought[StreamablePotInput, StreamablePotOutput]]]
      .knownSignatures(cot)

    assertEquals(predictKnown, Vector(predict.moduleName -> signature.layout))
    assertEquals(cotKnown.map(_._1), Vector("predict"))
    assertEquals(cotKnown.head._2.outputFields.head.name, "reasoning")
  }
