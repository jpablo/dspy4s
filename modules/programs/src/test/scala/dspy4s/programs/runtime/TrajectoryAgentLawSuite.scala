package dspy4s.programs.runtime

import dspy4s.core.contracts.{:=, ContextWindowExceededError, DynamicValues, LmUsage, RuntimeError}
import dspy4s.core.data.{Completions, RawPrediction}
import dspy4s.programs.contracts.Prediction
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** Behavioral laws of the final trajectory/extraction algorithm owned by [[TrajectoryAgent]]. Implementations choose
  * their transition language, renderer, and extractor, but cannot change these orchestration semantics.
  */
final class TrajectoryAgentLawSuite extends FunSuite:

  test("Done extracts exactly once from the completed trajectory") {
    val extractions = AtomicInteger(0)
    val result      = TrajectoryAgent.runAndExtract[String, String](
      maxIterations = 5,
      render = _.mkString(" -> ")
    )(
      step = (trajectory, iteration) =>
        val next = trajectory :+ s"step-$iteration"
        Right(if iteration == 1 then AgentLoop.Step.Done(next) else AgentLoop.Step.Continue(next))
    )(
      extract = rendered =>
        extractions.incrementAndGet()
        Right(rendered)
    )

    assertEquals(result, Right("step-0 -> step-1" -> "step-0 -> step-1"))
    assertEquals(extractions.get(), 1)
  }

  test("exhaustion extracts exactly once from the final accumulated trajectory") {
    val extractions = AtomicInteger(0)
    val result      = TrajectoryAgent.runAndExtract[Int, String](
      maxIterations = 3,
      render = _.mkString(",")
    )(
      step = (trajectory, iteration) => Right(AgentLoop.Step.Continue(trajectory :+ iteration))
    )(
      extract = rendered =>
        extractions.incrementAndGet()
        Right(rendered)
    )

    assertEquals(result, Right("0,1,2" -> "0,1,2"))
    assertEquals(extractions.get(), 1)
  }

  test("a failed trajectory step short-circuits without running extraction") {
    val extractions = AtomicInteger(0)
    val failure     = RuntimeError("trajectory-law", "step failed")
    val result      = TrajectoryAgent.runAndExtract[String, String](
      maxIterations = 3,
      render = _.mkString(",")
    )(
      step = (_, _) => Left(failure)
    )(
      extract = _ =>
        extractions.incrementAndGet()
        Right("unreachable")
    )

    assertEquals(result, Left(failure))
    assertEquals(extractions.get(), 0)
  }

  test("runAndExtractPrediction attaches the complete trajectory and preserves the extractor envelope") {
    val completionRows = Vector(
      DynamicValues.record("answer" := "first"),
      DynamicValues.record("answer" := "second")
    )
    val completions = Completions.fromRows(completionRows).toOption.get
    val usage       = LmUsage(totalTokens = 9, promptTokens = 5, completionTokens = 4)
    val extracted   = Prediction(
      output = "expected-answer",
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
    assertEquals(prediction.output, "expected-answer")
    assertEquals(prediction.raw.asString("answer"), Right("first"))
    assertEquals(prediction.raw.asString("metadata"), Right("preserved"))
    assertEquals(prediction.raw.asString("trajectory"), Right("step-1 -> step-2"))
    assertEquals(prediction.raw.completions, Some(completions))
    assertEquals(prediction.raw.lmUsage, Some(usage))
  }

  test("extractor-local truncation does not change the complete attached trajectory") {
    val attempts = ArrayBuffer.empty[String]
    val result   = TrajectoryAgent.runAndExtractPrediction[String, String](
      maxIterations = 3,
      render = _.mkString(" -> "),
      trajectoryKey = "trajectory",
      extractAttempts = 3
    )(
      step = (trajectory, iteration) =>
        val next = trajectory :+ s"step-${iteration + 1}"
        Right(AgentLoop.Step.Continue(next))
    )(
      extract = rendered =>
        attempts += rendered
        if attempts.size == 1 then Left(ContextWindowExceededError())
        else Right(Prediction("answer", RawPrediction(DynamicValues.record("answer" := "answer"))))
    )

    val prediction = result.toOption.get
    assertEquals(attempts.toVector, Vector("step-1 -> step-2 -> step-3", "step-2 -> step-3"))
    assertEquals(prediction.raw.asString("trajectory"), Right("step-1 -> step-2 -> step-3"))
  }
