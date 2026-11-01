package dspy4s.optimize

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.{
  ConfigurationError,
  DspyError,
  ErrorLimit,
  PythonText,
  RuntimeContext,
  RuntimeError,
  SignatureLayout,
  ThreadCount,
  ValidationError
}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{
  DecisionEvidence,
  DecisionEvidenceLog,
  DecisionKind,
  DecisionParameter,
  DecisionPlan,
  DecisionSettings
}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.optimize.ReAnchorSearch.{Tried, gaps, mean, select, summary}
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport, Teleprompter}
import dspy4s.programs.ProgramRunner
import dspy4s.programs.optimization.{IdentifiedOptimizable, OptimizableStructure, PredictorTraces}
import dspy4s.programs.runtime.ParallelExecutor
import zio.blocks.schema.json.{JsonCodec, JsonCodecDeriver, WriterConfig}
import zio.blocks.schema.{DynamicValue, NameMapper, Schema}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** The settings of [[ReAnchor]].
  *
  * @param numThreads
  *   the number of examples that a pass runs at the same time, as in `Evaluate`
  * @param logDir
  *   when set, `compile` writes `report.json` to this folder
  * @param requireCache
  *   when `true`, `compile` fails unless the model of the runtime context is a `ManagedLanguageModel` with a cache,
  *   because each candidate setting runs the program over the training set again. Set it to `false` for a model that a
  *   program picks at run time, or to allow requests with no cache.
  */
final case class ReAnchorConfig(
    numThreads  : ThreadCount  = ThreadCount(1),
    logDir      : Option[Path] = None,
    requireCache: Boolean      = true
)

/** One row of the report: the fit of one decision output of one predictor (upstream `calibrate` report row).
  *
  * @param predictor
  *   the name of the predictor in the program (the name of `OptimizableStructure.inspectNamed`)
  * @param parameter
  *   the name of the fitted [[dspy4s.core.decisions.DecisionParameter]]: `threshold`, `cuts`, or `weights`
  * @param value
  *   the fitted value in the JSON form of [[dspy4s.core.decisions.DecisionSettings]] (a number, a list, or an object;
  *   [[dspy4s.core.decisions.DecisionParameter.jsonIn]]), or `None` when the fit was not kept
  * @param trainScore
  *   the mean metric score with the kept behavior
  * @param trainScoreAtStart
  *   the mean score with the evidence decoding on, before the fit
  * @param trainScoreOriginal
  *   the mean score with the original settings of the output (before the evidence decoding was on)
  * @param observed
  *   what the fit observed
  * @param foldCheck
  *   the count of search steps whose better training score passed and failed the fold check
  * @param skipped
  *   the reason when the fitted behavior did not beat the original ("fitted behavior did not beat the original")
  */
final case class ReAnchorRow(
    predictor         : String,
    field             : String,
    parameter         : String,
    value             : Option[DynamicValue],
    trainScore        : Double,
    trainScoreAtStart : Double,
    trainScoreOriginal: Double,
    observed          : ReAnchorRow.Observed,
    foldCheck         : ReAnchorRow.FoldCheck,
    skipped           : Option[String]
) derives CanEqual, Schema

object ReAnchorRow:

  /** What the fit of one output observed.
    *
    * @param calls
    *   the count of calls with evidence
    * @param distinct
    *   for a threshold or cuts with at least one call, the count of the distinct observed values
    * @param min
    *   for a threshold or cuts with at least one call, the smallest observed value, rounded to 4 decimal places
    * @param max
    *   for a threshold or cuts with at least one call, the largest observed value, rounded to 4 decimal places
    * @param candidates
    *   the count of the candidate settings that the fit scored
    */
  final case class Observed(
      calls     : Int,
      distinct  : Option[Int],
      min       : Option[Double],
      max       : Option[Double],
      candidates: Int
  ) derives CanEqual, Schema

  /** The count of search steps whose better training score passed and failed the fold check. */
  final case class FoldCheck(passed: Int, failed: Int) derives CanEqual, Schema

/** The report of one `compile` (upstream `ReAnchor.report`). Each score is a mean metric score rounded to 4 decimal
  * places.
  */
