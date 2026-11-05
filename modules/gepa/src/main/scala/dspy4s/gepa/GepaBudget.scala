package dspy4s.gepa

/** The budget of a GEPA run in metric calls (gepa's `max_metric_calls`, `max_full_evals`, and `auto`). Upstream takes
  * exactly one of the three; the enum makes that a type.
  */
enum GepaBudget derives CanEqual:
  /** A number of metric calls (gepa's `max_metric_calls`). */
  case MetricCalls(calls: MetricCallCount)

  /** A number of full evaluations of the trainset and the given validation set (gepa's `max_full_evals`). */
  case FullEvals(evals: FullEvalCount)

  /** A budget from the upstream formula for a level of effort (gepa's `auto`). */
  case Auto(level: AutoBudget)

  /** The number of metric calls of this budget, as `dspy.GEPA.compile` computes it.
    *
    *   - `MetricCalls(n)`: `n`.
    *   - `FullEvals(n)`: `n × (trainsetSize + valsetSize)`, with 0 for a validation set that the caller did not give.
    *   - `Auto(level)`: [[GepaBudget.auto]] with `max(components, 1)` components, the candidates of the level, and the
    *     size of the given validation set, or of the trainset when the caller gave no validation set.
    *
    * `valsetSize` is `None` when the caller gave no validation set. An empty validation set that the caller gave is
    * `Some(0)`, as upstream (`len(valset) if valset is not None`).
    *
    * A number of calls larger than `Int.MaxValue` gives `Int.MaxValue`, and not an overflow.
    */
  def resolve(components: Int, trainsetSize: Int, valsetSize: Option[Int]): MetricCallCount =
    this match
      case MetricCalls(calls) => calls
      case FullEvals(evals)   =>
        val examples = BigInt(trainsetSize) + BigInt(valsetSize.getOrElse(0))
        MetricCallCount.applyUnsafe(GepaBudget.saturated(BigInt(evals) * examples))
      case Auto(level) => MetricCallCount.applyUnsafe(
          GepaBudget.auto(math.max(components, 1), level.candidates, valsetSize.getOrElse(trainsetSize))
        )

object GepaBudget:

  /** The default `minibatch_size` of gepa's `auto_budget`. */
  private val AutoMinibatchSize = 35

  /** The default `full_eval_steps` of gepa's `auto_budget`. */
  private val AutoFullEvalSteps = 5

  /** gepa's `auto_budget` (`dspy/teleprompt/gepa/gepa.py`), with its defaults for `minibatchSize` and `fullEvalSteps`:
    *
    * {{{
    * N     = int(max(2 × (components × 2) × log2(candidates), 1.5 × candidates))
    * total = V + 5 × candidates + N × minibatchSize
    * if N > 0: total += ((N + 1) / fullEvalSteps + 1 + (1 if N < fullEvalSteps else 0)) × V
    * }}}
    *
    * `V` is `valsetSize`, and the division is an integer division. Examples: one component, 6 candidates, and 4
    * validation examples give 396; three components, 18 candidates, and 300 validation examples give 5440.
    *
    * `candidates` is one of the values of [[AutoBudget.candidates]]. A total larger than `Int.MaxValue` gives
    * `Int.MaxValue`, and not an overflow.
    */
  private[gepa] def auto(components: Int, candidates: Int, valsetSize: Int): Int =
    // Python's `int` of a float rounds toward zero, as `toLong` does. The value is positive, because
    // `1.5 × candidates` is positive.
    val trials = BigInt(math.max(4.0 * components * log2(candidates), 1.5 * candidates).toLong)
    val v      = BigInt(valsetSize)
    val steps  = BigInt(AutoFullEvalSteps)
    // The seed evaluation, 5 trials to bootstrap each candidate, and the minibatch evaluations.
    val initial = v + BigInt(candidates) * BigInt(5) + trials * BigInt(AutoMinibatchSize)
    // The periodic full evaluations, and one final full evaluation when the number of trials is below the steps.
    val fullEvaluations = (trials + BigInt(1)) / steps + BigInt(1) + (if trials < steps then BigInt(1) else BigInt(0))
    saturated(initial + fullEvaluations * v)

  /** `log2(n)` for a positive `n`. */
  private def log2(n: Int): Double = math.log(n.toDouble) / math.log(2.0)

  /** `value`, or `Int.MaxValue` when `value` is larger. `value` must not be negative. */
  private def saturated(value: BigInt): Int = if value > BigInt(Int.MaxValue) then Int.MaxValue else value.toInt

/** A level of effort for [[GepaBudget.Auto]] (gepa's `AUTO_RUN_SETTINGS`). */
enum AutoBudget derives CanEqual:
  case Light, Medium, Heavy

  /** The number of candidates of the level: 6, 12, or 18. */
  def candidates: Int =
    this match
      case Light  => 6
      case Medium => 12
      case Heavy  => 18

/** Why a GEPA run stopped. A run that stops with an error returns a `Left` and has no stop reason. */
enum GepaStopReason derives CanEqual:
  /** The run used each metric call of its budget. */
  case BudgetExhausted

  /** The next iteration needs `required` metric calls at most, and the budget has `remaining` calls (`remaining` is
    * smaller). The budget is a hard limit, so the iteration does not start. This includes a budget that holds no
    * iteration after the seed evaluation.
    */
  case IterationDoesNotFit(required: Long, remaining: Long)

  /** `GepaConfig.stopOnPerfectScore` is on, and the best candidate has the perfect mean validation score. */
  case PerfectScore
