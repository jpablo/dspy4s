package dspy4s.optimize

import dspy4s.core.contracts.PythonText

import java.math.BigDecimal as JBigDecimal

/** The search rules of [[ReAnchor]] (upstream `dspy/teleprompt/reanchor/calibrate.py`): the candidate settings, the
  * fold check, and the number rules of Python that the search uses, so that the port keeps the same settings as
  * upstream.
  */
private[optimize] object ReAnchorSearch:

  /** The largest count of candidate settings for one parameter (upstream `MAX_CANDIDATES`). */
  val MaxCandidates: Int = 40

  /** The smallest and the largest Choice weight that the search tries (upstream `WEIGHT_RANGE`). */
  val WeightRange: (Double, Double) = (1e-3, 1e3)

  /** The count of parts that the fold check holds out in turn (upstream `FOLDS`). */
  val Folds: Int = 5

  /** One candidate that the search scored: the score of each example, the key that breaks a tie of the total, and the
    * setting. Python compares the key as a tuple; here the key is a vector of numbers with the same order.
    */
  final case class Tried[A](scores: Vector[Double], key: Vector[Double], setting: A)

  /** The result of [[select]]: the candidate to keep (`None` keeps the start), and `true` when the fold check refused a
    * candidate that scored better on the whole training set.
    */
  final case class Selection[A](kept: Option[Tried[A]], refused: Boolean)

  /** The candidates between the distinct values of `values` in the open range (`lo`, `hi`), each with the width of its
    * gap (upstream `_gaps`). The candidate of a gap is its midpoint, rounded with [[tidy]]. More than `MaxCandidates`
    * gaps are thinned to evenly spaced quantiles of the values. With `geometric`, the midpoint is `sqrt(a * b)` and the
    * width is `log(b / a)`. A gap whose computed midpoint is not strictly between its ends gives no candidate (two
    * adjacent floats).
    */
  def gaps(values: Vector[Double], lo: Double, hi: Double, geometric: Boolean = false): Vector[(Double, Double)] =
    val all   = values.filter(v => lo < v && v < hi).distinct.sorted
    val inner =
      if all.size <= MaxCandidates - 1 then all
      else
        val step = (all.size - 1).toDouble / (MaxCandidates - 2)
        // Python `round` of a float rounds a half to the even integer, as `Math.rint` does.
        (0 until MaxCandidates - 1).map(i => all(Math.rint(i * step).toInt)).distinct.sorted.toVector
    val points = (lo +: inner) :+ hi
    points.zip(points.drop(1)).flatMap { (a, b) =>
      val (middle, width) = if geometric then (math.sqrt(a * b), math.log(b / a)) else ((a + b) / 2, b - a)
      Option.when(a < middle && middle < b)(tidy(middle, a, b) -> width)
    }

  /** The shortest decimal strictly between `lo` and `hi` that rounds from `x`, with 1 to 11 decimal places, else `x`
    * (upstream `_tidy`).
    */
  def tidy(x: Double, lo: Double, hi: Double): Double =
    val inside = (1 until 12).iterator.map(PythonText.round(x, _)).find(r => lo < r && r < hi)
    inside.getOrElse(x)

  /** The mean of `values` rounded to 4 decimal places (upstream `_mean`). */
  def mean(values: Vector[Double]): Double = PythonText.round(fsum(values) / values.size, 4)

  /** The count of the values, and for values that are not empty, the count of the distinct values and the smallest and
    * the largest value rounded to 4 decimal places (upstream `_summary`). The count of candidates is 0.
    */
  def summary(values: Vector[Double]): ReAnchorRow.Observed =
    val distinct = values.distinct.sorted
    ReAnchorRow.Observed(
      calls = values.size,
      distinct = Option.when(distinct.nonEmpty)(distinct.size),
      min = distinct.headOption.map(PythonText.round(_, 4)),
      max = distinct.lastOption.map(PythonText.round(_, 4)),
      candidates = 0
    )

  /** The candidate of `tried` with the best total on the examples `rows`, or `None` when no candidate has a total that
    * is strictly higher than the total of `start` there (upstream `_pick`). Among equal totals, the larger key wins,
    * and among equal keys, the first candidate.
    */
  def pick[A](start: Vector[Double], tried: Vector[Tried[A]], rows: Vector[Int]): Option[Tried[A]] =
    def total(values: Vector[Double]): Double = fsum(rows.map(values))
    val ranked                                = tried.map(t => t -> (total(t.scores) +: t.key))
    val best                                  = ranked.foldLeft(Option.empty[(Tried[A], Vector[Double])]) { (found, next) =>
      found match
        case Some(current) if compare(next._2, current._2) <= 0 => found
        case _                                                  => Some(next)
    }
    best.collect { case (candidate, order) if order.head > total(start) => candidate }

  /** The candidate of `tried` to keep instead of `start` (upstream `_select`). The candidate with the best total on the
    * whole training set is kept only when the fold check passes: for each fold of [[folds]], the pick on the other
    * folds (else `start`) is scored on the fold, and the sum of these held-out scores must be strictly higher than the
    * sum of the scores of `start` on the same examples.
    */
  def select[A](start: Vector[Double], tried: Vector[Tried[A]]): Selection[A] =
    pick(start, tried, start.indices.toVector) match
      case None       => Selection(None, refused = false)
      case Some(best) =>
        val (held, heldStart) = folds(start.size).foldLeft((0.0, 0.0)) { case ((sum, sumStart), fold) =>
          val out    = fold.toSet
          val chosen = pick(start, tried, start.indices.filterNot(out.contains).toVector).fold(start)(_.scores)
          (sum + fsum(fold.map(chosen)), sumStart + fsum(fold.map(start)))
        }
        if held > heldStart then Selection(Some(best), refused = false) else Selection(None, refused = true)

  /** The example indexes `0` to `n - 1` split into up to [[Folds]] parts, the same way on each run (upstream `_folds`:
    * `random.Random(0).shuffle`, then each `Folds`-th index).
    */
  def folds(n: Int): Vector[Vector[Int]] =
    val order = PythonRandom(0).shuffled(n)
    val k     = math.min(Folds, n)
    (0 until k).toVector.map(i => order.drop(i).grouped(k).map(_.head).toVector)

  /** The exactly rounded sum of the finite `values`, as Python `math.fsum`. The search compares totals with it, so that
    * the order of the examples does not change a result.
    */
  def fsum(values: Iterable[Double]): Double =
    values.foldLeft(JBigDecimal.ZERO)((sum, value) => sum.add(new JBigDecimal(value))).doubleValue

  /** The lexicographic order of two keys of the same length. As in Python, `-0.0` and `0.0` are equal. */
  private def compare(a: Vector[Double], b: Vector[Double]): Int =
    a.zip(b).iterator.map((x, y) => if x < y then -1 else if x > y then 1 else 0).find(_ != 0).getOrElse(0)

