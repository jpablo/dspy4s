package dspy4s.optimize

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.{
  ConfigurationError,
  DspyError,
  DynamicValues,
  ErrorLimit,
  RuntimeContext,
  RuntimeError,
  SignatureLayout,
  ThreadCount,
  ValidationError
}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{DecisionEvidence, DecisionEvidenceLog, DecisionKind, DecisionPlan, DecisionSettings}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.lm.runtime.ManagedLanguageModel
import dspy4s.optimize.ReAnchorSearch.{Tried, gaps, mean, select, summary}
import dspy4s.optimize.contracts.{CandidateProgram, OptimizationReport, Teleprompter}
import dspy4s.programs.ProgramRunner
import dspy4s.programs.optimization.{IdentifiedOptimizable, OptimizableStructure, PredictorTraces}
import dspy4s.programs.runtime.ParallelExecutor
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.WriterConfig
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

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
final case class ReAnchorConfig(numThreads: Int = 1, logDir: Option[Path] = None, requireCache: Boolean = true)

/** One row of the report: the fit of one decision output of one predictor (upstream `calibrate` report row).
  *
  * @param predictor
  *   the name of the predictor in the program (the name of `OptimizableStructure.inspectNamed`)
  * @param parameter
  *   `threshold`, `cuts`, or `weights`
  * @param value
  *   the fitted value in the JSON form of [[dspy4s.core.decisions.DecisionSettings]] (a number, a list, or an object),
  *   or `None` when the fit was not kept
  * @param trainScore
  *   the mean metric score with the kept behavior
  * @param trainScoreAtStart
  *   the mean score with the evidence decoding on, before the fit
  * @param trainScoreOriginal
  *   the mean score with the original settings of the output (before the evidence decoding was on)
  * @param observed
  *   `calls`, `candidates`, and for a threshold or cuts `distinct`, `min`, and `max` of the observed values (rounded to
  *   4 decimal places)
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
    observed          : Map[String, Double],
    foldCheck         : (Int, Int),
    skipped           : Option[String]
) derives CanEqual

/** The report of one `compile` (upstream `ReAnchor.report`). Each score is a mean metric score rounded to 4 decimal
  * places.
  */
final case class ReAnchorReport(
    trainScoreBefore: Double,
    trainScore      : Double,
    valScoreBefore  : Option[Double],
    valScore        : Option[Double],
    fitted          : Vector[ReAnchorRow]
) derives CanEqual:

  /** The JSON form of `report.json`: `train_score_before`, `train_score`, `val_score_before` and `val_score` when a
    * validation set was given, and `fitted`, one object for each row with the upstream keys (`predictor`, `field`,
    * `parameter`, `value` when kept, `train_score`, `train_score_at_start`, `train_score_original`, `observed`,
    * `fold_check` with `passed` and `failed`, and `skipped` when skipped).
    */
  def toDynamic: DynamicValue.Record =
    import ReAnchorReport.{number, record}
    record(
      Vector(
        Some("train_score_before" -> number(trainScoreBefore)),
        Some("train_score"        -> number(trainScore)),
        valScoreBefore.map("val_score_before" -> number(_)),
        valScore.map("val_score" -> number(_)),
        Some("fitted" -> DynamicValue.Sequence(Chunk.from(fitted.map(ReAnchorReport.rowJson))))
      ).flatten
    )

