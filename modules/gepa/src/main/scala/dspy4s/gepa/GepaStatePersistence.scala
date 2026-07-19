package dspy4s.gepa

import dspy4s.programs.PredictorId
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal

/** JSON persistence for [[GepaState]] — the basis for resuming an interrupted run (gepa's `state.save`/`load` into a
  * run dir). The search state contains enough information to warm the evaluation cache on resume: every candidate's
  * validation subscores are aligned with the current validation set. RNG position and merge schedule are not persisted.
  */
object GepaStatePersistence:

  /** Flat, JSON-friendly projection of [[GepaState]]'s fields (no methods / `require`), so the codec derives cleanly.
    */
  private final case class Snapshot(
      candidates: Vector[Map[String, String]],
      valSubscores: Vector[Vector[Double]],
      parents: Vector[Vector[Int]],
      totalMetricCalls: Int
  ) derives Schema

  private val codec = Schema[Snapshot].derive(JsonCodecDeriver)

  val fileName: String = "gepa_state.json"

  def toJson(state: GepaState): String =
    val candidates = state.candidates.map(_.iterator.map { case (id, instruction) => id.render -> instruction }.toMap)
    new String(
      codec.encode(Snapshot(candidates, state.valSubscores, state.parents, state.totalMetricCalls)),
      StandardCharsets.UTF_8
    )

  def fromJson(json: String): Either[String, GepaState] =
    codec.decode(json.getBytes(StandardCharsets.UTF_8)).left.map(_.toString).flatMap { s =>
      // GepaState's invariants (aligned vectors, uniform subscore-row lengths) guard paretoFrontier's indexing;
      // a malformed snapshot must surface as a clean Left here, not an IndexOutOfBounds deep in the search.
      val candidates = s.candidates.foldLeft[Either[String, Vector[Candidate]]](Right(Vector.empty)) { (acc, raw) =>
        for
          parsed <- acc
          candidate <- raw.foldLeft[Either[String, Candidate]](Right(Map.empty)) { case (candidateAcc, (key, value)) =>
            for
              candidate <- candidateAcc
              id        <- PredictorId.parse(key)
            yield candidate.updated(id, value)
          }
        yield parsed :+ candidate
      }
      candidates.flatMap { parsed =>
        scala.util.Try(GepaState(parsed, s.valSubscores, s.parents, s.totalMetricCalls)).toEither.left
          .map(e => s"invalid GEPA state snapshot: ${Option(e.getMessage).getOrElse(e.toString)}")
      }
    }

  /** Write `state` to `<dir>/gepa_state.json`, creating `dir` if needed. */
  def save(dir: Path, state: GepaState): Unit =
    val _ = Files.createDirectories(dir)
    val _ = Files.write(dir.resolve(fileName), toJson(state).getBytes(StandardCharsets.UTF_8))

  /** Load a previously-saved state from `dir`. Absence is distinct from an unreadable or invalid checkpoint: a corrupt
    * file must never be silently treated as a fresh run and overwritten. */
  def load(dir: Path): Either[String, Option[GepaState]] =
    val file = dir.resolve(fileName)
    if !Files.exists(file) then Right(None)
    else
      try fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).map(Some(_))
      catch
        case NonFatal(error) =>
          Left(s"Could not read GEPA checkpoint '$file': ${Option(error.getMessage).getOrElse(error.toString)}")
