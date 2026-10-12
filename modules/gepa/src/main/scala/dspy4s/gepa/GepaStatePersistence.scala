package dspy4s.gepa

import dspy4s.programs.optimization.OptimizableId
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal

/** JSON persistence for [[GepaState]] — the basis for resuming an interrupted run (gepa's `state.save`/`load` into a
  * run dir). The search state contains enough information to warm the evaluation cache on resume: every candidate's
  * validation subscores and objective scores are aligned with the current validation set. RNG position and merge
  * schedule are not persisted. The round-robin pointers, the objective scores, and the frontier type are persisted.
  */
object GepaStatePersistence:

  /** Flat, JSON-friendly projection of [[GepaState]]'s fields (no methods / `require`), so the codec derives cleanly.
    *
    * `nextComponent` is a collection, because the derived codec reads a collection field that is absent as an empty
    * collection, and does not write an empty collection. A snapshot that an older version wrote has no such field. It
    * loads with an empty `nextComponent`, which [[GepaState]] reads as the pointer 0 for each candidate.
    *
    * The codec handles `valObjectiveScores` in the same way. A snapshot with no such field loads with no objective
    * data, and a state with no objective data writes no such field.
    *
    * `frontierType` is the name of the [[FrontierType]]. The derived codec reads an `Option` field that is absent as
    * `None`, and does not write `None`. `None` is the instance type, so a state with the instance type and no objective
    * data has the fields of an older snapshot only.
    */
  private final case class Snapshot(
      candidates        : Vector[Map[String, Option[String]]],
      valSubscores      : Vector[Vector[Double]],
      parents           : Vector[Vector[Int]],
      totalMetricCalls  : Int,
      nextComponent     : Vector[Int],
      valObjectiveScores: Vector[Vector[Map[String, Double]]],
      frontierType      : Option[String]
  ) derives Schema

  private val codec = Schema[Snapshot].derive(JsonCodecDeriver)

  val fileName: String = "gepa_state.json"

  def toJson(state: GepaState): String =
    val candidates = state.candidates.map(_.iterator.map { case (id, instruction) => id.render -> instruction }.toMap)
    new String(
      codec.encode(
        Snapshot(
          candidates,
          state.valSubscores,
          state.parents,
          state.totalMetricCalls,
          state.nextComponent,
          state.valObjectiveScores,
          Option.when(state.frontierType != FrontierType.Instance)(state.frontierType.toString)
        )
      ),
      StandardCharsets.UTF_8
    )

  def fromJson(json: String): Either[String, GepaState] =
    codec.decode(json.getBytes(StandardCharsets.UTF_8)).left.map(_.toString).flatMap { s =>
      // GepaState's invariants (aligned vectors, uniform subscore-row lengths) guard paretoFrontier's indexing;
      // a malformed snapshot must surface as a clean Left here, not an IndexOutOfBounds deep in the search.
      val candidates = s.candidates.foldLeft[Either[String, Vector[Candidate]]](Right(Vector.empty)) { (acc, raw) =>
        for
          parsed    <- acc
          candidate <- raw.foldLeft[Either[String, Candidate]](Right(Map.empty)) { case (candidateAcc, (key, value)) =>
                         for
                           candidate <- candidateAcc
                           id        <- OptimizableId.parse(key)
                         yield candidate.updated(id, value)
                       }
        yield parsed :+ candidate
      }
      candidates.flatMap { parsed =>
        for
          pool  <- CandidatePool.either(parsed).left.map(_ => "invalid GEPA state snapshot: candidate pool is empty")
          calls <- MetricCallCount
                     .either(s.totalMetricCalls)
                     .left
                     .map(_ => "invalid GEPA state snapshot: totalMetricCalls must be non-negative")
          frontierType <- s.frontierType.fold[Either[String, FrontierType]](Right(FrontierType.Instance)) { name =>
                            FrontierType
                              .parse(name)
                              .toRight(s"invalid GEPA state snapshot: the frontier type '$name' does not exist")
                          }
          state <- scala.util
                     .Try(
                       GepaState(
                         pool,
                         s.valSubscores,
                         s.parents,
                         calls,
                         s.nextComponent,
                         s.valObjectiveScores,
                         frontierType
                       )
                     )
                     .toEither
                     .left
                     .map(e => s"invalid GEPA state snapshot: ${Option(e.getMessage).getOrElse(e.toString)}")
        yield state
      }
    }

  /** Write `state` to `<dir>/gepa_state.json`, creating `dir` if needed. */
  def save(dir: Path, state: GepaState): Unit =
    val _ = Files.createDirectories(dir)
    val _ = Files.write(dir.resolve(fileName), toJson(state).getBytes(StandardCharsets.UTF_8))

  /** Load a previously-saved state from `dir`. Absence is distinct from an unreadable or invalid checkpoint: a corrupt
    * file must never be silently treated as a fresh run and overwritten.
    */
  def load(dir: Path): Either[String, Option[GepaState]] =
    val file = dir.resolve(fileName)
    if !Files.exists(file) then Right(None)
    else
      try fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).map(Some(_))
      catch
        case NonFatal(error) =>
          Left(s"Could not read GEPA checkpoint '$file': ${Option(error.getMessage).getOrElse(error.toString)}")
