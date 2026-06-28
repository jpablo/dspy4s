package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeError
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

/** Laws for the bounded agentic-iteration primitive [[AgentLoop.run]] (Algebra 2; the shared core of
  * ReAct/CodeAct/RLM/PoT). The end-to-end agent behavior is covered by their own suites; this pins the
  * control-flow primitive with pure synthetic steps. */
class AgentLoopLawSuite extends FunSuite:

  test("maxIterations = 0 runs no steps and returns onExhausted(initialState)") {
    val steps = AtomicInteger(0)
    val result = AgentLoop.run[Vector[Int], String](Vector(1, 2), iteration = 0, maxIterations = 0)(
      onExhausted = state => Right(s"exhausted:${state.mkString(",")}")
    ) { (state, _) =>
      steps.incrementAndGet()
      Right(AgentLoop.Step.Continue(state))
    }
    assertEquals(result, Right("exhausted:1,2"))
    assertEquals(steps.get(), 0)
  }

  test("Done short-circuits at the first finishing step") {
    val steps = AtomicInteger(0)
    val result = AgentLoop.run[Vector[Int], Int](Vector.empty, 0, maxIterations = 5)(
      onExhausted = _ => Right(-1)
    ) { (state, iteration) =>
      steps.incrementAndGet()
      if iteration == 1 then Right(AgentLoop.Step.Done(99))
      else Right(AgentLoop.Step.Continue(state :+ iteration))
    }
    assertEquals(result, Right(99))
    assertEquals(steps.get(), 2) // iteration 0 (Continue), iteration 1 (Done); later not run
  }

  test("Continue threads the updated state; exhaustion calls onExhausted with the final state") {
    val result = AgentLoop.run[Vector[Int], Vector[Int]](Vector.empty, 0, maxIterations = 3)(
      onExhausted = state => Right(state)
    ) { (state, iteration) =>
      Right(AgentLoop.Step.Continue(state :+ iteration))
    }
    assertEquals(result, Right(Vector(0, 1, 2))) // iterations 0,1,2 accumulated, then exhausted
  }

  test("a Left from a step short-circuits the loop") {
    val steps = AtomicInteger(0)
    val boom: DspyError = RuntimeError("loop", "boom")
    val result = AgentLoop.run[Vector[Int], Int](Vector.empty, 0, maxIterations = 10)(
      onExhausted = _ => Right(0)
    ) { (state, iteration) =>
      steps.incrementAndGet()
      if iteration == 2 then Left(boom)
      else Right(AgentLoop.Step.Continue(state :+ iteration))
    }
    assertEquals(result, Left(boom))
    assertEquals(steps.get(), 3) // iterations 0,1 (Continue), 2 (Left); stops
  }

  test("the iteration index is 0-based and increments by one") {
    val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
    val _ = AgentLoop.run[Unit, Unit]((), 0, maxIterations = 4)(
      onExhausted = _ => Right(())
    ) { (_, iteration) =>
      seen += iteration
      Right(AgentLoop.Step.Continue(()))
    }
    assertEquals(seen.toVector, Vector(0, 1, 2, 3))
  }
