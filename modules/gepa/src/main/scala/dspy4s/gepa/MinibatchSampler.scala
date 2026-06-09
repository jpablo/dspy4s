package dspy4s.gepa

import scala.collection.mutable
import scala.util.Random

/** Chooses the train-example indices for an iteration's reflection minibatch. Stateful: created once per
  * `optimize` run and advanced by iteration index (gepa's `BatchSampler`). */
trait MinibatchSampler:
  /** The train indices (each in `[0, trainsetSize)`) for the minibatch of `iteration` (0-based). */
  def sample(trainsetSize: Int, iteration: Int): Vector[Int]

/** The selectable batch-sampling strategy (gepa's `batch_sampler`). `EpochShuffled` is gepa's default. */
enum BatchSamplerKind derives CanEqual:
  case EpochShuffled, RandomDraw

object MinibatchSampler:

  def of(kind: BatchSamplerKind, minibatchSize: Int, seed: Long): MinibatchSampler = kind match
    case BatchSamplerKind.EpochShuffled => new EpochShuffled(minibatchSize, seed)
    case BatchSamplerKind.RandomDraw    => new RandomDraw(minibatchSize, seed)

  /** Independent random draw (without replacement within a draw) each iteration — GEPA v0's sampler. Simple, but
    * a given example can be starved or over-sampled across iterations since draws are independent. */
  final class RandomDraw(minibatchSize: Int, seed: Long) extends MinibatchSampler:
    require(minibatchSize > 0, "minibatchSize must be > 0")
    private val rng = new Random(seed)
    def sample(trainsetSize: Int, iteration: Int): Vector[Int] =
      require(trainsetSize > 0, "cannot sample from an empty trainset")
      rng.shuffle((0 until trainsetSize).toVector).take(minibatchSize)

  /** gepa's default `EpochShuffledBatchSampler`: shuffle the trainset once per epoch and walk it in sequential
    * minibatch-sized windows, so every example is used once per epoch before any repeats (sampling WITHOUT
    * replacement across an epoch — better coverage than [[RandomDraw]]). The shuffled list is padded to a multiple
    * of `minibatchSize` with the least-frequently-used ids so the final window is full; a new shuffle is drawn each
    * time the walk wraps into the next epoch.
    *
    * Deltas from gepa: the iteration index starts at 0 (gepa's at 1, a harmless one-window offset), and padding
    * ties break by smallest id (gepa uses `Counter` insertion order) — both change only WHICH example fills a pad
    * slot, not the coverage guarantee. Deterministic for a given `seed`. */
  final class EpochShuffled(minibatchSize: Int, seed: Long) extends MinibatchSampler:
    require(minibatchSize > 0, "minibatchSize must be > 0")
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

    def sample(trainsetSize: Int, iteration: Int): Vector[Int] =
      require(trainsetSize > 0, "cannot sample from an empty trainset")
      val baseIdx   = iteration * minibatchSize
      val currEpoch = if epoch == -1 then 0 else baseIdx / math.max(shuffled.size, 1)
      if shuffled.isEmpty || trainsetSize != lastSize || currEpoch > epoch then
        epoch = currEpoch
        refresh(trainsetSize)
      val start = baseIdx % shuffled.size
      shuffled.slice(start, start + minibatchSize)
