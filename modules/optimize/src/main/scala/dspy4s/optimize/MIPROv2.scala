package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.optimization.{OptimizableParameters, OptimizableStructure}

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.PythonText
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.optimize.propose.GroundedProposer
import dspy4s.optimize.propose.GroundedProposerConfig

import scala.util.Random

/** Knobs for [[MIPROv2]], mirroring the relevant slice of upstream's `MIPROv2.__init__` / `.compile`.
  *
  * @param metric
  *   task metric used to score whole-program candidates on the valset (or trainset)
  * @param numCandidates
  *   the total number of demo sets (upstream `num_fewshot_candidates`). The first three seeds are the fixed sets of
  *   upstream `create_n_fewshot_demo_sets`, so the value 5 gives the zero-shot set, the labeled set, the unshuffled
  *   bootstrap, and two shuffled bootstraps. `proposerConfig.numInstructions` gives the number of proposed
  *   instructions.
  * @param numTrials
  *   number of search trials over (demo-assignment, per-predictor instruction) choices (upstream's `num_trials`). Must
  *   be `> 0`.
  * @param maxBootstrappedDemos
  *   cap on bootstrapped demos per demo-set candidate (forwarded to [[BootstrapFewShot]]). A shuffled set takes a
  *   random size between 1 and this cap
  * @param maxLabeledDemos
  *   cap on labeled demos per demo-set candidate (forwarded to [[LabeledFewShot]] and [[BootstrapFewShot]])
  * @param seed
  *   RNG seed; deterministic for a fixed seed. It gives the shuffled demo sets, the seed of the proposer (it replaces
  *   `proposerConfig.seed`, as upstream gives one `rng` to the proposer), and the trial search RNG
  * @param proposerConfig
  *   configuration for the [[GroundedProposer]] phase (instruction proposal)
  */
final case class MIPROv2Config(
    metric              : Metric,
    numCandidates       : CandidateCount         = CandidateCount(5),
    numTrials           : TrialCount             = TrialCount(10),
    maxBootstrappedDemos: DemoCount              = DemoCount(4),
    maxLabeledDemos     : DemoCount              = DemoCount(4),
    seed                : Long                   = 0L,
    proposerConfig      : GroundedProposerConfig = GroundedProposerConfig()
)

/** MIPROv2, the Multiprompt Instruction PRoposal Optimizer (v2). A v1 port of DSPy's `dspy.teleprompt.MIPROv2`
  * (`dspy/teleprompt/mipro_optimizer_v2.py`).
  *
  * '''Three-phase composition (reuses, does not reinvent).'''
  *   1. '''Demo-set candidates''' (Step 1, "bootstrap few-shot examples"; upstream `create_n_fewshot_demo_sets`). The
  *      seeds run from -3 until `numCandidates - 3`. Seed -3 is zero-shot. Seed -2 with `maxLabeledDemos > 0` is
  *      [[LabeledFewShot]]. Seed -1 is [[BootstrapFewShot]] on the trainset in its order. Each other seed, also -2 with
  *      no labeled demos, is [[BootstrapFewShot]] on the shuffled trainset with a random size between 1 and
  *      `maxBootstrappedDemos`. The result is a `Vector` of demo-assignments, each a per-predictor
  *      `Vector[Vector[Example]]` of length == predictor count. 2. '''Instruction candidates''' (Step 2,
  *      GroundedProposer). Calls [[GroundedProposer.proposeInstructions]]`(student, trainset, demoCandidates = <first
  *      assignment with a bootstrapped demo, or empty>)` → `Vector[Vector[String]]` (per predictor,
  *      `proposerConfig.numInstructions` instructions). Each predictor's CURRENT instruction is prepended as an extra
  *      candidate. 3. '''Search''' (Step 3, "find optimal prompt parameters"). For `numTrials` trials with a seeded
  *      RNG, randomly picks one demo-assignment index (applied whole-program) and, per predictor, one
  *      instruction-candidate index. The trial program is built with a single [[OptimizableStructure.replace]] applying
  *      each chosen instruction AND chosen demos. Each trial is scored on the valset (falling back to the trainset) via
  *      [[dspy4s.evaluate.Evaluate]] + [[ProgramRunner]] + the metric. A baseline candidate (the unmodified student) is
  *      always scored too. The best-scoring candidate is returned as `bestProgram`; all scored candidates (trials +
  *      baseline) are returned sorted descending.
  *
  * A failed bootstrap and a failed proposal are the `Left` of `compile`, as upstream raises. A failed evaluation of a
  * trial or of the baseline is the score 0.0 with a warning, as upstream `eval_candidate_program`.
  *
  * '''Deltas from Python.'''
  *   - '''Random search instead of Optuna TPE.''' Python's Step 3 drives an Optuna `TPESampler` study that builds a
  *     surrogate over the categorical (instruction-index, demo-index) parameters and samples promising combinations.
  *     dspy4s uses a seeded uniform-random search over the same parameter space. No surrogate model is built; trials
  *     are i.i.d. given the seed.
  *   - '''Whole-program demo choice.''' Each trial picks ONE demo-assignment for the whole program.
  *     [[BootstrapFewShot]] gives each predictor the demos of its own trace entries, so the per-predictor vectors of
  *     one assignment can be different. Python picks a demo-set index for each predictor, so one trial can use demo
  *     sets from different bootstrap runs.
  *   - '''Minibatch / full-eval distinction dropped.''' Python evaluates trials on a minibatch and periodically re-runs
  *     full evaluations (with successive promotion of the best param combo). dspy4s always does a full evaluation on
  *     the provided set every trial. No `minibatch_full_eval_steps`, no `_perform_full_evaluation`.
  *   - '''The current instruction stays a candidate that writes nothing.''' A leaf with no instructions keeps `None`,
  *     and a prompt shows its effective instructions. Python reads the default text and writes it back, which gives the
  *     same prompt. A blank proposal gives no candidate.
  *   - '''No surrogate model / no successive halving.''' Beyond dropping Optuna, there is no early stopping, promotion,
  *     or candidate pruning between trials.
  *   - '''Two knobs, with no `auto` mode.''' `numCandidates` is `num_fewshot_candidates`, and
  *     `proposerConfig.numInstructions` is `num_instruct_candidates`. dspy4s has no `auto` run modes that derive them.
  *   - '''A demo cap of 0.''' A `maxBootstrappedDemos` of 0 gives the size 0, where upstream `randint(1, 0)` raises.
  *   - '''Dataset split / `auto` settings omitted.''' No automatic train/val splitting or `light/medium/heavy` presets;
  *     the caller supplies `valset` explicitly (else the trainset is reused).
  *   - '''`track_stats`, `log_dir`, LM-call estimation, and program persistence are omitted.''' The
  *     [[OptimizationReport]] carries the scored candidate list and summary metadata instead.
  */
