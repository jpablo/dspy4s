package dspy4s.optimize

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.optimize.propose.GroundedProposer
import dspy4s.optimize.propose.GroundedProposerConfig

import scala.collection.mutable
import scala.util.Random

/** Knobs for [[MIPROv2]], mirroring the relevant slice of upstream's `MIPROv2.__init__` / `.compile`.
  *
  * @param metric               task metric used to score whole-program candidates on the valset (or trainset)
  * @param numCandidates        number of demo-set candidates bootstrapped AND instruction candidates proposed per
  *                             predictor (upstream's `num_candidates` / `num_fewshot_candidates` /
  *                             `num_instruct_candidates`, collapsed to one knob). Must be `> 0`.
  * @param numTrials            number of search trials over (demo-assignment, per-predictor instruction) choices
  *                             (upstream's `num_trials`). Must be `> 0`.
  * @param maxBootstrappedDemos cap on bootstrapped demos per demo-set candidate (forwarded to [[BootstrapFewShot]])
  * @param maxLabeledDemos      cap on labeled demos per demo-set candidate (forwarded to [[BootstrapFewShot]])
  * @param seed                 RNG seed; deterministic for a fixed seed (drives bootstrap seeds, the proposer, and
  *                             the trial search RNG)
  * @param proposerConfig       configuration for the [[GroundedProposer]] phase (instruction proposal)
  */
final case class MIPROv2Config(
    metric: Metric,
    numCandidates: Int = 5,
    numTrials: Int = 10,
    maxBootstrappedDemos: Int = 4,
    maxLabeledDemos: Int = 4,
    seed: Long = 0L,
    proposerConfig: GroundedProposerConfig = GroundedProposerConfig()
):
  require(numCandidates > 0, "MIPROv2 numCandidates must be greater than 0")
  require(numTrials > 0, "MIPROv2 numTrials must be greater than 0")

/** MIPROv2 — Multiprompt Instruction PRoposal Optimizer (v2). A v1 port of DSPy's
  * `dspy.teleprompt.MIPROv2` (`dspy/teleprompt/mipro_optimizer_v2.py`).
  *
  * '''Three-phase composition (reuses, does not reinvent).'''
  *   1. '''Demo-set candidates''' (Step 1, "bootstrap few-shot examples"). Runs [[BootstrapFewShot]] `numCandidates`
  *      times with distinct seeds (`seed + k`), collecting each compiled program's per-predictor demos via
  *      `Predictors.read(compiled).map(_.demos)`. A zero-shot candidate (empty demos for every predictor) is also
  *      included. The result is a `Vector` of demo-assignments, each a per-predictor `Vector[Vector[Example]]` of
  *      length == predictor count.
  *   2. '''Instruction candidates''' (Step 2, GroundedProposer). Calls
  *      [[GroundedProposer.proposeInstructions]]`(student, trainset, demoCandidates = <first bootstrapped
  *      assignment, or empty>)` → `Vector[Vector[String]]` (per predictor, `numCandidates` instructions). Each
  *      predictor's CURRENT instruction is prepended as an extra candidate.
  *   3. '''Search''' (Step 3, "find optimal prompt parameters"). For `numTrials` trials with a seeded RNG, randomly
  *      picks one demo-assignment index (applied whole-program) and, per predictor, one instruction-candidate
  *      index. The trial program is built with a single [[Predictors.replace]] applying each chosen instruction
  *      (`leaf.copy(layout = leaf.layout.withInstructions(Some(instr)))`) AND chosen demos (`.copy(demos = ...)`).
  *      Each trial is scored on the valset (falling back to the trainset) via [[dspy4s.evaluate.Evaluate]] + [[Runnable]] + the
  *      metric. A baseline candidate (the unmodified student) is always scored too. The best-scoring candidate is
  *      returned as `bestProgram`; all scored candidates (trials + baseline) are returned sorted descending.
  *
  * '''Deltas from Python.'''
  *   - '''Random search instead of Optuna TPE.''' Python's Step 3 drives an Optuna `TPESampler` study that builds a
  *     surrogate over the categorical (instruction-index, demo-index) parameters and samples promising
  *     combinations. dspy4s uses a seeded uniform-random search over the same parameter space. No surrogate model is
  *     built; trials are i.i.d. given the seed.
  *   - '''Whole-program demo choice (broadcast), not per-predictor trace-routed demos.''' Each trial picks ONE
  *     demo-assignment for the whole program. Today [[BootstrapFewShot]] itself broadcasts the same demos to every
  *     predictor, so a demo-assignment's per-predictor vectors are identical; the per-predictor structure is carried
  *     through faithfully so a future per-predictor bootstrap drops in without changing the search. Python's
  *     `create_n_fewshot_demo_sets` + trace routing can give different predictors different demos within one set.
  *   - '''Minibatch / full-eval distinction dropped.''' Python evaluates trials on a minibatch and periodically
  *     re-runs full evaluations (with successive promotion of the best param combo). dspy4s always does a full
  *     evaluation on the provided set every trial. No `minibatch_full_eval_steps`, no `_perform_full_evaluation`.
  *   - '''No surrogate model / no successive halving.''' Beyond dropping Optuna, there is no early stopping,
  *     promotion, or candidate pruning between trials.
  *   - '''`num_candidates` is a single knob.''' Python separates `num_fewshot_candidates` and
  *     `num_instruct_candidates` (and `auto` run modes that derive them). dspy4s uses one `numCandidates` for both
  *     and has no `auto` mode.
  *   - '''Dataset split / `auto` settings omitted.''' No automatic train/val splitting or `light/medium/heavy`
  *     presets; the caller supplies `valset` explicitly (else the trainset is reused).
  *   - '''`track_stats`, `log_dir`, LM-call estimation, and program persistence are omitted.''' The
  *     [[OptimizationReport]] carries the scored candidate list and summary metadata instead.
  */
