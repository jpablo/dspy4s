package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SettingKeys
import dspy4s.core.contracts.SettingsData
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.PredictProgram
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolCallRequest
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.streaming.contracts.StatusEvent
import dspy4s.streaming.contracts.StreamEvent
import munit.FunSuite

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.collection.mutable.ArrayBuffer

/** Ports the Python `@pytest.mark.anyio`-marked tests in
  * `tests/streaming/test_streaming.py` that exercise the status-message side
  * of the stream rather than per-field listeners. None of these tests need a
  * live LM — they validate isolation and non-blocking semantics of the
  * status pipeline.
  *
  * Python originals:
  *   - `test_concurrent_status_message_providers`
  *   - `test_status_message_non_blocking`
  */
class StatusStreamingParitySuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  /** A program that invokes a tool and then "predicts" a fixed answer. We
    * keep it minimal — no LM is configured because the test only inspects
    * status events. */
  private def buildToolProgram(tool: ToolFunction, toolArgs: Map[String, Any]): PredictProgram =
    new PredictProgram:
      override val moduleName: String = "tool_caller"
      override def run(input: ProgramCall)(using RuntimeContext): Either[DspyError, Prediction] =
        ToolExecutor.invoke(ToolCallRequest(name = tool.name, args = toolArgs), Vector(tool)).map { _ =>
          PredictionData(values = Map("answer" -> "ok"))
        }

  test("concurrent status-message providers don't bleed messages across streamify invocations") {
    // Two streamify calls run on independent threads, each with a distinct
    // provider. Each consumer must see only its own provider's messages.
    val toolA = new ToolFunction:
      override val name: String = "tool_a"
      override def invoke(args: Map[String, Any])(using RuntimeContext) = Right("a-done")
    val toolB = new ToolFunction:
      override val name: String = "tool_b"
      override def invoke(args: Map[String, Any])(using RuntimeContext) = Right("b-done")

    val providerA = new StatusMessageProvider:
      override def toolStart(name: String, args: Map[String, Any]): Option[String] =
        Some(s"ProviderA: $name starting!")
      override def toolEnd(name: String, output: Any): Option[String] =
        Some(s"ProviderA: $name finished!")
    val providerB = new StatusMessageProvider:
      override def toolStart(name: String, args: Map[String, Any]): Option[String] =
        Some(s"ProviderB: $name starting!")
      override def toolEnd(name: String, output: Any): Option[String] =
        Some(s"ProviderB: $name finished!")

    val pool = Executors.newFixedThreadPool(2)
    val ready = new CountDownLatch(2)

    @volatile var messagesA: Vector[String] = Vector.empty
    @volatile var messagesB: Vector[String] = Vector.empty

    val runA: Runnable = () => {
      RuntimeEnvironment.withSettings(SettingsData(Map.empty)) {
        given RuntimeContext = RuntimeEnvironment.current
        val stream = Streamify.streamify(
          program = buildToolProgram(toolA, Map.empty),
          statusMessageProvider = Some(providerA)
        )(Map.empty)
        ready.countDown()
        ready.await(5, TimeUnit.SECONDS)
        val msgs = ArrayBuffer.empty[String]
        while stream.hasNext do
          stream.next() match
            case s: StatusEvent => msgs += s.message
            case _              => ()
        messagesA = msgs.toVector
      }
    }
    val runB: Runnable = () => {
      RuntimeEnvironment.withSettings(SettingsData(Map.empty)) {
        given RuntimeContext = RuntimeEnvironment.current
        val stream = Streamify.streamify(
          program = buildToolProgram(toolB, Map.empty),
          statusMessageProvider = Some(providerB)
        )(Map.empty)
        ready.countDown()
        ready.await(5, TimeUnit.SECONDS)
        val msgs = ArrayBuffer.empty[String]
        while stream.hasNext do
          stream.next() match
            case s: StatusEvent => msgs += s.message
            case _              => ()
        messagesB = msgs.toVector
      }
    }

    val futureA = pool.submit(runA)
    val futureB = pool.submit(runB)
    futureA.get(10, TimeUnit.SECONDS)
    futureB.get(10, TimeUnit.SECONDS)
    pool.shutdown()

    // ProviderA's stream must contain ProviderA messages and never ProviderB.
    assert(messagesA.forall(_.startsWith("ProviderA:")), s"A bled providers: $messagesA")
    assert(messagesA.exists(_.contains("tool_a")), s"A missing its tool: $messagesA")
    // Same for B.
    assert(messagesB.forall(_.startsWith("ProviderB:")), s"B bled providers: $messagesB")
    assert(messagesB.exists(_.contains("tool_b")), s"B missing its tool: $messagesB")
  }

  test("blocking tool: status events flow during a slow tool — start arrives before the tool returns") {
    // Python's test_status_message_non_blocking asserts that >= 1s elapses
    // between the tool-start and tool-end status events when the tool
    // sleeps for 1s. We use 200ms to keep the test snappy while still
    // demonstrating that the start event is consumable before the tool
    // returns.
    val sleepMillis = 200L
    val slowTool = new ToolFunction:
      override val name: String = "slow"
      override def invoke(args: Map[String, Any])(using RuntimeContext) =
        Thread.sleep(sleepMillis)
        Right("done")

    given RuntimeContext = RuntimeEnvironment.current
    val stream = Streamify.streamify(
      program = buildToolProgram(slowTool, Map.empty),
      statusMessageProvider = Some(StatusMessageProvider.default)
    )(Map.empty)

    val statusTimestamps = ArrayBuffer.empty[(String, Long)]
    while stream.hasNext do
      stream.next() match
        case s: StatusEvent =>
          statusTimestamps += ((s.message, s.timestamp.toEpochMilli))
        case _ => ()

    // We expect at least a "Calling tool slow..." start and a
    // "Tool calling finished!..." end status.
    val startTs = statusTimestamps.find(_._1.contains("Calling tool slow")).map(_._2)
    val endTs = statusTimestamps.find(_._1.contains("Tool calling finished")).map(_._2)
    assert(startTs.isDefined, s"no tool-start status: ${statusTimestamps.map(_._1)}")
    assert(endTs.isDefined, s"no tool-end status: ${statusTimestamps.map(_._1)}")
    val delta = endTs.get - startTs.get
    assert(
      delta >= sleepMillis,
      s"tool-start..tool-end delta should be >= ${sleepMillis}ms (got ${delta}ms); status callbacks must not block on the tool"
    )
  }
