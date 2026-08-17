package dspy4s.programs.contracts

import dspy4s.core.contracts.{DspyError, TypeRef}
import zio.IO
import zio.ZIO
import zio.blocks.schema.{DynamicValue, Schema}

/** An effectful host tool. The value contains metadata and one explicit execution function. */
final case class Tool(
    name       : String,
    description: String                    = "",
    argSchema  : Vector[(String, TypeRef)] = Vector.empty,
    invoke     : DynamicValue.Record => IO[DspyError, DynamicValue]
)

object Tool:

  def fromEither(
      name       : String,
      description: String                    = "",
      argSchema  : Vector[(String, TypeRef)] = Vector.empty
  )(
      invoke: DynamicValue.Record => Either[DspyError, DynamicValue]
  ): Tool =
    Tool(name, description, argSchema, arguments => ZIO.fromEither(invoke(arguments)))

  def succeed[A: Schema](
      name       : String,
      description: String                    = "",
      argSchema  : Vector[(String, TypeRef)] = Vector.empty
  )(
      invoke: DynamicValue.Record => A
  ): Tool =
    Tool(name, description, argSchema, arguments => ZIO.succeed(Schema[A].toDynamicValue(invoke(arguments))))
