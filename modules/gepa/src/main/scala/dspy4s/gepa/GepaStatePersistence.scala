package dspy4s.gepa

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.programs.optimization.OptimizableId
import zio.blocks.schema.Schema
import zio.blocks.schema.json.JsonCodecDeriver

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
    * The derived codec does not write a field that has its default, and it reads an absent field as its default. It
    * reads an absent collection as an empty collection, so a snapshot with no `nextComponent`, `valObjectiveScores`, or
    * `discoveryCalls` gives a state that is not aligned, and thus a `Left`.
    */
  private final case class Snapshot(
      candidates        : Vector[Map[String, Option[String]]],
      valSubscores      : Vector[Vector[Double]],
      parents           : Vector[Vector[Int]],
      totalMetricCalls  : Int,
      nextComponent     : Vector[Int],
      valObjectiveScores: Vector[Vector[Map[String, Double]]],
      discoveryCalls    : Vector[Int],
      frontierType      : FrontierType  = FrontierType.Instance,
      iteration         : Int           = 0,
      merge             : MergeSnapshot = MergeSnapshot(),
      failedProposals   : Int           = 0
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
      mergesDue              : Int                         = 0,
      totalMergesTested      : Int                         = 0,
      lastIterFoundNewProgram: Boolean                     = false,
      performedTriplets      : Vector[Vector[Int]]         = Vector.empty,
      performedDescriptions  : Vector[DescriptionSnapshot] = Vector.empty
  ) derives Schema

  /** One merge description of [[MergeSnapshot]]: the two candidates and the source of each component. */
  private final case class DescriptionSnapshot(first: Int, second: Int, sources: Vector[Int]) derives Schema

  private val codec = Schema[Snapshot].derive(JsonCodecDeriver)

  val fileName: String = "gepa_state.json"

  def toJson(state: GepaState): String =
    val candidates = state.candidates.map(_.iterator.map { case (id, instruction) => id.render -> instruction }.toMap)
    codec.encodeToString(
      Snapshot(
        candidates,
        state.valSubscores,
        state.parents,
        state.totalMetricCalls,
        state.nextComponent,
        state.valObjectiveScores,
        state.discoveryCalls,
        state.frontierType,
        state.iteration,
        mergeSnapshot(state.merge),
        state.failedProposals
      )
    )

  def fromJson(json: String): Either[String, GepaState] =
    codec.decode(json).left.map(_.toString).flatMap { s =>
      // GepaState's invariants (aligned vectors, uniform subscore-row lengths) guard the indexing of the fronts;
      // a malformed snapshot must surface as a clean Left here, not an IndexOutOfBounds deep in the search.
      val candidates = Traverse.either(s.candidates) { raw =>
        Traverse.either(raw)((key, value) => OptimizableId.parse(key).map(_ -> value)).map(_.toMap)
      }
      candidates.flatMap { parsed =>
        for
          pool  <- CandidatePool.either(parsed).left.map(_ => "invalid GEPA state snapshot: candidate pool is empty")
          calls <- MetricCallCount
                     .either(s.totalMetricCalls)
                     .left
                     .map(_ => "invalid GEPA state snapshot: totalMetricCalls must be non-negative")
          merge <- mergeSchedule(s.merge)
          state <- scala.util
                     .Try(
                       GepaState(
                         pool,
                         s.valSubscores,
                         s.parents,
                         calls,
                         s.nextComponent,
                         s.valObjectiveScores,
                         s.discoveryCalls,
                         s.frontierType,
                         s.iteration,
                         merge,
                         s.failedProposals
                       )
                     )
                     .toEither
                     .left
                     .map(e => s"invalid GEPA state snapshot: ${DspyError.messageOf(e)}")
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
    val triplets = Traverse.either(snapshot.performedTriplets) {
      case Vector(i, j, ancestor) => Right((i, j, ancestor))
      case other                  => Left(s"invalid GEPA state snapshot: a merge triplet has ${other.size} indices")
    }
    triplets.map { performed =>
      MergeSchedule(
        snapshot.mergesDue,
        snapshot.totalMergesTested,
        snapshot.lastIterFoundNewProgram,
        performed.toSet,
        snapshot.performedDescriptions.iterator.map(d => (d.first, d.second, d.sources)).toSet
      )
    }

  /** Write `state` to `<dir>/gepa_state.json`, creating `dir` if needed. */
  def save(dir: Path, state: GepaState): Unit =
    val _ = Files.createDirectories(dir)
    val _ = Files.writeString(dir.resolve(fileName), toJson(state))

  /** Load a previously-saved state from `dir`. Absence is distinct from an unreadable or invalid checkpoint: a corrupt
    * file must never be silently treated as a fresh run and overwritten.
    */
  def load(dir: Path): Either[String, Option[GepaState]] =
    val file = dir.resolve(fileName)
    if !Files.exists(file) then Right(None)
    else
      try fromJson(Files.readString(file)).map(Some(_))
      catch
        case NonFatal(error) => Left(s"Could not read GEPA checkpoint '$file': ${DspyError.messageOf(error)}")
