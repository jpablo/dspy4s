/**
 * DSPy Cheatsheet
 *
 * Source:   docs/docs/cheatsheet.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/cheatsheet.md
 * Status:   translated (the portable snippets: modules 1–5/7/8, metrics/eval 9/11, retrieval 6, optimizers
 *           12/13/18/19/21/22/23/24, save/load 16/17, tools 27, streaming 28, usage 30, cache 31, refinement
 *           32–35). The few that remain depend on subsystems dspy4s doesn't have and are marked inline:
 *             - LLM-as-judge metric (10): now expressible (G-6) — see learn/evaluation/Metrics + `SemanticF1`
 *             - async `asyncify` (29): no async program path
 *             - optimizers not ported: BootstrapFinetune/HFModel (20, G-16), Optuna (25, G-17), SIMBA (26, G-13)
 *           These constructs are demonstrated more fully in the dedicated tutorial examples (output_refinement,
 *           streaming, cache, learn/evaluation, learn/optimization, tutorials/saving).
 */
package dspy4s.examples

import dspy4s.core.contracts.{:=, DspyError, DynamicPrediction, DynamicValues, Example, RuntimeContext}
import dspy4s.core.runtime.SubprocessPythonInterpreter
import dspy4s.evaluate.{Evaluate, EvaluateConfig}
import dspy4s.evaluate.contracts.Metric
import dspy4s.evaluate.metrics.FunctionMetric
import dspy4s.lm.runtime.UsageTracking
import dspy4s.lm.contracts.Embedder
import dspy4s.optimize.{BootstrapFewShot, BootstrapFewShotConfig, BootstrapFewShotWithRandomSearch,
  COPRO, COPROConfig, Ensemble, KNNFewShot, LabeledFewShot, LabeledFewShotConfig, MIPROv2, MIPROv2Config,
  ProgramPersistence, RandomSearchConfig}
import dspy4s.programs.{BestOfN, ChainOfThought, CodeAct, DynamicPredict, Parallel, Predict, ProgramOfThought, ReAct, Refine}
import dspy4s.programs.contracts.{DynamicModule, ProgramCall, ToolFunction, TypedCall, description}
import dspy4s.programs.retrievers.KNN
import dspy4s.typed.{InputField, OutputField, Signature, Spec}
import zio.blocks.schema.DynamicValue

// ── Snippet 2 (lines 24–30) — a class-based signature ──
// | class BasicQA(dspy.Signature): """Answer questions with short factoid answers."""
// |     question: str = dspy.InputField(); answer: str = dspy.OutputField(desc="often between 1 and 5 words")
// (dspy4s `Spec` carries no per-field `desc`; the hint is dropped.)
trait BasicQA extends Spec:
  def question: InputField[String]
  def answer:   OutputField[String]