/** The shuffle of Python `random.Random(seed).shuffle` for a seed in [0, 2^31): the Mersenne Twister MT19937 with the
  * seed of CPython `random_seed` (`init_by_array` with one key word), and `_randbelow` with `getrandbits`.
  */
private[optimize] final class PythonRandom(seed: Int):
  private val N      = 624
  private val M      = 397
  private val state  = new Array[Int](N)
  private var index  = N
  private val Matrix = 0x9908b0df

  locally {
    state(0) = 19650218
    (1 until N).foreach(i => state(i) = 1812433253 * (state(i - 1) ^ (state(i - 1) >>> 30)) + i)
    // `init_by_array` with the key `[seed]`.
    var i = 1
    (0 until N).foreach { _ =>
      state(i) = (state(i) ^ ((state(i - 1) ^ (state(i - 1) >>> 30)) * 1664525)) + seed
      i += 1
      if i >= N then
        state(0) = state(N - 1)
        i = 1
    }
    (0 until N - 1).foreach { _ =>
      state(i) = (state(i) ^ ((state(i - 1) ^ (state(i - 1) >>> 30)) * 1566083941)) - i
      i += 1
      if i >= N then
        state(0) = state(N - 1)
        i = 1
    }
    state(0) = 0x80000000
  }

  /** `0` to `n - 1` in the order of Python `shuffle` (from the last position down, `_randbelow(i + 1)`). */
  def shuffled(n: Int): Vector[Int] =
    val order = Array.tabulate(n)(identity)
    (n - 1 until 0 by -1).foreach { i =>
      val j = below(i + 1)
      val t = order(i)
      order(i) = order(j)
      order(j) = t
    }
    order.toVector

  /** A uniform integer in [0, `n`) for `n` in [1, 2^31) (Python `_randbelow_with_getrandbits`). */
  private def below(n: Int): Int =
    val bits = 32 - Integer.numberOfLeadingZeros(n)
    var r    = next() >>> (32 - bits)
    while r >= n do r = next() >>> (32 - bits)
    r.toInt

  /** The next 32 random bits, as an unsigned number (MT19937 `genrand_uint32`). */
  private def next(): Long =
    if index >= N then
      (0 until N).foreach { k =>
        val y = (state(k) & 0x80000000) | (state((k + 1) % N) & 0x7fffffff)
        state(k) = state((k + M) % N) ^ (y >>> 1) ^ (if (y & 1) != 0 then Matrix else 0)
      }
      index = 0
    var y = state(index)
    index += 1
    y ^= y >>> 11
    y ^= (y << 7) & 0x9d2c5680
    y ^= (y << 15) & 0xefc60000
    y ^= y >>> 18
    y.toLong & 0xffffffffL
