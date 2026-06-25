/**
 * Talk to Your Data: the (synthetic, deterministic) dataset and the gold question set.
 *
 * A seeded generator produces ~10k e-commerce orders, so the data (and therefore every gold answer) is
 * reproducible and license-clean to ship. The agent only ever sees the data as CSV text (too big to put in a
 * prompt, which is exactly why the RLM stage writes code to crunch it). The gold answers are computed by the
 * Scala [[QueryEngine]] from a hand-written plan per question, so the answer key is correct *by construction*
 * (no LLM judge). That is the foundation that makes the GEPA optimization measurable.
 */
package dspy4s.examples.tutorials.talk_to_your_data

import java.time.LocalDate

object Dataset:

  private val regions    = Vector("North", "South", "East", "West")
  private val discounts  = Vector(0.0, 0.05, 0.10, 0.15, 0.20)
  private val baseDate   = LocalDate.of(2024, 1, 1)

  /** category -> (product, unit price). Fixed catalog so totals depend only on the seeded quantity/discount. */
  private val catalog: Map[String, Vector[(String, Double)]] = Map(
    "Electronics" -> Vector("Headphones" -> 79.99, "Laptop" -> 1299.00, "Phone" -> 899.00, "Charger" -> 19.99, "Monitor" -> 249.00),
    "Apparel"     -> Vector("T-Shirt" -> 14.99, "Jeans" -> 49.99, "Jacket" -> 89.99, "Sneakers" -> 64.99, "Socks" -> 7.99),
    "Home"        -> Vector("Lamp" -> 34.99, "Cookware Set" -> 119.00, "Towels" -> 24.99, "Vacuum" -> 199.00, "Pillow" -> 29.99),
    "Grocery"     -> Vector("Coffee" -> 12.99, "Olive Oil" -> 9.99, "Cereal" -> 4.49, "Cheese" -> 7.49, "Chocolate" -> 3.99),
    "Toys"        -> Vector("Building Blocks" -> 39.99, "Board Game" -> 24.99, "Doll" -> 19.99, "RC Car" -> 59.99, "Puzzle" -> 12.99),
    "Books"       -> Vector("Novel" -> 14.99, "Cookbook" -> 29.99, "Textbook" -> 89.99, "Comic" -> 9.99, "Notebook" -> 6.99)
  )
  private val categories = catalog.keys.toVector.sorted

  private def roundCents(x: Double): Double = math.round(x * 100.0) / 100.0

  /** Deterministic for a given seed: the order list (and thus every gold answer) is reproducible. */
  def generate(n: Int, seed: Long): Vector[Order] =
    val rng = new scala.util.Random(seed)
    Vector.tabulate(n) { i =>
      val region              = regions(rng.nextInt(regions.size))
      val category            = categories(rng.nextInt(categories.size))
      val (product, unitPrice) = catalog(category)(rng.nextInt(catalog(category).size))
      val quantity            = 1 + rng.nextInt(5)
      val discount            = discounts(rng.nextInt(discounts.size))
      val date                = baseDate.plusDays(rng.nextInt(366)).toString
      Order(i, date, region, category, product, quantity, unitPrice, discount, roundCents(quantity * unitPrice * (1 - discount)))
    }

  /** The canonical dataset used everywhere (gold answers, the live agent, GEPA). */
  lazy val orders: Vector[Order] = generate(10_000, 42L)

  /** The agent's view of the data: CSV text. */
  lazy val csv: String =
    val header = "order_id,date,region,category,product,quantity,unit_price,discount,total"
    val rows = orders.iterator.map { o =>
      f"${o.orderId},${o.date},${o.region},${o.category},${o.product},${o.quantity},${o.unitPrice}%.2f,${o.discount}%.2f,${o.total}%.2f"
    }
    (Iterator(header) ++ rows).mkString("\n")

  /** Column description handed to the planner (so it knows what it can ask for) and to RLM (so it knows the CSV
    * layout). */
  val schemaDescription: String =
    """Table `orders`, one row per order. Columns:
      |- order_id (int)
      |- date (string, ISO yyyy-MM-dd, all in year 2024)
      |- region (string; one of North, South, East, West)
      |- category (string; one of Apparel, Books, Electronics, Grocery, Home, Toys)
      |- product (string)
      |- quantity (int, 1-5)
      |- unit_price (float)
      |- discount (float, one of 0.0, 0.05, 0.10, 0.15, 0.20)
      |- total (float = quantity * unit_price * (1 - discount))""".stripMargin

  /** A question paired with the plan that answers it; the gold answer is whatever the Scala engine computes. */
  final case class GoldQuestion(question: String, plan: QueryPlan):
    def answer(data: Vector[Order]): String = QueryEngine.run(plan, data).answer

  private def num(agg: Agg, column: Option[String] = None, filters: List[Filter] = Nil, timeRange: Option[TimeRange] = None): QueryPlan =
    QueryPlan(agg, column, Nil, filters, timeRange, None, None, AnswerKind.Number)

  private def topGroup(agg: Agg, by: String, answerKind: AnswerKind, column: Option[String] = None, descending: Boolean = true): QueryPlan =
    QueryPlan(agg, column, List(by), Nil, None, Some(Sort("value", descending)), Some(1), answerKind)

  private def f(col: String, op: FilterOp, v: String): Filter = Filter(col, op, v)
  private def q1                                              = Some(TimeRange("date", Some("2024-01-01"), Some("2024-03-31")))
  private def q4                                              = Some(TimeRange("date", Some("2024-10-01"), Some("2024-12-31")))

  /** ~24 questions spanning aggregates, filters, group-by/top-N, time windows, and both answer kinds. */
  val goldset: Vector[GoldQuestion] = Vector(
    GoldQuestion("What is the total revenue across all orders?", num(Agg.Sum, Some("total"))),
    GoldQuestion("What is the average order value?", num(Agg.Average, Some("total"))),
    GoldQuestion("How many orders were placed?", num(Agg.Count)),
    GoldQuestion("What is the total revenue in the West region?", num(Agg.Sum, Some("total"), List(f("region", FilterOp.Eq, "West")))),
    GoldQuestion("Which category has the highest total revenue?", topGroup(Agg.Sum, "category", AnswerKind.Category, Some("total"))),
    GoldQuestion("Which region has the most orders?", topGroup(Agg.Count, "region", AnswerKind.Category)),
    GoldQuestion("What is the total revenue for Electronics?", num(Agg.Sum, Some("total"), List(f("category", FilterOp.Eq, "Electronics")))),
    GoldQuestion("How many orders had a quantity greater than 3?", num(Agg.Count, None, List(f("quantity", FilterOp.Gt, "3")))),
    GoldQuestion("What is the average discount?", num(Agg.Average, Some("discount"))),
    GoldQuestion("What is the highest single-order total?", num(Agg.Max, Some("total"))),
    GoldQuestion("What is the total quantity sold of Books?", num(Agg.Sum, Some("quantity"), List(f("category", FilterOp.Eq, "Books")))),
    GoldQuestion("Which product category has the lowest total revenue?", topGroup(Agg.Sum, "category", AnswerKind.Category, Some("total"), descending = false)),
    GoldQuestion("What is the total revenue in Q1 (January to March 2024)?", num(Agg.Sum, Some("total"), Nil, q1)),
    GoldQuestion("How many North-region orders had a discount of at least 0.1?", num(Agg.Count, None, List(f("region", FilterOp.Eq, "North"), f("discount", FilterOp.Gte, "0.1")))),
    GoldQuestion("What is the average order total for Grocery?", num(Agg.Average, Some("total"), List(f("category", FilterOp.Eq, "Grocery")))),
    GoldQuestion("Which region generated the highest revenue?", topGroup(Agg.Sum, "region", AnswerKind.Category, Some("total"))),
    GoldQuestion("What is the total revenue from orders over $200?", num(Agg.Sum, Some("total"), List(f("total", FilterOp.Gt, "200")))),
    GoldQuestion("What is the median order total?", num(Agg.Median, Some("total"))),
    GoldQuestion("What is the total revenue for Apparel in the South region?", num(Agg.Sum, Some("total"), List(f("category", FilterOp.Eq, "Apparel"), f("region", FilterOp.Eq, "South")))),
    GoldQuestion("How many orders were placed in December 2024?", num(Agg.Count, None, Nil, Some(TimeRange("date", Some("2024-12-01"), Some("2024-12-31"))))),
    GoldQuestion("What is the maximum order total in the Toys category?", num(Agg.Max, Some("total"), List(f("category", FilterOp.Eq, "Toys")))),
    GoldQuestion("Which category has the highest average order value?", topGroup(Agg.Average, "category", AnswerKind.Category, Some("total"))),
    GoldQuestion("What is the total revenue for Home in Q4?", num(Agg.Sum, Some("total"), List(f("category", FilterOp.Eq, "Home")), q4)),
    GoldQuestion("How many orders had no discount?", num(Agg.Count, None, List(f("discount", FilterOp.Eq, "0"))))
  )
