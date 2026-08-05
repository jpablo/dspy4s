package dspy4s.optimize

import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.optimization.OptimizableId
import dspy4s.programs.optimization.OptimizableParameters

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ValidationError
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Program-level state save / load (PORT_GAPS G-4) — the analogue of Python's `BaseModule.dump_state` / `load_state`
  * and `save` / `load`.
  *
  * Built entirely on [[OptimizableStructure]], so a single `Predict` or `DynamicPredict` module and an arbitrary
  * composite use the same path: [[dumpState]] serializes every writable [[OptimizableParameters]], and [[loadState]]
  * writes those parameter values into a fresh program through `OptimizableStructure.replace`.
  *
  * '''Round-trip scope.''' The persisted state is exactly instructions, demos, and module-level config. Signature field
  * structure, module names, runtimes, output schemas, bound LMs, tools, callbacks, and history belong to the fresh
  * target program and are preserved during loading. Loading therefore requires the same optimizable-leaf structure and
  * order, plus a compatible architecture; ordinal IDs detect missing or extra entries, not a same-cardinality reorder.
  *
  * The JSON is produced by zio-blocks' `DynamicValue` JSON codec (the same codec `SignatureLayout.dumpJson` uses) —
  * clean, natural JSON with no ADT tags.
  */
object ProgramPersistence:

  /** JSON codec for the `DynamicValue`-shaped state, mirroring `SignatureLayout`'s private codec. */
  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Serialize a program's optimizable parameters to
    * `{ "optimizableParameters": { "optimizable-0": <OptimizableParameters>, ... } }`. [[OptimizableId]] keys make
    * loading independent of JSON object order and detect missing/unknown ordinals.
    */
  def dumpState[P](program: P)(using structure: OptimizableStructure[P]): DynamicValue.Record =
    val parameters: Seq[(String, DynamicValue)] = structure.readIdentified(program).map { identified =>
      identified.id.render -> (identified.parameters.dumpState: DynamicValue)
    }
    DynamicValue.Record(Chunk.from(Seq(
      "optimizableParameters" -> DynamicValue.Record(Chunk.from(parameters))
    )))

  private def decodeParameters(raw: DynamicValue, at: String): Either[DspyError, OptimizableParameters] =
    raw match
      case rec: DynamicValue.Record => OptimizableParameters.fromState(rec)
      case _                        => Left(ValidationError(s"Program state optimizable '$at' must be a record"))

  private def loadById[P](program: P, record: DynamicValue.Record)(using
      structure: OptimizableStructure[P]
  ): Either[DspyError, P] =
    val expectedIds = structure.readIdentified(program).map(_.id)
    val parsed      = record.fields.toVector.foldLeft[Either[DspyError, Vector[(OptimizableId, OptimizableParameters)]]](
      Right(Vector.empty)
    ) { case (acc, (rawId, rawParameters)) =>
      for
        entries    <- acc
        id         <- OptimizableId.parse(rawId).left.map(ValidationError(_))
        parameters <- decodeParameters(rawParameters, rawId)
      yield entries :+ (id -> parameters)
    }

    parsed.flatMap { entries =>
      val duplicateIds =
        entries.groupMap(_._1)(_._2).collect { case (id, values) if values.size > 1 => id }.toVector.sorted
      val actualIds   = entries.map(_._1).toSet
      val expectedSet = expectedIds.toSet
      val missing     = (expectedSet -- actualIds).toVector.sorted
      val unknown     = (actualIds -- expectedSet).toVector.sorted
      if duplicateIds.nonEmpty then
        Left(ValidationError(s"Program state has duplicate optimizable ids: ${duplicateIds.mkString(", ")}"))
      else if missing.nonEmpty || unknown.nonEmpty then
        val details = Vector(
          Option.when(missing.nonEmpty)(s"missing: ${missing.mkString(", ")}"),
          Option.when(unknown.nonEmpty)(s"unknown: ${unknown.mkString(", ")}")
        ).flatten.mkString("; ")
        Left(ValidationError(s"Program state optimizable ids do not match the program ($details)"))
      else
        val byId = entries.toMap
        Right(structure.replace(program, expectedIds.map(byId)))
    }

  /** Rebuild a program from the state produced by [[dumpState]], matching state by stable optimizable id. */
  def loadState[P](program: P, state: DynamicValue.Record)(using
      structure: OptimizableStructure[P]
  ): Either[DspyError, P] =
    DynamicValues.recordGet(state, "optimizableParameters") match
      case Some(record: DynamicValue.Record) => loadById(program, record)
      case Some(_)                           => Left(ValidationError("Program state 'optimizableParameters' must be an id-keyed record"))
      case None                              => Left(ValidationError("Program state is missing 'optimizableParameters'"))

  /** Serialize a program's state to a clean JSON string (via the `DynamicValue` JSON codec). Round-trips with
    * [[loadJson]].
    */
  def dumpJson[P](program: P)(using OptimizableStructure[P]): String =
    new String(dynamicJsonCodec.encode(dumpState(program)), StandardCharsets.UTF_8)

  /** Rebuild a program from the JSON string produced by [[dumpJson]]. */
  def loadJson[P](program: P, json: String)(using OptimizableStructure[P]): Either[DspyError, P] =
    dynamicJsonCodec.decode(json.getBytes(StandardCharsets.UTF_8)) match
      case Right(rec: DynamicValue.Record) => loadState(program, rec)
      case Right(other)                    => Left(ValidationError(s"Expected a JSON object for program state, got: $other"))
      case Left(err)                       => Left(ValidationError(s"Invalid program-state JSON: ${err.toString}"))

  /** Write a program's state JSON to `path`. IO failures are wrapped into a [[RuntimeError]]. */
  def save[P](program: P, path: String)(using OptimizableStructure[P]): Either[DspyError, Unit] =
    try
      Files.write(Paths.get(path), dumpJson(program).getBytes(StandardCharsets.UTF_8))
      Right(())
    catch
      case error: Throwable =>
        Left(RuntimeError("program_save", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))

  /** Read a program's state JSON from `path` and rebuild it. IO failures are wrapped into a [[RuntimeError]]; malformed
    * JSON / state surfaces as the [[loadJson]] error.
    */
  def load[P](program: P, path: String)(using OptimizableStructure[P]): Either[DspyError, P] =
    val read: Either[DspyError, String] =
      try Right(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
      catch
        case error: Throwable =>
          Left(RuntimeError("program_load", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    read.flatMap(json => loadJson(program, json))
