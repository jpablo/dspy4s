/**
 * Talk to Your Data: the deterministic Scala query engine.
 *
 * Pure JVM execution of a [[QueryPlan]] over the dataset. It plays two roles and never calls an LM:
 *   - the GEPA **oracle**: score a planner's QueryPlan by running it here and comparing to the gold answer;
 *   - the verify-stage **cross-check**: the agent's RLM stage computes the answer in a Python sandbox; this
 *     recomputes the same plan on the JVM, and the two must agree. (Two independent engines agreeing is a far
 *     stronger trust signal than "the model said so.")
 *
 * It also quietly shows Scala doing real work: exhaustive `match` over the [[Agg]]/[[FilterOp]] enums means the
 * compiler guarantees every case is handled.
 */
package dspy4s.examples.tutorials.talk_to_your_data

object QueryEngine:

  /** The outcome of running a plan: a canonical single-line `answer` (number or category label), the headline
    * `value` when numeric, and the full result `table` for display. */
  final case class EvalResult(answer: String, value: Option[Double], columns: List[String], rows: List[List[String]])

  /** Run a plan over the data. Total function: any plan yields a result (an empty selection aggregates to 0). */
  def run(plan: QueryPlan, data: Vector[Order]): EvalResult =
    val filtered = data.filter(o => plan.filters.forall(passes(o, _)) && inRange(o, plan.timeRange))
    if plan.groupBy.isEmpty then
      val v = aggregate(filtered, plan.agg, plan.column)
      EvalResult(formatNumber(v, plan.agg), Some(v), List("metric", "value"), List(List(metricLabel(plan), formatNumber(v, plan.agg))))
    else
      val grouped: Vector[(List[String], Double)] =
        filtered
          .groupBy(o => plan.groupBy.map(stringField(o, _)))
          .toVector
          .map((key, rows) => key -> aggregate(rows, plan.agg, plan.column))
      val descending = plan.sort.forall(_.descending) // default: largest first
      val ascending  = grouped.sortBy(_._2)
      val ordered    = if descending then ascending.reverse else ascending
      val limited    = plan.limit.filter(_ > 0).fold(ordered)(ordered.take)
      val columns    = plan.groupBy :+ metricLabel(plan)
      val rows       = limited.map((key, v) => key :+ formatNumber(v, plan.agg)).toList
      val answer =
        limited.headOption match
          case Some((key, v)) =>
            plan.answerKind match
              case AnswerKind.Category => key.mkString(" / ")
              case AnswerKind.Number   => formatNumber(v, plan.agg)
          case None => "(no rows)"
      val value = limited.headOption.map(_._2)
      EvalResult(answer, value, columns, rows)

  // ── aggregation ─────────────────────────────────────────────────────────────────────────────────────────

  private def aggregate(rows: Vector[Order], agg: Agg, column: Option[String]): Double =
    val xs = rows.map(numericField(_, column.getOrElse("total")))
    agg match
      case Agg.Count   => rows.size.toDouble
      case Agg.Sum     => xs.sum
      case Agg.Average => if xs.isEmpty then 0.0 else xs.sum / xs.size
      case Agg.Min     => if xs.isEmpty then 0.0 else xs.min
      case Agg.Max     => if xs.isEmpty then 0.0 else xs.max
      case Agg.Median  => median(xs)

  private def median(xs: Vector[Double]): Double =
    if xs.isEmpty then 0.0
    else
      val sorted = xs.sorted
      val n      = sorted.size
      if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0

  // ── fields & filters ────────────────────────────────────────────────────────────────────────────────────

  private def stringField(o: Order, column: String): String = column.trim.toLowerCase match
    case "region"   => o.region
    case "category" => o.category
    case "product"  => o.product
    case "date"     => o.date
    case "quantity" => o.quantity.toString
    case _          => formatNumber(numericField(o, column), Agg.Sum)

  private def numericField(o: Order, column: String): Double = column.trim.toLowerCase match
    case "total"     => o.total
    case "quantity"  => o.quantity.toDouble
    case "unitprice" => o.unitPrice
    case "discount"  => o.discount
    case "orderid"   => o.orderId.toDouble
    case _           => o.total

  private def passes(o: Order, f: Filter): Boolean =
    val numericColumn = Set("total", "quantity", "unitprice", "discount", "orderid").contains(f.column.trim.toLowerCase)
    val asNumber      = f.value.trim.toDoubleOption
    (f.op, numericColumn && asNumber.isDefined) match
      case (FilterOp.Contains, _) => stringField(o, f.column).toLowerCase.contains(f.value.trim.toLowerCase)
      case (op, true) =>
        val (l, r) = (numericField(o, f.column), asNumber.get)
        op match
          case FilterOp.Eq  => l == r
          case FilterOp.Ne  => l != r
          case FilterOp.Gt  => l > r
          case FilterOp.Gte => l >= r
          case FilterOp.Lt  => l < r
          case FilterOp.Lte => l <= r
          case FilterOp.Contains => true // handled above
      case (op, false) =>
        val (l, r) = (stringField(o, f.column).toLowerCase, f.value.trim.toLowerCase)
        op match
          case FilterOp.Eq => l == r
          case FilterOp.Ne => l != r
          case _           => l == r // ordering ops are meaningless on labels; treat as equality

  private def inRange(o: Order, range: Option[TimeRange]): Boolean = range match
    case None => true
    case Some(tr) =>
      val v = stringField(o, tr.column)
      tr.start.forall(v >= _) && tr.end.forall(v <= _)

  // ── formatting ──────────────────────────────────────────────────────────────────────────────────────────

  private def metricLabel(plan: QueryPlan): String =
    val col = plan.column.getOrElse("total")
    plan.agg match
      case Agg.Count => "count"
      case other     => s"${other.toString.toLowerCase}_$col"

  /** Canonical numeric rendering: counts are integers, everything else is 2 decimals. Used for both display and
    * (parsed back) equality in the metric, so gold and predicted answers compare on the same footing. */
  def formatNumber(v: Double, agg: Agg): String =
    if agg == Agg.Count then f"${v.round}%d" else f"$v%.2f"

  /** Numeric-aware equality for the metric: parse both sides and compare within a cent; fall back to
    * case-insensitive string equality for category answers. */
  def answersMatch(expected: String, actual: String): Boolean =
    (expected.trim.toDoubleOption, actual.trim.toDoubleOption) match
      case (Some(e), Some(a)) => math.abs(e - a) <= 0.01 + 1e-6 * math.abs(e)
      case _                  => expected.trim.equalsIgnoreCase(actual.trim)
