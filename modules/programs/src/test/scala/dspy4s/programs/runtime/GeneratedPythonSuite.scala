package dspy4s.programs.runtime

import munit.FunSuite

class GeneratedPythonSuite extends FunSuite:
  test("parse normalizes generated Python using DSPy's shared CodeAct/ProgramOfThought rules") {
    assertEquals(GeneratedPython.parse("```python\n\n```"), Left("Empty code after parsing."))
    assertEquals(GeneratedPython.parse("a=1; b=2"), Left("Code format is not correct."))

    assertEquals(GeneratedPython.parse("print(1)\n---\ngarbage"), Right("print(1)"))
    assertEquals(GeneratedPython.parse("print(1)\n\n\ngarbage"), Right("print(1)"))

    assertEquals(GeneratedPython.parse("y = 2\nx = y + 1"), Right("y = 2\nx = y + 1\nx"))
    assertEquals(GeneratedPython.parse("x = 1"), Right("x = 1"))

    assertEquals(GeneratedPython.parse("```py\nprint(2)\n```"), Right("print(2)"))
    assertEquals(GeneratedPython.parse("```\nprint(3)\n```"), Right("print(3)"))
  }
