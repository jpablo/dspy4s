/** DSPy Cheatsheet
  *
  * Source: docs/docs/cheatsheet.md Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/cheatsheet.md
  * Status: translated. The page has 35 snippets. This file has code for snippets 1–3, 5, 6, 8, 9, 11–13, 15–19, 21–24,
  * 27, 29, 30, and 32–35. A comment takes the place of each other snippet:
  *   - snippets 4 and 7 (`ProgramOfThought`, `CodeAct`): dspy4s does not have these modules. `RLM` is the replacement.
  *   - snippet 10 (an LM as the judge of a metric): `tweetMetric` in learn/evaluation/Metrics has the same form.
  *   - snippet 14 (`teacher_settings`): this option has no port.
  *   - snippets 20, 25, and 26 (`BootstrapFinetune` and `HFModel`, `BootstrapFewShotWithOptuna`, `SIMBA`): dspy4s does
  *     not have these optimizers (PORT_GAPS G-16, G-17, G-13).
  *   - snippets 28 and 31 (`streamify`, `configure_cache`): tutorials/streaming and tutorials/cache show them.
  *
  * The dedicated examples show more of these constructs: tutorials/output_refinement, tutorials/streaming,
  * tutorials/cache, learn/evaluation, learn/optimization, and tutorials/saving.
  */
package dspy4s.examples

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, ErrorLimit, RuntimeContext, ThreadCount}
import dspy4s.core.data.{RawPrediction, Example}
import dspy4s.evaluate.{Evaluate, TableDisplay}
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.FunctionMetric
import dspy4s.lm.runtime.UsageTracking
import dspy4s.lm.contracts.Embedder
import dspy4s.optimize.{
  BootstrapFewShot,
  BootstrapFewShotConfig,
  BootstrapFewShotWithRandomSearch,
  COPRO,
  COPROConfig,
  DemoCount,
  Ensemble,
  KNNFewShot,
  LabeledFewShot,
  LabeledFewShotConfig,
  MIPROv2,
  MIPROv2Config,
  RandomSearchConfig,
  RoundCount,
  SearchCandidateCount
}
import dspy4s.programs.{
  AttemptCount,
  BestOfN,
  ChainOfThought,
  DynamicPredict,
  FailureCount,
  Parallel,
  Predict,
  ReAct,
  Refine
}
import dspy4s.programs.contracts.{DynamicModule, ProgramCall, ToolFunction, description}
import dspy4s.programs.optimization.ProgramPersistence
import dspy4s.programs.retrievers.{KNN, NeighborCount, NonEmptyTrainset}
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.DynamicValue

import scala.concurrent.{ExecutionContext, Future}

// ── Snippet 2 (lines 24–30) — a class-based signature ──
// | class BasicQA(dspy.Signature): """Answer questions with short factoid answers."""
// |     question: str = dspy.InputField(); answer: str = dspy.OutputField(desc="often between 1 and 5 words")
// The docstring goes to `instructions`, where `Cheatsheet.basicQa` makes the signature. A `Spec` has no `desc` for a
// field, so the hint of `answer` has no port.
trait BasicQA extends Spec:
  def question: InputField[String]
  def answer: OutputField[String]

