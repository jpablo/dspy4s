package dspy4s.evaluate

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.EvaluationResult
import dspy4s.evaluate.contracts.ExampleEvaluation
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.evaluate.metrics.FunctionMetric
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

class EvaluateSuite extends FunSuite:
  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  override def beforeEach(context: BeforeEach): Unit =
    RuntimeEnvironment.resetForTests()

  override def afterEach(context: AfterEach): Unit =
    RuntimeEnvironment.resetForTests()

  private def ex(values: (String, DynamicValue)*): Example = Example(values*)
  private def pred(values: (String, DynamicValue)*): DynamicPrediction = DynamicPrediction(rec(values*))

  private def scriptedPredict(mappings: Map[String, DynamicPrediction]): Example => Either[dspy4s.core.contracts.DspyError, DynamicPrediction] =
    (ex: Example) =>
      val key = ex.get("question").map(DynamicValues.renderText).getOrElse("")
      mappings.get(key) match
        case Some(p) => Right(p)
        case None    => Right(DynamicPrediction(rec("answer" := "unknown")))

  test("Evaluate runs a program over a dev set and aggregates metric scores as percentage") {
    val dataset = Vector(
      ex("question" := "q1", "answer" := "Paris"),
      ex("question" := "q2", "answer" := "Rome"),
      ex("question" := "q3", "answer" := "Madrid")
    )
    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch())
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(scriptedPredict(Map(
      "q1" -> pred("answer" := "Paris"),
      "q2" -> pred("answer" := "Naples"),
      "q3" -> pred("answer" := "Madrid")
    )))
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.results.size, 3)
    assertEqualsDouble(eval.score, 200.0 / 3.0, 0.0001)
    assertEquals(eval.metricName, "exact_match")
  }

  test("Evaluate threads callbackMetadata into the run scope so callbacks/programs can read it") {
    val seen    = new java.util.concurrent.atomic.AtomicReference[DynamicValue.Record](DynamicValue.Record.empty)
    val dataset = Vector(ex("question" := "q1", "answer" := "Paris"))
    val meta    = rec("run_id" := "abc123")
    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), callbackMetadata = meta)
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator() { (_: Example) =>
      // Runs on a worker thread; the eval scope's callbackMetadata must be visible here (as it is to callbacks).
      seen.set(RuntimeEnvironment.current.callbackMetadata)
      Right(pred("answer" := "Paris"))
    }
    assert(result.isRight, s"got: $result")
    assertEquals(DynamicValues.recordGet(seen.get(), "run_id").map(DynamicValues.renderText), Some("abc123"))
  }

  test("Evaluate with empty devset returns score 0") {
    val evaluator = Evaluate(devset = Vector.empty, metric = new ExactMatch())
    given RuntimeContext = RuntimeEnvironment.current
    val result = evaluator()((_: Example) => Right(DynamicPrediction.empty))
    assert(result.isRight)
    assertEquals(result.toOption.get.score, 0.0)
  }

  test("Evaluate failure_score is used when predict fails") {
    val failingPredict: Example => Either[dspy4s.core.contracts.DspyError, DynamicPrediction] =
      _ => Left(dspy4s.core.contracts.RuntimeError("test", "program failed"))

    val dataset = Vector(ex("question" := "q1", "answer" := "Paris"))
    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), failureScore = 0.0)
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(failingPredict)
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.score, 0.0)
    assertEquals(eval.results.head.score, 0.0)
  }

  test("Evaluate parallel execution preserves devset order") {
    val dataset = (1 to 20).map(i => ex("question" := s"q$i", "answer" := s"a$i")).toVector
    val program: Example => Either[dspy4s.core.contracts.DspyError, DynamicPrediction] = ex =>
      val q = ex.get("question").map(DynamicValues.renderText).getOrElse("")
      Thread.sleep(5)
      Right(DynamicPrediction(rec("answer" := s"a${q.stripPrefix("q")}")))

    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), numThreads = Some(4))
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(program)
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.score, 100.0)
    (0 until 20).foreach { i =>
      assertEquals(
        eval.results(i).example.get("question").map(DynamicValues.renderText),
        Some(s"q${i + 1}")
      )
    }
  }

  test("Evaluate applies displayProgress without side-effect failure") {
    val dataset = Vector(ex("answer" := "x"))
    val program: Example => Either[dspy4s.core.contracts.DspyError, DynamicPrediction] =
      _ => Right(pred("answer" := "x"))

    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch(), displayProgress = true)
    given RuntimeContext = RuntimeEnvironment.current
    val result = evaluator()(program)
    assert(result.isRight)
  }

  test("Evaluate captures the DspyError on a failing example when provideTraceback is set") {
    val failingPredict: Example => Either[dspy4s.core.contracts.DspyError, DynamicPrediction] =
      _ => Left(dspy4s.core.contracts.RuntimeError("test", "boom: program exploded"))

    val dataset = Vector(ex("question" := "q1", "answer" := "Paris"))
    val evaluator = new Evaluate(
      EvaluateConfig(
        devset = dataset,
        metric = new ExactMatch(),
        failureScore = 0.0,
        provideTraceback = true
      )
    )
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(failingPredict)
    assert(result.isRight)
    val eval = result.toOption.get
    val failing = eval.results.head
    assertEquals(failing.score, 0.0)
    assert(failing.error.isDefined, "expected error to be captured on failing example")
    assert(
      failing.error.exists(_.contains("boom: program exploded")),
      s"expected error to contain failure message, got ${failing.error}"
    )
  }

  test("Evaluate does not capture errors when provideTraceback is not set") {
    val failingPredict: Example => Either[dspy4s.core.contracts.DspyError, DynamicPrediction] =
      _ => Left(dspy4s.core.contracts.RuntimeError("test", "boom"))

    val dataset = Vector(ex("question" := "q1", "answer" := "Paris"))
    val evaluator = Evaluate(devset = dataset, metric = new ExactMatch())
    given RuntimeContext = RuntimeEnvironment.current

    val result = evaluator()(failingPredict)
    assert(result.isRight)
    assertEquals(result.toOption.get.results.head.error, None)
  }

  test("EvaluationResult.renderTable produces columns and rows") {
    val results = Vector(
      ExampleEvaluation(ex("question" := "q1", "answer" := "Paris"), pred("answer" := "Paris"), 1.0),
      ExampleEvaluation(ex("question" := "q2", "answer" := "Rome"), pred("answer" := "Naples"), 0.0)
    )
    val evalResult = EvaluationResult(score = 50.0, results = results, metricName = "exact_match")
    val table = evalResult.renderTable()

    assert(table.contains("question"), s"expected 'question' column header, got:\n$table")
    assert(table.contains("answer"), s"expected 'answer' column header, got:\n$table")
    assert(table.contains("score"), s"expected 'score' column header, got:\n$table")
    assert(table.contains("Paris"), s"expected 'Paris' row value, got:\n$table")
    assert(table.contains("Naples"), s"expected prediction 'Naples', got:\n$table")
    assert(table.contains("q2"), s"expected 'q2' row value, got:\n$table")
  }

  test("EvaluationResult.renderTable respects a row limit") {
    val results = (1 to 5).map { i =>
      ExampleEvaluation(ex("question" := s"q$i"), pred("answer" := s"a$i"), 1.0)
    }.toVector
    val evalResult = EvaluationResult(score = 100.0, results = results, metricName = "exact_match")
    val table = evalResult.renderTable(Some(2))

    assert(table.contains("q1"), s"expected 'q1' present, got:\n$table")
    assert(table.contains("q2"), s"expected 'q2' present, got:\n$table")
    assert(!table.contains("q3"), s"expected 'q3' to be omitted by limit, got:\n$table")
  }

  test("Evaluate accepts a FunctionMetric and runs it on each example") {
    val metric = FunctionMetric.bool("lengthy")((_, pred) =>
      pred.get("answer").exists {
        case DynamicValue.Primitive(PrimitiveValue.String(s)) => s.length > 3
        case _                                                => false
      }
    )
    val dataset = Vector(
      ex("answer" := "hi"),
      ex("answer" := "hello world"),
      ex("answer" := "bye")
    )
    val evaluator = Evaluate(devset = dataset, metric = metric)
    given RuntimeContext = RuntimeEnvironment.current
    val result = evaluator()((ex: Example) => Right(DynamicPrediction(ex.values)))
    assert(result.isRight)
    val eval = result.toOption.get
    assertEquals(eval.metricName, "lengthy")
    assertEquals(eval.results.map(_.score).toList, List(0.0, 1.0, 0.0))
  }
