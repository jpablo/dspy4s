package dspy4s.gepa

import dspy4s.programs.PredictorId
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** JSON persistence for [[GepaState]] — the basis for resuming an interrupted run (gepa's `state.save`/`load` into a
  * run dir). Only the search state (candidate pool, per-instance validation subscores, lineage, and the metric-call
  * meter) is persisted; the eval cache, RNG position, and merge schedule are not — a resumed run keeps every discovered
  * candidate (so no budget is re-spent rediscovering them) and continues searching from that pool.
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

  /** Load a previously-saved state from `dir`, or `None` if there is no (readable) snapshot there. */
  def load(dir: Path): Option[GepaState] =
    val file = dir.resolve(fileName)
    if !Files.exists(file) then None
    else fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).toOption
