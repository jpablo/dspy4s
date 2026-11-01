package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableView
import dspy4s.programs.optimization.OptimizableStructure

/** Knobs for [[InferRules]], mirroring upstream's `InferRules(num_candidates, num_rules, metric, ...)`.
  *
  * @param metric
  *   task metric used to score whole-program candidates
  * @param numCandidates
  *   number of rule-augmented candidate programs to generate and score (best wins)
  * @param numRules
  *   how many rules the induction prompt asks the LM to extract
  * @param initTemperature
  *   sampling temperature for rule induction (so each candidate gets DIFFERENT rules)
  * @param bootstrap
  *   few-shot demos are bootstrapped first (InferRules extends BootstrapFewShot upstream); this config is forwarded to
  *   a composed [[BootstrapFewShot]] run. Its metric defaults to `metric` when unset. Its `maxErrors` is also the error
  *   limit of an evaluation, as upstream `evaluate_program`.
  * @param seed
  *   RNG seed; deterministic for a fixed seed
  */
final case class InferRulesConfig(
    metric         : Metric,
    numCandidates  : CandidateCount         = CandidateCount(10),
    numRules       : RuleCount              = RuleCount(10),
    initTemperature: Double                 = 1.0,
    bootstrap      : BootstrapFewShotConfig = BootstrapFewShotConfig(),
    seed           : Long                   = 0L
)

/** InferRules induces natural-language RULES from the trainset and appends them to the instructions of each predictor.
  * A v1 port of `dspy.teleprompt.InferRules` (`dspy/teleprompt/infer_rules.py`).
  *
  * '''Algorithm.'''
  *   1. If no valset is given, split the trainset 50/50 into train and val, as upstream.
  *   1. Bootstrap few-shot demos with a composed [[BootstrapFewShot]] (upstream INHERITS it; dspy4s composes), and take
  *      its best program as the base. The bootstrap makes the check of the predictor keys (decision D2).
  *   1. For each of `numCandidates` candidates: for each predictor, induce rules from the trainset (with a
  *      [[DynamicPredict]] at `initTemperature`, and a `rolloutId` for each candidate and predictor, so the candidates
  *      get different rules). The rules go after the ORIGINAL effective instructions of that predictor. Then score the
  *      whole candidate program on the valset.
  *   1. Return the candidate with the highest score.
  *
  * A `Left` of the bootstrap, of an induction, or of an evaluation is the `Left` of `compile`, as upstream raises.
  *
  * '''Deltas from Python.'''
  *   - '''Composes [[BootstrapFewShot]] instead of inheriting it''' (dspy4s teleprompters are not a class hierarchy).
  *   - '''Rule induction uses a [[DynamicPredict]], not `ChainOfThought`.''' Upstream's `RulesInductionProgram` wraps
  *     the induction signature in CoT (a `reasoning` field); dspy4s applies only the induced rules. The reasoning field
  *     is a quality lever, not structural, and is omitted for v1 (consistent with COPRO).
  *   - '''Context-window fallback narrows on [[ContextWindowExceededError]]''' specifically (upstream also accepts
  *     `ValueError`/`BadRequestError`), dropping the last example and retrying down to a single example.
  *   - '''A blank rule text keeps the instructions of the leaf.''' Upstream appends the header with no rules.
  */
