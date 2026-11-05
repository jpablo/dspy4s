package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, RuntimeError}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.gepa.contracts.ScoreWithFeedback
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.ProgramRunner
import dspy4s.programs.strategies.Predict
import munit.FunSuite

import java.util.Locale

/** The engine is the oracle of the demo, so each expected value here comes directly from [[Dataset.orders]], with no
  * engine code.
  */
class QueryEngineSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  private def orders: Vector[Order] = Dataset.orders

  private def plan(agg: Agg, column: Option[String], filters: Filter*): QueryPlan =
    QueryPlan(agg, column, Nil, filters.toList, None, None, None, AnswerKind.Number)

  private def grouped(agg: Agg, column: Option[String], by: String, descending: Boolean): QueryPlan =
    QueryPlan(agg, column, List(by), Nil, None, Some(Sort(descending)), Some(1), AnswerKind.Category)

  private def resultOf(query: QueryPlan): QueryEngine.EvalResult =
    QueryEngine.run(query, orders).fold(problem => fail(problem), identity)

  private def valueOf(query: QueryPlan): Double = resultOf(query).value.getOrElse(Double.NaN)

  private def answerOf(query: QueryPlan): String = resultOf(query).answer

  /** The problem that the engine reports for `query`. */
  private def problemOf(query: QueryPlan): Option[String] = QueryEngine.run(query, orders).left.toOption

  private def assertProblem(query: QueryPlan, names: String*): Unit =
    val problem = problemOf(query).getOrElse(fail(s"the engine accepted $query"))
    names.foreach(name => assert(problem.contains(name), problem))

  private def medianOf(xs: Vector[Double]): Double =
    val sorted = xs.sorted
    val n      = sorted.size
    if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2.0

  // ── columns ──

  test("the column names of the schema read their own columns") {
    assertEquals(valueOf(plan(Agg.Max, Some("order_id"))), orders.map(_.orderId).max.toDouble)
    assertEquals(valueOf(plan(Agg.Median, Some("unit_price"))), medianOf(orders.map(_.unitPrice)))
    assertEqualsDouble(
      valueOf(plan(Agg.Average, Some("unit_price"))),
      orders.map(_.unitPrice).sum / orders.size,
      1e-6
    )
  }

  test("a filter on a column name of the schema reads that column") {
    assertEquals(
      valueOf(plan(Agg.Count, None, Filter("unit_price", FilterOp.Gt, "100"))),
      orders.count(_.unitPrice > 100).toDouble
    )
    assertEquals(valueOf(plan(Agg.Count, None, Filter("order_id", FilterOp.Lt, "100"))), 100.0)
  }

  test("the engine knows each column of the CSV header") {
    val header = Dataset.csv.linesIterator.next().split(",").toVector
    assertEquals(header.size, 9)
    header.foreach(name => assertEquals(problemOf(plan(Agg.Count, None, Filter(name, FilterOp.Contains, "1"))), None))
  }

  test("a plan with an unknown column is a problem that names the column and the valid columns") {
    val valid = Vector("order_id", "unit_price", "total")
    assertProblem(plan(Agg.Sum, Some("revenue")), ("revenue" +: valid)*)
    assertProblem(plan(Agg.Count, None, Filter("revenue", FilterOp.Eq, "1")), ("revenue" +: valid)*)
    assertProblem(grouped(Agg.Count, None, "revenue", descending = true), ("revenue" +: valid)*)
    assertProblem(
      QueryPlan(
        Agg.Count,
        None,
        Nil,
        Nil,
        Some(TimeRange("revenue", Some("2024-01-01"), None)),
        None,
        None,
        AnswerKind.Number
      ),
      ("revenue" +: valid)*
    )
  }

  test("an aggregate of a text column is a problem") {
    assertProblem(plan(Agg.Sum, Some("region")), "region")
  }

  test("a value that is not a number on a numeric column is a problem") {
    assertProblem(plan(Agg.Count, None, Filter("total", FilterOp.Gt, "abc")), "total", "abc")
  }

  test("a count reads no column, so the column of a count is not a problem") {
    assertEquals(problemOf(plan(Agg.Count, Some("*"))), None)
    assertEquals(valueOf(plan(Agg.Count, Some("*"))), orders.size.toDouble)
  }

  // ── filters ──

  test("an ordering operator compares a date") {
    assertEquals(
      valueOf(plan(Agg.Count, None, Filter("date", FilterOp.Gte, "2024-12-01"))),
      orders.count(_.date >= "2024-12-01").toDouble
    )
    val q1 = orders.filter(o => o.date >= "2024-01-01" && o.date <= "2024-03-31").map(_.total).sum
    assertEqualsDouble(
      valueOf(plan(
        Agg.Sum,
        Some("total"),
        Filter("date", FilterOp.Gte, "2024-01-01"),
        Filter("date", FilterOp.Lte, "2024-03-31")
      )),
      q1,
      1e-6
    )
  }

  test("an ordering operator on a label does not become equality") {
    assertEquals(valueOf(plan(Agg.Count, None, Filter("region", FilterOp.Gt, "West"))), 0.0)
    assertEquals(
      valueOf(plan(Agg.Count, None, Filter("region", FilterOp.Lt, "West"))),
      orders.count(_.region != "West").toDouble
    )
  }

  // ── groups ──

  test("a tie at the top takes the first group label, in the two sort directions") {
    val top     = orders.map(_.total).max
    val bottom  = orders.map(_.total).min
    val atTop   = orders.filter(_.total == top).map(_.region).distinct.sorted
    val regions = orders.groupBy(_.region).view.mapValues(_.map(_.total).max).toMap
    assert(atTop.size > 1, s"the data has no tie: $atTop")
    assertEquals(answerOf(grouped(Agg.Max, Some("total"), "region", descending = true)), atTop.head)
    val lowest = regions.values.min
    assertEquals(
      answerOf(grouped(Agg.Max, Some("total"), "region", descending = false)),
      regions.collect { case (region, max) if max == lowest => region }.toVector.sorted.head
    )
    assert(bottom <= lowest)
  }

  test("the number text does not depend on the default locale") {
    val original = Locale.getDefault
    Locale.setDefault(Locale.GERMANY)
    try assertEquals(QueryEngine.formatNumber(1234.5, Agg.Sum), "1234.50")
    finally Locale.setDefault(original)
  }

  // ── the metric ──

  private def goldExample(question: String): Example = (Optimize.trainset ++ Optimize.valset)
    .find(_.get("question").map(DynamicValues.renderText).contains(question))
    .getOrElse(fail(s"no gold question: $question"))

  private def scoreOf(question: String, query: QueryPlan): ScoreWithFeedback =
    val shape      = Agent.plannerSignature(Agent.plannerInstructionsBaseline).outputShape
    val prediction = RawPrediction(values = shape.encode(query))
    Optimize.metric.feedback(goldExample(question), prediction, Vector.empty, None, Vector.empty)
      .fold(error => fail(error.message), identity)

  test("each gold plan gets a score of 1") {
    Dataset.goldset.foreach(gold => assertEquals(scoreOf(gold.question, gold.plan).score, 1.0, gold.question))
  }

  test("a plan that reads another column gets a score of 0") {
    assertEquals(scoreOf("What is the highest single-order total?", plan(Agg.Max, Some("order_id"))).score, 0.0)
    assertEquals(scoreOf("What is the median order total?", plan(Agg.Median, Some("unit_price"))).score, 0.0)
  }

  test("a plan that compares a label with an ordering operator gets a score of 0") {
    val query = plan(Agg.Sum, Some("total"), Filter("region", FilterOp.Gt, "West"))
    assertEquals(scoreOf("What is the total revenue in the West region?", query).score, 0.0)
  }

  test("a plan with the correct label and another aggregate gets a score of 0") {
    val query = grouped(Agg.Sum, Some("total"), "category", descending = true)
    assertEquals(scoreOf("Which category has the highest average order value?", query).score, 0.0)
  }

  test("a plan with a value that rounds to the gold text gets a score of 0") {
    assertEquals(scoreOf("What is the average discount?", plan(Agg.Median, Some("discount"))).score, 0.0)
  }

  test("a correct plan with date filters gets a score of 1") {
    val query = plan(
      Agg.Sum,
      Some("total"),
      Filter("date", FilterOp.Gte, "2024-01-01"),
      Filter("date", FilterOp.Lte, "2024-03-31")
    )
    assertEquals(scoreOf("What is the total revenue in Q1 (January to March 2024)?", query).score, 1.0)
  }

  test("a plan with an unknown column gets a score of 0, and the feedback names the valid columns") {
    val result = scoreOf("What is the median order total?", plan(Agg.Median, Some("revenue")))
    assertEquals(result.score, 0.0)
    assert(result.feedback.contains("revenue"), result.feedback)
    assert(result.feedback.contains("unit_price"), result.feedback)
  }

  // ── the cross-check of the agent ──

  private def totalRevenue: Double = orders.map(_.total).sum

  private def sandbox(value: Double): AnalysisResult = AnalysisResult(f"$value%.2f", Some(value), Nil, "code")

  test("verify accepts a sandbox value within a cent") {
    assert(Agent.verify(plan(Agg.Sum, Some("total")), sandbox(totalRevenue + 0.005)).ok)
  }

  test("verify rejects a sandbox value that is more than a cent away") {
    assert(!Agent.verify(plan(Agg.Sum, Some("total")), sandbox(totalRevenue + 0.02)).ok)
  }

  test("the issue of a mismatch does not give the JVM answer to the sandbox") {
    val query   = plan(Agg.Sum, Some("total"))
    val verdict = Agent.verify(query, sandbox(1.0))
    assert(!verdict.ok)
    val jvmAnswer = answerOf(query)
    verdict.issues.foreach(issue => assert(!issue.contains(jvmAnswer), issue))
  }

  test("verify rejects a plan with an unknown column, and names the column") {
    val verdict = Agent.verify(plan(Agg.Sum, Some("revenue")), sandbox(totalRevenue))
    assert(!verdict.ok)
    assert(verdict.issues.exists(_.contains("revenue")), verdict.issues.toString)
  }

  // ── the program that GEPA tunes ──

  private def countReply =
    """{"agg": "Count", "column": null, "groupBy": [], "filters": [], "timeRange": null, "sort": null, "limit": null, "answerKind": "Number"}"""

  private final class Script(reply: LmRequest => Either[DspyError, String]) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "script"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests = requests :+ request }
      reply(request).map(text => LmResponse(outputs = Vector(LmOutput(text))))

  private def contextOf(lm: LanguageModel): RuntimeContext =
    RuntimeContext(lm = Some(lm), adapter = Some(JSONAdapter()))

  test("GEPA tunes the same prompt that the agent runs") {
    val lm       = Script(_ => Right(countReply))
    val question = "How many orders were placed?"
    val _        = Agent.plan(question, "Plan the query.")(using contextOf(lm))
    val inputs   = DynamicValues.record(
      "question" -> DynamicValues.string(question),
      "schema"   -> DynamicValues.string(Dataset.schemaDescription)
    )
    val runner = summon[ProgramRunner[Predict[Question, QueryPlan]]]
    val _      = runner.run(Agent.planner("Plan the query."), inputs)(using contextOf(lm))
    assertEquals(lm.requests.size, 2)
    assertEquals(lm.requests(1).messages, lm.requests(0).messages)
    assertEquals(lm.requests(1).options, lm.requests(0).options)
  }

  test("the planner sends no temperature, because a reasoning model rejects the option") {
    val lm = Script(_ => Right(countReply))
    val _  = Agent.plan("How many orders were placed?", "Plan the query.")(using contextOf(lm))
    val _  = Optimize.accuracy("Plan the query.", Optimize.trainset.take(1))(using contextOf(lm))
    lm.requests.foreach(request => assertEquals(DynamicValues.recordGet(request.options, "temperature"), None))
  }

  /** The errors of the examples that the accuracy reports. */
  private def accuracyErrors(lm: LanguageModel): Vector[String] =
    Optimize.accuracy("Plan the query.", Optimize.valset)(using contextOf(lm))
      .fold(error => fail(error.message), _.errors)

  test("the accuracy reports the failed calls of the model, and does not show them as wrong plans") {
    val lm = Script(_ => Left(RuntimeError("script", "rate limited")))
    assert(accuracyErrors(lm).exists(_.contains("rate limited")))
  }

  test("the agent stops at a plan with a problem, and the sandbox does not run") {
    val reply =
      """{"agg": "Sum", "column": "revenue", "groupBy": [], "filters": [], "timeRange": null, "sort": null, "limit": null, "answerKind": "Number"}"""
    val lm     = Script(_ => Right(reply))
    val result = Agent.ask("What is the total revenue?")(using contextOf(lm))
    assert(result.left.exists(_.message.contains("revenue")), result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("GEPA runs on the planner with a scripted model") {
    val task       = Script(_ => Right(countReply))
    val reflection = Script(_ => Right("```\nCount the orders.\n```"))
    val report     = Optimize.run(reflectionLm = reflection, budget = 30, minibatch = 2)(using contextOf(task))
      .fold(error => fail(error.message), identity)
    assert(report.numCandidates >= 1)
    assertEquals(report.baselineAccuracy.score, 0.0)
  }

  test("the self check passes") {
    SelfCheck.checks().foreach((label, ok) => assert(ok, label))
  }
