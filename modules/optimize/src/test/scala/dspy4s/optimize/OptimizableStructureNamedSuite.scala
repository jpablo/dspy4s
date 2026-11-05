package dspy4s.optimize

import dspy4s.programs.optimization.optimizableParameters

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite

/** Top-level so its Mirror (and `MirroredElemLabels`) are available to `OptimizableStructure.derived`. */
final case class NamedPipeline(retrieve: DynamicPredict, answer: DynamicPredict)

class OptimizableStructureNamedSuite extends FunSuite:

  private def predict(instruction: String): DynamicPredict =
    DynamicPredict(layout =
      SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some(instruction))
    )

  test("a standalone leaf predictor names as 'self'") {
    assertEquals(summon[OptimizableStructure[DynamicPredict]].readNamed(predict("x")).map(_._1), Vector("self"))
  }

  test("a composite names its predictors by case-class field label (the latent Mirror names, P-c)") {
    val pipeline = NamedPipeline(retrieve = predict("retrieve instr"), answer = predict("answer instr"))
    val named    = summon[OptimizableStructure[NamedPipeline]].readNamed(pipeline)

    assertEquals(named.map(_._1), Vector("retrieve", "answer"))
    assertEquals(named.map(_._2.instructions.getOrElse("")), Vector("retrieve instr", "answer instr"))
  }

  test("readNamed is aligned with read") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val ps       = summon[OptimizableStructure[NamedPipeline]]
    assertEquals(ps.readNamed(pipeline).map(_._2), ps.read(pipeline))
  }

  test("readIdentified separates unique machine IDs from structural display names") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val entries  = summon[OptimizableStructure[NamedPipeline]].readIdentified(pipeline)

    assertEquals(entries.map(_.id), Vector(OptimizableId(0), OptimizableId(1)))
    assertEquals(entries.map(_.displayName), Vector("retrieve", "answer"))
    assertEquals(
      entries.map(_.parameters),
      Vector(pipeline.retrieve.optimizableParameters, pipeline.answer.optimizableParameters)
    )
    assertEquals(entries.map(_.layout), Vector(pipeline.retrieve.layout, pipeline.answer.layout))
  }

  test("optimizable IDs are preserved by replace") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val ps       = summon[OptimizableStructure[NamedPipeline]]
    val before   = ps.readIdentified(pipeline).map(_.id)
    val updated  = ps.replace(pipeline, ps.read(pipeline).map(_.copy(instructions = Some("x"))))

    assertEquals(ps.readIdentified(updated).map(_.id), before)
  }
