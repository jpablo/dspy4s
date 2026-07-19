package dspy4s.optimize

import dspy4s.programs.Predictors

import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.DynamicPredict
import dspy4s.programs.PredictorId
import munit.FunSuite

/** Top-level so its Mirror (and `MirroredElemLabels`) are available to `Predictors.derived`. */
final case class NamedPipeline(retrieve: DynamicPredict, answer: DynamicPredict)

class PredictorsNamedSuite extends FunSuite:

  private def predict(instruction: String): DynamicPredict =
    DynamicPredict(layout =
      SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some(instruction))
    )

  test("a standalone leaf predictor names as 'self'") {
    assertEquals(summon[Predictors[DynamicPredict]].readNamed(predict("x")).map(_._1), Vector("self"))
  }

  test("a composite names its predictors by case-class field label (the latent Mirror names, P-c)") {
    val pipeline = NamedPipeline(retrieve = predict("retrieve instr"), answer = predict("answer instr"))
    val named    = summon[Predictors[NamedPipeline]].readNamed(pipeline)

    assertEquals(named.map(_._1), Vector("retrieve", "answer"))
    assertEquals(named.map(_._2.layout.instructions.getOrElse("")), Vector("retrieve instr", "answer instr"))
  }

  test("readNamed is aligned with read") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val ps       = summon[Predictors[NamedPipeline]]
    assertEquals(ps.readNamed(pipeline).map(_._2), ps.read(pipeline))
  }

  test("readIdentified separates unique machine IDs from structural display names") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val entries  = summon[Predictors[NamedPipeline]].readIdentified(pipeline)

    assertEquals(entries.map(_.id), Vector(PredictorId(0), PredictorId(1)))
    assertEquals(entries.map(_.displayName), Vector("retrieve", "answer"))
    assertEquals(entries.map(_.predictor), Vector(pipeline.retrieve, pipeline.answer))
  }

  test("predictor IDs are preserved by replace") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val ps       = summon[Predictors[NamedPipeline]]
    val before   = ps.readIdentified(pipeline).map(_.id)
    val updated =
      ps.replace(pipeline, ps.read(pipeline).map(p => p.copy(layout = p.layout.withInstructions(Some("x")))))

    assertEquals(ps.readIdentified(updated).map(_.id), before)
  }
