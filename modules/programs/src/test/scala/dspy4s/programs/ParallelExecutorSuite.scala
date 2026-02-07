package dspy4s.programs

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.runtime.ParallelExecutor
import munit.FunSuite
import java.util.concurrent.atomic.AtomicInteger

class ParallelExecutorSuite extends FunSuite:
  private val sampleKey = SettingKey[String]("sample")

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("worker threads inherit captured runtime context") {
    RuntimeEnvironment.withSettings(SettingsData(Map(sampleKey.name -> "scoped"))) {
      given RuntimeContext = RuntimeEnvironment.current
      val executor = ParallelExecutor(numThreads = 3, maxErrors = 3)

      val result = executor.execute(
        task = (_: Int) => RuntimeEnvironment.currentSettings.get(sampleKey).getOrElse("missing"),
        data = Vector(1, 2, 3, 4, 5)
      )

      assert(result.isRight)
      val values = result.toOption.get.results.flatten
      assertEquals(values, Vector("scoped", "scoped", "scoped", "scoped", "scoped"))
    }
  }

  test("max errors not met keeps execution result with failed indices") {
    given RuntimeContext = RuntimeEnvironment.current
    val executor = ParallelExecutor(numThreads = 3, maxErrors = 2)

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
    val executor = ParallelExecutor(numThreads = 3, maxErrors = 1)

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
      SettingsData(
        Map(
          SettingKeys.numThreads.name -> 2,
          SettingKeys.maxErrors.name -> 1
        )
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
    val executor = ParallelExecutor(numThreads = 2, maxErrors = 1)

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
