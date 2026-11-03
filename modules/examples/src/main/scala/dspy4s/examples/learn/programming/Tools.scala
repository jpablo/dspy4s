/** Tools
  *
  * Source: docs/docs/learn/programming/tools.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/tools.md Status: translated (ReAct +
  * tools, snippets 1/2/9; the manual path, snippets 3/5; the properties of a tool, snippet 4; native function calling,
  * snippet 6). Snippets 7 and 8 (async tools) have a note and no code, because `ToolFunction.invoke` is synchronous.
  */
package dspy4s.examples.learn.programming

import dspy4s.adapters.{ChatAdapter, JSONAdapter}
import dspy4s.adapters.contracts.{Adapter, NativeTools, ToolSpec}
import dspy4s.core.contracts.{DspyError, DynamicValues, NotFoundError, RuntimeContext, ToolCall, TypeRef, :=}
import dspy4s.examples.Demo
import dspy4s.programs.IterationLimit
import dspy4s.programs.strategies.{Predict, ReAct}
import dspy4s.programs.contracts.{ToolFunction, description}
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.DynamicValue

// ── Snippet 3 (lines 74–118): the signature of the manual path ──
// | class ToolSignature(dspy.Signature):
// |     """Signature for manual tool handling."""
// |     question: str = dspy.InputField()
// |     tools: list[dspy.Tool] = dspy.InputField()
// |     outputs: dspy.ToolCalls = dspy.OutputField()
// `dspy.ToolCalls` becomes `Vector[ToolCall]`. The tools are not an input field: they go to `Predict` as `ToolSpec`s.
// The docstring goes to `instructions`, where `ManualToolExample` makes the signature.
trait ToolSignature extends Spec:
  def question: InputField[String]
  def outputs: OutputField[Vector[ToolCall]]

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
  // the dspy4s counterpart of `dspy.Tool(fn)`: annotate a method with `@description` and the
  // macro derives the tool's name, description, and argument schema (`{city: string}`, surfaced in the
  // agent's prompt) and decodes each argument from the call record by name/type.
  object WeatherAgentExample:
    // --8<-- [start:tools]
    @description("Get the current weather for a city.")
    def get_weather(city: String): String = s"The weather in $city is sunny and 75°F"

    @description("Search the web for information.")
    def search_web(query: String): String = s"Search results for '$query': [relevant information...]"

    val getWeather: ToolFunction = ToolFunction.fromMethod(get_weather)
    val searchWeb: ToolFunction  = ToolFunction.fromMethod(search_web)
    // --8<-- [end:tools]

    // ── Snippet 2 (lines 57–63) — ReAct configuration ──
    // | react_agent = dspy.ReAct(signature="question -> answer", tools=[tool1, tool2, tool3], max_iters=10)
    // --8<-- [start:react-agent]
    val reactAgent = ReAct(
      baseSignature = Signature.fromString("question -> answer"),
      tools = Vector(getWeather, searchWeb),
      maxIterations = IterationLimit(5)
    )

    def call(question: String)(using RuntimeContext): Either[DspyError, String] =
      reactAgent((question = question)).map(_.output.answer)
    // --8<-- [end:react-agent]

  // ── Snippet 3 (lines 74–118): the manual path. The model gives the tool calls, and the program runs them ──
  // | def weather(city: str) -> str:
  // |     """Get weather information for a city."""
  // |     return f"The weather in {city} is sunny"
  // |
  // | def calculator(expression: str) -> str:
  // |     """Evaluate a mathematical expression."""
  // |     try:
  // |         result = eval(expression)  # Note: Use safely in production
  // |         return f"The result is {result}"
  // |     except:
  // |         return "Invalid expression"
  // |
  // | # Create tool instances
  // | tools = {
  // |     "weather": dspy.Tool(weather),
  // |     "calculator": dspy.Tool(calculator)
  // | }
  // |
  // | # Create predictor
  // | predictor = dspy.Predict(ToolSignature)
  // |
  // | # Make a prediction
  // | response = predictor(
  // |     question="What's the weather in New York?",
  // |     tools=list(tools.values())
  // | )
  // |
  // | # Execute the tool calls
  // | for call in response.outputs.tool_calls:
  // |     # Execute the tool call
  // |     result = call.execute()
  // |     # For versions earlier than 3.0.4b2, use: result = tools[call.name](**call.args)
  // |     print(f"Tool: {call.name}")
  // |     print(f"Args: {call.args}")
  // |     print(f"Result: {result}")
  //
  // ── Snippet 5 (lines 147–167): the options of `call.execute` ──
  // | # Option 1: Automatic discovery (finds functions in locals/globals)
  // | result = call.execute()  # Automatically finds functions by name
  // | # Option 2: Pass tools as a dict (most explicit)
  // | result = call.execute(functions={"weather": weather, "calculator": calculator})
  // | # Option 3: Pass Tool objects as a list
  // | result = call.execute(functions=[dspy.Tool(weather), dspy.Tool(calculator)])
  // | # Option 4: For versions earlier than 3.0.4b2 (manual tool lookup)
  // | # tools_dict = {"weather": weather, "calculator": calculator}
  // | # result = tools_dict[call.name](**call.args)
  //
  // A `ToolCall` has no `execute`, and dspy4s has no automatic discovery. `execute` below looks up the tool in
  // `ToolFunction.byName(tools)`, which is the dict of options 2 and 4. The `Predict` gets a `ToolSpec` for each tool.
  // `ReActV2` makes its specs in a private method, so this example writes them. The call needs an adapter with native
  // function calling and a model that supports it, because the tools reach the model only as provider tools.
  object ManualToolExample:
    @description("Get weather information for a city.")
    def weather(city: String): String = s"The weather in $city is sunny"

    // The JVM has no `eval`, so this tool only adds numbers.
    @description("Evaluate a mathematical expression.")
    def calculator(expression: String): String =
      val terms = expression.split('+').toVector.map(_.trim.toDoubleOption)
      if terms.forall(_.isDefined) then s"The result is ${terms.flatten.sum}" else "Invalid expression"

    val tools: Vector[ToolFunction] = Vector(ToolFunction.fromMethod(weather), ToolFunction.fromMethod(calculator))

    // Each argument of these two tools is a string.
    private def specOf(tool: ToolFunction): ToolSpec =
      val arguments = tool.argSchema.map((name, _) => name)
      ToolSpec(
        name = tool.name,
        description = Some(tool.description),
        inputSchema = DynamicValues.record(
          "type"       := "object",
          "properties" -> DynamicValues.recordFromEntries(arguments.map(_ -> DynamicValues.record("type" := "string"))),
          "required"   := arguments
        )
      )

    val predictor = Predict(
      signature = Signature.of[ToolSignature](instructions = "Signature for manual tool handling."),
      tools = tools.map(specOf)
    )

    /** The result of one call of the model, or an error for a name that is not a tool. */
    def execute(call: ToolCall)(using RuntimeContext): Either[DspyError, DynamicValue] =
      ToolFunction.byName(tools).get(call.name)
        .toRight(NotFoundError("tool", s"No tool with the name '${call.name}'"))
        .flatMap(_.invoke(call.args))

    /** Each call of the model with its result. A failed prediction is an error. */
    def call(question: String)(using
        ctx: RuntimeContext
    ): Either[DspyError, Vector[(ToolCall, Either[DspyError, DynamicValue])]] =
      predictor((question = question))(using
        ctx.copy(adapter = Some(ChatAdapter(native = NativeTools(enabled = true))))
      )
        .map(_.output.outputs.map(call => call -> execute(call)))

  // ── Snippet 4 (lines 125–136): the properties of a tool ──
  // | def my_function(param1: str, param2: int = 5) -> str:
  // |     """A sample function with parameters."""
  // |     return f"Processed {param1} with value {param2}"
  // |
  // | # Create a tool
  // | tool = dspy.Tool(my_function)
  // |
  // | # Tool properties
  // | print(tool.name)        # "my_function"
  // | print(tool.desc)        # The function's docstring
  // | print(tool.args)        # Parameter schema
  // | print(str(tool))        # Full tool description
  // `tool.desc` becomes `description`, and `tool.args` becomes `argSchema`. `str(tool)` has no public counterpart.
  // `fromMethod` rejects a missing argument, so the default of `param2` has no port.
  object ToolPropertiesExample:
    @description("A sample function with parameters.")
    def my_function(param1: String, param2: Int): String = s"Processed $param1 with value $param2"

    val tool: ToolFunction = ToolFunction.fromMethod(my_function)

    def properties: (String, String, Vector[(String, TypeRef)]) = (tool.name, tool.description, tool.argSchema)

  // ── Snippet 9 (lines 270–288) — what makes a good tool ──────────────────────
  // | def good_tool(city: str, units: str = "celsius") -> str:
  // |     """
  // |     Get weather information for a specific city.
  // |
  // |     Args:
  // |         city: The name of the city to get weather for
  // |         units: Temperature units, either 'celsius' or 'fahrenheit'
  // |
  // |     Returns:
  // |         A string describing the current weather conditions
  // |     """
  // |     # Implementation with proper error handling
  // |     if not city.strip():
  // |         return "Error: City name cannot be empty"
  // |
  // |     # Weather logic here...
  // |     return f"Weather in {city}: 25°{units[0].upper()}, sunny"
  // A clear description, a list of the arguments, and careful checks of the arguments carry over to `ToolFunction`.
  // `argSchema` lists the arguments for the model: with no `argSchema`, ReAct says "It takes arguments {}". `fromMethod`
  // rejects a missing argument, so it cannot give `units` its default.
  object GoodToolExample:
    val goodTool: ToolFunction = ToolFunction(
      name = "good_tool",
      description = "Get weather information for a specific city. The argument `city` is the name of the city. " +
        "The argument `units` is optional, and it is either 'celsius' (the default) or 'fahrenheit'.",
      argSchema = Vector("city" -> TypeRef.string, "units" -> TypeRef.string)
    ) { args =>
      val city  = DynamicValues.recordGet(args, "city").map(DynamicValues.renderText).getOrElse("").trim
      val units = DynamicValues.recordGet(args, "units").map(DynamicValues.renderText).getOrElse("celsius")
      Right(ToolFunction.result(
        if city.isEmpty then "Error: City name cannot be empty"
        else s"Weather in $city: 25°${units.take(1).toUpperCase}, sunny"
      ))
    }

  // ── Snippet 6 (lines 191–202): native function calling ──
  // | # ChatAdapter with native function calling enabled
  // | chat_adapter_native = dspy.ChatAdapter(use_native_function_calling=True)
  // |
  // | # JSONAdapter with native function calling disabled
  // | json_adapter_manual = dspy.JSONAdapter(use_native_function_calling=False)
  //
  // Ported (PORT_GAPS G-7b): native function calling is an option of the adapter, as upstream. `ChatAdapter` has it off
  // by default, and `JSONAdapter` has it on by default. When it is on and `supportsFunctionCalling` of the LM is true,
  // the tools go to the provider as a native `tools` array (from the `ToolSpec`s of the predictor, through
  // `AdapterInvocation.tools`), and the `tool_calls` of the provider fill the output field of the type `Vector[ToolCall]`.
  // Decision G-7b: ReAct stays on the text protocol, as upstream.
  object NativeFunctionCallingExample:
    // --8<-- [start:native-fc]
    val nativeChatAdapter: Adapter = ChatAdapter(native = NativeTools(enabled = true))
    val nativeJsonAdapter: Adapter = JSONAdapter() // on by default
    val manualJsonAdapter: Adapter = JSONAdapter(native = NativeTools(enabled = false))
    // --8<-- [end:native-fc]

  // ── Snippets 7 / 8 — async tools (`tool.acall`, async→sync conversion) ──
  // Not portable: `ToolFunction.invoke` is synchronous; there is no async tool path.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.toolsMain"
@main def toolsMain(): Unit =
  Demo.withLm {
    println("ReAct: " + Tools.WeatherAgentExample.call("What's the weather like in Tokyo?"))
    println("Manual: " + Tools.ManualToolExample.call("What's the weather in New York?"))
  }
