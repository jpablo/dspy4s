package dspy4s.optimize

import dspy4s.programs.Predictors
import dspy4s.programs.PredictorId

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.ValidationError
import dspy4s.programs.DynamicPredict
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Program-level state save / load (PORT_GAPS G-4) — the analogue of Python's
  * `BaseModule.dump_state` / `load_state` and `save` / `load`.
  *
  * Built entirely on the [[Predictors]] introspection layer (G-1), so a single `Predict` (a length-1
  * predictor list) and an arbitrary composite are covered by one code path: [[dumpState]] serializes every
  * predictor `Predictors.read` exposes, and [[loadState]] rebuilds each and writes it back via
  * `Predictors.replace`.
  *
  * '''Round-trip scope.''' What survives a save/load depends on the target predictor's `Predictor.set`:
  *   - For a [[DynamicPredict]] leaf (and user composites whose leaves are `DynamicPredict`), `set` is the
  *     identity, so signature/layout, demos, and config all round-trip fully.
  *   - For [[dspy4s.programs.Predict]], `set` restores '''demos, config, and the layout instructions''' (the
  *     instruction string is shape-safe to write back); [[dspy4s.programs.ChainOfThought]] restores '''demos and
  *     instructions''' (it has no module-level config field). What is NOT written back is the field '''structure'''
  *     of the layout — that would desync `signature.outputShape` from `signature.layout`, so the typed program
  *     keeps its own field shape. (The full layout still round-trips in the JSON itself.) This covers the
  *     "optimize once (demos + instructions), deploy the artifact" workflow the optimizers produce.
  *
  * The JSON is produced by zio-blocks' `DynamicValue` JSON codec (the same codec
  * `SignatureLayout.dumpJson` uses) — clean, natural JSON with no ADT tags.
  */
object ProgramPersistence:

  /** JSON codec for the `DynamicValue`-shaped state, mirroring `SignatureLayout`'s private codec. */
  private lazy val dynamicJsonCodec = Schema.dynamic.jsonCodec

  /** Serialize a program's learnable state to a `DynamicValue.Record`: `{ "predictors": {
    * "predictor-0": <DynamicPredict state>, ... } }`. Stable [[PredictorId]] keys make loading independent of JSON
    * object order and let topology mismatches fail explicitly instead of silently applying state positionally. */
  def dumpState[P](program: P)(using predictors: Predictors[P]): DynamicValue.Record =
    val states: Seq[(String, DynamicValue)] = predictors.readIdentified(program).map { identified =>
      identified.id.render -> (identified.predictor.dumpState: DynamicValue)
    }
    DynamicValue.Record(Chunk.from(Seq(
      "predictors" -> DynamicValue.Record(Chunk.from(states))
    )))

  private def decodePredictor(raw: DynamicValue, at: String): Either[DspyError, DynamicPredict] = raw match
    case rec: DynamicValue.Record => DynamicPredict.fromState(rec)
    case _                        => Left(ValidationError(s"Program state predictor '$at' must be a record"))

  private def loadById[P](program: P, record: DynamicValue.Record)(using
      predictors: Predictors[P]
  ): Either[DspyError, P] =
    val expectedIds = predictors.readIdentified(program).map(_.id)
    val parsed = record.fields.toVector.foldLeft[Either[DspyError, Vector[(PredictorId, DynamicPredict)]]](
      Right(Vector.empty)
    ) { case (acc, (rawId, rawState)) =>
      for
        entries <- acc
        id <- PredictorId.parse(rawId).left.map(ValidationError.apply)
        predictor <- decodePredictor(rawState, rawId)
      yield entries :+ (id -> predictor)
    }

    parsed.flatMap { entries =>
      val duplicateIds = entries.groupMap(_._1)(_._2).collect { case (id, values) if values.size > 1 => id }.toVector.sorted
      val actualIds     = entries.map(_._1).toSet
      val expectedSet   = expectedIds.toSet
      val missing       = (expectedSet -- actualIds).toVector.sorted
      val unknown       = (actualIds -- expectedSet).toVector.sorted
      if duplicateIds.nonEmpty then
        Left(ValidationError(s"Program state has duplicate predictor ids: ${duplicateIds.mkString(", ")}"))
      else if missing.nonEmpty || unknown.nonEmpty then
        val details = Vector(
          Option.when(missing.nonEmpty)(s"missing: ${missing.mkString(", ")}"),
          Option.when(unknown.nonEmpty)(s"unknown: ${unknown.mkString(", ")}")
        ).flatten.mkString("; ")
        Left(ValidationError(s"Program state predictor ids do not match the program ($details)"))
      else
        val byId = entries.toMap
        Right(predictors.replace(program, expectedIds.map(byId)))
    }

  /** Rebuild a program from the state produced by [[dumpState]], matching state by stable predictor id. */
  def loadState[P](program: P, state: DynamicValue.Record)(using predictors: Predictors[P]): Either[DspyError, P] =
    DynamicValues.recordGet(state, "predictors") match
      case Some(record: DynamicValue.Record) => loadById(program, record)
      case Some(_) => Left(ValidationError("Program state 'predictors' must be an id-keyed record"))
      case None    => Left(ValidationError("Program state is missing 'predictors'"))

  /** Serialize a program's state to a clean JSON string (via the `DynamicValue` JSON codec). Round-trips with
    * [[loadJson]]. */
  def dumpJson[P](program: P)(using Predictors[P]): String =
    new String(dynamicJsonCodec.encode(dumpState(program)), StandardCharsets.UTF_8)

  /** Rebuild a program from the JSON string produced by [[dumpJson]]. */
  def loadJson[P](program: P, json: String)(using Predictors[P]): Either[DspyError, P] =
    dynamicJsonCodec.decode(json.getBytes(StandardCharsets.UTF_8)) match
      case Right(rec: DynamicValue.Record) => loadState(program, rec)
      case Right(other) => Left(ValidationError(s"Expected a JSON object for program state, got: $other"))
      case Left(err)    => Left(ValidationError(s"Invalid program-state JSON: ${err.toString}"))

  /** Write a program's state JSON to `path`. IO failures are wrapped into a [[RuntimeError]]. */
  def save[P](program: P, path: String)(using Predictors[P]): Either[DspyError, Unit] =
    try
      Files.write(Paths.get(path), dumpJson(program).getBytes(StandardCharsets.UTF_8))
      Right(())
    catch
      case error: Throwable =>
        Left(RuntimeError("program_save", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))

  /** Read a program's state JSON from `path` and rebuild it. IO failures are wrapped into a [[RuntimeError]];
    * malformed JSON / state surfaces as the [[loadJson]] error. */
  def load[P](program: P, path: String)(using Predictors[P]): Either[DspyError, P] =
    val read: Either[DspyError, String] =
      try Right(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8))
      catch
        case error: Throwable =>
          Left(RuntimeError("program_load", Option(error.getMessage).getOrElse(error.getClass.getSimpleName)))
    read.flatMap(json => loadJson(program, json))