object Cheatsheet:

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  // ── Snippet 1 (lines 17–20) — Predict with per-call config ──
  // | predict = dspy.Predict("question -> answer"); predict(question="1+1", config={"rollout_id": 1, "temperature": 1.0})
  // `rollout_id` is a framework control → the typed `rolloutId`; `temperature` is a provider knob → `config`.
  def predictWithConfig(using RuntimeContext): Either[DspyError, String] =
    Predict(Signature.fromString("question -> answer"))
      .apply(TypedCall((question = "1+1"), config = rec("temperature" := 1.0), rolloutId = Some(1)))
      .map(_.output.answer)

  // ── Snippet 3 (lines 34–40) — ChainOfThought ──
  // | generate_answer = dspy.ChainOfThought(BasicQA); generate_answer(question="What is the color of the sky?")
  def chainOfThought(question: String)(using RuntimeContext): Either[DspyError, String] =
    ChainOfThought(Signature.of[BasicQA]).apply((question = question)).map(_.output.answer)

  // ── Snippet 4 (lines 44–52) — ProgramOfThought ──
  // | pot = dspy.ProgramOfThought(BasicQA); pot(question="Sarah has 5 apples...")
  // dspy4s PoT executes generated Python via a `CodeInterpreter` (here a python3 subprocess).
  def programOfThought(question: String)(using RuntimeContext): Either[DspyError, String] =
    ProgramOfThought(Signature.of[BasicQA], interpreter = new SubprocessPythonInterpreter())
      .apply((question = question)).map(_.output.answer)

  // ── Snippet 5 (lines 56–64) — ReAct ──
  // | react_module = dspy.ReAct(BasicQA); react_module(question="Sarah has 5 apples...")
  // ReAct needs a tool set; Python's `dspy.ReAct(BasicQA)` with no tools is degenerate (finish-only) — so is this.
  def react(question: String)(using RuntimeContext): Either[DspyError, String] =
    ReAct(baseSignature = Signature.of[BasicQA], tools = Vector.empty).apply((question = question)).map(_.output.answer)

  // ── Snippet 6 (lines 68–82) — retrieval ──
  // The legacy `dspy.ColBERTv2` / `dspy.Retrieve` global-RM path is deliberately not ported; the modern
  // embedding-retrieval track IS (PORT_GAPS G-10): `Embedder`/`OpenAiEmbedder` (lm), `KNN`/`EmbeddingsRetriever`
  // (programs.retrievers), `KNNFewShot` (optimize). A KNN retriever over a trainset, given an embedder:
  def knnRetriever(trainset: Vector[Example], embedder: Embedder)(using RuntimeContext): Either[DspyError, KNN] =
    KNN.create(k = 3, trainset = trainset, embedder = embedder)

  // ── Snippet 7 (lines 86–98) — CodeAct ──
  // | def factorial(n): ...; act = CodeAct("n -> factorial", tools=[factorial]); act(n=5)  # 120
  // dspy4s CodeAct generates+runs Python through a `CodeInterpreter`. `tools` exist too: pass ToolFunctions
  // (listed in the prompt) and, on a sandboxed DenoPyodideInterpreter, wire `program.sandboxTools` into the
  // interpreter so generated Python can call them. Here the model just writes the factorial itself.
  def codeAct(n: Int)(using RuntimeContext): Either[DspyError, String] =
    CodeAct(Signature.fromString("n: int -> factorial"), interpreter = new SubprocessPythonInterpreter())
      .apply((n = n)).map(_.output.factorial)

  // ── Snippet 8 (lines 102–114) — Parallel ──
  // | parallel = dspy.Parallel(num_threads=2); parallel([(predict, ex1), (predict, ex2)])
  def parallel(using RuntimeContext): Either[DspyError, Vector[Option[DynamicPrediction]]] =
    val predict = DynamicPredict(Signature.fromString("question -> answer").layout)
    Parallel(numThreads = Some(2)).apply(Vector(
      predict -> ProgramCall(inputs = rec("question" := "1+1")),
      predict -> ProgramCall(inputs = rec("question" := "2+2"))
    )).map(_.results)

  // ── Snippet 9 (lines 122–143) — a function metric (gsm8k) ──
  // | def gsm8k_metric(gold, pred, trace=None): return parse_integer_answer(gold.answer) == parse_integer_answer(pred.answer)
  private def parseIntegerAnswer(answer: String): Int =
    answer.trim.split("\n").headOption.getOrElse("")
      .split("\\s+").reverse.find(_.exists(_.isDigit))                 // last token containing a digit
      .map(_.takeWhile(_ != '.').filter(_.isDigit))                    // up to a '.', digits only
      .flatMap(_.toIntOption).getOrElse(0)

  val gsm8kMetric: FunctionMetric = FunctionMetric.bool("gsm8k_metric") { (gold, pred) =>
    parseIntegerAnswer(gold.get("answer").map(DynamicValues.renderText).getOrElse("")) ==
      parseIntegerAnswer(pred.get("answer").map(DynamicValues.renderText).getOrElse(""))
  }

  // ── Snippet 10 (lines 147–161) — LLM-as-judge metric ──
  // Now expressible (PORT_GAPS G-6): `Metric.score` carries `(using RuntimeContext)`, so a metric can run a
  // judge sub-program over an LM. See learn/evaluation/Metrics (snippet 6) and the ported `SemanticF1` /
  // `CompleteAndGrounded` in `dspy4s.evaluate.metrics`.

  // ── Snippet 11 (lines 165–171) — Evaluate ──
  // | evaluate_program = Evaluate(devset=devset, metric=..., num_threads=..., display_progress=True, display_table=n)
  def evaluator(devset: Vector[Example], metric: Metric): Evaluate =
    new Evaluate(EvaluateConfig(devset = devset, metric = metric, numThreads = Some(4),
      displayProgress = true, displayTable = Right(5)))

  // ── Snippets 12/13/18 — few-shot optimizers (operate on the untyped DynamicPredict) ──
  // | LabeledFewShot(k=8).compile(student, trainset)
  // --8<-- [start:opt-labeled]
  def labeledFewShot(student: DynamicPredict, trainset: Vector[Example])(using RuntimeContext)
      : Either[DspyError, DynamicPredict] =
    new LabeledFewShot[DynamicPredict](LabeledFewShotConfig(k = 8)).compile(student, trainset).map(_.bestProgram)
  // --8<-- [end:opt-labeled]

  // | BootstrapFewShot(metric=..., max_bootstrapped_demos=4, max_labeled_demos=16, max_rounds=1, max_errors=10).compile(...)
  // --8<-- [start:opt-bootstrap]
  def bootstrapFewShot(metric: Metric, student: DynamicPredict, trainset: Vector[Example])(using RuntimeContext)
      : Either[DspyError, DynamicPredict] =
    new BootstrapFewShot[DynamicPredict](BootstrapFewShotConfig(
      metric = Some(metric), maxBootstrappedDemos = 4, maxLabeledDemos = 16, maxRounds = 1, maxErrors = 10
    )).compile(student, trainset).map(_.bestProgram)
  // --8<-- [end:opt-bootstrap]

  // | BootstrapFewShotWithRandomSearch(metric=..., max_bootstrapped_demos=2, num_candidate_programs=8).compile(student, trainset, valset=devset)
  def bootstrapRandomSearch(metric: Metric, student: DynamicPredict, trainset: Vector[Example], devset: Vector[Example])(
      using RuntimeContext): Either[DspyError, DynamicPredict] =
    new BootstrapFewShotWithRandomSearch[DynamicPredict](RandomSearchConfig(
      metric = metric, maxBootstrappedDemos = 2, numCandidates = 8
    )).compile(student, trainset, valset = Some(devset)).map(_.bestProgram)

  // ── Snippets 16/17 — save / load ──
  // Ported (PORT_GAPS G-4): persist a program's learnable state (per-predictor signature + demos + config) as
  // JSON via `ProgramPersistence`. `load` takes a freshly-recreated program and writes the saved state back.
  def save(program: DynamicPredict, path: String): Either[DspyError, Unit] = ProgramPersistence.save(program, path)
  def load(fresh: DynamicPredict, path: String): Either[DspyError, DynamicPredict] = ProgramPersistence.load(fresh, path)

  // ── Snippets 19/21/22/23/24 — optimizers now ported ──
  // | Ensemble(reduce_fn=dspy.majority).compile([prog1, prog2, prog3])
  def ensemble(members: Vector[DynamicModule]): DynamicModule = Ensemble().compile(members)

  // | COPRO(metric=..., breadth=10, depth=3).compile(student, trainset=trainset)
  // --8<-- [start:opt-copro]
  def copro(metric: Metric, student: DynamicPredict, trainset: Vector[Example])(using RuntimeContext)
      : Either[DspyError, DynamicPredict] =
    new COPRO[DynamicPredict](COPROConfig(metric = metric)).compile(student, trainset).map(_.bestProgram)
  // --8<-- [end:opt-copro]

  // | MIPROv2(metric=..., auto="light").compile(student, trainset=trainset)   (dspy4s: explicit knobs, no `auto`)
  // --8<-- [start:opt-miprov2]
  def miprov2(metric: Metric, student: DynamicPredict, trainset: Vector[Example], devset: Vector[Example])(
      using RuntimeContext): Either[DspyError, DynamicPredict] =
    new MIPROv2[DynamicPredict](MIPROv2Config(metric = metric))
      .compile(student, trainset, valset = Some(devset)).map(_.bestProgram)
  // --8<-- [end:opt-miprov2]

  // | knn = KNN(k=3, trainset, embedder); KNNFewShot(KNN=knn).compile(student)
  // --8<-- [start:opt-knn]
  def knnFewShot(student: DynamicPredict, trainset: Vector[Example], embedder: Embedder)(using RuntimeContext)
      : Either[DspyError, DynamicModule] =
    new KNNFewShot[DynamicPredict](k = 3, trainset = trainset, embedder = embedder).compile(student)
  // --8<-- [end:opt-knn]

  // ── Snippets 20/25/26 — optimizers still not ported ──
  // Not portable: BootstrapFinetune/HFModel (20, weight optimization — PORT_GAPS G-16),
  // BootstrapFewShotWithOptuna (25, no JVM Optuna — G-17), SIMBA (26 — G-13).

  // ── Snippet 27 (lines 403–412) — dspy.Tool ──
  // | def search_web(query: str) -> str: """Search the web for information"""
  // | tool = dspy.Tool(search_web); tool(query="Python programming")
  @description("Search the web for information")
  def search_web(query: String): String = s"Search results for: $query"

  def tool(query: String)(using RuntimeContext): Either[DspyError, DynamicValue] =
    ToolFunction.fromMethod(search_web).invoke(rec("query" := query))

  // ── Snippet 28 (lines 416–434) — streamify ──
  // dspy4s streamify is synchronous; see tutorials/streaming for the full treatment. Sketch:
  //   Streamify.streamify(DynamicPredict(Signature.fromString("question -> answer").layout),
  //     streamListeners = Vector(StreamListener("answer")))(rec("question" := "...")) // -> ClosableIterator[StreamEvent]

  // ── Snippet 29 — asyncify ──
  // Not portable: dspy4s has no async program path.

  // ── Snippet 30 (lines 451–457) — usage tracking ──
  // | dspy.configure(track_usage=True); result = dspy.ChainOfThought(BasicQA)(question="..."); result.get_lm_usage()
  def withUsageTracking(question: String)(using RuntimeContext): Either[DspyError, Map[String, dspy4s.lm.contracts.LmUsage]] =
    UsageTracking.withNewTracker { tracker =>
      ChainOfThought(Signature.of[BasicQA]).apply((question = question)).map(_ => tracker.totalUsage)
    }

  // ── Snippet 31 (lines 461–469) — cache configuration ──
  // dspy4s has no global `dspy.configure_cache`; wrap the LM in `ManagedLanguageModel(lm, cache = ...)`.
  // See tutorials/cache for NoopLmCache / InMemoryLmCache / DiskLmCache and a custom LmCache.

  // ── Snippets 32/33 (lines 479–503) — BestOfN / Refine ──
  // | best_of_3 = dspy.BestOfN(module=qa, N=3, reward_fn=one_word_answer, threshold=1.0); best_of_3(question=...).answer
  // The reward `one_word_answer(args, pred)` becomes the typed `(input, prediction) => Double` below.
  // --8<-- [start:best-of-n]
  def bestOfN(question: String)(using RuntimeContext): Either[DspyError, String] =
    val qa = ChainOfThought(Signature.of[BasicQA])
    BestOfN(module = qa, n = 3, rewardFn = (_, pred) => if pred.output.answer.length == 1 then 1.0 else 0.0,
      threshold = 1.0).apply((question = question)).map(_.output.answer)
  // --8<-- [end:best-of-n]

  // ── Snippets 34/35 (lines 509–522) — Refine with fail_count ──
  // | refine = dspy.Refine(module=qa, N=3, reward_fn=..., threshold=1.0, fail_count=1)  # raise after 1 failure
  def refine(question: String, failCount: Int)(using RuntimeContext): Either[DspyError, String] =
    val qa = ChainOfThought(Signature.of[BasicQA])
    Refine(module = qa, n = 3, rewardFn = (_, pred) => if pred.output.answer.length == 1 then 1.0 else 0.0,
      threshold = 1.0, failCount = Some(failCount)).apply((question = question)).map(_.output.answer)

// Pure surface check (no LM). Run with: sbt "examples/runMain dspy4s.examples.cheatsheetMain"
@main def cheatsheetMain(): Unit =
  println("gsm8k_metric is a FunctionMetric named: " + Cheatsheet.gsm8kMetric.name)
