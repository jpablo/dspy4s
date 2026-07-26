package dspy4s.programs

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ErrorLimit
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.runtime.ParallelExecutor
import munit.FunSuite
import java.util.concurrent.atomic.AtomicInteger

class ParallelExecutorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("worker threads inherit captured runtime context") {
    RuntimeEnvironment.withSettings(RuntimeContext(numThreads = Some(ThreadCount(42)))) {
      given RuntimeContext = RuntimeEnvironment.current
      val executor = ParallelExecutor(numThreads = ThreadCount(3), maxErrors = ErrorLimit(3))

      val result = executor.execute(
        task = (_: Int) => RuntimeEnvironment.current.numThreads.map(_.toString).getOrElse("missing"),
        data = Vector(1, 2, 3, 4, 5)
      )

      assert(result.isRight)
      val values = result.toOption.get.results.flatten
      assertEquals(values, Vector("42", "42", "42", "42", "42"))
    }
  }

  test("max errors not met keeps execution result with failed indices") {
    given RuntimeContext = RuntimeEnvironment.current
    val executor = ParallelExecutor(numThreads = ThreadCount(3), maxErrors = ErrorLimit(2))

    val result = executor.execute(
      task = (item: Int) =>
        if item == 3 then throw IllegalArgumentException("Intentional error")
        else item,
      data = Vector(1, 2, 3, 4, 5)
    )

    assert(result.isRight)
    val output = result.toOption.get
    assertEquals(output.results, Vector(Some(1), Some(2), None, Some(4), Some(5)))
    assertEquals(output.failedIndices, Vector(2))
    assert(output.errors.get(2).exists(_.isInstanceOf[RuntimeError]))
  }

  test("max errors met returns cancellation error") {
    given RuntimeContext = RuntimeEnvironment.current
    val executor = ParallelExecutor(numThreads = ThreadCount(3), maxErrors = ErrorLimit(1))

    val result = executor.execute(
      task = (item: Int) =>
        if item == 3 then throw IllegalArgumentException("Intentional error")
        else item,
      data = Vector(1, 2, 3, 4, 5)
    )

    assert(result.isLeft)
    val error = result.left.toOption.get
    assert(error.isInstanceOf[RuntimeError])
    assertEquals(error.message, "Execution cancelled due to errors or interruption.")
  }

  test("fromSettings uses runtime numThreads and maxErrors values") {
    RuntimeEnvironment.withSettings(
      RuntimeContext(
          numThreads = Some(ThreadCount(2)),
          maxErrors = Some(ErrorLimit(1))
        )
    ) {
      given RuntimeContext = RuntimeEnvironment.current
      val executor = ParallelExecutor.fromSettings()

      val result = executor.execute(
        task = (item: Int) =>
          if item == 2 then throw IllegalStateException("boom")
          else item,
        data = Vector(1, 2, 3)
      )

      assert(result.isLeft)
      assertEquals(result.left.toOption.get.message, "Execution cancelled due to errors or interruption.")
    }
  }

  test("max errors stops scheduling additional work") {
    given RuntimeContext = RuntimeEnvironment.current
    val started = AtomicInteger(0)
    val executor = ParallelExecutor(numThreads = ThreadCount(2), maxErrors = ErrorLimit(1))

    val result = executor.execute(
      task = (item: Int) =>
        started.incrementAndGet()
        if item == 0 then throw IllegalStateException("boom")
        Thread.sleep(100)
        item,
      data = (0 until 20).toVector
    )

    assert(result.isLeft)
    assert(started.get() <= 2)
  }