final case class ReAnchorReport(
    trainScoreBefore: Double,
    trainScore      : Double,
    valScoreBefore  : Option[Double],
    valScore        : Option[Double],
    fitted          : Vector[ReAnchorRow]
) derives CanEqual, Schema

object ReAnchorReport:

  /** The codec of `report.json`: `train_score_before`, `train_score`, `val_score_before` and `val_score` when a
    * validation set was given, and `fitted`, one object for each row with the upstream keys (`predictor`, `field`,
    * `parameter`, `value` when kept, `train_score`, `train_score_at_start`, `train_score_original`, `observed`,
    * `fold_check` with `passed` and `failed`, and `skipped` when skipped).
    */
  private[optimize] val jsonCodec: JsonCodec[ReAnchorReport] =
    Schema[ReAnchorReport].derive(JsonCodecDeriver.withFieldNameMapper(NameMapper.SnakeCase))

/** Experimental. The calibration optimizer of the decision outputs (upstream `dspy.experimental.ReAnchor`, in
  * `dspy/teleprompt/reanchor/`). It fits the threshold of each Noul output, the cuts of each Score output, and the
  * weights of each Choice output against the metric. It changes no instructions and no demos.
  *
  * The rules of upstream `calibrate`:
  *   - The predictors with a decision output are the leaves of the program whose layout has an output with a
  *     [[dspy4s.core.decisions.DecisionKind]] (a rich output, or a native `Boolean` or enum output). A program with
  *     none fails.
  *   - For each such output, in the order of the predictors and of the outputs: the score of each example with the
  *     original settings; then the effective settings of the output become explicit (this turns the evidence decoding
  *     on for a native output); then one pass with [[dspy4s.core.decisions.DecisionEvidenceLog]] gives the observed
  *     evidence of this predictor and output; then the fit.
  *   - The candidates of a threshold are the midpoints of the gaps between the observed P(true) values in [0, 1], the
  *     upper value of each two values with no float between them, and 0 when P(true) = 0 is observed. The candidates of
  *     a Score cut move one cut at a time between its neighbors, at the midpoints of the observed mean level indexes.
  *     The candidates of a Choice weight move one label at a time, at the geometric midpoints of the observed flip
  *     points, inside [1e-3, 1e3] (narrowed to a quarter of the smallest and four times the largest flip point). More
  *     than 40 gaps are thinned to evenly spaced quantiles. A midpoint is rounded to the shortest decimal that stays
  *     strictly inside its gap.
  *   - A candidate replaces the current setting only when its total score over the training set is strictly higher, and
  *     a fold check passes: the examples split into up to 5 folds with a fixed seed; for each fold, the best candidate
  *     on the other folds is scored on the fold; the sum of these held-out scores must be higher than the sum of the
  *     current setting. Among equal totals, the candidate in the widest gap wins (then, for a threshold, the one
  *     nearest the start, then the larger one).
  *   - After the fit, the fitted behavior must beat the original behavior with the same fold check, else the original
  *     settings of the output come back (no entry when it had none).
  *   - A pass fails at the first error of the program or the metric, and on a metric score that is not finite.
  *
  * The result program is the student with the fitted settings; the student does not change.
  *
  * The port finds the evidence of a predictor by its predictor key (decision D2), not by object identity as upstream
  * does, so two predictors with a decision output must have different keys. The pass with the evidence log also gives
  * the scores at the start of the fit (upstream scores the program one more time for them). The folds come from the
  * shuffle of Python `random.Random(0)`, so that the fold check makes the same decisions as upstream.
  */
