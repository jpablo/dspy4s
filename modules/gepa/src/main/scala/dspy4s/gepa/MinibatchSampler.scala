package dspy4s.gepa

import scala.annotation.unused
import scala.collection.mutable
import scala.util.Random

/** Chooses the train-example indices for an iteration's reflection minibatch. Stateful: created once per `optimize` run
  * and advanced by iteration index (gepa's `BatchSampler`).
  */
trait MinibatchSampler:
  /** The train indices (each in `[0, trainsetSize)`) for one minibatch of `iteration` (0-based).
    *
    * `callInIteration` is the number of earlier calls for the same iteration: an iteration with more than one proposal
    * task asks for one minibatch for each task (gepa's `_calls_in_iteration`). The call 0 gives the minibatch that an
    * iteration with one task gets.
    */
  def sample(trainsetSize: Int, iteration: Int, callInIteration: Int = 0): Vector[Int]

/** The selectable batch-sampling strategy (gepa's `batch_sampler`). `EpochShuffled` is gepa's default. */
enum BatchSamplerKind derives CanEqual:
  case EpochShuffled, RandomDraw

object MinibatchSampler:

  def of(kind: BatchSamplerKind, minibatchSize: MinibatchSize, seed: Long): MinibatchSampler =
    kind match
      case BatchSamplerKind.EpochShuffled => new EpochShuffled(minibatchSize, seed)
      case BatchSamplerKind.RandomDraw    => new RandomDraw(minibatchSize, seed)

  /** Independent random draw (without replacement within a draw) each iteration — GEPA v0's sampler. Simple, but a
    * given example can be starved or over-sampled across iterations since draws are independent.
    */
  final class RandomDraw(minibatchSize: MinibatchSize, seed: Long) extends MinibatchSampler:
    private val rng = new Random(seed)

    /** Each call gives a new draw, so `callInIteration` has no effect. */
    def sample(trainsetSize: Int, iteration: Int, @unused callInIteration: Int): Vector[Int] =
      require(trainsetSize > 0, "cannot sample from an empty trainset")
      rng.shuffle((0 until trainsetSize).toVector).take(minibatchSize)

  /** gepa's default `EpochShuffledBatchSampler`: shuffle the trainset once per epoch and walk it in sequential
    * minibatch-sized windows, so every example is used once per epoch before any repeats (sampling WITHOUT replacement
    * across an epoch — better coverage than [[RandomDraw]]). The shuffled list is padded to a multiple of
    * `minibatchSize` with the least-frequently-used ids so the final window is full; a new shuffle is drawn each time
    * the walk wraps into the next epoch.
    *
    * The iteration index starts at 0, as in gepa (its `state.i` is -1 before the first iteration and 0 in it). Delta
    * from gepa: padding ties break by smallest id (gepa uses `Counter` insertion order). This changes only WHICH
    * example fills a pad slot, not the coverage guarantee. Deterministic for a given `seed`.
    *
    * A call after the first one of an iteration takes the next window: the call `c` of the iteration `i` starts at
    * `(i + c) × minibatchSize`, modulo the length of the shuffled list. The epoch comes from the iteration only, so a
    * later call of an iteration makes no new shuffle, and the walk of the first calls does not change.
    */
  final class EpochShuffled(minibatchSize: MinibatchSize, seed: Long) extends MinibatchSampler:
    private val rng                   = new Random(seed)
    private var shuffled: Vector[Int] = Vector.empty
    private var epoch                 = -1
    private var lastSize              = 0

    private def refresh(trainsetSize: Int): Unit =
      val base   = rng.shuffle((0 until trainsetSize).toVector)
      val mod    = trainsetSize % minibatchSize
      val pad    = if mod != 0 then minibatchSize - mod else 0
      val counts = mutable.Map.from((0 until trainsetSize).map(_ -> 1))
      val padded = mutable.ArrayBuffer.from(base)
      var k      = 0
      while k < pad do
        val pick = counts.toVector.minBy { case (id, c) => (c, id) }._1 // least frequent, ties by smallest id
        padded += pick
        counts(pick) += 1
        k += 1
      shuffled = padded.toVector
      lastSize = trainsetSize

    def sample(trainsetSize: Int, iteration: Int, callInIteration: Int): Vector[Int] =
      require(trainsetSize > 0, "cannot sample from an empty trainset")
      require(callInIteration >= 0, s"callInIteration must not be negative: $callInIteration")
      // The epoch and the refresh use the index of the iteration only (gepa: the un-offset `base_idx`).
      val baseIdx   = iteration * minibatchSize
      val currEpoch = if epoch == -1 then 0 else baseIdx / math.max(shuffled.size, 1)
      if shuffled.isEmpty || trainsetSize != lastSize || currEpoch > epoch then
        epoch = currEpoch
        refresh(trainsetSize)
      // The length of the shuffled list is a multiple of the minibatch size, so each window is full.
      val start = ((baseIdx + callInIteration.toLong * minibatchSize) % shuffled.size).toInt
      shuffled.slice(start, start + minibatchSize)
