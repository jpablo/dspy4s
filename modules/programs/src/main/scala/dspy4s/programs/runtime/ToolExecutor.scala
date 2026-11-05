package dspy4s.programs.runtime

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.CallbackDispatcher
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.ToolFunction
import zio.blocks.schema.DynamicValue

import scala.util.control.NonFatal

/** Runs the tools of the tool-using programs. `ReAct` and `ReActV2` run each tool call through [[run]]. */
object ToolExecutor:

  /** Run `tool` with `args` in a tool callback scope ([[CallbackDispatcher.withTool]]).
    *
    * A non-fatal exception of the tool (`scala.util.control.NonFatal`) gives a `Left` with a [[RuntimeError]], as a
    * `Left` of the tool does (upstream: `except Exception` in `ReAct.forward` and in `ReActV2._execute_tool_calls`).
    * The message is the simple class name and the message of the exception, `<ClassName>: <message>`, or the class name
    * alone when the message is empty or null. This is the port form of the last line of the upstream traceback
    * (`format_error_for_lm`). The catch is inside the scope, thus the `ToolEndEvent` gets the `Left` (upstream:
    * `on_tool_end` gets the exception). A fatal error, for example an `InterruptedException`, goes up to the caller.
    *
    * The scope and the tool run with the context that `run` gets as the installed context (UPGRADE-3.2.1-to-3.4.0,
    * decision D8: the given context wins). The tool gets the effective context of the call as its `using` value.
    */
  def run(tool: ToolFunction, args: DynamicValue.Record)(using
      context: RuntimeContext
  ): Either[DspyError, DynamicValue] =
    RuntimeEnvironment.withInstalled(context) { effective ?=>
      CallbackDispatcher.withTool(toolName = tool.name, args = args) {
        try tool.invoke(args)(using effective)
        catch case NonFatal(error) => Left(RuntimeError(tool.name, DspyError.describe(error)))
      }
    }

  /** The text that the model gets for the error of a tool (upstream `ReAct.forward` and `ReActV2._execute_tool_calls`).
    */
  def errorText(toolName: String, error: DspyError): String =
    s"Execution error in $toolName: ${error.message}"
