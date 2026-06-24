package dspy4s.gepa

import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** JSON persistence for [[GepaState]] — the basis for resuming an interrupted run (gepa's `state.save`/`load` into a
  * run dir). Only the search state (candidate pool, per-instance validation subscores, lineage, and the metric-call
  * meter) is persisted; the eval cache, RNG position, and merge schedule are not — a resumed run keeps every
  * discovered candidate (so no budget is re-spent rediscovering them) and continues searching from that pool. */
object GepaStatePersistence:

  /** Flat, JSON-friendly projection of [[GepaState]]'s fields (no methods / `require`), so the codec derives cleanly. */
  private final case class Snapshot(
      candidates: Vector[Map[String, String]],
      valSubscores: Vector[Vector[Double]],
      parents: Vector[Vector[Int]],
      totalMetricCalls: Int
  ) derives Schema

  private val codec = Schema[Snapshot].derive(JsonCodecDeriver)

  val fileName: String = "gepa_state.json"

  def toJson(state: GepaState): String =
    new String(codec.encode(Snapshot(state.candidates, state.valSubscores, state.parents, state.totalMetricCalls)), StandardCharsets.UTF_8)

  def fromJson(json: String): Either[String, GepaState] =
    codec.decode(json.getBytes(StandardCharsets.UTF_8)).left.map(_.toString).flatMap { s =>
      // GepaState's invariants (aligned vectors, uniform subscore-row lengths) guard paretoFrontier's indexing;
      // a malformed snapshot must surface as a clean Left here, not an IndexOutOfBounds deep in the search.
      scala.util.Try(GepaState(s.candidates, s.valSubscores, s.parents, s.totalMetricCalls)).toEither.left
        .map(e => s"invalid GEPA state snapshot: ${Option(e.getMessage).getOrElse(e.toString)}")
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
