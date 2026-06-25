/**
 * Talk to Your Data — offline self-check (no LM, no Deno).
 *
 * Proves the dataset + Scala query engine are internally consistent before any model is involved: the parts that
 * the gold answer key and the GEPA metric depend on. Run it any time to sanity-check the foundation.
 *
 * Run with: sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"
 */
package dspy4s.examples.tutorials.talk_to_your_data

object SelfCheck:

  private def value(plan: QueryPlan): Double = QueryEngine.run(plan, Dataset.orders).value.getOrElse(Double.NaN)

  private val categories = Vector("Apparel", "Books", "Electronics", "Grocery", "Home", "Toys")
  private val regions    = Vector("North", "South", "East", "West")

  /** Round-trip a value through its signature Shape — the exact decode path the planner output and the RLM
    * SUBMIT rely on. If these fail, the structured outputs won't parse at runtime (so catch it offline). */
  private def roundTrips[A](shape: dspy4s.typed.Shape[A], value: A): Boolean =
    shape.decode(shape.encode(value)).toOption.map(_.toString).contains(value.toString)

  private val sampleQp = QueryPlan(Agg.Sum, Some("total"), List("category"),
    List(Filter("region", FilterOp.Eq, "West")),
    Some(TimeRange("date", Some("2024-01-01"), Some("2024-03-31"))),
    Some(Sort("value", descending = true)), Some(3), AnswerKind.Category)

  private val sampleAr = AnalysisResult("Electronics", Some(123.45), List("assumed full-year data"), "summed totals by category")

  /** Returns (label, passed) for each invariant the engine must satisfy. */
  def checks(): Vector[(String, Boolean)] =
    val qpShape = Agent.plannerSignature(Agent.plannerInstructionsBaseline).outputShape
    val arShape = Agent.executor.baseSignature.outputShape
    val totalRevenue = value(QueryPlan(Agg.Sum, Some("total"), Nil, Nil, None, None, None, AnswerKind.Number))
    val byCategory   = categories.map(c => value(QueryPlan(Agg.Sum, Some("total"), Nil, List(Filter("category", FilterOp.Eq, c)), None, None, None, AnswerKind.Number))).sum
    val totalCount   = value(QueryPlan(Agg.Count, None, Nil, Nil, None, None, None, AnswerKind.Number))
    val byRegionCnt  = regions.map(r => value(QueryPlan(Agg.Count, None, Nil, List(Filter("region", FilterOp.Eq, r)), None, None, None, AnswerKind.Number))).sum
    val avg          = value(QueryPlan(Agg.Average, Some("total"), Nil, Nil, None, None, None, AnswerKind.Number))

    Vector(
      s"row count == 10000 (got ${totalCount.toInt})"                                              -> (totalCount.toInt == 10000),
      f"revenue by category sums to total ($byCategory%.2f vs $totalRevenue%.2f)"                  -> (math.abs(byCategory - totalRevenue) < 0.5),
      s"order count by region sums to total (${byRegionCnt.toInt} vs ${totalCount.toInt})"         -> (byRegionCnt.toInt == totalCount.toInt),
      f"average == total / count ($avg%.4f vs ${totalRevenue / totalCount}%.4f)"                   -> (math.abs(avg - totalRevenue / totalCount) < 0.01),
      "QueryPlan round-trips through its Schema (planner decode path)"                              -> roundTrips(qpShape, sampleQp),
      "AnalysisResult round-trips through its Schema (RLM SUBMIT decode path)"                      -> roundTrips(arShape, sampleAr)
    )

@main def tytdSelfCheckMain(): Unit =
  println(s"orders: ${Dataset.orders.size} rows, CSV ${Dataset.csv.length} chars\n")

  println("Invariants:")
  val results = SelfCheck.checks()
  results.foreach((label, ok) => println(s"  [${if ok then "PASS" else "FAIL"}] $label"))

  println("\nGold question -> answer (computed by the Scala engine):")
  Dataset.goldset.foreach(g => println(f"  ${g.answer(Dataset.orders)}%-12s  ${g.question}"))

  val allPass = results.forall(_._2)
  println(s"\n${if allPass then "ALL INVARIANTS PASS" else "SOME INVARIANTS FAILED"}")
  if !allPass then sys.exit(1)
