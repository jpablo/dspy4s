package dspy4s.evaluate

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  ErrorLimit,
  NotFoundError,
  RuntimeContext,
  RuntimeError,
  ThreadCount,
  TraceEntry,
  :=
}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.{EvaluationResult, ExampleEvaluation}
import dspy4s.evaluate.metrics.{
  CompleteAndGrounded,
  ExactMatch,
  F1Score,
  FunctionMetric,
  NormalizeText,
  PassageMatch,
  SemanticF1
}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

/** The rules of upstream `dspy/evaluate/` and `dspy/dsp/utils/dpr.py` that the port did not follow. */
class EvaluateUpstreamRulesSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private given RuntimeContext = RuntimeContext()

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  private def example(entries: (String, DynamicValue)*): Example =
    Example(rec(entries*), inputKeys = entries.headOption.map(_._1).toSet)

  private def prediction(entries: (String, DynamicValue)*): RawPrediction = RawPrediction(values = rec(entries*))

  // ── normalize_text (`dspy/evaluate/metrics.py`) ───────────────────────────────────────────────────────────────────

  test("normalize_text deletes punctuation, as upstream") {
    assertEquals(NormalizeText("U.S."), "us")
    assertEquals(NormalizeText("don't"), "dont")
    assertEquals(NormalizeText("The,  Eiffel  Tower!"), "eiffel tower")
  }

  test("normalize_text keeps the NFD marks, as upstream") {
    assertEquals(NormalizeText("Café"), "café")
    assertNotEquals(NormalizeText("Café"), NormalizeText("Cafe"))
  }

  test("normalize_text removes only whole articles") {
    assertEquals(NormalizeText("an apple and the pear"), "apple and pear")
    assertEquals(NormalizeText("theater"), "theater")
  }

  test("ExactMatch and F1 follow normalize_text") {
    val truth = example("answer" := "U.S.")
    assertEquals(new ExactMatch().score(truth, prediction("answer" := "US")), Right(1.0))
    assertEquals(new F1Score().score(truth, prediction("answer" := "U S")), Right(0.0))
  }

  // ── A null value ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("a null answer is a Left, not an exception") {
    val truth = example("answer" := "a")
    assert(new ExactMatch().score(truth, prediction("answer" -> DynamicValue.Null)).isLeft)
    assert(new ExactMatch().score(example("answer" -> DynamicValue.Null), prediction("answer" := "a")).isLeft)
  }

  test("a judged metric gives a null field to the judge, not an exception") {
    val lm = new Lm(_ => chat("reasoning" -> "r", "recall" -> "1.0", "precision" -> "1.0"))
    withLm(lm) {
      val result = SemanticF1().score(
        example("question"    := "q", "response" := "r"),
        prediction("response" -> DynamicValue.Null)
      )
      assertEquals(result, Right(1.0))
    }
  }

  // ── has_answer (`dspy/dsp/utils/dpr.py`) ──────────────────────────────────────────────────────────────────────────

  private def passageScore(answer: String, passages: String*): Either[DspyError, Double] =
    new PassageMatch().score(
      example("answer"     := answer),
      prediction("context" -> DynamicValues.sequence(passages.toVector.map(DynamicValues.string)))
    )

  test("PassageMatch compares token sequences, not substrings") {
    assertEquals(passageScore("cat", "concatenate"), Right(0.0))
    assertEquals(passageScore("cat", "the cat sat"), Right(1.0))
    assertEquals(passageScore("Eiffel Tower", "The Eiffel Tower is in Paris."), Right(1.0))
    assertEquals(passageScore("Eiffel Tower", "A tower in Paris named Eiffel"), Right(0.0))
  }

  test("PassageMatch with no passages scores 0.0, as upstream `any` over an empty list") {
    assertEquals(passageScore("cat"), Right(0.0))
  }

  test("PassageMatch splits symbols into tokens of one character, as the tokenizer of upstream") {
    assertEquals(passageScore("cat", "«cat»"), Right(1.0))
    assertEquals(passageScore("cat", "«cats»"), Right(0.0))
  }

  // ── The score and the failures of Evaluate (`dspy/evaluate/evaluate.py`, `dspy/utils/parallelizer.py`) ────────────

  private def truthy = FunctionMetric.bool("m")((_, pred) => pred.get("ok").contains(DynamicValues.string("y")))

  test("the score of Evaluate is round(100 * ncorrect / ntotal, 2)") {
    val devset = Vector(example("q" := "1"), example("q" := "2"), example("q" := "3"))
    val runs   = Iterator("y", "y", "n")
    val result =
      Evaluate(devset, truthy, numThreads = Some(ThreadCount(1)))()(_ => Right(prediction("ok" := runs.next())))
    assertEquals(result.map(_.score), Right(66.67))
  }

  test("a failed example writes its cause, and its result holds the cause, as upstream logs each error") {
    val captured = new ByteArrayOutputStream
    val result   = Console.withErr(captured) {
      Evaluate(Vector(example("q" := "1")), truthy)()(_ => Left(RuntimeError("program", "the model is down")))
    }
    assertEquals(result.map(_.results.head.error.exists(_.contains("the model is down"))), Right(true))
    assert(captured.toString.contains("the model is down"), captured.toString)
  }

  test("a table with no rows is no table, as upstream reads 0 as false") {
    val printed = new ByteArrayOutputStream
    val result  = Console.withOut(printed) {
      Evaluate(Vector(example("q" := "1")), truthy, displayTable = TableDisplay.Rows(0))()(_ =>
        Right(prediction("ok" := "y"))
      )
    }
    assert(result.isRight, result.toString)
    assertEquals(printed.toString, "")
  }

  test("the limit of errors writes the cause of each failure") {
    val captured = new ByteArrayOutputStream
    val result   = Console.withErr(captured) {
      Evaluate(Vector(example("q" := "1")), truthy, maxErrors = Some(ErrorLimit(1)))()(_ =>
        Left(RuntimeError("program", "the model is down"))
      )
    }
    assert(result.isLeft, result.toString)
    assert(captured.toString.contains("the model is down"), captured.toString)
  }

  // ── The saved rows (`merge_dicts`, `_prepare_results_output`) ─────────────────────────────────────────────────────

  private def resultOf(evaluations: ExampleEvaluation*): EvaluationResult =
    EvaluationResult(score = 50.0, results = evaluations.toVector, metricName = "m")

  private def saved(save: (EvaluationResult, String) => Either[String, Unit], result: EvaluationResult): String =
    val path = Files.createTempFile("evaluate", ".out")
    try
      assertEquals(save(result, path.toString), Right(()))
      Files.readString(path)
    finally Files.deleteIfExists(path): Unit

  test("the saved JSON has the rows of upstream merge_dicts, in the form of json.dump") {
    val text = saved(
      EvaluationResultPersistence.saveAsJson,
      resultOf(ExampleEvaluation(
        example("question"  := "café", "answer" := "a", "note" -> DynamicValue.Null),
        prediction("answer" := "b", "extra"     := 1),
        0.5
      ))
    )
    assertEquals(
      text,
      "[{\"question\": \"caf\\u00e9\", \"example_answer\": \"a\", \"note\": null, \"pred_answer\": \"b\", " +
        "\"extra\": 1, \"m\": 0.5}]"
    )
  }

  test("the saved CSV has the rows of upstream, also for a field with a prefix of the header") {
    val text = saved(
      EvaluationResultPersistence.saveAsCsv,
      resultOf(ExampleEvaluation(
        example("pred_x" := "from example", "example_y" := "also", "note" -> DynamicValue.Null),
        prediction("z"   := "from prediction"),
        0.5
      ))
    )
    assertEquals(
      text,
      "pred_x,example_y,note,z,m\r\nfrom example,also,,from prediction,0.5\r\n"
    )
  }

  test("the metric value wins over a field with the name of the metric, as upstream `|`") {
    val text = saved(
      EvaluationResultPersistence.saveAsCsv,
      resultOf(ExampleEvaluation(example("m" := "field"), RawPrediction.empty, 0.5))
    )
    assertEquals(text, "m\r\n0.5\r\n")
  }

  // ── The table (`_display_result_table`, `truncate_cell`) ──────────────────────────────────────────────────────────

  test("a table cell shows the field, also for a field named score or error") {
    val table = resultOf(
      ExampleEvaluation(example("score" := "field score", "error" := "field error"), RawPrediction.empty, 0.5)
    ).renderTable()
    assert(table.contains("field score"), table)
    assert(table.contains("field error"), table)
  }

  test("a table cell has at most 25 words, and the table says how many rows it hides") {
    val long  = Vector.tabulate(30)(index => s"w$index").mkString(" ")
    val rows  = Vector.fill(3)(ExampleEvaluation(example("q" := long), RawPrediction.empty, 0.5))
    val table = resultOf(rows*).renderTable(Some(1))
    assert(table.contains("w24..."), table)
    assert(!table.contains("w25"), table)
    assert(table.contains("... 2 more rows not displayed ..."), table)
  }

  // ── The judged metrics (`dspy/evaluate/auto_evaluation.py`) ───────────────────────────────────────────────────────

  private final class Lm(reply: LmRequest => String) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty
    override val id: String              = "judge"
    override val mode: LmMode            = LmMode.Chat

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = requests.synchronized(requests += request)
      Right(LmResponse(outputs = Vector(LmOutput(text = reply(request)))))

  private def chat(fields: (String, String)*): String =
    fields.map((name, value) => s"[[ ## $name ## ]]\n$value").mkString("", "\n\n", "\n\n[[ ## completed ## ]]")

  private def withLm[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def text(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def semanticExample    = example("question" := "q", "response" := "truth")
  private def semanticPrediction =
    prediction(
      "response" := "r",
      "context"  -> DynamicValues.sequence(
        Vector(DynamicValues.string("first passage"), DynamicValues.string("second passage"))
      )
    )
  private def aTrace = Vector(TraceEntry("predict", DynamicValue.Record.empty, DynamicValue.Record.empty))

  test("with a trace, SemanticF1 gives the result of its threshold, as upstream `score >= self.threshold`") {
    val lm = new Lm(_ => chat("reasoning" -> "r", "recall" -> "0.5", "precision" -> "0.5"))
    withLm(lm) {
      assertEquals(SemanticF1(threshold = 0.66).score(semanticExample, semanticPrediction), Right(0.5))
      assertEquals(SemanticF1(threshold = 0.66).score(semanticExample, semanticPrediction, aTrace), Right(0.0))
      assertEquals(SemanticF1(threshold = 0.4).score(semanticExample, semanticPrediction, aTrace), Right(1.0))
    }
  }

  test("with a trace, CompleteAndGrounded gives the result of its threshold") {
    val lm = new Lm(request =>
      if text(request).contains("[[ ## completeness ## ]]") then
        chat(
          "reasoning"                 -> "r",
          "ground_truth_key_ideas"    -> "i",
          "system_response_key_ideas" -> "i",
          "discussion"                -> "d",
          "completeness"              -> "0.5"
        )
      else chat("reasoning" -> "r", "system_response_claims" -> "c", "discussion" -> "d", "groundedness" -> "0.5")
    )
    withLm(lm) {
      assertEquals(CompleteAndGrounded(threshold = 0.66).score(semanticExample, semanticPrediction), Right(0.5))
      assertEquals(CompleteAndGrounded(threshold = 0.66).score(semanticExample, semanticPrediction, aTrace), Right(0.0))
    }
  }

  test("the judge prompts have the lines of the upstream docstrings") {
    val lm = new Lm(_ => chat("reasoning" -> "r", "recall" -> "1.0", "precision" -> "1.0"))
    withLm(lm)(assert(SemanticF1().score(semanticExample, semanticPrediction).isRight))
    val prompt = text(lm.requests.head)
    assert(
      "compute its recall and precision\\.\\s*\\n\\s*If asked to reason".r.findFirstIn(prompt).isDefined,
      prompt
    )
  }

  test("the judge gets a list context as a list, as upstream gives pred.context to the adapter") {
    val lm = new Lm(request =>
      if text(request).contains("[[ ## completeness ## ]]") then
        chat(
          "reasoning"                 -> "r",
          "ground_truth_key_ideas"    -> "i",
          "system_response_key_ideas" -> "i",
          "discussion"                -> "d",
          "completeness"              -> "1.0"
        )
      else chat("reasoning" -> "r", "system_response_claims" -> "c", "discussion" -> "d", "groundedness" -> "1.0")
    )
    withLm(lm)(assert(CompleteAndGrounded().score(semanticExample, semanticPrediction).isRight))
    val grounded = lm.requests.map(text).find(_.contains("[[ ## retrieved_context ## ]]")).get
    assert(grounded.contains("[1] «««first passage") || grounded.contains("[1] «first passage"), grounded)
  }

  test("a missing field is a NotFoundError for a judged metric") {
    val lm = new Lm(_ => chat("reasoning" -> "r", "recall" -> "1.0", "precision" -> "1.0"))
    withLm(lm) {
      val result = SemanticF1().score(example("question" := "q"), semanticPrediction)
      assert(result.left.exists(_.isInstanceOf[NotFoundError]), result.toString)
    }
  }
