package dspy4s.programs

import dspy4s.core.contracts.{DspyError, RuntimeError}
import dspy4s.core.data.RawPrediction
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

final class DeepProgramSuite extends FunSuite:

  private val unusedBackend = new PredictionBackend:
    def generate(@annotation.unused request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
      ZIO.fail(RuntimeError("unused_backend", "a pure program must not invoke prediction"))

  private def run[A](effect: ZIO[PredictionBackend, Nothing, A]): A =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(effect.provideEnvironment(ZEnvironment(unusedBackend)))
        .getOrThrowFiberFailure()
    }

  private def chain(size: Int): ProgramWithEnv[Int, Int, Any] =
    val increment = Program.lift[Int, Int](_ + 1)
    var result    = Program.identity[Int]
    var index     = 0
    while index < size do
      result = result >>> increment
      index += 1
    result

  test("20,000 sequential syntax nodes execute without using the JVM call stack") {
    val deep = chain(20_000)

    assertEquals(run(ProgramRunner.runJournaled(deep, 0)).outcome.map(_.output), Right(20_000))
  }

  test("the graph interpreter handles the same 20,000-node program") {
    val graph = ProgramGraph.from(chain(20_000))

    assertEquals(graph.nodes.size, 40_001)
    assertEquals(graph.edges.size, 40_000)
  }

  test("20,000 bounded loop transitions use the effect continuation stack") {
    val step = Program.lift[Int, LoopDecision[Int, Int]] { state =>
      if state >= 20_000 then LoopDecision.Done(state)
      else LoopDecision.Continue(state + 1)
    }
    val loop = Program.iterate(step, maxSteps = 20_001)

    assertEquals(run(ProgramRunner.runJournaled(loop, 0)).outcome.map(_.output), Right(20_000))
  }

  test("20,000 collectAll members execute and graph without using the JVM call stack") {
    val members   = Vector.fill(20_000)(Program.lift[Int, Int](_ + 1))
    val collected = Program.collectAll(members)
    val execution = run(ProgramRunner.runJournaled(collected, 1))
    val graph     = ProgramGraph.from(collected)

    assertEquals(execution.outcome.map(_.output.size), Right(20_000))
    assertEquals(execution.outcome.map(_.output.headOption), Right(Some(2)))
    assertEquals(graph.nodes.size, 20_001)
    assertEquals(graph.edges.size, 20_000)
  }