final class MIPROv2[P: {OptimizableStructure, ProgramRunner}](config: MIPROv2Config) extends Teleprompter[P]:

  override val name: String = "mipro_v2"

  private val ps: OptimizableStructure[P] = summon[OptimizableStructure[P]]
  private val runner: ProgramRunner[P]    = summon[ProgramRunner[P]]

  override protected[optimize] def compileInScope(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P],
      valset  : Option[Vector[Example]]
  )(using RuntimeContext): Either[DspyError, OptimizationReport[P]] =
    val evalset: Vector[Example] = valset.getOrElse(trainset)
    val leafCount                = ps.read(student).size

    if leafCount == 0 then
      Right(OptimizerSupport.noOptimizableLeavesReport(student))
    else if evalset.isEmpty then Left(OptimizerSupport.emptyEvalsetError(name))
    else
      for
        // Two leaves with the same predictor key are a configuration error (decision D2).
        _ <- OptimizerSupport.requireDistinctKeys(student, teacher)
        // ── Phase 1: demo-set candidates (whole-program demo assignments) ──
        demoCandidates <- bootstrapDemoCandidates(student, trainset, teacher)
        // ── Phase 2: instruction candidates (per predictor) ──
        instructionCandidates <- proposeInstructionCandidates(student, trainset, demoCandidates)
      yield
        // ── Phase 3: random search over (demo-assignment, per-predictor instruction) ──
        searchTrials(student, evalset, demoCandidates, instructionCandidates, leafCount)

  // ── Phase 1 ─────────────────────────────────────────────────────────────

  /** The `numCandidates` whole-program demo assignments (upstream `create_n_fewshot_demo_sets`, with its
    * `num_candidate_sets -= 3`), one for each seed from -3 until `numCandidates - 3`, each one the per-predictor demo
    * vectors. One random source serves all shuffled seeds, in order. A `Left` of a bootstrap run is the `Left` of
    * `compile`, as upstream raises.
    */
  private def bootstrapDemoCandidates(
      student : P,
      trainset: Vector[Example],
      teacher : Option[P]
  )(using RuntimeContext): Either[DspyError, Vector[Vector[Vector[Example]]]] =
    val bootstrapConfig = BootstrapFewShotConfig(
      metric = Some(config.metric),
      maxBootstrappedDemos = config.maxBootstrappedDemos,
      maxLabeledDemos = config.maxLabeledDemos,
      seed = config.seed
    )
    val rng = new Random(config.seed)

    def demosOf(report: OptimizationReport[P]): Vector[Vector[Example]] = ps.read(report.bestProgram).map(_.demos)

    Traverse.either(-3 until config.numCandidates - 3) {
      case -3                               => Right(Vector.fill(ps.read(student).size)(Vector.empty[Example]))
      case -2 if config.maxLabeledDemos > 0 =>
        new LabeledFewShot[P](LabeledFewShotConfig(k = config.maxLabeledDemos, seed = config.seed))
          .compile(student, trainset)
          .map(demosOf)
      case -1   => new BootstrapFewShot[P](bootstrapConfig).compile(student, trainset, teacher).map(demosOf)
      case seed => OptimizerSupport
          .shuffledBootstrap(rng, bootstrapConfig.copy(seed = config.seed + seed), student, trainset, teacher)
          .map(demosOf)
    }

  // ── Phase 2 ─────────────────────────────────────────────────────────────

  /** The instruction candidates of each predictor: `None` means "keep the current instructions", so the baseline is
    * always reachable and a leaf with no instructions keeps `None`. After it come the proposals of
    * [[GroundedProposer]]. A blank proposal gives no candidate.
    */
  private def proposeInstructionCandidates(
      student       : P,
      trainset      : Vector[Example],
      demoCandidates: Vector[Vector[Vector[Example]]]
  )(using RuntimeContext): Either[DspyError, Vector[Vector[Option[String]]]] =
    // Upstream gives one `rng` to the proposer and to the optimizer.
    val proposer = new GroundedProposer[P](config.proposerConfig.copy(seed = config.seed))
    // Ground the proposer on the first assignment with a bootstrapped demo. A labeled set has none.
    val seedDemos: Vector[Vector[Example]] =
      demoCandidates.find(_.exists(_.exists(_.augmented))).getOrElse(Vector.empty)
    val effective: Vector[String] = ps.inspect(student).map(_.layout.effectiveInstructions)

    proposer.proposeInstructions(student, trainset, seedDemos).map { proposed =>
      proposed.zip(effective).map { (perLeaf, current) =>
        val found = perLeaf.filter(text => PythonText.strip(text).nonEmpty)
        (None +: found.map(OptimizerSupport.instructionCandidate(_, current))).distinct
      }
    }

  // ── Phase 3 ─────────────────────────────────────────────────────────────

  /** Random search over (demo-assignment index, per-predictor instruction index). Always scores the baseline
    * (unmodified student) plus `numTrials` randomly-assembled candidates; returns the best, with all candidates sorted
    * descending.
    */
  private def searchTrials(
      student              : P,
      evalset              : Vector[Example],
      demoCandidates       : Vector[Vector[Vector[Example]]],
      instructionCandidates: Vector[Vector[Option[String]]],
      leafCount            : Int
  )(using RuntimeContext): OptimizationReport[P] =
    val rng = new Random(config.seed)

    // Baseline: the unmodified student.
    val baselineScore = scoreProgram(student, evalset)
    val baseline      = CandidateProgram(student, baselineScore, metadata = Map("trial" -> -1, "baseline" -> true))

    // `student` is immutable, so its optimizable leaves are the same in each trial. Read them one time.
    val leaves = ps.read(student)
    val trials = Vector.tabulate(config.numTrials) { trial =>
      val demoIdx        = rng.nextInt(demoCandidates.size)
      val demoAssignment = demoCandidates(demoIdx)
      val instrIndices   = (0 until leafCount).map(p => rng.nextInt(instructionCandidates(p).size)).toVector
      val chosenInstrs   = instrIndices.zipWithIndex.map { case (i, p) => instructionCandidates(p)(i) }

      val applied = applyTrial(leaves, student, chosenInstrs, demoAssignment)
      val score   = scoreProgram(applied, evalset)
      CandidateProgram(
        program = applied,
        score = score,
        metadata = Map("trial" -> trial, "demo_index" -> demoIdx, "instruction_indices" -> instrIndices)
      )
    }

    val sorted = (baseline +: trials).sortBy(-_.score)
    val best   = sorted.head
    OptimizationReport(
      bestProgram = best.program,
      candidates = sorted,
      metadata = Map(
        "num_candidates"      -> sorted.size,
        "best_score"          -> best.score,
        "num_trials"          -> config.numTrials,
        "num_demo_candidates" -> demoCandidates.size,
        "optimizable_leaves"  -> leafCount
      )
    )

  /** Build a trial program by applying, via a single [[OptimizableStructure.replace]], each predictor's chosen
    * instruction and chosen demos. `instructions(p)` and `demoAssignment(p)` line up with [[OptimizableStructure.read]]
    * order. An instruction of `None` keeps the current instructions of the leaf.
    */
  private def applyTrial(
      leaves        : Vector[OptimizableParameters],
      student       : P,
      instructions  : Vector[Option[String]],
      demoAssignment: Vector[Vector[Example]]
  ): P =
    ps.replace(
      student,
      leaves.lazyZip(instructions).lazyZip(demoAssignment).map { (leaf, instruction, demos) =>
        leaf.copy(instructions = instruction.orElse(leaf.instructions), demos = demos)
      }
    )

  /** The score of the whole program on the evalset. A failed evaluation is the score 0.0, with a warning, as upstream
    * `eval_candidate_program` (`dspy/teleprompt/utils.py`).
    */
  private def scoreProgram(program: P, evalset: Vector[Example])(using RuntimeContext): Double =
    OptimizerSupport.evalScore(program, evalset, config.metric, runner) match
      case Right(score) => score
      case Left(error)  =>
        RuntimeEnvironment.warn("MIPROv2", s"An exception occurred during evaluation: ${error.message}")
        0.0