final class MIPROv2[P: Predictors: Runnable](config: MIPROv2Config) extends Teleprompter[P]:

  override val name: String = "mipro_v2"

  private val ps: Predictors[P]   = summon[Predictors[P]]
  private val runner: Runnable[P] = summon[Runnable[P]]

  override def compile(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P] = None,
      valset: Option[Vector[Example]] = None
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val evalset: Vector[Example] = valset.getOrElse(trainset)
    val predictorCount           = ps.read(student).size

    if predictorCount == 0 then
      Right(OptimizationReport(bestProgram = student, candidates = Vector.empty,
        metadata = Map("no_predictors" -> true)))
    else
      // ── Phase 1: demo-set candidates (whole-program demo assignments) ──
      val demoCandidates: Vector[Vector[Vector[Example]]] = bootstrapDemoCandidates(student, trainset, teacher)

      // ── Phase 2: instruction candidates (per predictor) ──
      proposeInstructionCandidates(student, trainset, demoCandidates).map { instructionCandidates =>
        // ── Phase 3: random search over (demo-assignment, per-predictor instruction) ──
        searchTrials(student, evalset, demoCandidates, instructionCandidates, predictorCount)
      }

  // ── Phase 1 ─────────────────────────────────────────────────────────────

  /** Produce `numCandidates` whole-program demo assignments via [[BootstrapFewShot]] (distinct seeds), each read
    * back as the per-predictor demo vectors, PLUS a zero-shot assignment (empty demos for every predictor). */
  private def bootstrapDemoCandidates(
      student: P,
      trainset: Vector[Example],
      teacher: Option[P]
  )(using RuntimeContext): Vector[Vector[Vector[Example]]] =
    val predictorCount = ps.read(student).size
    val zeroShot       = Vector.fill(predictorCount)(Vector.empty[Example])

    val bootstrapped = (0 until config.numCandidates).iterator.map { k =>
      val bootstrap = new BootstrapFewShot[P](
        BootstrapFewShotConfig(
          metric = Some(config.metric),
          maxBootstrappedDemos = config.maxBootstrappedDemos,
          maxLabeledDemos = config.maxLabeledDemos,
          seed = config.seed + k
        )
      )
      bootstrap.compile(student, trainset, teacher) match
        case Right(report) => ps.read(report.bestProgram).map(_.demos)
        case Left(_)       => zeroShot
    }.toVector

    (zeroShot +: bootstrapped).distinct

  // ── Phase 2 ─────────────────────────────────────────────────────────────

  /** Propose `numCandidates` instructions per predictor via [[GroundedProposer]], then prepend each predictor's
    * CURRENT instruction as an extra candidate (so the baseline instruction is always reachable). */
  private def proposeInstructionCandidates(
      student: P,
      trainset: Vector[Example],
      demoCandidates: Vector[Vector[Vector[Example]]]
  )(using RuntimeContext): Either[DspyError, Vector[Vector[String]]] =
    val proposer = new GroundedProposer[P](config.proposerConfig)
    // Ground the proposer on the first bootstrapped demo assignment if present (index 0 is zero-shot), else empty.
    val seedDemos: Vector[Vector[Example]] = demoCandidates.lift(1).getOrElse(Vector.empty)
    val currentInstructions: Vector[String] =
      ps.read(student).map(_.layout.instructions.getOrElse(""))

    proposer.proposeInstructions(student, trainset, seedDemos).map { proposed =>
      proposed.zipWithIndex.map { case (perPredictor, idx) =>
        val current = currentInstructions.lift(idx).getOrElse("")
        (current +: perPredictor).filter(_.nonEmpty).distinct match
          case Vector() => Vector(current) // never empty: keep the (possibly blank) baseline as the sole candidate
          case nonEmpty => nonEmpty
      }
    }

  // ── Phase 3 ─────────────────────────────────────────────────────────────

  /** Random search over (demo-assignment index, per-predictor instruction index). Always scores the baseline
    * (unmodified student) plus `numTrials` randomly-assembled candidates; returns the best, with all candidates
    * sorted descending. */
  private def searchTrials(
      student: P,
      evalset: Vector[Example],
      demoCandidates: Vector[Vector[Vector[Example]]],
      instructionCandidates: Vector[Vector[String]],
      predictorCount: Int
  )(using RuntimeContext): OptimizationReport[P] =
    val rng        = new Random(config.seed)
    val candidates = mutable.ArrayBuffer.empty[CandidateProgram[P]]

    // Baseline: the unmodified student.
    val baselineScore = scoreProgram(student, evalset)
    candidates += CandidateProgram(student, baselineScore, metadata = Map("trial" -> -1, "baseline" -> true))

    (0 until config.numTrials).foreach { trial =>
      val demoIdx       = rng.nextInt(demoCandidates.size)
      val demoAssignment = demoCandidates(demoIdx)
      val instrIndices  = (0 until predictorCount).map(p => rng.nextInt(instructionCandidates(p).size)).toVector
      val chosenInstrs  = instrIndices.zipWithIndex.map { case (i, p) => instructionCandidates(p)(i) }

      val applied = applyTrial(student, chosenInstrs, demoAssignment)
      val score   = scoreProgram(applied, evalset)
      candidates += CandidateProgram(
        program = applied,
        score = score,
        metadata = Map("trial" -> trial, "demo_index" -> demoIdx, "instruction_indices" -> instrIndices)
      )
    }

    val sorted = candidates.toVector.sortBy(-_.score)
    val best   = sorted.head
    OptimizationReport(
      bestProgram = best.program,
      candidates = sorted,
      metadata = Map(
        "num_candidates"      -> sorted.size,
        "best_score"          -> best.score,
        "num_trials"          -> config.numTrials,
        "num_demo_candidates" -> demoCandidates.size,
        "predictors"          -> predictorCount
      )
    )

  /** Build a trial program by applying, via a single [[Predictors.replace]], each predictor's chosen instruction
    * and chosen demos. `instructions(p)` and `demoAssignment(p)` line up with [[Predictors.read]] order. */
  private def applyTrial(
      student: P,
      instructions: Vector[String],
      demoAssignment: Vector[Vector[Example]]
  ): P =
    val leaves = ps.read(student)
    val updated = leaves.zipWithIndex.map { case (leaf, idx) =>
      val instr = instructions.lift(idx).getOrElse(leaf.layout.instructions.getOrElse(""))
      val demos = demoAssignment.lift(idx).getOrElse(leaf.demos)
      leaf.copy(layout = leaf.layout.withInstructions(Some(instr)), demos = demos)
    }
    ps.replace(student, updated)

  /** Run the whole program on the evalset and return the aggregate metric score (0..100), 0.0 on failure. */
  private def scoreProgram(program: P, evalset: Vector[Example])(using RuntimeContext): Double =
    OptimizerSupport.evalScore(program, evalset, config.metric, runner).getOrElse(0.0)