object Cheatsheet:

  private val basicQa = Signature.of[BasicQA](instructions = "Answer questions with short factoid answers.")

  // ── Snippet 1 (lines 17–20) — Predict with per-call config ──
  // | predict = dspy.Predict("question -> answer"); predict(question="1+1", config={"rollout_id": 1, "temperature": 1.0})
  // `rollout_id` is a framework control → the `rolloutId`; `temperature` is a provider knob → `config`.
  def predictWithConfig(using RuntimeContext): Either[DspyError, String] =
    Predict(Signature.fromString("question -> answer"))(
      ProgramCall((question = "1+1"), config = DynamicValues.record("temperature" := 1.0), rolloutId = Some(1))
    )
      .map(_.output.answer)

  // ── Snippet 3 (lines 34–40) — ChainOfThought ──
  // | generate_answer = dspy.ChainOfThought(BasicQA); generate_answer(question="What is the color of the sky?")
  def chainOfThought(question: String)(using RuntimeContext): Either[DspyError, String] =
    ChainOfThought(basicQa)((question = question)).map(_.output.answer)

  // ── Snippets 4 (lines 44–52) and 7 (lines 86–98): ProgramOfThought and CodeAct ──
  // Not ported. Upstream deprecated the two modules in DSPy 3.4. `RLM` is the replacement.

  // ── Snippet 5 (lines 56–64) — ReAct ──
  // | react_module = dspy.ReAct(BasicQA); react_module(question="Sarah has 5 apples...")
  // The call on the page raises `TypeError`, because upstream `ReAct` requires `tools` (`dspy/predict/react.py:17`).
  // `ReAct` in dspy4s also requires `tools`. With an empty list, the only step of the agent is `finish`.
  def react(question: String)(using RuntimeContext): Either[DspyError, String] =
    ReAct(baseSignature = basicQa, tools = Vector.empty)((question = question)).map(_.output.answer)

  // ── Snippet 6 (lines 68–82) — retrieval ──
  // The legacy `dspy.ColBERTv2` / `dspy.Retrieve` global-RM path is deliberately not ported; the modern
  // embedding-retrieval track IS (PORT_GAPS G-10): `Embedder`/`OpenAiEmbedder` (lm), `KNN`/`EmbeddingsRetriever`
  // (programs.retrievers), `KNNFewShot` (optimize). A KNN retriever over a trainset, given an embedder:
  def knnRetriever(trainset: NonEmptyTrainset, embedder: Embedder)(using RuntimeContext): Either[DspyError, KNN] =
    KNN.create(k = NeighborCount(3), trainset = trainset, embedder = embedder)

  // ── Snippet 8 (lines 102–114) — Parallel ──
  // | parallel = dspy.Parallel(num_threads=2); parallel([(predict, ex1), (predict, ex2)])
  def parallel(using RuntimeContext): Either[DspyError, Vector[Either[DspyError, RawPrediction]]] =
    val predict = DynamicPredict(Signature.fromString("question -> answer").layout)
    Parallel(numThreads = Some(ThreadCount(2))).run(Vector(
      predict -> ProgramCall(input = DynamicValues.record("question" := "1+1")),
      predict -> ProgramCall(input = DynamicValues.record("question" := "2+2"))
    )).map(_.results)

  // ── Snippet 9 (lines 122–143) — a function metric (gsm8k) ──
  // | def gsm8k_metric(gold, pred, trace=None): return parse_integer_answer(gold.answer) == parse_integer_answer(pred.answer)
  private def parseIntegerAnswer(answer: String): Int =
    answer.trim.split("\n").headOption.getOrElse("")
      .split("\\s+").reverse.find(_.exists(_.isDigit)) // last token containing a digit
      .map(_.takeWhile(_ != '.').filter(_.isDigit))    // up to a '.', digits only
      .flatMap(_.toIntOption).getOrElse(0)

  val gsm8kMetric: FunctionMetric = FunctionMetric.bool("gsm8k_metric") { (gold, pred) =>
    parseIntegerAnswer(gold.get("answer").map(DynamicValues.renderText).getOrElse("")) ==
      parseIntegerAnswer(pred.get("answer").map(DynamicValues.renderText).getOrElse(""))
  }

  // ── Snippet 10 (lines 147–161): an LM as the judge of a metric ──
  // | judge = dspy.ChainOfThought(FactJudge)
  // | def factuality_metric(example, pred):
  // |     factual = judge(context=example.context, question=example.question, answer=pred.answer)
  // |     return factual.factually_correct
  // `Metric.score` gets a `RuntimeContext`, so a metric can run a judge program over an LM. `tweetMetric` in
  // learn/evaluation/Metrics has the same form, and `dspy4s.evaluate.metrics` has `SemanticF1` and `CompleteAndGrounded`.

  // ── Snippet 11 (lines 165–171) — Evaluate ──
  // | evaluate_program = Evaluate(devset=devset, metric=..., num_threads=..., display_progress=True, display_table=n)
  def evaluator(devset: Vector[Example], metric: Metric): Evaluate =
    Evaluate(
      devset = devset,
      metric = metric,
      numThreads = Some(ThreadCount(4)),
      displayProgress = true,
      displayTable = TableDisplay.Rows(5)
    )

  // ── Snippets 12/13/18 — few-shot optimizers (these snippets instantiate generic optimizers with DynamicPredict) ──
  // | LabeledFewShot(k=8).compile(student, trainset)
  // --8<-- [start:opt-labeled]
  def labeledFewShot(student: DynamicPredict, trainset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, DynamicPredict] =
    new LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = DemoCount(8)))
      .compile(student, trainset)
      .map(_.bestProgram)
  // --8<-- [end:opt-labeled]

  // | BootstrapFewShot(metric=..., max_bootstrapped_demos=4, max_labeled_demos=16, max_rounds=1, max_errors=10).compile(...)
  // --8<-- [start:opt-bootstrap]
  def bootstrapFewShot(metric: Metric, student: DynamicPredict, trainset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, DynamicPredict] =
    new BootstrapFewShot[DynamicPredict](BootstrapFewShotConfig(
      metric = Some(metric),
      maxBootstrappedDemos = DemoCount(4),
      maxLabeledDemos = DemoCount(16),
      maxRounds = RoundCount(1),
      maxErrors = Some(ErrorLimit(10))
    )).compile(student, trainset).map(_.bestProgram)
  // --8<-- [end:opt-bootstrap]

  // ── Snippet 14 (lines 196–202): `teacher_settings` ──
  // | fewshot_optimizer = BootstrapFewShot(metric=..., teacher_settings=dict(lm=gpt4))
  // This option has no port.

  // ── Snippet 15 (lines 206–212): compile a compiled program ──
  // | your_dspy_program_compiledx2 = teleprompter.compile(your_dspy_program, teacher=your_dspy_program_compiled, trainset=trainset)
  def bootstrapFromCompiled(
      optimizer: BootstrapFewShot[DynamicPredict],
      student  : DynamicPredict,
      compiled : DynamicPredict,
      trainset : Vector[Example]
  )(using RuntimeContext): Either[DspyError, DynamicPredict] =
    optimizer.compile(student, trainset, teacher = Some(compiled)).map(_.bestProgram)

  // | BootstrapFewShotWithRandomSearch(metric=..., max_bootstrapped_demos=2, num_candidate_programs=8).compile(student, trainset, valset=devset)
  def bootstrapRandomSearch(
      metric  : Metric,
      student : DynamicPredict,
      trainset: Vector[Example],
      devset  : Vector[Example]
  )(
      using RuntimeContext
  ): Either[DspyError, DynamicPredict] =
    new BootstrapFewShotWithRandomSearch[DynamicPredict](RandomSearchConfig(
      metric = metric,
      maxBootstrappedDemos = DemoCount(2),
      numCandidates = SearchCandidateCount(8)
    )).compile(student, trainset, valset = Some(devset)).map(_.bestProgram)

  // ── Snippets 16/17 — save / load ──
  // Ported (PORT_GAPS G-4): persist each leaf's `OptimizableParameters` (instructions + demos + module config) as JSON.
  // `load` applies it to a fresh program with the same optimizable structure and leaf order; metadata/resources stay fresh.
  def save(program: DynamicPredict, path: String): Either[DspyError, Unit]         = ProgramPersistence.save(program, path)
  def load(fresh: DynamicPredict, path: String): Either[DspyError, DynamicPredict] =
    ProgramPersistence.load(fresh, path)

  // ── Snippets 19/21/22/23/24: more optimizers ──
  // | Ensemble(reduce_fn=dspy.majority).compile([prog1, prog2, prog3])
  def ensemble(members: Vector[DynamicModule]): DynamicModule = Ensemble().compile(members)

  // | COPRO(metric=..., breadth=10, depth=3).compile(student, trainset=trainset)
  // --8<-- [start:opt-copro]
  def copro(metric: Metric, student: DynamicPredict, trainset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, DynamicPredict] =
    new COPRO[DynamicPredict](COPROConfig(metric = metric)).compile(student, trainset).map(_.bestProgram)
  // --8<-- [end:opt-copro]

  // | MIPROv2(metric=..., auto="light").compile(student, trainset=trainset)   (dspy4s: explicit knobs, no `auto`)
  // --8<-- [start:opt-miprov2]
  def miprov2(metric: Metric, student: DynamicPredict, trainset: Vector[Example], devset: Vector[Example])(
      using RuntimeContext
  ): Either[DspyError, DynamicPredict] =
    new MIPROv2[DynamicPredict](MIPROv2Config(metric = metric))
      .compile(student, trainset, valset = Some(devset)).map(_.bestProgram)
  // --8<-- [end:opt-miprov2]

  // ── Snippet 23 (lines 333–358): the instructions only (0-shot) ──
  // | teleprompter = MIPROv2(metric=gsm8k_metric, auto="light")
  // | optimized_program = teleprompter.compile(program.deepcopy(), trainset=trainset, max_bootstrapped_demos=0, max_labeled_demos=0)
  // With the two demo counts at zero, the optimizer searches the instructions only, and each predictor keeps its demos.
  def miprov2ZeroShot(metric: Metric, student: DynamicPredict, trainset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, DynamicPredict] =
    new MIPROv2[DynamicPredict](MIPROv2Config(
      metric = metric,
      maxBootstrappedDemos = DemoCount(0),
      maxLabeledDemos = DemoCount(0)
    )).compile(student, trainset).map(_.bestProgram)

  // ── Snippet 24 (lines 362–371): KNNFewShot ──
  // | knn_optimizer = KNNFewShot(k=3, trainset=trainset, vectorizer=Embedder(SentenceTransformer("all-MiniLM-L6-v2").encode))
  // | qa_compiled = knn_optimizer.compile(student=ChainOfThought("question -> answer"))
  // --8<-- [start:opt-knn]
  def knnFewShot(student: DynamicPredict, trainset: NonEmptyTrainset, embedder: Embedder)(using
      RuntimeContext
  ): Either[DspyError, DynamicModule] =
    new KNNFewShot[DynamicPredict](k = NeighborCount(3), trainset = trainset, embedder = embedder).compile(student)
  // --8<-- [end:opt-knn]

  // ── Snippets 20/25/26 — optimizers still not ported ──
  // Not ported: BootstrapFinetune and HFModel (20, weight optimization, PORT_GAPS G-16),
  // BootstrapFewShotWithOptuna (25, no Optuna on the JVM, G-17), and SIMBA (26, G-13).

  // ── Snippet 27 (lines 403–412) — dspy.Tool ──
  // | def search_web(query: str) -> str: """Search the web for information"""
  // | tool = dspy.Tool(search_web); tool(query="Python programming")
  @description("Search the web for information")
  def search_web(query: String): String = s"Search results for: $query"

  def tool(query: String)(using RuntimeContext): Either[DspyError, DynamicValue] =
    ToolFunction.fromMethod(search_web).invoke(DynamicValues.record("query" := query))

  // ── Snippet 28 (lines 416–434) — streamify ──
  // dspy4s streamify is synchronous; see tutorials/streaming for the full treatment. Sketch:
  //   Streamify.streamify(DynamicPredict(Signature.fromString("question -> answer").layout),
  //     streamListeners = Vector(StreamListener("answer")))(DynamicValues.record("question" := "..."))
  //     // -> ClosableIterator[StreamEvent]

  // ── Snippet 29 (lines 439–446): asyncify ──
  // | dspy_program = dspy.ChainOfThought("question -> answer")
  // | dspy_program = dspy.asyncify(dspy_program)
  // | asyncio.run(dspy_program(question="What is DSPy"))
  // `Module.applyAsync` runs the program on the `ExecutionContext` and gives a `Future`. tutorials/async shows more.
  def asyncified(question: String)(using RuntimeContext, ExecutionContext): Future[Either[DspyError, String]] =
    ChainOfThought(Signature.fromString("question -> answer"))
      .applyAsync(ProgramCall((question = question)))
      .map(_.map(_.output.answer))

  // ── Snippet 30 (lines 451–457) — usage tracking ──
  // | dspy.configure(track_usage=True); result = dspy.ChainOfThought(BasicQA)(question="..."); result.get_lm_usage()
  def withUsageTracking(question: String)(using
      RuntimeContext
  ): Either[DspyError, Map[String, dspy4s.core.contracts.LmUsage]] =
    UsageTracking.withNewTracker { tracker =>
      ChainOfThought(basicQa)((question = question)).map(_ => tracker.totalUsage)
    }

  // ── Snippet 31 (lines 461–469) — cache configuration ──
  // dspy4s has no global `dspy.configure_cache`; wrap the LM in `ManagedLanguageModel(lm, cache = ...)`.
  // See tutorials/cache for NoopLmCache / InMemoryLmCache / DiskLmCache and a custom LmCache.

  // ── Snippets 32/33 (lines 479–503) — BestOfN / Refine ──
  // | qa = dspy.ChainOfThought("question -> answer")
  // | best_of_3 = dspy.BestOfN(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0); best_of_3(question=...).answer
  // The reward `one_word_answer(args, pred)` becomes the `(input, prediction) => Double` below.
  // --8<-- [start:best-of-n]
  def bestOfN(question: String)(using RuntimeContext): Either[DspyError, String] =
    val qa = ChainOfThought(Signature.fromString("question -> answer"))
    BestOfN(
      module = qa,
      n = AttemptCount(3),
      rewardFn = (_, pred) => if pred.output.answer.length == 1 then 1.0 else 0.0,
      threshold = 1.0
    )((question = question)).map(_.output.answer)
  // --8<-- [end:best-of-n]

  // ── Snippets 34/35 (lines 509–522) — Refine with fail_count ──
  // | refine = dspy.Refine(module=qa, N=3, reward_fn=..., threshold=1.0, fail_count=1)  # raise after 1 failure
  def refine(question: String, failCount: FailureCount)(using RuntimeContext): Either[DspyError, String] =
    val qa = ChainOfThought(Signature.fromString("question -> answer"))
    Refine(
      module = qa,
      n = AttemptCount(3),
      rewardFn = (_, pred) => if pred.output.answer.length == 1 then 1.0 else 0.0,
      threshold = 1.0,
      failCount = Some(failCount)
    )((question = question)).map(_.output.answer)

// Pure surface check (no LM). Run with: sbt "examples/runMain dspy4s.examples.cheatsheetMain"
@main def cheatsheetMain(): Unit =
  println("gsm8k_metric is a FunctionMetric named: " + Cheatsheet.gsm8kMetric.name)
