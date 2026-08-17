package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, RuntimeError, ValidationError}
import dspy4s.programs.ProgramParameters
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** Save and load only the immutable optimizer parameters of a program. */
object ProgramPersistence:

  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  def dumpState[P: ProgramParameters](program: P): DynamicValue.Record =
    ProgramParameters[P].read(program).dumpState

  def loadState[P: ProgramParameters](program: P, state: DynamicValue.Record): Either[DspyError, P] =
    val parameters = ProgramParameters[P]
    parameters.read(program).loadState(state).flatMap { loaded =>
      parameters.replace(program, loaded.all.map(binding => binding.id -> binding.value).toMap)
    }

  def dumpJson[P: ProgramParameters](program: P): String =
    new String(dynamicJsonCodec.encode(dumpState(program)), StandardCharsets.UTF_8)

  def loadJson[P: ProgramParameters](program: P, json: String): Either[DspyError, P] =
    dynamicJsonCodec.decode(json.getBytes(StandardCharsets.UTF_8)) match
      case Right(record: DynamicValue.Record) => loadState(program, record)
      case Right(other)                       => Left(ValidationError(s"Expected a JSON object for program state, got: $other"))
      case Left(error)                        => Left(ValidationError(s"Invalid program-state JSON: $error"))

  def save[P: ProgramParameters](program: P, path: String): Either[DspyError, Unit] =
    try
      Files.write(Paths.get(path), dumpJson(program).getBytes(StandardCharsets.UTF_8))
      Right(())
    catch
      case error: Throwable => Left(ioError("program_save", error))

  def load[P: ProgramParameters](program: P, path: String): Either[DspyError, P] =
    try loadJson(program, new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
    catch
      case error: Throwable => Left(ioError("program_load", error))

  private def ioError(component: String, error: Throwable): RuntimeError =
    RuntimeError(component, Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName))
