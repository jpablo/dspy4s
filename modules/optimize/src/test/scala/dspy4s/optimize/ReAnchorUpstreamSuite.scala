package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{
  :=,
  DspyError,
  DynamicValues,
  FieldSpec,
  PredictorKey,
  RuntimeContext,
  SignatureLayout,
  ThreadCount,
  TraceEntry,
  TypeRef
}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.decisions.{DecisionEvidence, DecisionEvidenceLog, DecisionSettings}
import dspy4s.core.runtime.{ContextPropagation, RuntimeEnvironment}
import dspy4s.evaluate.contracts.Metric
import dspy4s.lm.contracts.{DecisionRequests, LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.lm.runtime.{CacheCapacity, InMemoryLmCache, ManagedLanguageModel}
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.{OptimizableLeaf, OptimizableStructure}
import dspy4s.programs.runtime.ParallelExecutor
import dspy4s.programs.strategies.{ChainOfThought, DynamicPredict}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets
import java.util.concurrent.{Callable, CyclicBarrier, Executors, TimeUnit}

/** Two judges: the second runs only when the first says yes (upstream `Wrapper` of `test_reanchor.py`). The tuple is
  * the port form of a listed predictor.
  */
final case class RaWrapper(judge: DynamicPredict, judges: Tuple1[DynamicPredict])

object RaWrapper:
  given OptimizableStructure[RaWrapper] = OptimizableStructure.derived

  given ProgramRunner[RaWrapper] with
    def run(program: RaWrapper, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      program.judge(call).flatMap { first =>
        DynamicValues.recordGet(first.output, "match") match
          case Some(DynamicValue.Primitive(PrimitiveValue.Boolean(true))) => program.judges._1(call).map(_.raw)
          case _                                                          => Right(RawPrediction(DynamicValues.record("match" := false)))
      }

/** A predictor that runs once for each item of the input `pairs`, and gives the list `matches` (upstream `Items` and
  * `Batch`).
  */
final case class RaItems(judge: DynamicPredict)

object RaItems:
  given OptimizableStructure[RaItems] = OptimizableStructure.derived

  given ProgramRunner[RaItems] with
    def run(program: RaItems, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      val pairs = DynamicValues.recordGet(call.input, "pairs") match
        case Some(sequence: DynamicValue.Sequence) => sequence.elements.toVector
        case _                                     => Vector.empty
      pairs
        .foldLeft[Either[DspyError, Vector[DynamicValue]]](Right(Vector.empty)) { (acc, pair) =>
          acc.flatMap(done =>
            program.judge(call.mapInput(_ => DynamicValues.record("pair" -> pair))).map(prediction =>
              done :+ DynamicValues.recordGet(prediction.output, "match").getOrElse(DynamicValue.Null)
            )
          )
        }
        .map(matches => RawPrediction(DynamicValues.record("matches" -> DynamicValue.Sequence(Chunk.from(matches)))))

/** A predictor that runs with the model that the program sets in its own context (upstream `Routed` with the `context`
  * binding).
  */
final case class RaRouted(judge: DynamicPredict, client: LanguageModel)

object RaRouted:
  given OptimizableStructure.WithArity[LanguageModel, 0] = OptimizableStructure.empty
  given OptimizableStructure[RaRouted]                   = OptimizableStructure.derived

  given ProgramRunner[RaRouted] with
    def run(program: RaRouted, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(program.client))) {
        program.judge(call)(using RuntimeEnvironment.current).map(_.raw)
      }

/** A judge for each item, then one kind (upstream `Two` of `test_reanchor_calibrate.py`). */
final case class RaTwo(judge: DynamicPredict, kind: DynamicPredict)

object RaTwo:
  given OptimizableStructure[RaTwo] = OptimizableStructure.derived

  given ProgramRunner[RaTwo] with
    def run(program: RaTwo, call: ProgramCall[DynamicValue.Record])(using
        RuntimeContext
    ): Either[DspyError, RawPrediction] =
      val items = DynamicValues.recordGet(call.input, "items") match
        case Some(sequence: DynamicValue.Sequence) => sequence.elements.toVector
        case _                                     => Vector.empty
      val judged = items.foldLeft[Either[DspyError, Unit]](Right(())) { (acc, item) =>
        acc.flatMap(_ => program.judge(call.mapInput(_ => DynamicValues.record("pair" -> item))).map(_ => ()))
      }
      judged.flatMap(_ => program.kind(call.mapInput(_ => DynamicValues.record("item" := "a"))).map(_.raw))

/** A judge, a listed judge, and a writer with no decision output (upstream
  * `test_predictors_finds_nested_and_listed_predictors`).
  */
final case class RaListed(judge: DynamicPredict, judges: Tuple1[DynamicPredict], writer: DynamicPredict)

object RaListed:
  given OptimizableStructure[RaListed] = OptimizableStructure.derived

final case class RaPairInput(pair: String) derives Schema
final case class RaSameOutput(same: Boolean) derives Schema

/** The other cases of upstream `tests/teleprompt/reanchor/test_reanchor.py` and `test_reanchor_calibrate.py` for
  * ReAnchor (experimental), and the search rules of `calibrate.py`. A fake System One model answers each question with
  * a function of the state, as the upstream `FakeClient`, and a fake generative model answers a plain value or the
  * evidence, as the upstream `ComputedLM`.
  */
class ReAnchorUpstreamSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  private def bool(value: Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(value))

  private def noul(p: Double): DynamicValue = json(s"""{"type": "noul", "noul": $p}""")

  private def score(p0: Double, p1: Double, p2: Double): DynamicValue =
    json(s"""{"type": "score", "confidence": 0.8, "probabilities": {"0": $p0, "1": $p1, "2": $p2}}""")

  private def choice(labels: (String, Double)*): DynamicValue =
    val probabilities = labels.map((label, p) => s""""$label": $p""").mkString(", ")
    json(s"""{"type": "choice", "confidence": 0.7, "probabilities": {$probabilities}}""")

  /** The input `name` of the state of a request. */
  private def input(state: DynamicValue, name: String): String =
    state match
      case record: DynamicValue.Record =>
        DynamicValues.recordGet(record, "inputs").collect { case r: DynamicValue.Record => r }
          .flatMap(DynamicValues.recordGet(_, name)).map(DynamicValues.renderText).getOrElse("")
      case _ => ""

  /** `item` with no digits at its end (Python `rstrip("0123456789")`). */
  private def stem(item: String): String = item.reverse.dropWhile(_.isDigit).reverse

  /** A System One model: `answer(state, question name)` gives each answer. It keeps each request. */
  private final class FakeSystemOne(answer: (DynamicValue, String) => DynamicValue) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "jev-test"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsDecisionRequests: Boolean                                             = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests = requests :+ request }
      DecisionRequests.parts(request).map { (state, questions) =>
        val answers = DynamicValues.recordKeys(questions).map(name => name -> answer(state, name))
        DecisionRequests.response(DynamicValues.recordFromEntries(answers), None, Some("jev-test"))
      }

  /** A generative model whose reply gives the text of each output of `answer(inputs, evidence)` (upstream
    * `ComputedLM`). `inputs` maps each input field to its text in the last message, and `evidence` is `true` when the
    * request asks for the evidence of a decision output.
    */
  private final class ComputedLm(answer: (Map[String, String], Boolean) => Vector[(String, String)])
      extends LanguageModel:
    private val field                                                                          = """\[\[ ## (\w+) ## \]\]\n(.*)""".r
    override val id: String                                                                    = "computed"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val texts    = request.messages.flatMap(_.text)
      val inputs   = field.findAllMatchIn(texts.lastOption.getOrElse("")).map(m => m.group(1) -> m.group(2)).toMap
      val evidence = texts.exists(t => t.contains("noul") || t.contains("probabilities"))
      val reply    = answer(inputs, evidence).map((name, value) => s"[[ ## $name ## ]]\n$value\n\n").mkString
      Right(LmResponse(outputs = Vector(LmOutput(text = reply + "[[ ## completed ## ]]"))))

  private def layoutOf(input: String, outputs: FieldSpec*): SignatureLayout =
    SignatureLayout.create("S", Vector(FieldSpec(input)), outputs.toVector).fold(error => fail(error.message), identity)

  private val matchField  = FieldSpec("match", TypeRef.bool, description = Some("Are the two the same?"))
  private val matchLayout = layoutOf("pair", matchField)
  private val kindField   = FieldSpec("kind", enumValues = Vector("x", "y"), description = Some("Which kind?"))
  private val kindLayout  = layoutOf("item", kindField)
  private val rateLevels  = Vector("low", "mid", "high")
  private val rateLayout  = layoutOf(
    "item",
    FieldSpec("rating", TypeRef.score, description = Some("How good is it?"), enumValues = rateLevels)
  )
  private val noteLayout = layoutOf("pair", FieldSpec("note", description = Some("A note.")))

  /** A metric with the score `f(example, prediction values)`. */
  private def metricOf(label: String)(f: (Example, DynamicValue.Record) => Double): Metric =
    new Metric:
      override val name: String = label
      override def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry])(using
          RuntimeContext
      ): Either[DspyError, Double] = Right(f(example, prediction.values))

  private def textOf(values: DynamicValue.Record, field: String): Option[String] =
    DynamicValues.recordGet(values, field).map(DynamicValues.renderText)

  /** 1 when the value of `field` is the label (upstream `metric`). */
  private def equalOn(field: String): Metric =
    metricOf(s"equal_$field")((example, values) =>
      if textOf(values, field) == example.get(field).map(DynamicValues.renderText) then 1.0 else 0.0
    )

  /** 1 when the level of the Score `rating` is the label, a level index. The decode gives the level by its name. */
  private val levelMetric: Metric = metricOf("level") { (example, values) =>
    val level = DynamicValues.recordGet(values, "rating").collect { case r: DynamicValue.Record => r }
      .flatMap(textOf(_, "level")).map(name => rateLevels.indexOf(name).toString)
    if level == example.get("rating").map(DynamicValues.renderText) then 1.0 else 0.0
  }

  /** The items of the list `field`, as text. */
  private def itemsOf(value: Option[DynamicValue]): Vector[String] =
    value match
      case Some(sequence: DynamicValue.Sequence) => sequence.elements.toVector.map(DynamicValues.renderText)
      case _                                     => Vector.empty

  private def pairs(kinds: Seq[String]): Vector[Example] =
    kinds.toVector.map(k =>
      Example(values = DynamicValues.record("pair" := k, "match" := k.startsWith("same")), inputKeys = Set("pair"))
    )

  private def items(labels: Seq[(String, DynamicValue)], output: String): Vector[Example] =
    labels.toVector.map((item, label) =>
      Example(values = DynamicValues.record("item" := item, output -> label), inputKeys = Set("item"))
    )

  private def labeled(kinds: Seq[String], output: String): Vector[Example] = items(kinds.map(k => k -> text(k)), output)

  private def context(model: LanguageModel, cached: Boolean = true): RuntimeContext =
    val lm = ManagedLanguageModel(model, cache = Option.when(cached)(new InMemoryLmCache(CacheCapacity(10_000))))
    RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))

  private def run[A](settings: RuntimeContext)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(settings)(body(using RuntimeEnvironment.current))

  private def calibrate[P: {OptimizableStructure, ProgramRunner}](
      program : P,
      trainset: Vector[Example],
      metric  : Metric,
      settings: RuntimeContext,
      config  : ReAnchorConfig = ReAnchorConfig(numThreads = 2)
  ): (P, ReAnchorReport) =
    run(settings)(ReAnchor[P](metric, config).calibrate(program, trainset)).fold(error => fail(error.message), identity)

  private def leaning(state: DynamicValue, @scala.annotation.unused name: String): DynamicValue =
    noul(if input(state, "pair") == "same" then 0.9 else 0.7)

  /** Upstream `leaning` of `test_reanchor.py`: a tricky same pair reads as a different one. */
  private def tricky(state: DynamicValue, @scala.annotation.unused name: String): DynamicValue =
    val pair = input(state, "pair")
    noul(if pair.endsWith("same-tricky") then 0.7 else if pair.contains("same") then 0.9 else 0.7)

  private lazy val trickyTrain: Vector[Example] =
    pairs(Seq.fill(6)("same") ++ Seq.fill(4)("same-tricky") ++ Seq.fill(8)("different"))

  /** Upstream `generative`: `true` for each pair when asked for a Boolean; asked for evidence, it leans as `tricky`. */
  private def generative(inputs: Map[String, String], evidence: Boolean): Vector[(String, String)] =
    val pair = inputs.getOrElse("pair", "")
    if !evidence then Vector("match" -> "True")
    else
      val p = if pair.endsWith("same-tricky") then 0.7 else if pair.contains("same") then 0.9 else 0.7
      Vector("match" -> s"""{"noul": $p}""")

  private def threshold(program: DynamicPredict): Option[Double] = program.decisions.get("match").flatMap(_.threshold)

  private def onlyRow(report: ReAnchorReport): ReAnchorRow =
    report.fitted match
      case Vector(row) => row
      case other       => fail(s"not one row: $other")

  private def runInputs[P](program: P, inputs: DynamicValue.Record)(using runner: ProgramRunner[P]): RawPrediction =
    runner.run(program, inputs)(using RuntimeEnvironment.current).fold(error => fail(error.message), identity)

  // ── test_reanchor.py ──────────────────────────────────────────────────────────────────────────────────────────

  test("compile leaves the student unchanged") {
    val student = DynamicPredict(matchLayout)
    val result  = run(context(FakeSystemOne(tricky))) {
      ReAnchor[DynamicPredict](equalOn("match"), ReAnchorConfig(numThreads = 2)).compile(student, trickyTrain)
    }.fold(error => fail(error.message), identity)
    assertEquals(student.decisions, Map.empty[String, DecisionSettings])
    assert(!(result.bestProgram eq student))
    assertEquals(threshold(result.bestProgram), Some(0.8))
  }

  test("a program that holds predictors gets each one calibrated") {
    val program = RaWrapper(
      DynamicPredict(matchLayout, name = Some("judge")),
      Tuple1(DynamicPredict(matchLayout, name = Some("second")))
    )
    val (fitted, _) = calibrate(program, trickyTrain, equalOn("match"), context(FakeSystemOne(tricky)))
    assertEquals(threshold(fitted.judge), Some(0.8))
    assertEquals(fitted.judges._1.decisions, Map.empty[String, DecisionSettings], "no more gain after the first judge")
  }

  test("a predictor that runs once for each item is calibrated from the metric of the program") {
    val batch = Vector("same", "different", "different")
    val train = Vector.fill(4)(
      Example(
        values = DynamicValues.record(
          "pairs"   -> DynamicValue.Sequence(Chunk.from(batch.map(text))),
          "matches" -> DynamicValue.Sequence(Chunk.from(batch.map(k => bool(k.startsWith("same")))))
        ),
        inputKeys = Set("pairs")
      )
    )
    val itemsMetric = metricOf("items") { (example, values) =>
      val gold = itemsOf(example.get("matches"))
      val got  = itemsOf(DynamicValues.recordGet(values, "matches"))
      gold.zip(got).count(_ == _).toDouble / gold.size
    }
    val (fitted, _) =
      calibrate(RaItems(DynamicPredict(matchLayout)), train, itemsMetric, context(FakeSystemOne(leaning)))
    assertEquals(threshold(fitted.judge), Some(0.8))
  }

  test("a native Boolean on a generative model gets evidence decoding when the probabilities score better") {
    val student           = DynamicPredict(matchLayout)
    val (program, report) = calibrate(
      student,
      trickyTrain,
      equalOn("match"),
      context(ComputedLm(generative), cached = false),
      ReAnchorConfig(numThreads = 2, requireCache = false)
    )
    assertEquals(student.decisions, Map.empty[String, DecisionSettings])
    assertEquals(program.decisions, Map("match" -> DecisionSettings(threshold = Some(0.8))))
    val row = onlyRow(report)
    assertEquals((row.skipped, row.trainScoreOriginal, row.trainScore), (None, 0.5556, 0.7778))
  }

  test("a native Boolean stays native when the probabilities do not score better") {
    Vector(true, false).foreach { accurate =>
      def right(inputs: Map[String, String], evidence: Boolean): Vector[(String, String)] =
        val answer      = inputs.getOrElse("pair", "").startsWith("same")
        val probability = if accurate then (if answer then 0.9 else 0.1) else 0.9
        Vector("match" -> (if evidence then s"""{"noul": $probability}""" else if answer then "True" else "False"))
      val (program, report) = calibrate(
        DynamicPredict(matchLayout),
        trickyTrain,
        equalOn("match"),
        context(ComputedLm(right), cached = false),
        ReAnchorConfig(numThreads = 2, requireCache = false)
      )
      assertEquals(program.decisions, Map.empty[String, DecisionSettings], s"accurate $accurate")
      val row = onlyRow(report)
      assertEquals(row.skipped, Some(ReAnchor.SkippedReason))
      assertEquals((row.trainScore, row.trainScoreOriginal), (1.0, 1.0))
      assertEquals(row.trainScoreAtStart, if accurate then 1.0 else 0.5556)
      assertEquals(row.value, None)
    }
  }

  test("a native Boolean stays native when the probabilities win only one example") {
    val names                                                                             = (0 until 20).map(i => s"same$i") ++ (0 until 20).map(i => s"different$i")
    def oneMiss(inputs: Map[String, String], evidence: Boolean): Vector[(String, String)] =
      val pair   = inputs.getOrElse("pair", "")
      val answer = pair.startsWith("same")
      if evidence then Vector("match" -> s"""{"noul": ${if answer then 0.9 else 0.1}}""")
      else Vector("match"             -> (if answer && pair != "same0" then "True" else "False"))
    val (program, report) = calibrate(
      DynamicPredict(matchLayout),
      pairs(names),
      equalOn("match"),
      context(ComputedLm(oneMiss), cached = false),
      ReAnchorConfig(numThreads = 2, requireCache = false)
    )
    assertEquals(program.decisions, Map.empty[String, DecisionSettings])
    assertEquals(onlyRow(report).skipped, Some(ReAnchor.SkippedReason))
  }

  test("a model that the program sets at run time can be calibrated with no cache check, with both backends") {
    Vector[LanguageModel](FakeSystemOne(tricky), ComputedLm(generative)).foreach { client =>
      val outer   = RuntimeContext(adapter = Some(ChatAdapter()))
      val program = RaRouted(DynamicPredict(matchLayout), client)
      val checked = run(outer)(ReAnchor[RaRouted](equalOn("match")).compile(program, trickyTrain))
      assert(checked.left.exists(_.message.contains("requireCache = false")), checked.toString)
      val (fitted, report) = calibrate(
        program,
        trickyTrain,
        equalOn("match"),
        outer,
        ReAnchorConfig(numThreads = 2, requireCache = false)
      )
      assertEquals(fitted.judge.decisions, Map("match" -> DecisionSettings(threshold = Some(0.8))), client.id)
      val different = run(outer)(runInputs(fitted, DynamicValues.record("pair" := "different")))
      assertEquals(textOf(different.values, "match"), Some("false"), client.id)
      assertEquals(report.trainScore, 0.7778, client.id)
    }
  }

  test("a rejected configuration of a System One output comes back exactly") {
    val criteria = DynamicValues.record("true" -> DynamicValues.record("what" := "same item"))
    Vector(None, Some(DecisionSettings()), Some(DecisionSettings(criteria = Some(criteria)))).foreach { original =>
      val student           = DynamicPredict(matchLayout).copy(decisions = original.map("match" -> _).toMap)
      val train             = pairs(Seq.fill(10)("same"))
      val (program, report) = calibrate(student, train, equalOn("match"), context(FakeSystemOne(tricky)))
      assertEquals(program.decisions, student.decisions, original.toString)
      val row = onlyRow(report)
      assertEquals(row.skipped, Some(ReAnchor.SkippedReason))
      assertEquals((row.trainScoreOriginal, row.trainScore), (1.0, 1.0))
      assertEquals(row.value, None)
    }
  }

  test("a partial entry gets the defaults of its kind") {
    val criteria = DynamicValues.record("true" := "The same item.", "false" := "Two different items.")
    val student  =
      DynamicPredict(matchLayout).withCriteria("match", criteria).fold(error => fail(error.message), identity)
    val (program, _) = calibrate(student, trickyTrain, equalOn("match"), context(FakeSystemOne(tricky)))
    assertEquals(
      program.decisions,
      Map("match" -> DecisionSettings(threshold = Some(0.8), criteria = Some(criteria)))
    )
  }

  // ── test_reanchor_calibrate.py ────────────────────────────────────────────────────────────────────────────────

  private lazy val leaningLevels: String => DynamicValue =
    item => if item == "lo" then score(0.2, 0.3, 0.5) else score(0.0, 0.2, 0.8)

  private def ratings(labels: Seq[(String, Int)]): Vector[Example] =
    items(labels.map((k, v) => k -> DynamicValue.Primitive(PrimitiveValue.Int(v))), "rating")

  test("Score cuts stay at their defaults when the metric does not read the level") {
    val model       = FakeSystemOne((state, _) => leaningLevels(input(state, "item")))
    val valueMetric = metricOf("value") { (example, values) =>
      val value = DynamicValues.recordGet(values, "rating").collect { case r: DynamicValue.Record => r }
        .flatMap(textOf(_, "value")).map(_.toDouble).getOrElse(Double.NaN)
      val label = example.get("rating").map(DynamicValues.renderText).map(_.toDouble).getOrElse(Double.NaN)
      1 - math.abs(value - label) / 2
    }
    val (program, report) =
      calibrate(DynamicPredict(rateLayout), ratings(Seq("lo" -> 0, "hi" -> 2)), valueMetric, context(model))
    assertEquals(program.decisions, Map.empty[String, DecisionSettings])
    val row = onlyRow(report)
    assertEquals(row.trainScore, row.trainScoreAtStart)
  }

  test("the search of a Score cut does not put two cuts at adjacent floats") {
    // The midpoint between 0.7 and this observed mean rounds back to 0.7.
    val p     = 0.7000000000000001
    val model = FakeSystemOne((_, _) => score(1 - p, p, 0.0))
    val start =
      DynamicPredict(rateLayout).copy(decisions = Map("rating" -> DecisionSettings(cuts = Some(Vector(0.7, 1.5)))))
    val (program, report) =
      calibrate(start, ratings((0 until 6).map(i => i.toString -> 1)), levelMetric, context(model))
    assertEquals(program.decisions, Map("rating" -> DecisionSettings(cuts = Some(Vector(0.7, 1.5)))))
    assertEquals(onlyRow(report).trainScore, 1.0)
  }

  test("a native enum output with Choice criteria gets weights and stays native") {
    val queue  = FieldSpec("queue", enumValues = Vector("billing", "tech"), description = Some("Which queue?"))
    val layout = layoutOf("ticket", queue)
    val model  = FakeSystemOne((_, _) => choice("billing" -> 0.6, "tech" -> 0.4))
    val labels = DynamicValues.record("billing" := "Payments", "tech" := "Bugs")
    val start  = DynamicPredict(layout).withCriteria("queue", labels).fold(error => fail(error.message), identity)
    val train  = (0 until 6).toVector.map(i =>
      Example(
        values = DynamicValues.record("ticket" := i.toString, "queue" := (if i % 3 != 0 then "tech" else "billing")),
        inputKeys = Set("ticket")
      )
    )
    val (program, report) = calibrate(start, train, equalOn("queue"), context(model))
    val row               = onlyRow(report)
    assertEquals((row.parameter, row.trainScore), ("weights", 0.6667))
    val answer = run(context(model))(runInputs(program, DynamicValues.record("ticket" := "0")))
    assertEquals(DynamicValues.recordGet(answer.values, "queue"), Some(text("tech")))
  }

  test("the predictors are the leaves with a decision output, also in a nested or a listed field") {
    val program = RaListed(DynamicPredict(matchLayout), Tuple1(DynamicPredict(kindLayout)), DynamicPredict(noteLayout))
    assertEquals(ReAnchor.predictors(program).map(_.displayName), Vector("judge", "judges._1"))
    assertEquals(ReAnchor.predictors(DynamicPredict(matchLayout)).map(_.displayName), Vector("self"))
  }

  test("the calibration does not change the request") {
    Vector(1, 2).foreach { threads =>
      val model = FakeSystemOne(leaning)
      val _     = calibrate(
        DynamicPredict(matchLayout),
        pairs(Seq("same", "different")),
        equalOn("match"),
        context(model, cached = false),
        ReAnchorConfig(numThreads = threads, requireCache = false)
      )
      assertEquals(model.requests.distinct.size, 2, s"$threads threads: one distinct request for each example")
    }
  }

  private lazy val twoFlagsLayout = layoutOf(
    "pair",
    matchField,
    FieldSpec("noise", TypeRef.bool, description = Some("Is it noisy?")),
    kindField
  )

  test("the outputs that the metric does not read keep their numeric settings") {
    val model = FakeSystemOne((state, name) =>
      if name == "kind" then choice("x" -> 0.6, "y" -> 0.4)
      else noul(if input(state, "pair") == "same" then 0.9 else 0.7)
    )
    val (program, report) = calibrate(
      DynamicPredict(twoFlagsLayout),
      pairs(Seq.fill(4)(Seq("same", "different")).flatten),
      equalOn("match"),
      context(model)
    )
    val rows = report.fitted.map(row => row.field -> row).toMap
    assertEquals(rows("noise").trainScore, rows("noise").trainScoreAtStart)
    assertEquals(rows("kind").trainScore, rows("kind").trainScoreAtStart)
    assertEquals(rows("match").skipped, None)
    assertEquals(threshold(program), Some(0.8))
    assertEquals(program.decisions.keySet, Set("match"))
    assertEquals(model.requests.distinct.size, 2)
  }

  test("balanced labels are not taken for an output that the metric ignores") {
    // All true and all false both score 0.5 on average; the score of each example still differs.
    val (program, report) = calibrate(
      DynamicPredict(matchLayout),
      pairs(Seq.fill(2)(Seq("same", "different")).flatten),
      equalOn("match"),
      context(FakeSystemOne(leaning))
    )
    assertEquals(onlyRow(report).skipped, None)
    assertEquals(threshold(program), Some(0.8))
  }

  test("equal scores at the extremes do not hide a useful threshold") {
    val model = FakeSystemOne((state, _) => noul(if input(state, "pair") == "same" then 0.4 else 0.2))
    val train = Vector.fill(10)(
      Example(
        values = DynamicValues.record(
          "pairs"   -> DynamicValue.Sequence(Chunk(text("same"), text("different"))),
          "matches" -> DynamicValue.Sequence(Chunk(bool(true), bool(false)))
        ),
        inputKeys = Set("pairs")
      )
    )
    // The default, all true, and all false decisions all fail the exact match. Only the split between them wins.
    val exact = metricOf("exact")((example, values) =>
      if itemsOf(example.get("matches")) == itemsOf(DynamicValues.recordGet(values, "matches")) then 1.0 else 0.0
    )
    val (program, report) = calibrate(RaItems(DynamicPredict(matchLayout)), train, exact, context(model))
    assertEqualsDouble(threshold(program.judge).getOrElse(fail("no threshold")), 0.3, 1e-9)
    val row = onlyRow(report)
    assertEquals((row.trainScoreAtStart, row.trainScore), (0.0, 1.0))
  }

  test("a Choice weight can leave the range of a fixed grid") {
    // A "y" item still puts 0.95 on "x"; to split the items, the weight of "y" must be above 19.
    val model = FakeSystemOne((state, _) =>
      if input(state, "item") == "x" then choice("x" -> 0.99, "y" -> 0.01) else choice("x" -> 0.95, "y" -> 0.05)
    )
    val (program, report) = calibrate(
      DynamicPredict(kindLayout),
      labeled(Seq.fill(4)(Seq("x", "y")).flatten, "kind"),
      equalOn("kind"),
      context(model)
    )
    assertEquals(onlyRow(report).trainScore, 1.0)
    val weights  = program.decisions.get("kind").flatMap(_.weights).getOrElse(fail("no weights"))
    val (wx, wy) = (weights.getOrElse("x", 1.0), weights.getOrElse("y", 1.0))
    assert(0.95 * wx < 0.05 * wy && 0.99 * wx > 0.01 * wy, weights.toString)
  }

  test("many distinct probabilities are thinned to the candidate cap") {
    val model = FakeSystemOne((state, _) => noul(input(state, "pair").toInt / 200.0))
    val train = (0 until 200).toVector.map(i =>
      Example(values = DynamicValues.record("pair" := i.toString, "match" := i >= 120), inputKeys = Set("pair"))
    )
    val (program, report) = calibrate(
      DynamicPredict(matchLayout),
      train,
      equalOn("match"),
      context(model),
      ReAnchorConfig(numThreads = 4)
    )
    val row = onlyRow(report)
    assertEquals(row.observed.get("distinct"), Some(200.0))
    // The midpoints of the gaps, and the zero end.
    assert(row.observed.getOrElse("candidates", Double.MaxValue) <= ReAnchorSearch.MaxCandidates + 1, row.toString)
    val t = threshold(program).getOrElse(fail("no threshold"))
    assert(0.55 < t && t <= 0.6 && row.trainScore >= 0.97, row.toString)
  }

  test("the evidence is recorded for the predictor that decoded it") {
    val model                = FakeSystemOne((_, name) => if name == "match" then noul(0.9) else choice("x" -> 0.6, "y" -> 0.4))
    val program              = RaTwo(DynamicPredict(matchLayout), DynamicPredict(kindLayout))
    val keys                 = summon[OptimizableStructure[RaTwo]].readIdentified(program).map(_.metadata.key)
    def call(names: String*) =
      runInputs(program, DynamicValues.record("items" -> DynamicValue.Sequence(Chunk.from(names.map(text)))))
    val (_, log) = run(context(model))(DecisionEvidenceLog.recording(call("a", "b", "c")))
    val _        = run(context(model))(call("d"))
    assertEquals(log.size, 4)
    assertEquals(log.count(entry => entry.predictor == keys(0) && entry.field == "match"), 3)
    assertEquals(
      log.last,
      DecisionEvidenceLog.Entry(keys(1), "kind", DecisionEvidence.Choice(Map("x" -> 0.6, "y" -> 0.4), 0.7))
    )
  }

  test("concurrent evidence logs are apart and reach the workers of the parallel executor") {
    val model = FakeSystemOne((state, _) => noul(if input(state, "pair") == "a" then 0.2 else 0.8))
    run(context(model, cached = false)) {
      val captured                                                                             = ContextPropagation.captureAll
      val barrier                                                                              = new CyclicBarrier(2)
      val pool                                                                                 = Executors.newFixedThreadPool(2)
      def collect(pair: String): Callable[(DynamicPredict, Vector[DecisionEvidenceLog.Entry])] =
        () =>
          captured.run {
            val predict  = DynamicPredict(matchLayout)
            val (_, log) = DecisionEvidenceLog.recording {
              val _ = barrier.await(10, TimeUnit.SECONDS)
              val _ = ParallelExecutor(numThreads = ThreadCount(2)).execute(
                (_: Int) => predict(DynamicValues.record("pair" := pair))(using RuntimeEnvironment.current),
                Vector(0, 1)
              )(using RuntimeEnvironment.current)
              barrier.await(10, TimeUnit.SECONDS)
            }
            (predict, log)
          }
      try
        val results = Vector("a", "b").map(pair => pool.submit(collect(pair))).map(_.get(30, TimeUnit.SECONDS))
        results.zip(Vector(0.2, 0.8)).foreach { case ((predict, log), p) =>
          val key = PredictorKey.from(predict.moduleName, predict.layout)
          assertEquals(log.size, 2)
          assert(log.forall(_ == DecisionEvidenceLog.Entry(key, "match", DecisionEvidence.Noul(p))), log.toString)
        }
      finally
        val _ = pool.shutdownNow()
    }
  }

  test("Score cuts stay when their whole gain is one example") {
    val levels = Map(
      "lo"  -> score(0.8, 0.1, 0.1),
      "hi"  -> score(0.1, 0.0, 0.9),
      "odd" -> score(0.5, 0.3, 0.2)
    )
    val model             = FakeSystemOne((state, _) => levels(stem(input(state, "item"))))
    val names             = (0 until 19).map(i => s"lo$i") ++ Seq("odd0") ++ (0 until 20).map(i => s"hi$i")
    val (program, report) = calibrate(
      DynamicPredict(rateLayout),
      ratings(names.map(k => k -> (if k.startsWith("hi") then 2 else 0))),
      levelMetric,
      context(model)
    )
    assertEquals(program.decisions, Map.empty[String, DecisionSettings])
    assert(onlyRow(report).foldCheck._2 >= 1, report.toString)
  }

  test("Choice weights stay when their whole gain is one example") {
    val leans = Map(
      "x"   -> choice("x" -> 0.8, "y" -> 0.2),
      "y"   -> choice("x" -> 0.2, "y" -> 0.8),
      "odd" -> choice("x" -> 0.55, "y" -> 0.45)
    )
    val model             = FakeSystemOne((state, _) => leans(stem(input(state, "item"))))
    val names             = (0 until 20).map(i => s"x$i") ++ (0 until 19).map(i => s"y$i") ++ Seq("odd0")
    val (program, report) = calibrate(
      DynamicPredict(kindLayout),
      items(names.map(k => k -> text(if k.startsWith("x") then "x" else "y")), "kind"),
      equalOn("kind"),
      context(model)
    )
    assertEquals(program.decisions, Map.empty[String, DecisionSettings])
    assert(onlyRow(report).foldCheck._2 >= 1, report.toString)
  }

  // ── the port ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("the evidence of the Predict inside a ChainOfThought belongs to the ChainOfThought leaf") {
    val signature = Signature.derived[RaPairInput, RaSameOutput]("Match", "Compare the pair.")
    val program   = ChainOfThought(signature)
      .withDecision("same", DecisionSettings(instructions = Some(text("Are the two the same?"))))
      .fold(error => fail(error.message), identity)
    val model = ComputedLm((_, evidence) =>
      Vector("reasoning" -> "They match.", "same" -> (if evidence then """{"noul": 0.9}""" else "True"))
    )
    val (answer, log) = run(context(model))(DecisionEvidenceLog.recording(program(RaPairInput("same"))))
    assertEquals(answer.map(_.output.same), Right(true))
    val key = summon[OptimizableLeaf[ChainOfThought[RaPairInput, RaSameOutput]]].metadata(program).key
    assertEquals(key.moduleName, "chain_of_thought")
    assertEquals(log, Vector(DecisionEvidenceLog.Entry(key, "same", DecisionEvidence.Noul(0.9))))
  }

  test("the search rules give the values of upstream calibrate.py") {
    // The folds of `random.Random(0).shuffle`, from the upstream Python environment.
    assertEquals(ReAnchorSearch.folds(4), Vector(Vector(2), Vector(0), Vector(1), Vector(3)))
    assertEquals(
      ReAnchorSearch.folds(18),
      Vector(Vector(11, 15, 14, 1), Vector(9, 10, 6, 13), Vector(0, 3, 7, 12), Vector(16, 5, 8), Vector(2, 17, 4))
    )
    assertEquals(ReAnchorSearch.folds(40).head, Vector(0, 15, 7, 37, 8, 4, 11, 32))
    assertEquals(
      ReAnchorSearch.gaps(Vector(0.7, 0.9), 0.0, 1.0),
      Vector((0.3, 0.7), (0.8, 0.20000000000000007), (0.95, 0.09999999999999998))
    )
    val thinned = ReAnchorSearch.gaps((0 until 200).toVector.map(_ / 200.0), 0.0, 1.0)
    assertEquals(thinned.size, 40)
    assertEquals(
      thinned.take(5),
      Vector(
        (0.003, 0.005),
        (0.02, 0.024999999999999998),
        (0.04, 0.025),
        (0.07, 0.030000000000000006),
        (0.1, 0.024999999999999994)
      )
    )
    assertEquals(
      ReAnchorSearch.gaps(Vector(0.6666666666666666, 0.5), 0.1, 2.6, geometric = true),
      Vector((0.2, 1.6094379124341003), (0.6, 0.28768207245178085), (1.3, 1.3609765531356008))
    )
    assertEquals(ReAnchorSearch.fsum(Vector(0.1, 0.2, 0.3, 1e100, -1e100)), 0.6)
    assertEquals(
      Vector((0.35, 1), (0.45, 1), (0.65, 1), (2.675, 2), (0.125, 2)).map(ReAnchorSearch.roundTo),
      Vector(0.3, 0.5, 0.7, 2.67, 0.12)
    )
  }
