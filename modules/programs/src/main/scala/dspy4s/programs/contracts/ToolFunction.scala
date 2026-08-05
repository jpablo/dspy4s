package dspy4s.programs.contracts

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import zio.blocks.schema.{DynamicValue, Schema}

/** A callable tool exposed to tool-using programs (`ReAct`, ...). `invoke` receives the call arguments as a
  * `DynamicValue.Record` (the named params parsed from the LM's tool call) and returns its result as a `DynamicValue`,
  * so both ends travel the spine without a lossy `Any` round-trip. Extract args with `DynamicValues.recordGet` / a
  * `Schema`-backed decode; build the result with [[ToolFunction.result]].
  */
trait ToolFunction:
  def name: String

  /** Human-readable description of what the tool does, surfaced in tool-using programs' prompts (e.g. ReAct lists each
    * tool's name + description so the model knows when to call it). Defaults to empty.
    */
  def description: String = ""

  /** The tool's parameters as `(name, wire type)` pairs, surfaced to the model by tool-using programs so it knows what
    * arguments to supply. Empty for hand-written tools and the function factories; populated by
    * [[ToolFunction.fromMethod]] from the method's signature.
    */
  def argSchema: Vector[(String, dspy4s.core.contracts.TypeRef)] = Vector.empty

  def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue]

/** Supplies a tool's description for [[ToolFunction.fromMethod]], co-located with the method definition:
  * `@description("Get the current weather for a city.") def getWeather(city: String): String = …`.
  */
final class description(val value: String) extends scala.annotation.StaticAnnotation

object ToolFunction:
  /** Lift a return value with a `Schema` into the `DynamicValue` a tool yields, so authors can write
    * `Right(ToolFunction.result("Brussels"))` (or return any case class with a `Schema`) instead of hand-constructing
    * `DynamicValue.Primitive(...)`.
    */
  def result[A](value: A)(using schema: Schema[A]): DynamicValue =
    schema.toDynamicValue(value)

  /** Build a tool from a plain function over the call args, instead of an anonymous `new ToolFunction`. The body may
    * use the ambient `RuntimeContext` (it's a context function, so it's available but not required) and returns the
    * tool result `DynamicValue`, or a `Left` to surface a failure:
    *
    * {{{
    * ToolFunction("get_weather", "Get the current weather for a city.") { args =>
    *   Right(ToolFunction.result(s"The weather in ${args.asString("city")} is sunny"))
    * }
    * }}}
    */
  def apply(name: String, description: String = "")(
      invoke: DynamicValue.Record => RuntimeContext ?=> Either[DspyError, DynamicValue]
  ): ToolFunction =
    val toolName = name
    val toolDesc = description
    val toolFn   = invoke // alias: avoid the method-name shadowing the param in the body below
    new ToolFunction:
      override val name: String                                                                             = toolName
      override val description: String                                                                      = toolDesc
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        toolFn(args)

  /** Like [[apply]] but the body returns a value with a `Schema` directly (lifted via [[result]]) — no
    * `Right(ToolFunction.result(...))` wrapping. Use this for tools that never fail:
    *
    * {{{
    * ToolFunction.of("get_weather", "Get the current weather for a city.") { args =>
    *   s"The weather in ${args.asString("city")} is sunny"
    * }
    * }}}
    */
  def of[A](name: String, description: String = "")(
      invoke: DynamicValue.Record => RuntimeContext ?=> A
  )(using Schema[A]): ToolFunction =
    apply(name, description)(args => Right(result(invoke(args))))

  /** Build a tool from a **method** — the dspy4s analogue of Python's `dspy.Tool(fn)`. The macro reads the method's
    * name, its `@description` annotation, and its parameters, and produces a `ToolFunction` that (1) decodes each
    * argument from the call `Record` by name/type, (2) calls the method, lifting its result via its `Schema`, and (3)
    * carries an [[argSchema]] so tool-using programs can tell the model what arguments the tool takes:
    *
    * {{{
    * @description("Get the current weather for a city.")
    * def getWeather(city: String): String = s"The weather in $city is sunny"
    *
    * val tool = ToolFunction.fromMethod(getWeather)   // name "getWeather", desc, argSchema {city: string}
    * }}}
    *
    * The method's parameter types and return type must each have a `zio.blocks.schema.Schema`. (Tools that need the
    * ambient `RuntimeContext`, or full `Left`/`Right` control, use the [[apply]] / [[of]] factories instead.)
    */
  inline def fromMethod[F](inline method: F): ToolFunction =
    ${ dspy4s.programs.internal.ToolMacro.fromMethodImpl[F]('method) }
