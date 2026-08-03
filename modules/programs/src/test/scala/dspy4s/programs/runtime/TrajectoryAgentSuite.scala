package dspy4s.programs.runtime

import dspy4s.core.contracts.{:=, DynamicValues}
import dspy4s.core.data.{Completions, RawPrediction}
import dspy4s.lm.contracts.LmUsage
import dspy4s.typed.Prediction
import munit.FunSuite

final class TrajectoryAgentSuite extends FunSuite:

  test("runAndExtractPrediction attaches the complete trajectory and preserves the extractor envelope") {
    val completionRows = Vector(
      DynamicValues.record("answer" := "first"),
      DynamicValues.record("answer" := "second")
    )
    val completions = Completions.fromRows(completionRows).toOption.get
    val usage       = LmUsage(totalTokens = 9, promptTokens = 5, completionTokens = 4)
    val extracted = Prediction(
      output = "typed-answer",
      raw = RawPrediction(
        values = DynamicValues.record("answer" := "first", "metadata" := "preserved"),
        completions = Some(completions),
        lmUsage = Some(usage)
      )
    )

    val result = TrajectoryAgent.runAndExtractPrediction[String, String](
      maxIterations = 2,
      render = _.mkString(" -> "),
      trajectoryKey = "trajectory"
    )(
      step = (trajectory, iteration) =>
        val next = trajectory :+ s"step-${iteration + 1}"
        Right(if iteration == 0 then AgentLoop.Step.Continue(next) else AgentLoop.Step.Done(next))
    )(
      extract = _ => Right(extracted)
    )

    val prediction = result.toOption.get
    assertEquals(prediction.output, "typed-answer")
    assertEquals(prediction.raw.asString("answer"), Right("first"))
    assertEquals(prediction.raw.asString("metadata"), Right("preserved"))
    assertEquals(prediction.raw.asString("trajectory"), Right("step-1 -> step-2"))
    assertEquals(prediction.raw.completions, Some(completions))
    assertEquals(prediction.raw.lmUsage, Some(usage))
  }
