package dspy4s.programs

import dspy4s.programs.predictors.*
import dspy4s.core.contracts.{CodeInterpreter, CodeResult, DspyError}
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

private final case class ProgramOfThoughtStateInput(question: String) derives Schema
private final case class ProgramOfThoughtStateOutput(answer: String) derives Schema

class ProgramOfThoughtPredictorTraversalSuite extends FunSuite:

  private object Interpreter extends CodeInterpreter:
    def execute(code: String): Either[DspyError, CodeResult] =
      Right(CodeResult(stdout = "", stderr = "", exitCode = 0))
    def close(): Unit = ()

  private def program: ProgramOfThought[ProgramOfThoughtStateInput, ProgramOfThoughtStateOutput] =
    ProgramOfThought(
      baseSignature = Signature.derived[ProgramOfThoughtStateInput, ProgramOfThoughtStateOutput]("PoTState"),
      interpreter = Interpreter
    )

  test("PredictorTraversal exposes generator, regenerator, and answerer in stable named order") {
    val pot = program
    val P   = summon[PredictorTraversal[ProgramOfThought[ProgramOfThoughtStateInput, ProgramOfThoughtStateOutput]]]

    assertEquals(
      P.read(pot),
      Vector(
        pot.generatorPredict.optimizableParameters,
        pot.regeneratorPredict.optimizableParameters,
        pot.answererPredict.optimizableParameters
      )
    )
    assertEquals(P.inspectNamed(pot).map(_._1), Vector("generator", "regenerator", "answerer"))
    assertEquals(
      P.inspect(pot).map(_.moduleName),
      Vector(
        ProgramOfThought.generatorModuleName,
        ProgramOfThought.regeneratorModuleName,
        ProgramOfThought.answererModuleName
      )
    )
  }

  test("PredictorTraversal replacement obeys Get-Put, read-after-write, and the metadata frame") {
    val pot      = program
    val P        = summon[PredictorTraversal[ProgramOfThought[ProgramOfThoughtStateInput, ProgramOfThoughtStateOutput]]]
    val original = P.read(pot)
    val metadata = P.inspect(pot).map(_.metadata)

    val noOp = P.replace(pot, original)
    assert(noOp eq pot, "Get-Put should preserve the original ProgramOfThought value")
    assertEquals(noOp.generatorPredictOverride, None)
    assertEquals(noOp.regeneratorPredictOverride, None)
    assertEquals(noOp.answererPredictOverride, None)

    val tunedRegenerator = original(1).copy(instructions = Some("Repair the program carefully."))
    val updates          = original.updated(1, tunedRegenerator)
    val updated          = P.replace(pot, updates)

    assertEquals(P.read(updated), updates)
    assertEquals(P.inspect(updated).map(_.metadata), metadata)
    assert(updated.interpreter eq pot.interpreter)
    assert(updated.generatorPredict.runtime eq pot.generatorPredict.runtime)
    assert(updated.regeneratorPredict.runtime eq pot.regeneratorPredict.runtime)
    assert(updated.answererPredict.runtime eq pot.answererPredict.runtime)
    assertEquals(updated.generatorPredictOverride, None)
    assertEquals(updated.regeneratorPredictOverride.map(_.optimizableParameters), Some(tunedRegenerator))
    assertEquals(updated.answererPredictOverride, None)
  }
