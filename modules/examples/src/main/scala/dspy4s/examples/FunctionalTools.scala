package dspy4s.examples

import dspy4s.core.contracts.{DynamicValues, TypeRef, :=}
import dspy4s.programs.contracts.{Tool, ToolCallRequest}
import dspy4s.programs.{LiveToolBackend, Program, ProgramRunner, ToolBackend}
import zio.{Runtime, Unsafe, ZEnvironment}

/** Tool effects are explicit services and tool failures remain typed data. */
@main def functionalTools(): Unit =
  val greet = Tool.fromEither(
    name = "greet",
    description = "Return a greeting",
    argSchema = Vector("name" -> TypeRef.string)
  ) { arguments =>
    DynamicValues.requireString(arguments, "name", "greet").map(name => DynamicValues.fromAny(s"Hello, $name"))
  }
  val backend: ToolBackend = new LiveToolBackend(Vector(greet))
  val request              = ToolCallRequest("greet", DynamicValues.record("name" := "Scala"))

  val result = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe
      .run(ProgramRunner.run(Program.invokeTool, request).provideEnvironment(ZEnvironment(backend)))
      .getOrThrowFiberFailure()
  }

  println(result.output.result)
