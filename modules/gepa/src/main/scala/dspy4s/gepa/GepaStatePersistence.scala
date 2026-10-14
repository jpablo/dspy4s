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
  * validation subscores and objective scores are aligned with the current validation set. The round-robin pointers, the
  * objective scores, the frontier type, the iteration index, the merge schedule, the discovery counts, and the number
  * of failed proposals are persisted. The random sources derive from the seed and the iteration index, so the snapshot
  * needs no random state. `fromJson(toJson(state))` gives `Right(state)`.
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
    *
    * The fields of a later version follow the same rules, so a snapshot of an older version loads with their initial
    * values. A field with its initial value is not written:
    *   - `iteration` and `failedProposals` are `Option`s. `None` is 0.
    *   - `discoveryCalls` is a collection. Empty is no discovery count.
    *   - `merge` is an `Option` of [[MergeSnapshot]]. `None` is [[MergeSchedule.initial]].
    */
  private final case class Snapshot(
      candidates        : Vector[Map[String, Option[String]]],
      valSubscores      : Vector[Vector[Double]],
      parents           : Vector[Vector[Int]],
      totalMetricCalls  : Int,
      nextComponent     : Vector[Int],
      valObjectiveScores: Vector[Vector[Map[String, Double]]],
      frontierType      : Option[String],
      iteration         : Option[Int],
      merge             : Option[MergeSnapshot],
      discoveryCalls    : Vector[Int],
      failedProposals   : Option[Int]
  ) derives Schema

  /** The JSON form of a [[MergeSchedule]]. A set has no order, so the two sets are sorted lists, from the smallest
    * value to the largest value:
    *   - `performedTriplets`: one array `[i, j, ancestor]` for each triplet, sorted by `i`, then `j`, then `ancestor`.
    *   - `performedDescriptions`: one object `{"first": i, "second": j, "sources": [...]}` for each description, sorted
    *     by `first`, then `second`, then `sources`.
    *
    * An example: `{"mergesDue":1,"totalMergesTested":2,"lastIterFoundNewProgram":true,"performedTriplets":[[1,2,0]],
    * "performedDescriptions":[{"first":1,"second":2,"sources":[0,1]}]}`.
    */
  private final case class MergeSnapshot(
      mergesDue              : Int,
      totalMergesTested      : Int,
      lastIterFoundNewProgram: Boolean,
      performedTriplets      : Vector[Vector[Int]],
      performedDescriptions  : Vector[DescriptionSnapshot]
  ) derives Schema

  /** One merge description of [[MergeSnapshot]]: the two candidates and the source of each component. */
  private final case class DescriptionSnapshot(first: Int, second: Int, sources: Vector[Int]) derives Schema

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
          Option.when(state.frontierType != FrontierType.Instance)(state.frontierType.toString),
          Option.when(state.iteration != 0)(state.iteration),
          Option.when(state.merge != MergeSchedule.initial)(mergeSnapshot(state.merge)),
          state.discoveryCalls,
          Option.when(state.failedProposals != 0)(state.failedProposals)
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
          merge <- s.merge.fold[Either[String, MergeSchedule]](Right(MergeSchedule.initial))(mergeSchedule)
          state <- scala.util
                     .Try(
                       GepaState(
                         pool,
                         s.valSubscores,
                         s.parents,
                         calls,
                         s.nextComponent,
                         s.valObjectiveScores,
                         frontierType,
                         s.iteration.getOrElse(0),
                         merge,
                         s.discoveryCalls,
                         s.failedProposals.getOrElse(0)
                       )
                     )
                     .toEither
                     .left
                     .map(e => s"invalid GEPA state snapshot: ${Option(e.getMessage).getOrElse(e.toString)}")
        yield state
      }
    }

  /** The JSON form of `schedule`, with the two sets sorted. */
  private def mergeSnapshot(schedule: MergeSchedule): MergeSnapshot =
    import scala.math.Ordering.Implicits.seqOrdering
    MergeSnapshot(
      schedule.mergesDue,
      schedule.totalMergesTested,
      schedule.lastIterFoundNewProgram,
      schedule.performedTriplets.toVector.sorted.map((i, j, ancestor) => Vector(i, j, ancestor)),
      schedule.performedDescriptions.toVector.sorted.map((i, j, sources) => DescriptionSnapshot(i, j, sources))
    )

  /** The schedule of `snapshot`. `Left` when a triplet does not have three indices. */
  private def mergeSchedule(snapshot: MergeSnapshot): Either[String, MergeSchedule] =
    val triplets = snapshot.performedTriplets.foldLeft[Either[String, Set[(Int, Int, Int)]]](Right(Set.empty)) {
      (done, triplet) =>
        done.flatMap { found =>
          triplet match
            case Vector(i, j, ancestor) => Right(found + ((i, j, ancestor)))
            case other                  => Left(s"invalid GEPA state snapshot: a merge triplet has ${other.size} indices")
        }
    }
    triplets.map { performed =>
      MergeSchedule(
        snapshot.mergesDue,
        snapshot.totalMergesTested,
        snapshot.lastIterFoundNewProgram,
        performed,
        snapshot.performedDescriptions.iterator.map(d => (d.first, d.second, d.sources)).toSet
      )
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
