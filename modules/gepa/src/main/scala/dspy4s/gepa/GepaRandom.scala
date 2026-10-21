package dspy4s.gepa

import scala.util.Random

/** The users of the random sources of a GEPA run. Each user has a stream of its own, so two users never share draws.
  */
private[gepa] enum RandomStream derives CanEqual:
  /** The candidate selector and the merge proposer of one iteration. The coordinate is the iteration index. */
  case Engine

  /** The shuffle of [[MinibatchSampler.EpochShuffled]] for one epoch. The coordinate is the epoch. */
  case EpochShuffle

  /** One draw of [[MinibatchSampler.RandomDraw]]. The coordinates are the iteration index and the call in the
    * iteration.
    */
  case RandomDraw

  /** The source of [[GepaAdapter.makeReflectiveDataset]] for one proposal task. The coordinates are the iteration index
    * and the index of the task in the iteration.
    */
  case Reflection

/** The random sources of a GEPA run. Each source derives from the seed of the run, a [[RandomStream]], and fixed
  * coordinates, for example the iteration index. A source thus has no state that a resumed run can lose, and a resumed
  * run makes the same draws as a run with no stop.
  */
private[gepa] object GepaRandom:

  /** A new source for `stream` at `coordinates`. The same arguments give the same sequence of values. */
  def source(seed: Long, stream: RandomStream, coordinates: Long*): Random =
    new Random(derive(seed, stream, coordinates*))

  /** The seed of the source for `stream` at `coordinates`. Each step mixes one more value into the result with [[mix]],
    * so a change of one value gives an unrelated seed.
    */
  def derive(seed: Long, stream: RandomStream, coordinates: Long*): Long =
    coordinates.foldLeft(mix(mix(seed) ^ (stream.ordinal + 1L)))((done, coordinate) => mix(done ^ coordinate))

  /** The SplitMix64 output for `seed`. It changes about half of the 64 bits when one bit of `seed` changes, so near
    * seeds give unrelated random sequences. The result is the same for the same seed.
    */
  def mix(seed: Long): Long =
    val a = seed + 0x9e3779b97f4a7c15L
    val b = (a ^ (a >>> 30)) * 0xbf58476d1ce4e5b9L
    val c = (b ^ (b >>> 27)) * 0x94d049bb133111ebL
    c ^ (c >>> 31)
