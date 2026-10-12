package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.TraceEntry
import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.strategies.ReAct
import dspy4s.programs.strategies.RLM
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** GEPA on a `ReAct` program. The first leaf (the react step) runs in a loop before the second leaf (the extractor)
  * runs, so the position of a trace entry does not tell the leaf (PORT_GAPS G-44, item "trace association"). `RLM` has
  * the same structure of two leaves.
  */
class GepaReActTraceSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private final class SearchTool extends ToolFunction:
    override val name: String                                                                             = "search"
    override val description: String                                                                      = "Look up a fact about the world."
    override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
      Right(ToolFunction.result("Brussels"))

  /** Plays one ReAct episode by the last user message: a search step, then `finish`, then the extraction. */
  private final class ReActLm extends LanguageModel:
    override val id: String                                                                    = "react"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val ask  = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val text =
        if !ask.contains("`[[ ## next_thought ## ]]`") then
          "[[ ## reasoning ## ]]\nThe search gave the capital.\n\n[[ ## answer ## ]]\nBrussels\n\n[[ ## completed ## ]]"
        else if ask.contains("observation: Brussels") then step("I have the answer.", "finish", "{}")
        else step("I look it up.", "search", """{"query": "capital of Belgium"}""")
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

    private def step(thought: String, tool: String, args: String): String =
      s"[[ ## next_thought ## ]]\n$thought\n\n[[ ## next_tool_name ## ]]\n$tool\n\n" +
        s"[[ ## next_tool_args ## ]]\n$args\n\n[[ ## completed ## ]]"

  /** Records what the adapter gives to the metric for each component. */
  private final class RecordingMetric extends FeedbackMetric:
    val componentCalls: ArrayBuffer[(String, Vector[TraceEntry])] = ArrayBuffer.empty
    override def name: String                                     = "recording"
    override def feedback(
        example       : Example,
        prediction    : RawPrediction,
        trace         : Vector[TraceEntry],
        component     : Option[String],
        componentTrace: Vector[TraceEntry]
    )(using RuntimeContext): Either[DspyError, ScoreWithFeedback] =
      component.foreach(name => componentCalls.synchronized(componentCalls += (name -> componentTrace)))
      Right(ScoreWithFeedback(1.0, s"feedback for ${component.getOrElse("program")}"))

  private val react = ReAct(baseSignature = Signature.fromString("question -> answer"), tools = Vector(new SearchTool))

  private val reactId     = OptimizableId(0)
  private val extractorId = OptimizableId(1)

  private val dataset: Vector[Example] = Vector(
    Example(
      values = DynamicValues.record("question" := "What is the capital of Belgium?", "answer" := "Brussels"),
      inputKeys = Set("question")
    )
  )

  test("the react step runs two times before the extractor, and each leaf gets a record from its own call") {
    val metric = new RecordingMetric
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(new ReActLm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val adapter          = new GepaAdapter(react, metric)
      val seed             = Candidate.seed(react)
      val batch            = adapter.evaluate(dataset, seed, captureTraces = true)

      val trace = batch.trajectories.toVector.flatten.flatMap(_.trace)
      assertEquals(trace.flatMap(_.predictor).map(_.moduleName), Vector("react", "react", "react_extract"))
      // The `ReAct` module records an entry with the module name of the react step and no key. It is not a leaf call.
      assertEquals(trace.filter(_.predictor.isEmpty).map(_.component), Vector("react"))

      val records = adapter
        .makeReflectiveDataset(seed, batch, Vector(reactId, extractorId))
        .fold(error => fail(error.message), identity)

      assertEquals(records(extractorId).size, 1)
      assert(
        records(extractorId).head.generatedOutputs.contains("Brussels"),
        records(extractorId).head.generatedOutputs
      )
      assert(
        !records(extractorId).head.generatedOutputs.contains("next_tool_name"),
        records(extractorId).head.generatedOutputs
      )
      assertEquals(records(reactId).size, 1)
      assert(records(reactId).head.generatedOutputs.contains("next_tool_name"), records(reactId).head.generatedOutputs)
    }

    val byName = metric.componentCalls.toVector.groupMap(_._1)(_._2).view.mapValues(_.flatten).toMap
    assertEquals(byName.keySet, Set("react", "extractor"))
    assertEquals(byName("react").flatMap(_.predictor).map(_.moduleName), Vector("react"))
    assertEquals(byName("extractor").flatMap(_.predictor).map(_.moduleName), Vector("react_extract"))
  }

  test("the two leaves of ReAct and of RLM have different keys, so GEPA does not reject these programs") {
    val signature = Signature.fromString("question -> answer")
    val metric    = new RecordingMetric
    assertEquals(new GepaAdapter(react, metric).distinctKeys, Right(()))
    assertEquals(new GepaAdapter(RLM(signature), metric).distinctKeys, Right(()))
  }
