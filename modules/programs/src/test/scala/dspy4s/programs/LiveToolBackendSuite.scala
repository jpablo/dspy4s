package dspy4s.programs

import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.{Runtime, Unsafe, ZEnvironment}

final class LiveToolBackendSuite extends FunSuite:

  private val echo = Tool.fromEither("echo") { args =>
    Right(DynamicValues.recordGet(args, "value").getOrElse(DynamicValue.Null))
  }
  private val backend = LiveToolBackend(Vector(echo))

  test("live tool backend returns tool failures as result data") {
    val request   = ToolCallRequest("echo", DynamicValues.record("value" := "hello"))
    val execution = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.runJournaled(
          Program.invokeTool,
          request
        ).provideEnvironment(ZEnvironment[ToolBackend](backend)))
        .getOrThrowFiberFailure()
    }

    assertEquals(execution.outcome.flatMap(_.output.result), Right(DynamicValues.fromAny("hello")))
    assertEquals(ProgramGraph.from(Program.invokeTool).nodes.map(_.kind), Vector("invoke_tool"))
  }

  test("live tool backend fails when a tool name is not registered") {
    val result = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(backend.invoke(ToolCallRequest("missing", DynamicValue.Record.empty)).either)
        .getOrThrowFiberFailure()
    }

    assertEquals(result.left.map(_.code), Left("not_found"))
  }
