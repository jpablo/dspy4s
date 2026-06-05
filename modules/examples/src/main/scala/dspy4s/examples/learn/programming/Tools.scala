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
import dspy4s.programs.contracts.ToolFunction
import dspy4s.typed.Signature
import zio.blocks.schema.DynamicValue

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
  // dspy4s tools are `ToolFunction`s: a `name`, a `description` (surfaced in the agent's prompt),
  // and a body over the call args (a `DynamicValue.Record`). `ToolFunction.of(name, desc) { args => … }`
  // builds one from a plain function returning the result value (lifted via its `Schema`); use
  // `ToolFunction(name, desc) { args => Right/Left(...) }` when the tool can fail.
  object WeatherAgentExample:
    private def arg(args: DynamicValue.Record, key: String): String =
      DynamicValues.recordGet(args, key).map(DynamicValues.renderText).getOrElse("")

    val getWeather: ToolFunction = ToolFunction.of("get_weather", "Get the current weather for a city.") { args =>
      s"The weather in ${arg(args, "city")} is sunny and 75°F"
    }

    val searchWeb: ToolFunction = ToolFunction.of("search_web", "Search the web for information.") { args =>
      s"Search results for '${arg(args, "query")}': [relevant information...]"
    }

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
