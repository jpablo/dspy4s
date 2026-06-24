package dspy4s.optimize

import dspy4s.programs.Predictors

import dspy4s.core.contracts.:=
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
import dspy4s.programs.contracts.ProgramCall

import scala.collection.mutable

/** Knobs for [[COPRO]], mirroring upstream's `COPRO(metric, breadth, depth, init_temperature)`.
  *
  * @param metric            task metric used to score whole-program candidates
  * @param breadth           number of candidate instructions generated per predictor per round (must be > 1)
  * @param depth             number of refinement rounds (round 0 seeds from the current instruction; rounds
  *                          `1..depth-1` refine using past `(instruction, score)` attempts)
  * @param initTemperature   sampling temperature for instruction generation (forwarded to the LM option bag)
  * @param seed              RNG seed; deterministic for a fixed seed
  * @param instructionMarker a stable sentinel woven into the instruction-generation signature's instructions so
  *                          a generation prompt is distinguishable from a task prompt (also lets offline scripted
  *                          LMs branch). Defaults to the human-readable upstream-style preamble.
  */
final case class COPROConfig(
    metric: Metric,
    breadth: Int = 10,
    depth: Int = 3,
    initTemperature: Double = 1.4,
    seed: Long = 0L,
    instructionMarker: String =
      "You are an instruction optimizer for large language models. Propose an improved instruction."
):
  require(breadth > 1, "COPRO breadth must be greater than 1")

/** COPRO — Coordinate-ascent Prompt Optimizer. A v1 port of DSPy's `dspy.teleprompt.COPRO`
  * (`dspy/teleprompt/copro_optimizer.py`).
  *
  * '''Algorithm.''' For each learnable predictor exposed by [[Predictors.read]]:
  *   1. Seed `breadth - 1` candidate instructions with an instruction-generation [[DynamicPredict]] (the
  *      `BasicGenerateInstruction` analogue: `basic_instruction -> proposed_instruction`), seeded with the
  *      predictor's current instruction and its signature's field names. Distinct candidates are sampled by
  *      varying the call's `rolloutId` (and temperature in `config`), mirroring upstream's `n = breadth`. The
  *      predictor's own current instruction is added as the `breadth`-th candidate (matches upstream).
  *   2. Evaluate the WHOLE program with each candidate instruction applied to THIS predictor (via
  *      [[Predictors.replace]]) on the valset (falling back to the trainset) using [[dspy4s.evaluate.Evaluate]] + the metric.
  *   3. Keep the best-scoring instruction for this predictor, then run `depth - 1` further rounds that refine
  *      using the accumulated `(instruction, score)` attempts (the `GenerateInstructionGivenAttempts` analogue).
  *   4. Lock in the predictor's best instruction before moving to the next predictor (greedy coordinate ascent).
  *
  * '''Deltas from Python.'''
  *   - '''Multi-predictor strategy is greedy/sequential, not joint.''' Python re-evaluates every predictor's
  *     candidates against the current best of the others within each depth round (an interleaved joint search).
  *     This v1 fully optimizes one predictor's instruction (all depth rounds) with the others fixed, then moves
  *     on. Single-predictor programs — the primary tested path — are unaffected; multi-predictor results may be a
  *     local optimum of the joint search.
  *   - '''Prefix optimization is dropped.''' Python's signatures also emit `proposed_prefix_for_output_field` and
  *     mutate the last output field's prefix. dspy4s applies only the instruction; the output-field prefix is left
  *     untouched. (`SignatureLayout` supports per-field prefixes, but instruction-only keeps v1 tractable and is
  *     the dominant lever.)
  *   - '''`track_stats` is omitted.''' No per-depth min/max/avg/std bookkeeping; the [[OptimizationReport]] carries
  *     the scored candidate list and summary metadata instead.
  *   - '''Candidate sampling uses one `rolloutId`-varied call per candidate''' rather than a single `n=breadth`
  *     batch completion (dspy4s `Predict` returns one completion per call); the effect (distinct proposals) is the
  *     same.
  *   - '''De-duplication is by instruction string''', not Python's `(instruction, prefix)` + last-field equality.
  */
