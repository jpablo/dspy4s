package dspy4s.core

import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.ContextPropagation
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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
