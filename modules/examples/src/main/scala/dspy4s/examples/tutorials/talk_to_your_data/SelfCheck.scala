/** Talk to Your Data: offline self-check (no LM, no Deno).
  *
  * It checks the parts that the gold answer key and the GEPA metric depend on:
  *   - The totals of the engine agree with each other.
  *   - Four gold answers agree with a direct computation from [[Dataset.orders]], with no engine code. So a defect of
  *     the engine or of a gold plan on these paths gives a failure.
  *   - Each gold plan has no problem.
  *   - A JSON text with bare enum strings and nulls decodes to a [[QueryPlan]] and an [[AnalysisResult]]. This is the
  *     decode path of a reply of the model and of an RLM SUBMIT.
  *
  * It does not check the other gold answers against a direct computation, the model, or the sandbox.
  *
  * Run with: sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"
  */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.core.contracts.DynamicValues
import dspy4s.signatures.Shape

object SelfCheck:

  private def orders: Vector[Order] = Dataset.orders

  private def value(plan: QueryPlan): Double =
    QueryEngine.run(plan, orders).toOption.flatMap(_.value).getOrElse(Double.NaN)

  private val categories = Vector("Apparel", "Books", "Electronics", "Grocery", "Home", "Toys")
  private val regions    = Vector("North", "South", "East", "West")

  /** Decode a JSON text as the adapter and RLM do. If this fails, the structured outputs do not parse at runtime. */
  private def decodes[A](shape: Shape[A], json: String, expected: A): Boolean =
    DynamicValues.parseJsonRecord(json).exists(record => shape.decode(record).toOption.contains(expected))

  private val planReply = """{"agg": "Sum", "column": "total", "groupBy": ["category"],
      | "filters": [{"column": "region", "op": "Eq", "value": "West"}],
      | "timeRange": {"column": "date", "start": "2024-01-01", "end": null},
      | "sort": {"descending": true}, "limit": null, "answerKind": "Category"}""".stripMargin

  private val decodedPlan = QueryPlan(
    Agg.Sum,
    Some("total"),
    List("category"),
    List(Filter("region", FilterOp.Eq, "West")),
    Some(TimeRange("date", Some("2024-01-01"), None)),
    Some(Sort(descending = true)),
    None,
    AnswerKind.Category
  )

  private val submitReply = """{"answer": "Electronics", "value": null, "caveats": [], "method": "summed totals"}"""

  private val decodedSubmit = AnalysisResult("Electronics", None, Nil, "summed totals")

  /** The text of the gold answer of `question` must equal `direct`, a text that comes from no engine code. */
  private def agrees(question: String, direct: String): (String, Boolean) =
    val answer = Dataset.goldset.find(_.question == question).map(_.answer(orders))
    s"""gold answer of "$question" is the direct computation (${answer.getOrElse("no such question")} vs $direct)""" ->
      answer.contains(direct)

  private def twoDecimals(x: Double): String = String.format(java.util.Locale.ROOT, "%.2f", Double.box(x))

  private def directMedian(xs: Vector[Double]): Double =
    val sorted = xs.sorted
    val n      = sorted.size
    if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0

  /** The gold answers that this check computes with no engine code. */
  private def directChecks: Vector[(String, Boolean)] =
    val q1Revenue = orders.filter(o => o.date >= "2024-01-01" && o.date <= "2024-03-31").map(_.total).sum
    val lowest    = orders.groupMapReduce(_.category)(_.total)(_ + _).minBy(_._2)._1
    val count     = orders.count(o => o.region == "North" && o.discount >= 0.1)
    Vector(
      agrees("What is the total revenue in Q1 (January to March 2024)?", twoDecimals(q1Revenue)),
      agrees("Which product category has the lowest total revenue?", lowest),
      agrees("How many North-region orders had a discount of at least 0.1?", count.toString),
      agrees("What is the median order total?", twoDecimals(directMedian(orders.map(_.total))))
    )

  /** Returns (label, passed) for each check. */
  def checks(): Vector[(String, Boolean)] =
    val qpShape      = Agent.plannerSignature(Agent.plannerInstructionsBaseline).outputShape
    val arShape      = Agent.executor.baseSignature.outputShape
    val totalRevenue = value(Dataset.num(Agg.Sum, Some("total")))
    val byCategory   = categories.map(c =>
      value(Dataset.num(Agg.Sum, Some("total"), List(Filter("category", FilterOp.Eq, c))))
    ).sum
    val totalCount  = value(Dataset.num(Agg.Count))
    val byRegionCnt = regions.map(r => value(Dataset.num(Agg.Count, None, List(Filter("region", FilterOp.Eq, r))))).sum
    val avg         = value(Dataset.num(Agg.Average, Some("total")))
    val badPlans    = Dataset.goldset.filter(g => QueryEngine.run(g.plan, orders).isLeft).map(_.question)

    Vector(
      s"row count == 10000 (got ${totalCount.toInt})"                             -> (totalCount.toInt == 10000),
      f"revenue by category sums to total ($byCategory%.2f vs $totalRevenue%.2f)" ->
        (math.abs(byCategory - totalRevenue) < 0.5),
      s"order count by region sums to total (${byRegionCnt.toInt} vs ${totalCount.toInt})" ->
        (byRegionCnt.toInt == totalCount.toInt),
      f"average == total / count ($avg%.4f vs ${totalRevenue / totalCount}%.4f)" ->
        (math.abs(avg - totalRevenue / totalCount) < 0.01),
      s"each gold plan has no problem (bad plans: ${badPlans.mkString("; ")})" -> badPlans.isEmpty
    ) ++ directChecks ++ Vector(
      "a JSON reply decodes to a QueryPlan (planner decode path)"    -> decodes(qpShape, planReply, decodedPlan),
      "a JSON SUBMIT decodes to an AnalysisResult (RLM decode path)" -> decodes(arShape, submitReply, decodedSubmit)
    )

@main def tytdSelfCheckMain(): Unit =
  println(s"orders: ${Dataset.orders.size} rows, CSV ${Dataset.csv.length} chars\n")

  println("Checks:")
  val results = SelfCheck.checks()
  results.foreach((label, ok) => println(s"  [${if ok then "PASS" else "FAIL"}] $label"))

  println("\nGold question -> answer (computed by the Scala engine):")
  Dataset.goldset.foreach(g => println(f"  ${g.answer(Dataset.orders)}%-12s  ${g.question}"))

  val allPass = results.forall(_._2)
  println(s"\n${if allPass then "ALL CHECKS PASS" else "SOME CHECKS FAILED"}")
  if !allPass then sys.exit(1)
