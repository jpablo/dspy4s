/** Tools
  *
  * Source: docs/docs/learn/programming/tools.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/tools.md Status: translated (ReAct +
  * tools, snippets 1/2/9; native-function-calling toggle, snippet 6). The manual-tool-call path (snippets 3/5) and
  * async tools (7/8) aren't part of dspy4s's surface — dspy4s's ReAct selects tools via output fields and tools are
  * explicit `Tool` values and `ReAct` receives separate generator, invocation, and extractor programs.
  */
package dspy4s.examples.learn.programming

import dspy4s.adapters.{ChatAdapter, JSONAdapter}
import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeError, TypeRef}
import dspy4s.examples.Demo
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import dspy4s.programs.{LiveToolBackend, PredictionBackend, Program, ReAct, ToolBackend}
import dspy4s.signatures.Signature
import zio.ZEnvironment
import zio.blocks.schema.Schema

final case class WeatherQuestion(question: String) derives Schema
final case class WeatherDecision(thought: String, toolName: String, city: String, finished: Boolean) derives Schema
final case class WeatherAnswer(answer: String) derives Schema
final case class WeatherGeneratorInput(question: String, trajectory: String) derives Schema
final case class WeatherExtractorInput(question: String, trajectory: String) derives Schema

object Tools:

  // ── Snippet 1 (lines 20–45) — a ReAct agent over two tools ──────────────────
  // | def get_weather(city: str) -> str:
  // |     """Get the current weather for a city."""
  // |     return f"The weather in {city} is sunny and 75°F"
  // | def search_web(query: str) -> str:
  // |     """Search the web for information."""
  // |     return f"Search results for '{query}': [relevant information...]"
  // | react_agent = dspy.ReAct(signature="question -> answer", tools=[get_weather, search_web], max_iters=5)
  // | result = react_agent(question="What's the weather like in Tokyo?")
  // | print(result.answer)
  // | print("Tool calls made:", result.trajectory)
  //
  // The functional API represents tools as effectful values. ReAct interprets tool requests through `ToolBackend`.
  object WeatherAgentExample:
    // --8<-- [start:tools]
    def get_weather(city: String): String = s"The weather in $city is sunny and 75°F"

    def search_web(query: String): String = s"Search results for '$query': [relevant information...]"

    val getWeather: Tool = Tool.fromEither(
      "get_weather",
      "Get the current weather for a city.",
      Vector("city" -> TypeRef.string)
    )(args =>
      DynamicValues.requireString(args, "city", "get_weather").map(city => DynamicValues.fromAny(get_weather(city)))
    )
    val searchWeb: Tool = Tool.fromEither(
      "search_web",
      "Search the web for information.",
      Vector("query" -> TypeRef.string)
    )(args =>
      DynamicValues.requireString(args, "query", "search_web").map(query => DynamicValues.fromAny(search_web(query)))
    )
    // --8<-- [end:tools]

    // ── Snippet 2 (lines 57–63) — ReAct configuration ──
    // | react_agent = dspy.ReAct(signature="question -> answer", tools=[tool1, tool2, tool3], max_iters=10)
    // --8<-- [start:react-agent]
    private val generator = Program
      .predict(
        Signature.derived[WeatherGeneratorInput, WeatherDecision](
          "WeatherReActStep",
          "Select get_weather or search_web. Set finished=true when the trajectory contains enough information."
        )
      )
      .contramap[ReAct.StepInput[WeatherQuestion]](input =>
        WeatherGeneratorInput(input.input.question, input.trajectory.mkString("\n"))
      )
      .map { decision =>
        val action =
          if decision.finished then ReAct.Action.Finish()
          else
            val args =
              if decision.toolName == "search_web" then
                DynamicValues.recordFromEntries(Seq("query" -> DynamicValues.fromAny(decision.city)))
              else DynamicValues.recordFromEntries(Seq("city" -> DynamicValues.fromAny(decision.city)))
            ReAct.Action.Invoke(ToolCallRequest(decision.toolName, args))
        ReAct.Step(decision.thought, action)
      }

    private val extractor = Program
      .predict(
        Signature.derived[WeatherExtractorInput, WeatherAnswer](
          "WeatherReActAnswer",
          "Answer the question from the tool trajectory."
        )
      )
      .contramap[ReAct.ExtractInput[WeatherQuestion]](input =>
        WeatherExtractorInput(input.input.question, input.trajectory.mkString("\n"))
      )

    val reactAgent = ReAct(
      generator = generator,
      toolInvoker = Program.invokeTool,
      extractor = extractor,
      maxIterations = 5
    )

    def call(question: String)(using backend: PredictionBackend): Either[DspyError, String] =
      val toolBackend: ToolBackend = new LiveToolBackend(Vector(getWeather, searchWeb))
      val environment              = ZEnvironment[PredictionBackend](backend) ++ ZEnvironment[ToolBackend](toolBackend)
      Demo.runWith(reactAgent, WeatherQuestion(question), environment).map(_.output.answer)
    // --8<-- [end:react-agent]

  // ── Snippet 9 (lines 270–288) — what makes a good tool ──────────────────────
  // | def good_tool(city: str, units: str = "celsius") -> str:
  // |     """Get weather information for a specific city. Args: city, units. Returns: ..."""
  // |     if not city.strip(): return "Error: City name cannot be empty"
  // |     return f"Weather in {city}: 25°{units[0].upper()}, sunny"
  // A clear `name` + `description` and defensive argument handling carry over directly to `Tool`:
  object GoodToolExample:
    val goodTool: Tool = Tool.fromEither(
      "good_tool",
      "Get weather information for a specific city (units: celsius | fahrenheit)."
    ) { args =>
      val city  = DynamicValues.recordGet(args, "city").map(DynamicValues.renderText).getOrElse("").trim
      val units = DynamicValues.recordGet(args, "units").map(DynamicValues.renderText).getOrElse("celsius")
      if city.isEmpty then Left(RuntimeError("good_tool", "City name cannot be empty"))
      else Right(DynamicValues.fromAny(s"Weather in $city: 25°${units.take(1).toUpperCase}, sunny"))
    }

  // ── Snippets 3 / 5 — manual `dspy.Tool` input field + `ToolCalls` output + `call.execute()` ──
  // Not portable: dspy4s selects tools via output fields inside an agent (ReAct), not by passing a
  // `list[dspy.Tool]` input and reading a `ToolCalls` output, and it has no `call.execute()` surface.
  //
  // ── Snippet 6 — `ChatAdapter(use_native_function_calling=True)` / `JSONAdapter(...)` ──
  // Ported (PORT_GAPS G-7b): native function-calling is an adapter-level toggle, exactly as upstream. Set
  // `useNativeFunctionCalling = true`; tools then reach the provider as a native `tools` array (built from the
  // predictor's `ToolSpec`s, threaded via `AdapterInvocation.tools`) and the provider's `tool_calls` fill a
  // `tool_calls` output field — gated on the LM's `supportsFunctionCalling`. Per the G-7b decision, ReAct
  // itself stays on the text protocol (native calling is adapter-level, not a ReAct rewrite — matching upstream).
  object NativeFunctionCallingExample:
    // --8<-- [start:native-fc]
    val nativeChatAdapter: Adapter = ChatAdapter(useNativeFunctionCalling = true)
    val nativeJsonAdapter: Adapter = JSONAdapter(useNativeFunctionCalling = true)
    // --8<-- [end:native-fc]

  // ── Snippets 7 / 8 — async tools (`tool.acall`, async→sync conversion) ──
  // Tool effects use ZIO. A tool can perform asynchronous work without a separate `acall` method.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.toolsMain"
@main def toolsMain(): Unit =
  Demo.withLm {
    println("ReAct: " + Tools.WeatherAgentExample.call("What's the weather like in Tokyo?"))
  }
