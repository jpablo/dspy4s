/** Talk to Your Data: the deterministic Scala query engine.
  *
  * Pure JVM execution of a [[QueryPlan]] over the dataset. It has two roles and never calls an LM:
  *   - the GEPA **oracle**: run the QueryPlan of a planner here, and compare the result with the gold result;
  *   - the verify-stage **cross-check**: the RLM stage of the agent computes the answer in a Python sandbox. This
  *     engine computes the same plan on the JVM, and the two answers must agree.
  *
  * A plan can name a column that the table does not have, because the column names are text. [[run]] then gives a
  * problem that names the column, and it computes nothing. A plan with no problem always has a result: an empty
  * selection aggregates to 0.
  *
  * The `match` over the [[Agg]] and [[FilterOp]] enums is exhaustive, so the compiler finds a case with no rule.
  */
package dspy4s.examples.tutorials.talk_to_your_data

import java.util.Locale
import scala.collection.immutable.VectorMap

object QueryEngine:

  /** The outcome of a plan: a one-line `answer` (a number or a group label), and the unrounded aggregate `value`. The
    * `value` is empty when a grouped plan selects no rows.
    */
  final case class EvalResult(answer: String, value: Option[Double])

  /** Run a plan over the data. A `Left` is one text with each problem of the plan and the valid columns. */
  def run(plan: QueryPlan, data: Vector[Order]): Either[String, EvalResult] =
    problems(plan) match
      case Nil   => Right(evaluate(plan, data))
      case found => Left(s"${found.mkString(" ")} The valid columns are: $validColumns.")

  // ── columns ─────────────────────────────────────────────────────────────────────────────────────────────

  /** One column of the table: its text (the text of the CSV), and its number when the column is numeric. */
  private final case class Column(text: Order => String, number: Option[Order => Double])

  private def integerColumn(read: Order => Int): Column    = Column(read(_).toString, Some(read(_).toDouble))
  private def decimalColumn(read: Order => Double): Column = Column(o => decimal(read(o)), Some(read))
  private def textColumn(read   : Order => String): Column = Column(read, None)

  /** The columns of the table, with the names and the order of the CSV header ([[Dataset.csv]]). */
  private val columns: VectorMap[String, Column] = VectorMap(
    "order_id"   -> integerColumn(_.orderId),
    "date"       -> textColumn(_.date),
    "region"     -> textColumn(_.region),
    "category"   -> textColumn(_.category),
    "product"    -> textColumn(_.product),
    "quantity"   -> integerColumn(_.quantity),
    "unit_price" -> decimalColumn(_.unitPrice),
    "discount"   -> decimalColumn(_.discount),
    "total"      -> decimalColumn(_.total)
  )

  private val validColumns: String =
    val (numeric, text) = columns.partition((_, column) => column.number.isDefined)
    s"${numeric.keys.mkString(", ")} (numeric); ${text.keys.mkString(", ")} (text)"

  private def find(name: String): Option[Column] = columns.get(name.trim.toLowerCase)

  /** The column that an aggregate other than `Count` reads. */
  private def aggregateColumn(plan: QueryPlan): String = plan.column.getOrElse("total")

  /** The value of a filter as a number. NaN is not a number, and each compare with it is false. */
  private def numberOf(value: String): Option[Double] = value.trim.toDoubleOption.filterNot(_.isNaN)

  /** The problems of a plan. A count reads no column, so its `column` has no rule. */
  private def problems(plan: QueryPlan): List[String] =
    def unknown(name: String): String = s"The column '$name' does not exist."
    val aggregated                    = plan.agg match
      case Agg.Count => None
      case agg       =>
        val name = aggregateColumn(plan)
        find(name) match
          case None                                  => Some(unknown(name))
          case Some(column) if column.number.isEmpty =>
            Some(s"The aggregate $agg needs a numeric column, and '$name' is a text column.")
          case _ => None
    val named  = plan.filters.map(_.column) ++ plan.groupBy ++ plan.timeRange.map(_.column)
    val values = plan.filters.collect {
      case Filter(name, op, value)
          if op != FilterOp.Contains && find(name).exists(_.number.isDefined) && numberOf(value).isEmpty =>
        s"The filter on the numeric column '$name' compares with '$value', and that value is not a number."
    }
    (aggregated.toList ++ named.filter(find(_).isEmpty).map(unknown) ++ values).distinct

  // ── evaluation ──────────────────────────────────────────────────────────────────────────────────────────

  /** The result of a plan with no problem, so each column of the plan exists. */
  private def evaluate(plan: QueryPlan, data: Vector[Order]): EvalResult =
    val selected = data.filter(o => plan.filters.forall(passes(o, _)) && inRange(o, plan.timeRange))
    if plan.groupBy.isEmpty then
      val value = aggregate(selected, plan)
      EvalResult(formatNumber(value, plan.agg), Some(value))
    else
      val groupColumns = plan.groupBy.flatMap(find)
      val descending   = plan.sort.forall(_.descending) // default: largest first
      val groups       = selected
        .groupBy(o => groupColumns.map(_.text(o)).mkString(" / "))
        .toVector
        .map((name, rows) => name -> aggregate(rows, plan))
        // The group name breaks a tie in the two directions, so the first group does not depend on the map order.
        .sortBy((name, value) => (if descending then -value else value, name))
      groups.headOption match
        case None                => EvalResult("(no rows)", None)
        case Some((name, value)) => plan.answerKind match
            case AnswerKind.Category => EvalResult(name, Some(value))
            case AnswerKind.Number   => EvalResult(formatNumber(value, plan.agg), Some(value))

  private def aggregate(rows: Vector[Order], plan: QueryPlan): Double =
    lazy val xs = find(aggregateColumn(plan)).flatMap(_.number).fold(Vector.empty[Double])(read => rows.map(read))
    plan.agg match
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

  // ── filters ─────────────────────────────────────────────────────────────────────────────────────────────

  /** A numeric column compares numbers, except for `Contains`. Each other compare is on the lower-case text. */
  private def passes(o: Order, f: Filter): Boolean =
    find(f.column).exists { column =>
      (column.number, numberOf(f.value)) match
        case (Some(read), Some(number)) if f.op != FilterOp.Contains =>
          holds(f.op, read(o), number)(using Ordering.Double.IeeeOrdering)
        case _ => holds(f.op, column.text(o).toLowerCase, f.value.trim.toLowerCase)
    }

  /** The rule of an operator. `passes` gives `Contains` only the text of a column. */
  private def holds[A](op: FilterOp, l: A, r: A)(using order: Ordering[A]): Boolean =
    op match
      case FilterOp.Eq       => order.equiv(l, r)
      case FilterOp.Ne       => !order.equiv(l, r)
      case FilterOp.Gt       => order.gt(l, r)
      case FilterOp.Gte      => order.gteq(l, r)
      case FilterOp.Lt       => order.lt(l, r)
      case FilterOp.Lte      => order.lteq(l, r)
      case FilterOp.Contains => l.toString.contains(r.toString)

  private def inRange(o: Order, range: Option[TimeRange]): Boolean =
    range.forall { tr =>
      find(tr.column).exists { column =>
        val value = column.text(o)
        tr.start.forall(value >= _) && tr.end.forall(value <= _)
      }
    }

  // ── formatting and comparison ───────────────────────────────────────────────────────────────────────────

  /** A number with two decimals, with no dependency on the default locale. The CSV has this text too. */
  private[talk_to_your_data] def decimal(v: Double): String = String.format(Locale.ROOT, "%.2f", Double.box(v))

  /** The text of a number: an integer for a count, else two decimals. */
  def formatNumber(v: Double, agg: Agg): String =
    if agg == Agg.Count then v.round.toString else decimal(v)

  /** True when the two numbers are within a cent, with a small margin for binary rounding: the rule of the sandbox
    * value in [[Agent.verify]].
    */
  def withinCent(a: Double, b: Double): Boolean = math.abs(a - b) <= 0.01 + 1e-6

  /** The rule of the GEPA metric: true when `actual` gives the answer of `gold`. The unrounded values must agree, so a
    * value that only rounds to the gold text does not pass. A gold label must have the same label, ignoring case, and a
    * numeric gold answer must have a numeric answer.
    */
  def sameResult(gold: EvalResult, actual: EvalResult): Boolean =
    val sameValue = (gold.value, actual.value) match
      case (Some(e), Some(a)) => math.abs(e - a) <= 1e-9 * math.max(1.0, math.abs(e))
      case (None, None)       => true
      case _                  => false
    val sameAnswer =
      if gold.answer.trim.toDoubleOption.isDefined then actual.answer.trim.toDoubleOption.isDefined
      else gold.answer.trim.equalsIgnoreCase(actual.answer.trim)
    sameValue && sameAnswer