final class COPRO[P: Predictors: Runnable](config: COPROConfig) extends Teleprompter[P]:

  override val name: String = "copro"

  private val ps: Predictors[P]  = summon[Predictors[P]]
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
      // Greedy coordinate ascent: optimize each predictor's instruction in turn, keeping the others fixed.
      var current      = student
      val allCandidates = mutable.ArrayBuffer.empty[CandidateProgram[P]]

      (0 until predictorCount).foreach { idx =>
        val (updated, candidates) = optimizePredictor(current, idx, evalset)
        current = updated
        allCandidates ++= candidates
      }

      // Score the final program (all predictors at their best) so the report's best reflects the applied state.
      // A failed final eval reports as 0.0 in metadata (it's a summary number, not a selection input).
      val finalScore = scoreProgram(current, evalset).getOrElse(0.0)
      val sorted     = allCandidates.toVector.sortBy(-_.score)
      Right(
        OptimizationReport(
          bestProgram = current,
          candidates = sorted,
          metadata = Map(
            "num_candidates" -> sorted.size,
            "best_score"     -> finalScore,
            "predictors"     -> predictorCount
          )
        )
      )

  /** Optimize a single predictor's instruction (all depth rounds) with the rest of the program held fixed.
    * Returns the program with that predictor's best instruction applied, plus every whole-program candidate
    * scored along the way. */
  private def optimizePredictor(program: P, idx: Int, evalset: Vector[Example])(using
      RuntimeContext
  ): (P, Vector[CandidateProgram[P]]) =
    val leaves            = ps.read(program)
    val predictor         = leaves(idx)
    val baseInstruction   = predictor.layout.instructions.getOrElse("")
    val fieldNames        = predictor.layout.fields.map(_.name)

    // (instruction -> best score) seen for this predictor, plus the whole-program candidates emitted.
    val evaluated         = mutable.LinkedHashMap.empty[String, Double]
    val candidates        = mutable.ArrayBuffer.empty[CandidateProgram[P]]

    def scoreCandidate(instruction: String): P =
      val applied = OptimizerSupport.applyInstruction(program, idx, instruction)
      // A whole-eval failure (timeout / maxErrors) yields None — skip it entirely rather than recording a
      // real 0.0 that would corrupt selection (a genuinely-0-scoring instruction could then look "as good").
      scoreProgram(applied, evalset).foreach { score =>
        if evaluated.get(instruction).forall(score > _) then evaluated.update(instruction, score)
        candidates += CandidateProgram(program = applied, score = score,
          metadata = Map("predictor" -> idx, "instruction" -> instruction))
      }
      applied

    // ── Round 0: seed breadth-1 fresh candidates + the predictor's own current instruction ──
    val seedProposals =
      generateInstructions(idx, baseInstruction, fieldNames, config.breadth - 1, attempts = Vector.empty)
    val round0 = (seedProposals :+ baseInstruction).filter(_.nonEmpty).distinct
    round0.foreach(scoreCandidate)

    // ── Rounds 1..depth-1: refine using past (instruction, score) attempts ──
    (1 until config.depth).foreach { _ =>
      val attempts = evaluated.toVector.sortBy(_._2) // ascending score, as upstream presents them
      val refined  = generateInstructions(idx, baseInstruction, fieldNames, config.breadth, attempts)
      refined.filter(_.nonEmpty).distinct.foreach(scoreCandidate)
    }

    // Lock in the best instruction for this predictor.
    val bestInstruction =
      if evaluated.isEmpty then baseInstruction
      else evaluated.maxBy(_._2)._1
    val updated = OptimizerSupport.applyInstruction(program, idx, bestInstruction)
    (updated, candidates.toVector)

  /** Run the whole program on the evalset and return the aggregate metric score (0..100), or `None` when the
    * whole evaluation fails (timeout / maxErrors exceeded). `None` must NOT be collapsed into `0.0` at call
    * sites that select the best candidate — a failed eval is "unknown", not "scored zero". */
  private def scoreProgram(program: P, evalset: Vector[Example])(using RuntimeContext): Option[Double] =
    OptimizerSupport.evalScore(program, evalset, config.metric, runner)

  // ── Instruction generation sub-program ──────────────────────────────────

  /** The instruction-generation signature. Round 0 (`BasicGenerateInstruction`): `basic_instruction ->
    * proposed_instruction`. Refinement rounds (`GenerateInstructionGivenAttempts`): add an
    * `attempted_instructions` input. `instructionMarker` is set as the signature instructions so generation
    * prompts are distinguishable from task prompts. */
  private def instructionGenLayout(withAttempts: Boolean): SignatureLayout =
    val inputs =
      Vector(FieldSpec(name = "basic_instruction", role = FieldRole.Input)) ++
        (if withAttempts then Vector(FieldSpec(name = "attempted_instructions", role = FieldRole.Input))
         else Vector.empty)
    SignatureLayout(
      name = "GenerateInstruction",
      fields = inputs :+ FieldSpec(name = "proposed_instruction", role = FieldRole.Output),
      instructions = Some(config.instructionMarker)
    )

  /** Generate up to `count` distinct candidate instructions by running the generation [[DynamicPredict]] once
    * per candidate, varying `rolloutId` (and seeding temperature into the option bag) so the LM yields distinct
    * proposals. `attempts` (ascending by score) seed the refinement variant when non-empty. */
  private def generateInstructions(
      predictorIdx: Int,
      baseInstruction: String,
      fieldNames: Vector[String],
      count: Int,
      attempts: Vector[(String, Double)]
  )(using RuntimeContext): Vector[String] =
    if count <= 0 then Vector.empty
    else
      val withAttempts = attempts.nonEmpty
      val gen          = DynamicPredict(layout = instructionGenLayout(withAttempts), name = Some("copro_instruct"))
      val attemptsText =
        attempts.zipWithIndex
          .map { case ((instr, score), i) => s"Instruction #${i + 1}: $instr\nResulting Score #${i + 1}: $score" }
          .mkString("\n")
      val fieldsHint = fieldNames.mkString(", ")
      val baseInputs: Vector[(String, zio.blocks.schema.DynamicValue)] =
        Vector("basic_instruction" := s"$baseInstruction (fields: $fieldsHint)") ++
          (if withAttempts then Vector("attempted_instructions" := attemptsText) else Vector.empty)

      // Deterministic, contiguous rolloutId stream so candidate sampling is reproducible AND spans a
      // predictable window (a scripted/temperature-driven LM yields a distinct proposal per rolloutId). The
      // round salt keeps refinement rounds from re-drawing the same window as the seed round; the per-predictor
      // offset keeps HOMOGENEOUS predictors (identical baseInstruction/fields) from drawing the SAME window and
      // thus requesting byte-identical generations that hit the LM cache (yielding identical proposals).
      val roundSalt = if withAttempts then count * 7 else 0
      val base      = OptimizerSupport.seedBase(config.seed) + predictorIdx * 10000 + roundSalt
      val results = (0 until count).iterator.flatMap { i =>
        val rolloutId = base + i
        val call = ProgramCall(
          inputs    = DynamicValues.recordFromEntries(baseInputs),
          config    = DynamicValues.recordFromEntries(Vector("temperature" := config.initTemperature)),
          rolloutId = Some(rolloutId)
        )
        gen.apply(call) match
          case Right(pred) =>
            DynamicValues.recordGet(pred.values, "proposed_instruction")
              .map(DynamicValues.renderText)
              .map(_.trim)
              .filter(_.nonEmpty)
          case Left(_) => None
      }
      results.toVector.distinct
