package dspy4s.core

import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.ConfigurationError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKey
import dspy4s.core.contracts.Settings
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
    val scopedSettings = Settings(Map("sample" -> "scoped"))
    val scopedContext = RuntimeContext(settings = scopedSettings)

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

    val context = RuntimeContext(callbacks = Vector(callback))

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

  test("configure is allowed repeatedly within the same async task id") {
    val result = RuntimeEnvironment.withAsyncTask("task-a") {
      val first = RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "v1"))
      val second = RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "v2"))
      (first, second, RuntimeEnvironment.currentSettings.get(sampleKey))
    }

    assertEquals(result._1, Right(()))
    assertEquals(result._2, Right(()))
    assertEquals(result._3, Some("v2"))
  }

  test("configure is rejected from different async task ids on the same thread") {
    RuntimeEnvironment.withAsyncTask("task-a") {
      assertEquals(RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "v1")), Right(()))
    }

    val second = RuntimeEnvironment.withAsyncTask("task-b") {
      RuntimeEnvironment.configureEntries(Map(sampleKey.name -> "v2"))
    }

    assert(second.isLeft)
    assert(second.left.toOption.get.isInstanceOf[ConfigurationError])
  }

  test("withActiveCall maintains nested call stack and restores after scope") {
    assertEquals(RuntimeEnvironment.activeCallStack, Vector.empty)
    assertEquals(RuntimeEnvironment.activeCallDepth, 0)
    assertEquals(RuntimeEnvironment.activeCallId, None)

    RuntimeEnvironment.withActiveCall("parent") {
      assertEquals(RuntimeEnvironment.activeCallStack, Vector("parent"))
      assertEquals(RuntimeEnvironment.activeCallDepth, 1)
      assertEquals(RuntimeEnvironment.activeCallId, Some("parent"))

      RuntimeEnvironment.withActiveCall("child") {
        assertEquals(RuntimeEnvironment.activeCallStack, Vector("parent", "child"))
        assertEquals(RuntimeEnvironment.activeCallDepth, 2)
        assertEquals(RuntimeEnvironment.activeCallId, Some("child"))
      }

      assertEquals(RuntimeEnvironment.activeCallStack, Vector("parent"))
      assertEquals(RuntimeEnvironment.activeCallDepth, 1)
      assertEquals(RuntimeEnvironment.activeCallId, Some("parent"))
    }

    assertEquals(RuntimeEnvironment.activeCallStack, Vector.empty)
    assertEquals(RuntimeEnvironment.activeCallDepth, 0)
    assertEquals(RuntimeEnvironment.activeCallId, None)
  }

  test("appendHistory honors max history size and disable history settings") {
    RuntimeEnvironment.withSetting(dspy4s.core.contracts.SettingKeys.maxHistorySize, 2) {
      RuntimeEnvironment.appendHistory(dspy4s.core.contracts.HistoryEntry("lm", Map("n" -> 1)))
      RuntimeEnvironment.appendHistory(dspy4s.core.contracts.HistoryEntry("lm", Map("n" -> 2)))
      RuntimeEnvironment.appendHistory(dspy4s.core.contracts.HistoryEntry("lm", Map("n" -> 3)))

      val history = RuntimeEnvironment.current.history
      assertEquals(history.size, 2)
      assertEquals(history.head.payload("n"), 2)
      assertEquals(history.last.payload("n"), 3)
    }

    RuntimeEnvironment.withSetting(dspy4s.core.contracts.SettingKeys.disableHistory, true) {
      RuntimeEnvironment.appendHistory(dspy4s.core.contracts.HistoryEntry("lm", Map("n" -> 4)))
      assertEquals(RuntimeEnvironment.current.history, Vector.empty)
    }
  }
