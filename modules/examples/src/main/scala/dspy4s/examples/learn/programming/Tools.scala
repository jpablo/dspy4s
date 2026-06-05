/**
 * Tools
 *
 * Source:   docs/docs/learn/programming/tools.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/tools.md
 * Status:   translated (ReAct + tools, snippets 1/2/9). The manual-tool-call path (snippets 3/5),
 *           native-function-calling toggles (6) and async tools (7/8) aren't part of dspy4s's
 *           surface — dspy4s selects tools via output fields and `ToolFunction` is synchronous.
 */
package dspy4s.examples.learn.programming

import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.typed.Signature

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
  // The closest analogue to Python's "pass a plain function as a tool" is `ToolFunction.fromMethod`,
  // the dspy4s counterpart of `dspy.Tool(fn)`: annotate a typed method with `@description` and the
  // macro derives the tool's name, description, and argument schema (`{city: string}`, surfaced in the
  // agent's prompt) and decodes each argument from the call record by name/type.
  object WeatherAgentExample:
    @description("Get the current weather for a city.")
    def get_weather(city: String): String = s"The weather in $city is sunny and 75°F"

    @description("Search the web for information.")
    def search_web(query: String): String = s"Search results for '$query': [relevant information...]"

    val getWeather: ToolFunction = ToolFunction.fromMethod(get_weather)
    val searchWeb: ToolFunction  = ToolFunction.fromMethod(search_web)

    // ── Snippet 2 (lines 57–63) — ReAct configuration ──
    // | react_agent = dspy.ReAct(signature="question -> answer", tools=[tool1, tool2, tool3], max_iters=10)
    val reactAgent = ReAct(
      baseSignature = Signature.fromString("question -> answer"),
      tools         = Vector(getWeather, searchWeb),
      maxIterations = 5
    )

    /** The agent's `answer` (ReAct prepends `reasoning`); the full trajectory is on `.raw`
      * (`DynamicValues.renderText(prediction.raw.get("trajectory").getOrElse(...))`). */
    def call(question: String)(using RuntimeContext): Either[DspyError, String] =
      reactAgent.apply((question = question)).map(_.output.answer)

  // ── Snippet 9 (lines 270–288) — what makes a good tool ──────────────────────
  // | def good_tool(city: str, units: str = "celsius") -> str:
  // |     """Get weather information for a specific city. Args: city, units. Returns: ..."""
  // |     if not city.strip(): return "Error: City name cannot be empty"
  // |     return f"Weather in {city}: 25°{units[0].upper()}, sunny"
  // A clear `name` + `description` and defensive arg handling carry over directly to `ToolFunction`:
  object GoodToolExample:
    val goodTool: ToolFunction =
      ToolFunction.of("good_tool", "Get weather information for a specific city (units: celsius | fahrenheit).") { args =>
        val city  = DynamicValues.recordGet(args, "city").map(DynamicValues.renderText).getOrElse("").trim
        val units = DynamicValues.recordGet(args, "units").map(DynamicValues.renderText).getOrElse("celsius")
        if city.isEmpty then "Error: City name cannot be empty"
        else s"Weather in $city: 25°${units.take(1).toUpperCase}, sunny"
      }

  // ── Snippets 3 / 5 — manual `dspy.Tool` input field + `ToolCalls` output + `call.execute()` ──
  // Not portable: dspy4s selects tools via output fields inside an agent (ReAct), not by passing a
  // `list[dspy.Tool]` input and reading a `ToolCalls` output, and it has no `call.execute()` surface.
  //
  // ── Snippet 6 — `ChatAdapter(use_native_function_calling=True)` / `JSONAdapter(...)` ──
  // Not portable: dspy4s tool selection is via output fields, not provider-native function calling.
  //
  // ── Snippets 7 / 8 — async tools (`tool.acall`, async→sync conversion) ──
  // Not portable: `ToolFunction.invoke` is synchronous; there is no async tool path.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.toolsMain"
@main def toolsMain(): Unit = Demo.withLm {
  println("ReAct: " + Tools.WeatherAgentExample.call("What's the weather like in Tokyo?"))
}
