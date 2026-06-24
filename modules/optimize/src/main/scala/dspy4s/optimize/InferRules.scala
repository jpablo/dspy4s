package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.ContextWindowExceededError
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Predictors
import dspy4s.programs.contracts.ProgramCall

import scala.collection.mutable

/** Knobs for [[InferRules]], mirroring upstream's `InferRules(num_candidates, num_rules, metric, ...)`.
  *
  * @param metric               task metric used to score whole-program candidates
  * @param numCandidates        number of rule-augmented candidate programs to generate and score (best wins)
  * @param numRules             how many rules the induction prompt asks the LM to extract
  * @param initTemperature      sampling temperature for rule induction (so each candidate gets DIFFERENT rules)
  * @param bootstrap            few-shot demos are bootstrapped first (InferRules extends BootstrapFewShot upstream);
  *                             this config is forwarded to a composed [[BootstrapFewShot]] run. Its metric defaults
  *                             to `metric` when unset.
  * @param seed                 RNG seed; deterministic for a fixed seed
  * @param inductionMarker      stable preamble for the rule-induction signature so an induction prompt is
  *                             distinguishable from a task prompt (and offline scripted LMs can branch)
  */
final case class InferRulesConfig(
    metric: Metric,
    numCandidates: Int = 10,
    numRules: Int = 10,
    initTemperature: Double = 1.0,
    bootstrap: BootstrapFewShotConfig = BootstrapFewShotConfig(),
    seed: Long = 0L,
    inductionMarker: String = "You are inducing natural-language rules from labeled examples."
):
  require(numCandidates > 0, "InferRules numCandidates must be > 0")
  require(numRules > 0, "InferRules numRules must be > 0")

/** InferRules — induce explicit natural-language RULES from the trainset and append them to each predictor's
  * instructions. A v1 port of DSPy's `dspy.teleprompt.InferRules` (`dspy/teleprompt/infer_rules.py`).
  *
  * '''Algorithm.'''
  *   1. If no valset is given, split the trainset 50/50 into train/val (matching upstream).
  *   2. Bootstrap few-shot demos via a composed [[BootstrapFewShot]] (upstream INHERITS it; dspy4s composes), and
  *      take its best program as the base.
  *   3. Snapshot each predictor's original instruction.
  *   4. For each of `numCandidates` candidates: for every predictor, induce rules from the trainset (via a rule-
  *      induction [[DynamicPredict]] run at `initTemperature` with a per-candidate `rolloutId`, so candidates get
  *      different rules) and append them to that predictor's ORIGINAL instruction; then score the whole candidate
  *      program on the valset.
  *   5. Return the highest-scoring candidate.
  *
  * '''Deltas from Python.'''
  *   - '''Composes [[BootstrapFewShot]] instead of inheriting it''' (dspy4s teleprompters are not a class hierarchy).
  *   - '''Rule induction uses a [[DynamicPredict]], not `ChainOfThought`.''' Upstream's `RulesInductionProgram`
  *     wraps the induction signature in CoT (a `reasoning` field); dspy4s applies only the induced rules. The
  *     reasoning field is a quality lever, not structural, and is omitted for v1 (consistent with COPRO).
  *   - '''Context-window fallback narrows on [[ContextWindowExceededError]]''' specifically (upstream also accepts
  *     `ValueError`/`BadRequestError`), dropping the last example and retrying down to a single example.
  */