final class InferRules[P: {OptimizableStructure, ProgramRunner}](config: InferRulesConfig) extends Teleprompter[P]:

  override val name: String = "infer_rules"

  private val ps: OptimizableStructure[P] = summon[OptimizableStructure[P]]
  private val runner: ProgramRunner[P]    = summon[ProgramRunner[P]]

  override protected[optimize] def compileInScope(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      valset  : Option[Vector[Example]]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    // 1) Split if no valset (upstream's 50/50 holdout).
    val (effTrain, effVal) = valset match
      case Some(v) => (trainset, v)
      case None    =>
        val k = trainset.size / 2
        (trainset.take(k), trainset.drop(k))

    if ps.read(student).isEmpty then
      Right(OptimizerSupport.noOptimizableLeavesReport(student))
    else if effVal.isEmpty then Left(OptimizerSupport.emptyEvalsetError(name))
    else
      // 2) Bootstrap few-shot demos first; the rule-augmented search starts from that program.
      val bootstrapConfig =
        if config.bootstrap.metric.isDefined then config.bootstrap
        else config.bootstrap.copy(metric = Some(config.metric))
      // Each `Left` of the bootstrap stops the compile. Upstream `InferRules` is a subclass of `BootstrapFewShot` and
      // calls `super().compile`, which opens no compile scope inside the `InferRules` scope. Thus the bootstrap runs with
      // no compile scope of its own.
      for
        bootstrapReport <-
          new BootstrapFewShot[P](bootstrapConfig).compileInScope(student, effTrain, teacher, valset = None)
        base = bootstrapReport.bestProgram
        // 3) Generate and score `numCandidates` rule-augmented candidates.
        candidates <- Traverse.either(0 until config.numCandidates)(candidateOf(base, effTrain, effVal, _))
      yield
        val best = candidates.reduceLeft((best, next) => if next.score > best.score then next else best)
        OptimizationReport(
          bestProgram = best.program,
          candidates = candidates.sortBy(-_.score),
          metadata = Map(
            "optimizer"          -> name,
            "num_candidates"     -> candidates.size,
            "best_score"         -> best.score,
            "optimizable_leaves" -> ps.read(base).size
          )
        )

  /** One rule-augmented candidate: the rules of each leaf go after the ORIGINAL effective instructions of the leaf, so
    * they do not add up over the candidates. A `Left` of an induction or of the evaluation stops the compile, as
    * upstream raises. A blank rule text keeps the current instructions of the leaf.
    */
  private def candidateOf(base: P, trainset: Vector[Example], evalset: Vector[Example], candIdx: Int)(using
      RuntimeContext
  ): Either[DspyError, CandidateProgram[P]] =
    val leaves = ps.inspect(base)
    for
      rules <- Traverse.either(leaves.indices)(leafIdx =>
                 induceRules(leaves(leafIdx), trainset, candIdx, leafIdx, leaves.size)
               )
      program = rules.zipWithIndex.foldLeft(base) { case (prog, (induced, leafIdx)) =>
                  val augmented = induced.map(text =>
                    s"${leaves(leafIdx).layout.effectiveInstructions}\n\n" +
                      s"Please adhere to the following rules when making your prediction:\n$text"
                  )
                  OptimizerSupport.applyInstruction(prog, leafIdx, augmented)
                }
      score <-
        OptimizerSupport.evalScore(program, evalset, config.metric, runner, maxErrors = config.bootstrap.maxErrors)
    yield CandidateProgram(program, score, metadata = Map("candidate" -> candIdx))

  /** The rules of one leaf (upstream `induce_natural_language_rules`). A context-window overflow drops the last
    * example, and the call runs again. With one example left, the overflow is the `Left` of upstream. Each other `Left`
    * of the model stops the compile. The result is `None` when the model gives a blank text.
    */
  private def induceRules(
      leaf     : OptimizableView,
      trainset : Vector[Example],
      candIdx  : Int,
      leafIdx  : Int,
      leafCount: Int
  )(using RuntimeContext): Either[DspyError, Option[String]] =
    val gen = DynamicPredict(layout = ruleInductionLayout, name = Some("infer_rules_induction"))
    // The rollout ids do not overlap, for any size, so each candidate gets different rules.
    val rolloutId = OptimizerSupport.seedBase(config.seed) + candIdx * leafCount + leafIdx

    @annotation.tailrec
    def attempt(demos: Vector[Example]): Either[DspyError, Option[String]] =
      val inputs = DynamicValues.record("examples_text" := formatExamples(demos, leaf))
      OptimizerSupport.generateText(gen, inputs, "natural_language_rules", config.initTemperature, rolloutId) match
        case Left(_: ContextWindowExceededError) if demos.size > 1 => attempt(demos.dropRight(1))
        case Left(_: ContextWindowExceededError)                   => Left(RuntimeError(
            "infer_rules",
            "Failed to generate natural language rules since a single example couldn't fit in the model's context " +
              "window."
          ))
        case other => other

    attempt(trainset)

  /** The examples in the text form of upstream `format_examples`. Each example shows the fields of the leaf, in the
    * order of the example. A value has its Python `str` text.
    */
  private def formatExamples(demos: Vector[Example], leaf: OptimizableView): String =
    def render(fields: Vector[FieldSpec], example: Example): String =
      val names = fields.map(_.name).toSet
      DynamicValues.recordEntries(example.values)
        .collect { case (name, value) if names.contains(name) => s"$name: ${PythonText.str(value)}" }
        .mkString("\n")
    demos.map { example =>
      s"Input Fields:\n${render(leaf.layout.inputFields, example)}\n\n=========\n" +
        s"Output Fields:\n${render(leaf.layout.outputFields, example)}\n\n"
    }.mkString

  /** Upstream `CustomRulesInduction`. */
  private val ruleInductionLayout: SignatureLayout = SignatureLayout.of(
    name = "CustomRulesInduction",
    inputFields = Vector(
      FieldSpec(name = "examples_text", description = Some("Text containing examples"))
    ),
    outputFields = Vector(
      FieldSpec(name = "natural_language_rules", description = Some("Induced natural language rules"))
    ),
    instructions = Some(
      s"Given a set of examples, extract a list of ${config.numRules} concise and non-redundant natural language rules " +
        "that provide clear guidance for performing the task. All rules should be actionable for a well-specified " +
        "scope of examples of this general kind of task."
    )
  )