final class ReAnchor[P: {OptimizableStructure, ProgramRunner}](
    metric: Metric,
    config: ReAnchorConfig = ReAnchorConfig()
) extends Teleprompter[P]:

  override val name: String = "reanchor"

  private val structure: OptimizableStructure[P] = summon[OptimizableStructure[P]]
  private val runner: ProgramRunner[P]           = summon[ProgramRunner[P]]

  /** Calibrate `student`. The report of the result has the key `report` with the [[ReAnchorReport]]. `teacher` is not
    * supported: a `Some` fails, so that it is not ignored. An empty `trainset` fails. `valset` is scored before and
    * after, and no setting is fitted on it. The result has one candidate: the calibrated program with the mean training
    * score of the report.
    */
  override protected[optimize] def compileInScope(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      valset  : Option[Vector[Example]]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    for
      _          <- Either.cond(teacher.isEmpty, (), ValidationError(ReAnchor.TeacherError))
      calibrated <- calibrate(student, trainset, valset)
    yield
      val (program, report) = calibrated
      OptimizationReport(
        bestProgram = program,
        candidates = Vector(CandidateProgram(program = program, score = report.trainScore)),
        metadata = Map("report" -> report)
      )

  /** The calibrated program and its report, with no `OptimizationReport` wrapper. */
  def calibrate(
      student : P,
      trainset: Vector[Example],
      valset  : Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, (P, ReAnchorReport)] =
    // Upstream scores the validation set only when it is not empty.
    val validation = valset.filter(_.nonEmpty)
    for
      _    <- Either.cond(trainset.nonEmpty, (), ValidationError("trainset must contain at least one example."))
      found = ReAnchor.predictors(student)
      _    <- Either.cond(
             found.nonEmpty,
             (),
             ValidationError("The student must contain at least one predictor with a decision output.")
           )
      _         <- PredictorTraces.requireDistinctKeys(found)
      _         <- checkCache(found)
      before    <- scores(student, trainset)
      valBefore <- Traverse.either(validation.toVector)(scores(student, _))
      fitted    <- fit(student, found, trainset)
      after     <- scores(fitted._1, trainset)
      valAfter  <- Traverse.either(validation.toVector)(scores(fitted._1, _))
      report     = ReAnchorReport(
                 trainScoreBefore = mean(before),
                 trainScore = mean(after),
                 valScoreBefore = valBefore.headOption.map(mean),
                 valScore = valAfter.headOption.map(mean),
                 fitted = fitted._2
               )
      _ <- writeReport(report)
    yield (fitted._1, report)

  /** Upstream `caches`: with `requireCache`, the model of the context must be a [[ManagedLanguageModel]] with a cache.
    * A context with no model fails too, because the check cannot see a model that the program picks at run time.
    */
  private def checkCache(found: Vector[IdentifiedOptimizable])(using context: RuntimeContext): Either[DspyError, Unit] =
    if !config.requireCache then Right(())
    else
      context.lm match
        case Some(managed: ManagedLanguageModel) if managed.cache.isDefined => Right(())
        case None                                                           => Left(ConfigurationError(
            "ReAnchor cannot check caching without a configured model. For a model that the program picks at run " +
              "time, set requireCache = false."
          ))
        case Some(_) => Left(ConfigurationError(
            s"Predictors ${PythonText.nameList(found.map(_.displayName))} do not cache responses, so every candidate " +
              "setting would send new requests. Use a " +
              "ManagedLanguageModel with a cache, or set requireCache = false to calibrate anyway."
          ))

  /** Fit each decision output of each predictor of `found`, in order (upstream `calibrate`). */
  private def fit(
      student : P,
      found   : Vector[IdentifiedOptimizable],
      trainset: Vector[Example]
  )(using RuntimeContext): Either[DspyError, (P, Vector[ReAnchorRow])] =
    found.foldLeft[Either[DspyError, (P, Vector[ReAnchorRow])]](Right((student, Vector.empty))) { (done, leaf) =>
      DecisionKind.outputsOf(leaf.layout).foldLeft(done) { case (acc, (field, kind)) =>
        acc.flatMap((program, rows) =>
          OutputFit(leaf, field, trainset).run(program, kind).map((next, row) => (next, rows :+ row))
        )
      }
    }

  /** The fit of the output `field` of the predictor `leaf`. */
  private final class OutputFit(
      leaf    : IdentifiedOptimizable,
      field   : String,
      trainset: Vector[Example]
  )(using RuntimeContext):

    private val index: Int = leaf.id.ordinal

    /** `program` with `settings` as the entry of the output (no entry for `None`). */
    def withEntry(program: P, settings: Option[DecisionSettings]): P =
      val parameters = structure.read(program)
      val current    = parameters(index)
      val decisions  = settings.fold(current.decisions - field)(current.decisions.updated(field, _))
      structure.replace(program, parameters.updated(index, current.copy(decisions = decisions)))

    def run(program: P, kind: DecisionKind): Either[DspyError, (P, ReAnchorRow)] =
      val original = structure.read(program)(index).decisions.get(field)
      for
        before          <- scores(program, trainset)
        effective       <- ReAnchor.effective(leaf.layout, structure.read(program)(index).decisions, field)
        started          = withEntry(program, Some(effective))
        observed        <- observe(started)
        (evidence, base) = observed
        score            = (settings: DecisionSettings) => scores(withEntry(program, Some(settings)), trainset)
        result          <- kind match
                    case DecisionKind.Noul           => fitThreshold(effective, evidence, base, score)
                    case DecisionKind.Score(levels)  => fitCuts(levels.size, effective, evidence, base, score)
                    case DecisionKind.Choice(labels) => fitWeights(labels, effective, evidence, base, score)
      yield
        // The fitted behavior must beat the original behavior, with the same fold check (upstream `calibrate`).
        val kept = select(before, Vector(Tried(result.scores, Vector.empty, ()))).kept.isDefined
        val row  = ReAnchorRow(
          predictor = leaf.displayName,
          field = field,
          parameter = result.parameter.name,
          value = Option.when(kept)(result.parameter.jsonIn(result.settings)).flatten,
          trainScore = mean(if kept then result.scores else before),
          trainScoreAtStart = mean(base),
          trainScoreOriginal = mean(before),
          observed = result.observed,
          foldCheck = result.foldCheck,
          skipped = Option.unless(kept)(ReAnchor.SkippedReason)
        )
        (withEntry(program, if kept then Some(result.settings) else original), row)

    /** The evidence of this output in one pass over the training set, and the scores of the pass (upstream `_observe`).
      * An entry belongs to this output when it has the key of the leaf and the name of the output.
      */
    private def observe(program: P): Either[DspyError, (Vector[DecisionEvidence], Vector[Double])] =
      val (scored, entries) = DecisionEvidenceLog.recording(scores(program, trainset))
      scored.map(base =>
        (entries.filter(e => e.predictor == leaf.metadata && e.field == field).map(_.evidence), base)
      )

  /** The result of the fit of one output: the settings with the best parameter, the scores with them, the observed
    * values, and the counts of the fold check.
    */
  private final case class Fit(
      settings : DecisionSettings,
      parameter: DecisionParameter[?],
      scores   : Vector[Double],
      observed : ReAnchorRow.Observed,
      foldCheck: ReAnchorRow.FoldCheck = ReAnchorRow.FoldCheck(0, 0)
  )

  private type Scorer = DecisionSettings => Either[DspyError, Vector[Double]]

  /** The threshold that scores best, between the observed probabilities (upstream `_fit_threshold`). */
  private def fitThreshold(
      effective: DecisionSettings,
      evidence : Vector[DecisionEvidence],
      base     : Vector[Double],
      score    : Scorer
  ): Either[DspyError, Fit] =
    val probabilities = evidence.collect { case DecisionEvidence.Noul(p) => p }
    val distinct      = probabilities.distinct.sorted
    // No float is between two adjacent values, but `p >= upper` still splits them.
    val adjacent = distinct.zip(distinct.drop(1)).collect {
      case (lower, upper) if Math.nextUp(lower) == upper => (upper, upper - lower)
    }
    // `p >= threshold` makes zero an outcome of its own.
    val zero       = Option.when(probabilities.exists(_ == 0.0))((0.0, 0.0))
    val candidates = gaps(probabilities, 0.0, 1.0) ++ adjacent ++ zero
    for
      start <- ReAnchor.parameter(effective, DecisionParameter.Threshold)
      tried <- Traverse.either(candidates) { (t, width) =>
                 score(effective.copy(threshold = Some(t))).map(Tried(_, Vector(width, -math.abs(t - start), t), t))
               }
    yield step(
      Fit(effective, DecisionParameter.Threshold, base, summary(probabilities)),
      tried,
      threshold => effective.copy(threshold = Some(threshold))
    )

  /** The Choice weights that score best, one label at a time (upstream `_fit_weights`). The pick of a label flips where
    * its weighted probability meets the strongest rival, so the candidates are between these flip points.
    */
  private def fitWeights(
      labels   : Vector[String],
      effective: DecisionSettings,
      evidence : Vector[DecisionEvidence],
      base     : Vector[Double],
      score    : Scorer
  ): Either[DspyError, Fit] =
    val distributions = evidence.collect { case DecisionEvidence.Choice(p, _) => p }
    ReAnchor.parameter(effective, DecisionParameter.Weights).flatMap { start =>
      val initial = Fit(
        effective.copy(weights = Some(labels.map(l => l -> start.getOrElse(l, 1.0)).toMap)),
        DecisionParameter.Weights,
        base,
        ReAnchorRow.Observed(calls = evidence.size, distinct = None, min = None, max = None, candidates = 0)
      )
      sweep[String, Map[String, Double]](labels, initial, effective, score, (s, w) => s.copy(weights = Some(w))) {
        (current, label) =>
          val best  = current.settings.weights.getOrElse(Map.empty)
          val flips = distributions.flatMap { p =>
            val rivals = labels.filter(_ != label).map(other => best.getOrElse(other, 1.0) * p.getOrElse(other, 0.0))
            val rival  = rivals.maxOption.getOrElse(0.0)
            val own    = p.getOrElse(label, 0.0)
            Option.when(own > 0 && rival > 0)(rival / own)
          }
          val (low, high) =
            if flips.isEmpty then ReAnchorSearch.WeightRange
            else
              (
                math.max(ReAnchorSearch.WeightRange._1, flips.min / 4),
                math.min(ReAnchorSearch.WeightRange._2, flips.max * 4)
              )
          gaps(flips, low, high, geometric = true)
            .filterNot((w, _) => w == best.getOrElse(label, 1.0))
            .map((w, width) => (best.updated(label, w), width))
      }
    }

  /** The Score cuts that score best, one cut at a time between its neighbors (upstream `_fit_cuts`). The level of a
    * call depends only on its mean level index.
    */
  private def fitCuts(
      levels   : Int,
      effective: DecisionSettings,
      evidence : Vector[DecisionEvidence],
      base     : Vector[Double],
      score    : Scorer
  ): Either[DspyError, Fit] =
    val top   = (levels - 1).toDouble
    val means = evidence.collect { case DecisionEvidence.Score(p, _) =>
      DecisionPlan.meanLevel((0 until levels).toVector.map(i => p.getOrElse(i, 0.0)))
    }
    ReAnchor.parameter(effective, DecisionParameter.Cuts).flatMap { start =>
      val initial = Fit(effective, DecisionParameter.Cuts, base, summary(means))
      sweep[Int, Vector[Double]](start.indices, initial, effective, score, (s, c) => s.copy(cuts = Some(c))) {
        (current, i) =>
          val best  = current.settings.cuts.getOrElse(start)
          val below = if i > 0 then best(i - 1) else 0.0
          val above = if i + 1 < best.size then best(i + 1) else top
          gaps(means, below, above).filterNot((c, _) => c == best(i)).map((c, width) => (best.updated(i, c), width))
      }
    }

  /** The search of a parameter, one axis at a time. For each axis, `candidates` gives the values to try from the fit so
    * far, each with the width of its gap. `set` writes a value in the settings.
    */
  private def sweep[K, A](
      axes     : Iterable[K],
      initial  : Fit,
      effective: DecisionSettings,
      score    : Scorer,
      set      : (DecisionSettings, A) => DecisionSettings
  )(candidates: (Fit, K) => Vector[(A, Double)]): Either[DspyError, Fit] =
    axes.foldLeft[Either[DspyError, Fit]](Right(initial)) { (done, axis) =>
      done.flatMap { current =>
        Traverse.either(candidates(current, axis)) { (setting, width) =>
          score(set(effective, setting)).map(Tried(_, Vector(width), setting))
        }.map(step(current, _, set(current.settings, _)))
      }
    }

  /** `current` after one search step over `tried`: the kept candidate (with `update`) and its scores, the count of the
    * candidates, and the counts of the fold check.
    */
  private def step[A](current: Fit, tried: Vector[Tried[A]], update: A => DecisionSettings): Fit =
    val selection = select(current.scores, tried)
    val moved     =
      selection.kept.fold(current)(kept => current.copy(settings = update(kept.setting), scores = kept.scores))
    moved.copy(
      observed = current.observed.copy(candidates = current.observed.candidates + tried.size),
      foldCheck = ReAnchorRow.FoldCheck(
        passed = current.foldCheck.passed + (if selection.kept.isDefined then 1 else 0),
        failed = current.foldCheck.failed + (if selection.refused then 1 else 0)
      )
    )

  /** The metric score of `program` on each example of `examples` (upstream `scores`). The examples run on `numThreads`
    * threads. The pass fails at the first error of the program or the metric, and on a score that is not finite.
    */
  private def scores(program: P, examples: Vector[Example])(using
      context: RuntimeContext
  ): Either[DspyError, Vector[Double]] =
    val failure  = new AtomicReference[Option[DspyError]](None)
    val executor = ParallelExecutor(numThreads = config.numThreads, maxErrors = ErrorLimit(1))
    // The executor installs the given context, so the workers get it with the dynamic scope of this thread.
    val run = executor.executeEither[Example, Double](
      task = example =>
        given RuntimeContext = RuntimeEnvironment.current
        val scored           =
          try runner.run(program, example.inputs).flatMap(prediction => metric.score(example, prediction))
          catch
            case NonFatal(error) => Left(RuntimeError("reanchor", DspyError.messageOf(error)))
        scored.left.foreach(error => failure.compareAndSet(None, Some(error)))
        scored
      ,
      data = examples
    )(using context)
    run.left.map(error => failure.get.getOrElse(error)).flatMap { result =>
      // With the error limit 1, a `Right` holds no `Left`.
      val values = result.results.flatMap(_.toOption)
      if values.exists(value => !value.isFinite) then Left(ValidationError("ReAnchor requires finite metric values."))
      else Right(values)
    }

  /** Write `report.json` to the log folder, when the settings have one. */
  private def writeReport(report: ReAnchorReport): Either[DspyError, Unit] =
    config.logDir.fold[Either[DspyError, Unit]](Right(())) { folder =>
      try
        val _ = Files.createDirectories(folder)
        val _ = Files.write(
          folder.resolve("report.json"),
          ReAnchorReport.jsonCodec.encode(report, WriterConfig.withIndentionStep(2))
        )
        Right(())
      catch
        case NonFatal(error) => Left(RuntimeError(
            "reanchor",
            s"Failed to write report.json: ${DspyError.messageOf(error)}"
          ))
    }

object ReAnchor:

  /** The reason of a row whose fit did not beat the original behavior (upstream `calibrate`). */
  val SkippedReason: String = "fitted behavior did not beat the original"

  private val TeacherError: String = "ReAnchor does not use a teacher program: give only the student."

  /** The leaves of `program` with a decision output, with the names of `inspectNamed` (upstream `predictors`). */
  private[optimize] def predictors[P](program: P)(using
      structure: OptimizableStructure[P]
  ): Vector[IdentifiedOptimizable] =
    structure.readIdentified(program).filter(leaf => DecisionKind.outputsOf(leaf.layout).nonEmpty)

  /** The effective settings of the output `field` (upstream `effective`): its entry, with the defaults of its kind. An
    * output with no entry gets an empty entry first, so that a native output is a decision output.
    */
  private def effective(
      layout   : SignatureLayout,
      decisions: Map[String, DecisionSettings],
      field    : String
  ): Either[DspyError, DecisionSettings] =
    DecisionPlan
      .of(layout, decisions.updatedWith(field)(_.orElse(Some(DecisionSettings()))))
      .flatMap(_.effectiveSettings(field).toRight(ConfigurationError(s"'$field' is not a decision output")))

  /** The value of `parameter` in the effective settings `effective`. The effective settings always set it. */
  private def parameter[A](effective: DecisionSettings, parameter: DecisionParameter[A]): Either[DspyError, A] =
    parameter.valueIn(effective).toRight(
      RuntimeError("reanchor", s"The effective decision settings have no ${parameter.name}")
    )
