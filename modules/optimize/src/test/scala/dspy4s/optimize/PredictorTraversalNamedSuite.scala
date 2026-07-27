package dspy4s.optimize

import dspy4s.programs.predictors.{optimizableParameters, predictorView}

import dspy4s.programs.predictors.PredictorTraversal

import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.DynamicPredict
import dspy4s.programs.predictors.PredictorId
import munit.FunSuite

/** Top-level so its Mirror (and `MirroredElemLabels`) are available to `PredictorTraversal.derived`. */
final case class NamedPipeline(retrieve: DynamicPredict, answer: DynamicPredict)

class PredictorTraversalNamedSuite extends FunSuite:

  private def predict(instruction: String): DynamicPredict =
    DynamicPredict(layout =
      SignatureLayout.parse("question -> answer").toOption.get.withInstructions(Some(instruction))
    )

  test("a standalone leaf predictor names as 'self'") {
    assertEquals(summon[PredictorTraversal[DynamicPredict]].readNamed(predict("x")).map(_._1), Vector("self"))
  }

  test("a composite names its predictors by case-class field label (the latent Mirror names, P-c)") {
    val pipeline = NamedPipeline(retrieve = predict("retrieve instr"), answer = predict("answer instr"))
    val named    = summon[PredictorTraversal[NamedPipeline]].readNamed(pipeline)

    assertEquals(named.map(_._1), Vector("retrieve", "answer"))
    assertEquals(named.map(_._2.instructions.getOrElse("")), Vector("retrieve instr", "answer instr"))
  }

  test("readNamed is aligned with read") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val ps       = summon[PredictorTraversal[NamedPipeline]]
    assertEquals(ps.readNamed(pipeline).map(_._2), ps.read(pipeline))
  }

  test("readIdentified separates unique machine IDs from structural display names") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val entries  = summon[PredictorTraversal[NamedPipeline]].readIdentified(pipeline)

    assertEquals(entries.map(_.id), Vector(PredictorId(0), PredictorId(1)))
    assertEquals(entries.map(_.displayName), Vector("retrieve", "answer"))
    assertEquals(
      entries.map(_.parameters),
      Vector(pipeline.retrieve.optimizableParameters, pipeline.answer.optimizableParameters)
    )
    assertEquals(entries.map(_.layout), Vector(pipeline.retrieve.layout, pipeline.answer.layout))
  }

  test("identified and named reads reject a naming traversal with divergent views") {
    val canonical = predict("canonical").predictorView
    val misleading = predict("misleading").predictorView
    val ps = new PredictorTraversal[Unit]:
      def inspect(program: Unit) = Vector(canonical)
      def replace(program: Unit, updates: Vector[dspy4s.programs.predictors.OptimizableParameters]) = program
      override def inspectNamed(program: Unit) = Vector("leaf" -> misleading)

    val identifiedError = intercept[IllegalArgumentException](ps.readIdentified(()))
    val namedError       = intercept[IllegalArgumentException](ps.readNamed(()))
    assert(identifiedError.getMessage.contains("must preserve the views and order"))
    assert(namedError.getMessage.contains("must preserve the views and order"))
  }

  test("identified and named reads reject a naming traversal with different arity") {
    val canonical = predict("canonical").predictorView
    val ps = new PredictorTraversal[Unit]:
      def inspect(program: Unit) = Vector(canonical)
      def replace(program: Unit, updates: Vector[dspy4s.programs.predictors.OptimizableParameters]) = program
      override def inspectNamed(program: Unit) = Vector.empty

    val identifiedError = intercept[IllegalArgumentException](ps.readIdentified(()))
    val namedError       = intercept[IllegalArgumentException](ps.readNamed(()))
    assert(identifiedError.getMessage.contains("inspectNamed returned 0 entries but inspect returned 1"))
    assert(namedError.getMessage.contains("inspectNamed returned 0 entries but inspect returned 1"))
  }

  test("predictor IDs are preserved by replace") {
    val pipeline = NamedPipeline(predict("a"), predict("b"))
    val ps       = summon[PredictorTraversal[NamedPipeline]]
    val before   = ps.readIdentified(pipeline).map(_.id)
    val updated =
      ps.replace(pipeline, ps.read(pipeline).map(_.copy(instructions = Some("x"))))

    assertEquals(ps.readIdentified(updated).map(_.id), before)
  }