final class InferRules[P: Predictors: Runnable](config: InferRulesConfig) extends Teleprompter[P]:

  override val name: String = "infer_rules"

  private val ps: Predictors[P]   = summon[Predictors[P]]
  private val runner: Runnable[P] = summon[Runnable[P]]

  override def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    // 1) Split if no valset (upstream's 50/50 holdout).
    val (effTrain, effVal) = valset match
      case Some(v) => (trainset, v)
      case None =>
        val k = trainset.size / 2
        (trainset.take(k), trainset.drop(k))

    if ps.read(student).isEmpty then
      Right(OptimizationReport(bestProgram = student, candidates = Vector.empty, metadata = Map("no_predictors" -> true)))
    else
      // 2) Bootstrap few-shot demos first; the rule-augmented search starts from that program.
      val bootstrapConfig =
        if config.bootstrap.metric.isDefined then config.bootstrap else config.bootstrap.copy(metric = Some(config.metric))
      new BootstrapFewShot[P](bootstrapConfig).compile(student, effTrain, teacher).map { bootstrapReport =>
        val base = bootstrapReport.bestProgram

        // 3) Snapshot each predictor's original instruction (rules are appended to THIS, not cumulatively).
        val originalInstructions = ps.read(base).map(_.layout.instructions.getOrElse(""))

        // 4) Generate + score `numCandidates` rule-augmented candidates.
        val candidates = mutable.ArrayBuffer.empty[CandidateProgram[P]]
        var best: Option[(P, Double)] = None

        (0 until config.numCandidates).foreach { candIdx =>
          val candidate = ps.read(base).indices.foldLeft(base) { (prog, predIdx) =>
            induceRules(ps.read(base)(predIdx), effTrain, candIdx, predIdx) match
              case Some(rules) =>
                val augmented = s"${originalInstructions(predIdx)}\n\n" +
                  s"Please adhere to the following rules when making your prediction:\n$rules"
                OptimizerSupport.applyInstruction(prog, predIdx, augmented)
              case None => prog // induction failed for this predictor; leave its instruction unchanged
          }
          OptimizerSupport.evalScore(candidate, effVal, config.metric, runner).foreach { score =>
            candidates += CandidateProgram(candidate, score, metadata = Map("candidate" -> candIdx))
            if best.forall(score > _._2) then best = Some((candidate, score))
          }
        }

        val bestProgram = best.map(_._1).getOrElse(base)
        OptimizationReport(
          bestProgram = bestProgram,
          candidates = candidates.toVector.sortBy(-_.score),
          metadata = Map(
            "optimizer"      -> name,
            "num_candidates" -> candidates.size,
            "best_score"     -> best.map(_._2).getOrElse(0.0),
            "predictors"     -> ps.read(base).size
          )
        )
      }

  /** Induce rules for one predictor from the trainset, narrowing the example set on a context-window overflow.
    * Returns the induced rules text, or `None` if even a single example can't produce rules. */
  private def induceRules(predictor: DynamicPredict, trainset: Vector[Example], candIdx: Int, predIdx: Int)(using
      RuntimeContext
  ): Option[String] =
    val gen = DynamicPredict(layout = ruleInductionLayout, name = Some("infer_rules_induction"))
    // Deterministic, non-overlapping rolloutId per (candidate, predictor) so each candidate draws different rules.
    val rolloutId = OptimizerSupport.seedBase(config.seed) + candIdx * 10000 + predIdx * 100

    @annotation.tailrec
    def attempt(demos: Vector[Example]): Option[String] =
      if demos.isEmpty then None
      else
        val call = ProgramCall(
          inputs    = DynamicValues.record("examples_text" := formatExamples(demos, predictor)),
          config    = DynamicValues.record("temperature" := config.initTemperature),
          rolloutId = Some(rolloutId)
        )
        gen.apply(call) match
          case Right(pred) =>
            DynamicValues.recordGet(pred.values, "natural_language_rules").map(DynamicValues.renderText).map(_.trim).filter(_.nonEmpty)
          case Left(_: ContextWindowExceededError) if demos.size > 1 => attempt(demos.dropRight(1))
          case Left(_)                                               => None

    attempt(trainset)

  /** Render demos as upstream's `format_examples` text, projecting each example to the predictor's own input/output
    * fields. */
  private def formatExamples(demos: Vector[Example], predictor: DynamicPredict): String =
    def render(fields: Vector[FieldSpec], ex: Example): String =
      fields.flatMap(f => DynamicValues.recordGet(ex.values, f.name).map(v => s"${f.name}: ${DynamicValues.renderText(v)}")).mkString("\n")
    demos.map { ex =>
      s"Input Fields:\n${render(predictor.layout.inputFields, ex)}\n\n=========\n" +
        s"Output Fields:\n${render(predictor.layout.outputFields, ex)}\n"
    }.mkString("\n")


  /** The rule-induction signature: `examples_text -> natural_language_rules`, instructed to extract `numRules`
    * concise, non-redundant, actionable rules (a paraphrase of upstream's `CustomRulesInduction` docstring). */
  private val ruleInductionLayout: SignatureLayout =
    SignatureLayout(
      name = "RulesInduction",
      fields = Vector(
        FieldSpec(name = "examples_text", role = FieldRole.Input, description = Some("Text containing examples")),
        FieldSpec(name = "natural_language_rules", role = FieldRole.Output, description = Some("Induced natural language rules"))
      ),
      instructions = Some(
        s"${config.inductionMarker} Given a set of examples, extract a list of ${config.numRules} concise and " +
          "non-redundant natural language rules that provide clear, actionable guidance for performing the task."
      )
    )
