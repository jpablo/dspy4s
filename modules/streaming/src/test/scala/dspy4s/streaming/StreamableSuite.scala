package dspy4s.streaming

import dspy4s.programs.strategies.{ChainOfThought, Predict, ReAct}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

private final case class StreamableInput(question: String) derives Schema
private final case class StreamableOutput(answer: String) derives Schema

class StreamableSuite extends FunSuite:

  test("Streamable exposes the two stable executable predictor signatures of ReAct") {
    val program = ReAct(
      baseSignature = Signature.derived[StreamableInput, StreamableOutput]("StreamableReAct"),
      tools = Vector.empty
    )
    val known = summon[Streamable[ReAct[StreamableInput, StreamableOutput]]]
      .knownSignatures(program)

    assertEquals(
      known,
      Vector(
        program.reactPredict.moduleName     -> program.reactPredict.signature.layout,
        program.extractorPredict.moduleName -> program.extractorPredict.signature.layout
      )
    )
    assertEquals(known.map(_._1), Vector("react", "react_extract"))
    assertEquals(known(1)._2.outputFields.head.name, "reasoning")
  }

  test("Streamable derives Predict and ChainOfThought signatures from the shared runner") {
    val signature = Signature.derived[StreamableInput, StreamableOutput]("StreamableTyped")
    val predict   = Predict(signature)
    val cot       = ChainOfThought(signature)

    val predictKnown = summon[Streamable[Predict[StreamableInput, StreamableOutput]]]
      .knownSignatures(predict)
    val cotKnown = summon[Streamable[ChainOfThought[StreamableInput, StreamableOutput]]]
      .knownSignatures(cot)

    assertEquals(predictKnown, Vector(predict.moduleName -> signature.layout))
    assertEquals(cotKnown.map(_._1), Vector("predict"))
    assertEquals(cotKnown.head._2.outputFields.head.name, "reasoning")
  }
