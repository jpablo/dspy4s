package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  :=,
  DspyError,
  DynamicValues,
  FieldSpec,
  RuntimeContext,
  RuntimeError,
  SignatureLayout,
  TraceEntry,
  TypeRef,
  ValidationError
}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.decisions.{DecisionEvidence, DecisionEvidenceLog, DecisionSettings}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.lm.contracts.{DecisionRequests, LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.lm.runtime.{CacheCapacity, InMemoryLmCache, ManagedLanguageModel}
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.strategies.DynamicPredict
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** ReAnchor (experimental; upstream `dspy.experimental.ReAnchor` and `calibrate`, with the cases of
  * `tests/teleprompt/reanchor/test_reanchor_calibrate.py` and `test_reanchor.py`). A fake System One model answers each
  * question with a function of the state, as the upstream `FakeClient`.
  */
class ReAnchorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private def noul(p: Double): DynamicValue = json(s"""{"type": "noul", "noul": $p}""")

  private def score(p0: Double, p1: Double, p2: Double): DynamicValue =
    json(s"""{"type": "score", "confidence": 0.8, "probabilities": {"0": $p0, "1": $p1, "2": $p2}}""")

  private def choice(x: Double, y: Double): DynamicValue =
    json(s"""{"type": "choice", "confidence": 0.7, "probabilities": {"x": $x, "y": $y}}""")

  /** The input `name` of the state of a request. */
  private def input(state: DynamicValue, name: String): String =
    state match
      case record: DynamicValue.Record =>
        DynamicValues.recordGet(record, "inputs").collect { case r: DynamicValue.Record => r }
          .flatMap(DynamicValues.recordGet(_, name)).map(DynamicValues.renderText).getOrElse("")
      case _ => ""

  /** A System One model: `answer(state, question name)` gives each answer. It keeps each request. */
  private final class FakeSystemOne(answer: (DynamicValue, String) => DynamicValue) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "jev-test"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsDecisionRequests: Boolean                                             = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests = requests :+ request }
      request.decision.toRight(ValidationError("not a decision request")).map { payload =>
        val answers = DynamicValues.recordKeys(payload.questions).map(name => name -> answer(payload.state, name))
        DecisionRequests.response(DynamicValues.recordFromEntries(answers), None, Some("jev-test"))
      }

  private def layoutOf(input: String, output: FieldSpec): SignatureLayout =
    SignatureLayout.create("S", Vector(FieldSpec(input)), Vector(output)).fold(error => fail(error.message), identity)

  private val matchLayout =
    layoutOf("pair", FieldSpec("match", TypeRef.bool, description = Some("Are the two the same?")))
  private val kindLayout =
    layoutOf("item", FieldSpec("kind", enumValues = Vector("x", "y"), description = Some("Which kind?")))
  private val rateLayout = layoutOf(
    "item",
    FieldSpec("rating", TypeRef.score, description = Some("How good is it?"), enumValues = Vector("low", "mid", "high"))
  )

  /** A metric that compares the value of `field` with the label (a level name for a Score). */
  private def equalOn(field: String, level: Boolean = false): Metric =
    new Metric:
      override val name: String = s"equal_$field"
      override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
          RuntimeContext
      ): Either[DspyError, Double] =
        val got = DynamicValues.recordGet(prediction.values, field).map {
          case record: DynamicValue.Record if level =>
            DynamicValues.recordGet(record, "level").map(DynamicValues.renderText).getOrElse("")
          case other => DynamicValues.renderText(other)
        }
        Right(if got == example.get(field).map(DynamicValues.renderText) then 1.0 else 0.0)

  private def pairs(kinds: Seq[String]): Vector[Example] =
    kinds.toVector.map(k =>
      Example(values = DynamicValues.record("pair" := k, "match" := k.startsWith("same")), inputKeys = Set("pair"))
    )

  private def items(labels: Seq[(String, DynamicValue)], output: String): Vector[Example] =
    labels.toVector.map((item, label) =>
      Example(values = DynamicValues.record("item" := item, output -> label), inputKeys = Set("item"))
    )

  private def context(model: LanguageModel, cached: Boolean = true): RuntimeContext =
    val lm = ManagedLanguageModel(model, cache = Option.when(cached)(new InMemoryLmCache(CacheCapacity(10_000))))
    RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))

  private def calibrate(
      program : DynamicPredict,
      trainset: Vector[Example],
      metric  : Metric,
      model   : LanguageModel,
      config  : ReAnchorConfig = ReAnchorConfig(numThreads = 2)
  ): (DynamicPredict, ReAnchorReport) =
    RuntimeEnvironment.withSettings(context(model)) {
      ReAnchor[DynamicPredict](metric, config).calibrate(program, trainset)(using RuntimeEnvironment.current)
    }.fold(error => fail(error.message), identity)

  private def leaningPair(state: DynamicValue, @scala.annotation.unused name: String): DynamicValue =
    noul(if input(state, "pair") == "same" then 0.9 else 0.7)

  // ── calibrate ─────────────────────────────────────────────────────────────────────────────────────────────────

  test("a leaning Noul gets a threshold between its piles") {
    val (program, report) = calibrate(
      DynamicPredict(matchLayout),
      pairs(Seq.fill(4)(Seq("same", "different")).flatten),
      equalOn("match"),
      FakeSystemOne(leaningPair)
    )
    assertEquals(program.decisions.get("match").flatMap(_.threshold), Some(0.8))
    assertEquals(report.fitted.map(_.trainScore), Vector(1.0))
  }

  test("adjacent probabilities are split by the upper value") {
    val upper             = Math.nextUp(0.7)
    val model             = FakeSystemOne((state, _) => noul(if input(state, "pair") == "same" then upper else 0.7))
    val (program, report) = calibrate(
      DynamicPredict(matchLayout),
      pairs(Seq.fill(10)(Seq("same", "different")).flatten),
      equalOn("match"),
      model
    )
    assertEquals(program.decisions.get("match").flatMap(_.threshold), Some(upper))
    assertEquals((report.trainScoreBefore, report.trainScore), (0.5, 1.0))
  }

  test("a probability of zero can be classified on either side") {
    val model = FakeSystemOne((_, _) => noul(0.0))
    Vector(true, false).foreach { target =>
      val train = (0 until 10).toVector.map(i =>
        Example(values = DynamicValues.record("pair" := i.toString, "match" := target), inputKeys = Set("pair"))
      )
      val (program, report) = calibrate(DynamicPredict(matchLayout), train, equalOn("match"), model)
      val expected          =
        if target then Map("match" -> DecisionSettings(threshold = Some(0.0))) else Map.empty[String, DecisionSettings]
      assertEquals(program.decisions, expected, s"target $target")
      assertEquals(report.fitted.map(_.trainScore), Vector(1.0))
    }
  }

  test("a starting threshold stays unless a candidate scores strictly better") {
    val model             = FakeSystemOne((state, _) => noul(if input(state, "pair") == "same" then 0.74 else 0.72))
    val start             = DynamicPredict(matchLayout).copy(decisions = Map("match" -> DecisionSettings(threshold = Some(0.73))))
    val (program, report) =
      calibrate(start, pairs(Seq.fill(4)(Seq("same", "different")).flatten), equalOn("match"), model)
    assertEquals(program.decisions.get("match").flatMap(_.threshold), Some(0.73))
    assertEquals(report.fitted.map(row => (row.trainScoreAtStart, row.trainScore)), Vector((1.0, 1.0)))
  }

  test("Choice weights move an option that is picked too often") {
    val model =
      FakeSystemOne((state, _) => if input(state, "item") == "x" then choice(0.6, 0.4) else choice(0.55, 0.45))
    val train =
      items(Seq.fill(4)(Seq("x", "y")).flatten.map(k => k -> DynamicValue.Primitive(PrimitiveValue.String(k))), "kind")
    val (program, report) = calibrate(DynamicPredict(kindLayout), train, equalOn("kind"), model)
    assertEquals(report.fitted.map(_.trainScore), Vector(1.0))
    val weights  = program.decisions.get("kind").flatMap(_.weights).getOrElse(fail("no weights"))
    val (wx, wy) = (weights.getOrElse("x", 1.0), weights.getOrElse("y", 1.0))
    assert(0.55 * wx < 0.45 * wy && 0.6 * wx > 0.4 * wy, weights.toString)
  }

  private lazy val leaningLevels: String => DynamicValue =
    item => if item == "lo" then score(0.2, 0.3, 0.5) else score(0.0, 0.2, 0.8)

  test("Score cuts move to fit a metric on the level") {
    val model = FakeSystemOne((state, _) => leaningLevels(input(state, "item")))
    val train = items(
      Seq.fill(3)(Seq("lo" -> "low", "hi" -> "high")).flatten
        .map((k, v) => k -> DynamicValue.Primitive(PrimitiveValue.String(v))),
      "rating"
    )
    val (program, report) = calibrate(DynamicPredict(rateLayout), train, equalOn("rating", level = true), model)
    val cuts              = program.decisions.get("rating").flatMap(_.cuts).getOrElse(fail("no cuts"))
    assertEquals(
      report.fitted.map(row => (row.parameter, row.trainScoreAtStart, row.trainScore)),
      Vector(("cuts", 0.5, 1.0))
    )
    assert(1.3 < cuts(0) && cuts(0) < cuts(1) && cuts(1) <= 1.8, cuts.toString)
  }

  test("each distinct request goes once to the model, however many settings are tried") {
    val model = FakeSystemOne(leaningPair)
    val _     = calibrate(DynamicPredict(matchLayout), pairs(Seq("same", "different")), equalOn("match"), model)
    assertEquals(model.requests.map(_.decision).distinct.size, 2)
    assertEquals(model.requests.size, 2, "the cache serves each repeated request")
  }

  test("a metric error and a metric score that is not finite fail the pass") {
    val broken = new Metric:
      override val name: String                                                                                        = "broken"
      override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using RuntimeContext) =
        Left(RuntimeError("metric", "metric failed"))
    val infinite = new Metric:
      override val name: String                                                                                        = "infinite"
      override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using RuntimeContext) =
        Right(Double.PositiveInfinity)
    Vector(broken, infinite).foreach { metric =>
      val result = RuntimeEnvironment.withSettings(context(FakeSystemOne((_, _) => noul(0.9)))) {
        ReAnchor[DynamicPredict](metric).calibrate(DynamicPredict(matchLayout), pairs(Seq("same")))(using
          RuntimeEnvironment.current
        )
      }
      assert(result.isLeft, metric.name)
    }
  }

  test("probabilities bunched near one get a threshold between them, and the row reports the observation") {
    val model             = FakeSystemOne((state, _) => noul(if input(state, "pair") == "same" then 1.0 else 0.98))
    val (program, report) = calibrate(
      DynamicPredict(matchLayout),
      pairs(Seq.fill(4)(Seq("same", "different")).flatten),
      equalOn("match"),
      model
    )
    assertEquals(program.decisions.get("match").flatMap(_.threshold), Some(0.99))
    assertEquals(
      report.fitted.map(_.observed),
      Vector(Map("calls" -> 8.0, "distinct" -> 2.0, "min" -> 0.98, "max" -> 1.0, "candidates" -> 2.0))
    )
  }

  /** 20 same pairs, 19 different pairs, and `count` of the same pairs replaced by odd ones (upstream `lone_outlier`).
    */
  private def loneOutlier(count: Int): (FakeSystemOne, Vector[Example]) =
    val names = (0 until 20 - count).map(i => s"same$i") ++ (0 until count).map(i => s"odd$i") ++ (0 until 19).map(i =>
      s"diff$i"
    )
    val model = FakeSystemOne { (state, _) =>
      val pair = input(state, "pair")
      noul(if pair.startsWith("odd") then 0.3 else if pair.startsWith("same") then 0.9 else 0.1)
    }
    val train = names.toVector.map(p =>
      Example(values = DynamicValues.record("pair" := p, "match" := !p.startsWith("diff")), inputKeys = Set("pair"))
    )
    (model, train)

  test("a threshold stays when its whole gain is one example, and moves when the gain recurs") {
    val (lone, loneTrain)    = loneOutlier(1)
    val (stayed, stayReport) = calibrate(DynamicPredict(matchLayout), loneTrain, equalOn("match"), lone)
    assertEquals(stayed.decisions, Map.empty[String, DecisionSettings])
    assertEquals(stayReport.fitted.map(_.foldCheck), Vector((0, 1)))
    val (many, manyTrain)   = loneOutlier(6)
    val (moved, moveReport) = calibrate(DynamicPredict(matchLayout), manyTrain, equalOn("match"), many)
    assertEquals(moved.decisions.get("match").flatMap(_.threshold), Some(0.2))
    assertEquals(moveReport.fitted.map(_.foldCheck), Vector((1, 0)))
  }

  // ── compile ───────────────────────────────────────────────────────────────────────────────────────────────────

  private def tricky(state: DynamicValue, @scala.annotation.unused name: String): DynamicValue =
    val pair = input(state, "pair")
    noul(if pair.endsWith("same-tricky") then 0.7 else if pair.contains("same") then 0.9 else 0.7)

  private lazy val trickyTrain: Vector[Example] =
    pairs(Seq.fill(6)("same") ++ Seq.fill(4)("same-tricky") ++ Seq.fill(8)("different"))

  test("compile fits the threshold, reports the training scores, and puts the report in the result") {
    val result = RuntimeEnvironment.withSettings(context(FakeSystemOne(tricky))) {
      ReAnchor[DynamicPredict](equalOn("match"), ReAnchorConfig(numThreads = 2))
        .compile(DynamicPredict(matchLayout), trickyTrain)(using RuntimeEnvironment.current)
    }.fold(error => fail(error.message), identity)
    assertEquals(result.bestProgram.decisions.get("match").flatMap(_.threshold), Some(0.8))
    val report = result.metadata.get("report") match
      case Some(value: ReAnchorReport) => value
      case other                       => fail(s"no report: $other")
    assertEquals((report.trainScoreBefore, report.trainScore), (0.5556, 0.7778))
    assertEquals(report.fitted.map(_.parameter), Vector("threshold"))
    assertEquals((report.valScoreBefore, report.valScore), (None, None))
  }

  test(
    "compile fails on an empty training set, a teacher, a program with no decision output, and a model with no cache"
  ) {
    val reanchor                                                                                  = ReAnchor[DynamicPredict](equalOn("match"))
    def run(model: LanguageModel, cached: Boolean)(body: RuntimeContext ?=> Either[DspyError, ?]) =
      RuntimeEnvironment.withSettings(context(model, cached))(body(using RuntimeEnvironment.current))
    val model = FakeSystemOne(tricky)
    assert(run(model, cached = true)(reanchor.compile(DynamicPredict(matchLayout), Vector.empty)).isLeft)
    assert(run(
      model,
      cached = true
    )(reanchor.compile(DynamicPredict(matchLayout), trickyTrain, teacher = Some(DynamicPredict(matchLayout)))).isLeft)
    val plain = layoutOf("pair", FieldSpec("note", description = Some("A note.")))
    assert(run(model, cached = true)(reanchor.compile(DynamicPredict(plain), trickyTrain)).isLeft)
    assert(run(model, cached = false)(reanchor.compile(DynamicPredict(matchLayout), trickyTrain)).isLeft)
    val waived = ReAnchor[DynamicPredict](equalOn("match"), ReAnchorConfig(requireCache = false))
    assert(run(model, cached = false)(waived.compile(DynamicPredict(matchLayout), trickyTrain)).isRight)
  }

  test("the validation set is scored before and after, and the log folder holds report.json") {
    val folder = Files.createTempDirectory("reanchor")
    val result = RuntimeEnvironment.withSettings(context(FakeSystemOne(tricky))) {
      ReAnchor[DynamicPredict](equalOn("match"), ReAnchorConfig(numThreads = 2, logDir = Some(folder)))
        .calibrate(DynamicPredict(matchLayout), trickyTrain, valset = Some(trickyTrain.take(10)))(using
          RuntimeEnvironment.current
        )
    }.fold(error => fail(error.message), identity)
    val report = result._2
    assert(report.valScoreBefore.isDefined && report.valScore.isDefined, report.toString)
    val written = Files.readString(folder.resolve("report.json"))
    assertEquals(
      DynamicValues.recordKeys(json(written) match
        case r: DynamicValue.Record => r
        case other                  => fail(s"not an object: $other")).take(5),
      Vector("train_score_before", "train_score", "val_score_before", "val_score", "fitted")
    )
  }

  test("the calibrated settings survive the state record of the program") {
    val (program, _) = calibrate(
      DynamicPredict(matchLayout),
      pairs(Seq.fill(4)(Seq("same", "different")).flatten),
      equalOn("match"),
      FakeSystemOne(leaningPair)
    )
    val parameters = OptimizableParameters(decisions = program.decisions)
    assertEquals(OptimizableParameters.fromState(parameters.dumpState).map(_.decisions), Right(program.decisions))
  }

  // ── the evidence log ──────────────────────────────────────────────────────────────────────────────────────────

  test(
    "the evidence log keeps the evidence of each call with the predictor and the output, and nested logs are apart"
  ) {
    val model   = FakeSystemOne((state, _) => noul(input(state, "pair").toDouble))
    val program = DynamicPredict(matchLayout)
    RuntimeEnvironment.withSettings(context(model, cached = false)) {
      given RuntimeContext = RuntimeEnvironment.current
      val (inner, outer)   = DecisionEvidenceLog.recording {
        val _           = program(DynamicValues.record("pair" := "0.1"))
        val (_, nested) =
          try
            DecisionEvidenceLog.recording {
              val _ = program(DynamicValues.record("pair" := "0.2"))
              throw new IllegalStateException("stop")
            }
          catch case _: IllegalStateException => ((), Vector.empty[DecisionEvidenceLog.Entry])
        val _ = program(DynamicValues.record("pair" := "0.3"))
        nested
      }
      assertEquals(inner, Vector.empty[DecisionEvidenceLog.Entry], "an aborted scope gives no result")
      assertEquals(outer.map(_.evidence), Vector(DecisionEvidence.Noul(0.1), DecisionEvidence.Noul(0.3)))
      assertEquals(outer.map(entry => (entry.predictor.moduleName, entry.field)).distinct, Vector(("predict", "match")))
      val _ = program(DynamicValues.record("pair" := "0.4"))
    }
  }
