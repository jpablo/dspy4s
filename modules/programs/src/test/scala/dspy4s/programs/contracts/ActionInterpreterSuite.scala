package dspy4s.programs.contracts

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import munit.FunSuite

final class ActionInterpreterSuite extends FunSuite:

  test("an interpreter distinguishes successful and recoverable failed observations") {
    val interpreter = new ActionInterpreter[Int, String]:
      override def execute(action: Int)(using RuntimeContext): Either[DspyError, ActionOutcome[String]] =
        Right(
          if action >= 0 then ActionOutcome.Succeeded(action.toString)
          else ActionOutcome.Failed(s"negative: $action")
        )

    given RuntimeContext = RuntimeContext()

    val success = interpreter.execute(1).toOption.get
    assertEquals(success.observation, "1")
    assert(!success.isError)

    val failure = interpreter.execute(-1).toOption.get
    assertEquals(failure.observation, "negative: -1")
    assert(failure.isError)
  }
