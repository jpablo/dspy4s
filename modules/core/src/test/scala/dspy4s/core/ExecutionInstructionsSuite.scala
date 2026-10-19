package dspy4s.core

import dspy4s.core.contracts.ReplInterpreterFactory
import dspy4s.core.runtime.LocalPythonInterpreter
import munit.FunSuite

/** Each interpreter factory tells the model how its code runs (upgrade item I7, PORT_GAPS G-35; upstream
  * `execution_instructions` of `PythonInterpreter` in `dspy/primitives/python_interpreter.py` and of `LocalInterpreter`
  * in `dspy/primitives/local_interpreter.py`). The texts are the upstream texts. The text of the Deno sandbox is in
  * `ProgramPromptParitySuite`.
  */
class ExecutionInstructionsSuite extends FunSuite:

  test("the local Python factory has the upstream text of the local interpreter") {
    assertEquals(
      LocalPythonInterpreter.factory().executionInstructions,
      "Code runs as Python in a persistent local subprocess. State, imports, functions, and JSON-compatible " +
        "variables persist for this session. Host tools and SUBMIT are available as global functions. All threads " +
        "must finish before each code execution returns; use context-managed executors for parallel work."
    )
  }

  test("a factory from a function literal has no execution instructions") {
    val factory: ReplInterpreterFactory = (tools, outputFields) => LocalPythonInterpreter.factory()(tools, outputFields)
    assertEquals(factory.executionInstructions, "")
  }