object ReAnchorReport:

  /** The keys of `observed` in the order of upstream `_summary` and the fit, with the keys that are counts. */
  private val observedKeys: Vector[(String, Boolean)] =
    Vector("calls" -> true, "distinct" -> true, "min" -> false, "max" -> false, "candidates" -> true)

  private def rowJson(row: ReAnchorRow): DynamicValue =
    val observed = observedKeys.flatMap((key, count) =>
      row.observed.get(key).map(value => key -> (if count then integer(value.toInt) else number(value)))
    )
    record(
      Vector(
        Some("predictor" -> text(row.predictor)),
        Some("field"     -> text(row.field)),
        Some("parameter" -> text(row.parameter)),
        row.value.map("value" -> _),
        Some("train_score"          -> number(row.trainScore)),
        Some("train_score_at_start" -> number(row.trainScoreAtStart)),
        Some("train_score_original" -> number(row.trainScoreOriginal)),
        Some("observed"             -> record(observed)),
        Some("fold_check"           -> record(Vector(
          "passed" -> integer(row.foldCheck._1),
          "failed" -> integer(row.foldCheck._2)
        ))),
        row.skipped.map("skipped" -> text(_))
      ).flatten
    )

  private def record(entries: Vector[(String, DynamicValue)]): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(entries))

  private def number(value: Double): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Double(value))

  private def integer(value: Int): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Int(value))

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

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
  override def compile(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]               = None,
      valset  : Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    Teleprompter.withCompileCallbacks(this, trainset, valset) {
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
    }

  /** The calibrated program and its report, with no `OptimizationReport` wrapper. */
  def calibrate(
      student : P,
      trainset: Vector[Example],
      valset  : Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, (P, ReAnchorReport)] =
    // Upstream scores the validation set only when it is not empty.
    val validation = valset.filter(_.nonEmpty)
    for
      threads <- ThreadCount.either(config.numThreads).left.map(_ =>
                   ValidationError(s"ReAnchor numThreads must be positive, got ${config.numThreads}")
                 )
      _    <- Either.cond(trainset.nonEmpty, (), ValidationError("trainset must contain at least one example."))
      found = ReAnchor.predictors(student)
      _    <- Either.cond(
             found.nonEmpty,
             (),
             ValidationError("The student must contain at least one predictor with a decision output.")
           )
      _         <- PredictorTraces.requireDistinctKeys(found)
      _         <- checkCache(found)
      before    <- scores(student, trainset, threads)
      valBefore <- Traverse.either(validation.toVector)(scores(student, _, threads))
      fitted    <- fit(student, found, trainset, threads)
      after     <- scores(fitted._1, trainset, threads)
      valAfter  <- Traverse.either(validation.toVector)(scores(fitted._1, _, threads))
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
        case Some(_) =>
          val names = found.map(leaf => s"'${leaf.displayName}'").mkString("[", ", ", "]")
          Left(ConfigurationError(
            s"Predictors $names do not cache responses, so every candidate setting would send new requests. Use a " +
              "ManagedLanguageModel with a cache, or set requireCache = false to calibrate anyway."
          ))

  /** Fit each decision output of each predictor of `found`, in order (upstream `calibrate`). */
  private def fit(
      student : P,
      found   : Vector[IdentifiedOptimizable],
      trainset: Vector[Example],
      threads : ThreadCount
  )(using RuntimeContext): Either[DspyError, (P, Vector[ReAnchorRow])] =
    found.foldLeft[Either[DspyError, (P, Vector[ReAnchorRow])]](Right((student, Vector.empty))) { (done, leaf) =>
      DecisionKind.outputsOf(leaf.layout).foldLeft(done) { case (acc, (field, kind)) =>
        acc.flatMap((program, rows) =>
          OutputFit(leaf, field, trainset, threads).run(program, kind).map((next, row) => (next, rows :+ row))
        )
      }
    }

  /** The fit of the output `field` of the predictor `leaf`. */
  private final class OutputFit(
      leaf    : IdentifiedOptimizable,
      field   : String,
      trainset: Vector[Example],
      threads : ThreadCount
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
        before          <- scores(program, trainset, threads)
        effective       <- ReAnchor.effective(leaf.layout, structure.read(program)(index).decisions, field)
        started          = withEntry(program, Some(effective))
        observed        <- observe(started)
        (evidence, base) = observed
        score            = (settings: DecisionSettings) => scores(withEntry(program, Some(settings)), trainset, threads)
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
          parameter = result.parameter,
          value = Option.when(kept)(DynamicValues.recordGet(result.settings.toDynamic, result.parameter)).flatten,
          trainScore = mean(if kept then result.scores else before),
          trainScoreAtStart = mean(base),
          trainScoreOriginal = mean(before),
          observed = result.observed.toMap,
          foldCheck = (result.passed, result.failed),
          skipped = Option.unless(kept)(ReAnchor.SkippedReason)
        )
        (withEntry(program, if kept then Some(result.settings) else original), row)

    /** The evidence of this output in one pass over the training set, and the scores of the pass (upstream `_observe`).
      * An entry belongs to this output when it has the key of the leaf and the name of the output.
      */
    private def observe(program: P): Either[DspyError, (Vector[DecisionEvidence], Vector[Double])] =
      val (scored, entries) = DecisionEvidenceLog.recording(scores(program, trainset, threads))
      scored.map(base =>
        (entries.filter(e => e.predictor == leaf.metadata.key && e.field == field).map(_.evidence), base)
      )

  /** The result of the fit of one output: the settings with the best parameter, the scores with them, the observed
    * values, and the counts of the fold check.
    */
  private final case class Fit(
      settings : DecisionSettings,
      parameter: String,
      scores   : Vector[Double],
      observed : Vector[(String, Double)],
      passed   : Int,
      failed   : Int
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
      start <- ReAnchor.parameter(effective.threshold, "threshold")
      tried <- Traverse.either(candidates) { (t, width) =>
                 score(effective.copy(threshold = Some(t))).map(Tried(_, Vector(width, -math.abs(t - start), t), t))
               }
    yield
      val selection      = select(base, tried)
      val (best, values) = selection.kept.fold((start, base))(kept => (kept.setting, kept.scores))
      Fit(
        effective.copy(threshold = Some(best)),
        "threshold",
        values,
        summary(probabilities) :+ ("candidates" -> tried.size.toDouble),
        if selection.kept.isDefined then 1 else 0,
        if selection.refused then 1 else 0
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
    ReAnchor.parameter(effective.weights, "weights").flatMap { start =>
      val initial = Fit(
        effective.copy(weights = Some(labels.map(l => l -> start.getOrElse(l, 1.0)).toMap)),
        "weights",
        base,
        Vector.empty,
        0,
        0
      )
      labels.foldLeft[Either[DspyError, (Fit, Int)]](Right((initial, 0))) { (acc, label) =>
        acc.flatMap { (current, count) =>
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
          val candidates = gaps(flips, low, high, geometric = true).filterNot((w, _) => w == best.getOrElse(label, 1.0))
          Traverse.either(candidates) { (w, width) =>
            val weights = best.updated(label, w)
            score(effective.copy(weights = Some(weights))).map(Tried(_, Vector(width), weights))
          }.map(tried =>
            (
              step(current, select(current.scores, tried), weights => current.settings.copy(weights = Some(weights))),
              count + tried.size
            )
          )
        }
      }.map((done, count) =>
        done.copy(observed = Vector("calls" -> evidence.size.toDouble, "candidates" -> count.toDouble))
      )
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
    ReAnchor.parameter(effective.cuts, "cuts").flatMap { start =>
      val initial = Fit(effective, "cuts", base, Vector.empty, 0, 0)
      start.indices.foldLeft[Either[DspyError, (Fit, Int)]](Right((initial, 0))) { (acc, i) =>
        acc.flatMap { (current, count) =>
          val best       = current.settings.cuts.getOrElse(start)
          val below      = if i > 0 then best(i - 1) else 0.0
          val above      = if i + 1 < best.size then best(i + 1) else top
          val candidates = gaps(means, below, above).filterNot((c, _) => c == best(i))
          Traverse.either(candidates) { (c, width) =>
            val cuts = best.updated(i, c)
            score(effective.copy(cuts = Some(cuts))).map(Tried(_, Vector(width), cuts))
          }.map(tried =>
            (
              step(current, select(current.scores, tried), cuts => current.settings.copy(cuts = Some(cuts))),
              count + tried.size
            )
          )
        }
      }.map((done, count) => done.copy(observed = summary(means) :+ ("candidates" -> count.toDouble)))
    }

  /** `current` after one search step: the kept candidate (with `update`) and its scores, and the counts of the fold
    * check.
    */
  private def step[A](current: Fit, selection: ReAnchorSearch.Selection[A], update: A => DecisionSettings): Fit =
    val moved =
      selection.kept.fold(current)(kept => current.copy(settings = update(kept.setting), scores = kept.scores))
    moved.copy(
      passed = current.passed + (if selection.kept.isDefined then 1 else 0),
      failed = current.failed + (if selection.refused then 1 else 0)
    )

  /** The metric score of `program` on each example of `examples` (upstream `scores`). The examples run on `threads`
    * threads. The pass fails at the first error of the program or the metric, and on a score that is not finite.
    */
  private def scores(program: P, examples: Vector[Example], threads: ThreadCount)(using
      context: RuntimeContext
  ): Either[DspyError, Vector[Double]] =
    val failure  = new AtomicReference[Option[DspyError]](None)
    val executor = ParallelExecutor(numThreads = threads, maxErrors = ErrorLimit(1))
    // The executor installs the given context, so the workers get it with the dynamic scope of this thread.
    val run = executor.executeEither[Example, Double](
      task = example =>
        given RuntimeContext = RuntimeEnvironment.current
        val scored           =
          try runner.run(program, example.inputs).flatMap(prediction => metric.score(example, prediction))
          catch
            case NonFatal(error) => Left(RuntimeError("reanchor", Option(error.getMessage).getOrElse(error.toString)))
        scored.left.foreach(error => failure.compareAndSet(None, Some(error)))
        scored
      ,
      data = examples
    )(using context)
    run.left.map(error => failure.get.getOrElse(error)).flatMap { result =>
      val values = result.results.flatten
      if values.size != examples.size then
        Left(failure.get.getOrElse(RuntimeError("reanchor", "A pass did not score each example.")))
      else if values.exists(value => !value.isFinite) then
        Left(ValidationError("ReAnchor requires finite metric values."))
      else Right(values)
    }

  /** Write `report.json` to the log folder, when the settings have one. */
  private def writeReport(report: ReAnchorReport): Either[DspyError, Unit] =
    config.logDir.fold[Either[DspyError, Unit]](Right(())) { folder =>
      try
        val _ = Files.createDirectories(folder)
        val _ = Files.write(
          folder.resolve("report.json"),
          Schema.dynamic.jsonCodec.encode(report.toDynamic, WriterConfig.withIndentionStep(2))
        )
        Right(())
      catch
        case NonFatal(error) => Left(RuntimeError(
            "reanchor",
            s"Failed to write report.json: ${Option(error.getMessage).getOrElse(error.toString)}"
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

  /** The value of a parameter of the effective settings. The effective settings always set it. */
  private def parameter[A](value: Option[A], name: String): Either[DspyError, A] =
    value.toRight(RuntimeError("reanchor", s"The effective decision settings have no $name"))
