package dspy4s.optimize

import dspy4s.programs.Predictors

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

  /** Serialize a program's learnable state to a `DynamicValue.Record`: `{ "predictors": [ <DynamicPredict
    * state>... ] }`, one entry per predictor `Predictors.read` exposes, in stable order. */
  def dumpState[P](program: P)(using predictors: Predictors[P]): DynamicValue.Record =
    val states: Seq[DynamicValue] = predictors.read(program).map(p => p.dumpState: DynamicValue)
    DynamicValue.Record(Chunk.from(Seq(
      "predictors" -> DynamicValue.Sequence(Chunk.from(states))
    )))

  /** Rebuild a program from the state produced by [[dumpState]]. Reads the `predictors` array (which must have
    * the same length as `Predictors.read(program)`), re-hydrates each entry via [[DynamicPredict.fromState]], and
    * writes them back with `Predictors.replace`. See the object comment for the demos-only round-trip caveat on
    * typed targets. */
  def loadState[P](program: P, state: DynamicValue.Record)(using predictors: Predictors[P]): Either[DspyError, P] =
    val expected = predictors.read(program).size
    DynamicValues.recordGet(state, "predictors") match
      case Some(seq: DynamicValue.Sequence) =>
        val entries = seq.elements.toVector
        if entries.size != expected then
          Left(ValidationError(
            s"Program state has ${entries.size} predictors but the program expects $expected"
          ))
        else
          val rebuilt = entries.foldLeft[Either[DspyError, Vector[DynamicPredict]]](Right(Vector.empty)) {
            (acc, raw) =>
              for
                acc1 <- acc
                pred <- raw match
                          case rec: DynamicValue.Record => DynamicPredict.fromState(rec)
                          case _ => Left(ValidationError("Program state 'predictors' must be records"))
              yield acc1 :+ pred
          }
          rebuilt.map(updates => predictors.replace(program, updates))
      case _ =>
        Left(ValidationError("Program state is missing a 'predictors' sequence"))

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
