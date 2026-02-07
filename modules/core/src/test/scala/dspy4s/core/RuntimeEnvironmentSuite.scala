package dspy4s.core

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeContextData
import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.SettingsData
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

class RuntimeEnvironmentSuite extends FunSuite:
  private val sampleKey = SettingKey[String]("sample")

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  test("withContext restores previous context after scope") {
    val base = RuntimeEnvironment.current
    val scopedSettings = SettingsData(Map("sample" -> "scoped"))
    val scopedContext = RuntimeContextData(settings = scopedSettings)

    RuntimeEnvironment.withContext(scopedContext) {
      assertEquals(RuntimeEnvironment.current.settings.get(sampleKey), Some("scoped"))
    }

    assertEquals(RuntimeEnvironment.current.settings.get(sampleKey), base.settings.get(sampleKey))
  }

  test("withSettings applies only inside scope") {
    val updated = RuntimeEnvironment.current.settings.updated(sampleKey, "value")

    RuntimeEnvironment.withSettings(updated) {
      assertEquals(RuntimeEnvironment.current.settings.get(sampleKey), Some("value"))
    }

    assertEquals(RuntimeEnvironment.current.settings.get(sampleKey), None)
  }

  test("emit dispatches callback events") {
    val received = ArrayBuffer.empty[CallbackEvent]

    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        received += event

    val context = RuntimeContextData(callbacks = Vector(callback))

    RuntimeEnvironment.withContext(context) {
      RuntimeEnvironment.emit(ModuleStartEvent("predict", Map("q" -> "hi")))
    }

    assertEquals(received.size, 1)
    assert(received.head.isInstanceOf[ModuleStartEvent])
  }

  test("configure sets global defaults visible in current settings") {
    val configured = RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "global"))
    assertEquals(configured, Right(()))
    assertEquals(RuntimeEnvironment.currentSettings.get(sampleKey), Some("global"))
  }

  test("withSetting overrides global setting only inside scope") {
    RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "global"))

    RuntimeEnvironment.withSetting(sampleKey, "local") {
      assertEquals(RuntimeEnvironment.currentSettings.get(sampleKey), Some("local"))
    }

    assertEquals(RuntimeEnvironment.currentSettings.get(sampleKey), Some("global"))
  }

  test("configure is rejected from non-owner thread") {
    val first = RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "owner"))
    assertEquals(first, Right(()))

    val threadResult = ArrayBuffer.empty[Either[Throwable, Either[?, ?]]]
    val worker = Thread(
      new Runnable:
        override def run(): Unit =
          try
            threadResult += Right(RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "other")))
          catch
            case error: Throwable => threadResult += Left(error)
    )

    worker.start()
    worker.join()

    assertEquals(threadResult.size, 1)
    val result = threadResult.head match
      case Right(value) => value
      case Left(error)  => fail(s"Unexpected thread failure: $error")
    assert(result.isLeft)
    assert(result.left.toOption.get.isInstanceOf[ConfigurationError])
  }
