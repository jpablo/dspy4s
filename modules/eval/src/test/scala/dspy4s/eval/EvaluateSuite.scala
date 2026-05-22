package dspy4s.eval

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ExampleData
import dspy4s.core.contracts.Prediction
import dspy4s.core.contracts.PredictionData
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.eval.contracts.ExampleEvaluation
import dspy4s.eval.metrics.AnswerMatch
import dspy4s.eval.metrics.ExactMatch
import dspy4s.eval.metrics.FunctionMetric
import munit.FunSuite

class EvaluateSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private def ex(values: (String, Any)*): ExampleData = ExampleData(values.toMap)
  private def pred(values: (String, Any)*): PredictionData = PredictionData(values.toMap)

  private def scriptedPredict(mappings: Map[String, Prediction]): Example => Either[dspy4s.core.contracts.DspyError, Prediction] =
    (ex: Example) =>
      val key = ex.get("question").map(_.toString).getOrElse("")
      mappings.get(key) match
        case Some(p) => Right(p)
        case None    => Right(PredictionData(Map("answer" -> "unknown")))

  test("Evaluate runs a program over a dev set and aggregates metric scores as percentage") {
    val dataset = Vector(
      ex("question" -> "q1", "answer" -> "Paris"),
      ex("question" -> "q2", "answer" -> "Rome"),
      ex("question" -> "q3", "answer" -> "Madrid")
    )
    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch())
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(scriptedPredict(Map(
      "q1" -> pred("answer" -> "Paris"),
      "q2" -> pred("answer" -> "Naples"),
      "q3" -> pred("answer" -> "Madrid")
    )))
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.results.size, 3)
    assertEqualsDouble(eval.score, 200.0 / 3.0, 0.0001)
    assertEquals(eval.metricName, "exact_match")
  }

  test("Evaluate with empty devset returns score 0") {
    val evaluator = Evaluate(devset = Vector.empty, metric = new ExactMatch())
    given RuntimeContext = RuntimeEnvironment.current
    val result = evaluator()((_: Example) => Right(PredictionData.empty))
    assert(result.isRight)
    assertEquals(result.toOption.get.score, 0.0)
  }

  test("Evaluate failure_score is used when predict fails") {
    val failingPredict: Example => Either[dspy4s.core.contracts.DspyError, Prediction] =
      _ => Left(dspy4s.core.contracts.RuntimeError("test", "program failed"))

    val dataset = Vector(ex("question" -> "q1", "answer" -> "Paris"))
    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), failureScore = 0.0)
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(failingPredict)
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.score, 0.0)
    assertEquals(eval.results.head.score, 0.0)
  }

  test("Evaluate parallel execution preserves devset order") {
    val dataset = (1 to 20).map(i => ex("question" -> s"q$i", "answer" -> s"a$i")).toVector
    val program: Example => Either[dspy4s.core.contracts.DspyError, Prediction] = ex =>
      val q = ex.get("question").map(_.toString).getOrElse("")
      Thread.sleep(5)
      Right(PredictionData(Map("answer" -> s"a${q.stripPrefix("q")}")))

    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), numThreads = Some(4))
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(program)
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.score, 100.0)
    (0 until 20).foreach { i =>
      assertEquals(eval.results(i).example.values("question"), s"q${i + 1}")
    }
  }

  test("Evaluate applies displayProgress without side-effect failure") {
    val dataset = Vector(ex("answer" -> "x"))
    val program: Example => Either[dspy4s.core.contracts.DspyError, Prediction] =
      _ => Right(pred("answer" -> "x"))

    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), displayProgress = true)
    given RuntimeContext = RuntimeEnvironment.current
    val result = evaluator()(program)
    assert(result.isRight)
  }

  test("Evaluate accepts a FunctionMetric and runs it on each example") {
    val metric = FunctionMetric.bool("lengthy")((_, pred) =>
      pred.get("answer").exists { case s: String => s.length > 3; case _ => false }
    )
    val dataset = Vector(
      ex("answer" -> "hi"),
      ex("answer" -> "hello world"),
      ex("answer" -> "bye")
    )
    val evaluator = Evaluate(devset = dataset, metric = metric)
    given RuntimeContext = RuntimeEnvironment.current
    val result = evaluator()((ex: Example) => Right(PredictionData(ex.values)))
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.metricName, "lengthy")
    assertEquals(eval.results.map(_.score).toList, List(0.0, 1.0, 0.0))
  }
