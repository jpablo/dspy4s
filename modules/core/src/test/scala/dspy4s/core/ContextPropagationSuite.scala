package dspy4s.core

import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.SettingsData
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import java.util.concurrent.Executors

class ContextPropagationSuite extends FunSuite:
  private val sampleKey = SettingKey[String]("sample")

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("plain future does not inherit thread-local runtime context") {
    val base = ExecutionContext.global

    val observed = RuntimeEnvironment.withSettings(SettingsData(Map(sampleKey.name -> "scoped"))) {
      Await.result(Future(RuntimeEnvironment.currentSettings.get(sampleKey))(using base), 3.seconds)
    }

    assertNotEquals(observed, Some("scoped"))
  }

  test("context propagation wraps execution context with captured runtime context") {
    val base = ExecutionContext.global

    val observed = RuntimeEnvironment.withSettings(SettingsData(Map(sampleKey.name -> "scoped"))) {
      Await.result(
        ContextPropagation.future(RuntimeEnvironment.currentSettings.get(sampleKey))(using base),
        3.seconds
      )
    }

    assertEquals(observed, Some("scoped"))
  }

  test("context propagation assigns distinct async task ids across futures") {
    val singleThread: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(
      Executors.newSingleThreadExecutor()
    )

    try
      given ExecutionContext = singleThread

      val first = Await.result(
        ContextPropagation.future(RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "a"))),
        3.seconds
      )
      val second = Await.result(
        ContextPropagation.future(RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "b"))),
        3.seconds
      )

      assertEquals(first, Right(()))
      assert(second.isLeft)
      assert(second.left.toOption.get.isInstanceOf[ConfigurationError])
    finally
      singleThread.shutdownNow()
  }
