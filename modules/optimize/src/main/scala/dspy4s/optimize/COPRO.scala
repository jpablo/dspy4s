package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.strategies.DynamicPredict

import scala.collection.immutable.VectorMap

/** Knobs for [[COPRO]], mirroring upstream's `COPRO(metric, breadth, depth, init_temperature)`.
  *
  * @param metric
  *   task metric used to score whole-program candidates
  * @param breadth
  *   number of candidate instructions generated per predictor per round (must be > 1)
  * @param depth
  *   number of refinement rounds (round 0 seeds from the current instruction; rounds `1..depth-1` refine using past
  *   `(instruction, score)` attempts)
  * @param initTemperature
  *   sampling temperature for instruction generation (forwarded to the LM option bag)
  * @param seed
  *   RNG seed; deterministic for a fixed seed
  * @param instructionMarker
  *   a stable sentinel woven into the instruction-generation signature's instructions so a generation prompt is
  *   distinguishable from a task prompt (also lets offline scripted LMs branch). Defaults to the human-readable
  *   upstream-style preamble.
  */
final case class COPROConfig(
    metric           : Metric,
    breadth          : CoproBreadth = CoproBreadth(10),
    depth            : RoundCount   = RoundCount(3),
    initTemperature  : Double       = 1.4,
    seed             : Long         = 0L,
    instructionMarker: String       =
      "You are an instruction optimizer for large language models. Propose an improved instruction."
)

/** COPRO — Coordinate-ascent Prompt Optimizer. A v1 port of DSPy's `dspy.teleprompt.COPRO`
  * (`dspy/teleprompt/copro_optimizer.py`).
  *
  * '''Algorithm.''' For each learnable predictor exposed by [[OptimizableStructure.read]]:
  *   1. Seed `breadth - 1` candidate instructions with an instruction-generation [[DynamicPredict]] (the
  *      `BasicGenerateInstruction` analogue: `basic_instruction -> proposed_instruction`), seeded with the predictor's
  *      current instruction and its signature's field names. Distinct candidates are sampled by varying the call's
  *      `rolloutId` (and temperature in `config`), mirroring upstream's `n = breadth`. The predictor's own current
  *      instruction is added as the `breadth`-th candidate (matches upstream). 2. Evaluate the WHOLE program with each
  *      candidate instruction applied to THIS predictor (via [[OptimizableStructure.replace]]) on the valset (falling
  *      back to the trainset) using [[dspy4s.evaluate.Evaluate]] + the metric. 3. Keep the best-scoring instruction for
  *      this predictor, then run `depth - 1` further rounds that refine using the accumulated `(instruction, score)`
  *      attempts (the `GenerateInstructionGivenAttempts` analogue). 4. Lock in the predictor's best instruction before
  *      moving to the next predictor (greedy coordinate ascent).
  *
  * '''Deltas from Python.'''
  *   - '''Multi-predictor strategy is greedy/sequential, not joint.''' Python re-evaluates every predictor's candidates
  *     against the current best of the others within each depth round (an interleaved joint search). This v1 fully
  *     optimizes one predictor's instruction (all depth rounds) with the others fixed, then moves on. Single-predictor
  *     programs — the primary tested path — are unaffected; multi-predictor results may be a local optimum of the joint
  *     search.
  *   - '''Prefix optimization is dropped.''' Python's signatures also emit `proposed_prefix_for_output_field` and
  *     mutate the last output field's prefix. dspy4s applies only the instruction; the output-field prefix is left
  *     untouched. (`SignatureLayout` supports per-field prefixes, but instruction-only keeps v1 tractable and is the
  *     dominant lever.)
  *   - '''`track_stats` is omitted.''' No per-depth min/max/avg/std bookkeeping; the [[OptimizationReport]] carries the
  *     scored candidate list and summary metadata instead.
  *   - '''Candidate sampling uses one `rolloutId`-varied call per candidate''' rather than a single `n=breadth` batch
  *     completion (dspy4s `Predict` returns one completion per call); the effect (distinct proposals) is the same.
  *   - '''De-duplication is by instruction string''', not Python's `(instruction, prefix)` + last-field equality.
  */
final class COPRO[P: {OptimizableStructure, ProgramRunner}](config: COPROConfig) extends Teleprompter[P]:

  override val name: String = "copro"

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
      // Greedy coordinate ascent: optimize each leaf's instruction in turn, keeping the others fixed.
      val (current, candidates) = (0 until leafCount).foldLeft((student, Vector.empty[CandidateProgram[P]])) {
        case ((program, done), idx) =>
          val (updated, found) = optimizeLeaf(program, idx, evalset)
          (updated, done ++ found)
      }

      // Score the final program (all leaves at their best) so the report's best reflects the applied state.
      // A failed final eval reports as 0.0 in metadata (it's a summary number, not a selection input).
      val finalScore = scoreProgram(current, evalset).getOrElse(0.0)
      val sorted     = candidates.sortBy(-_.score)
      Right(
        OptimizationReport(
          bestProgram = current,
          candidates = sorted,
          metadata = Map(
            "num_candidates"     -> sorted.size,
            "best_score"         -> finalScore,
            "optimizable_leaves" -> leafCount
          )
        )
      )

  /** Optimize a single leaf's instruction (all depth rounds) with the rest of the program held fixed. Returns the
    * program with that leaf's best instruction applied, plus every whole-program candidate scored along the way.
    */
  private def optimizeLeaf(program: P, idx: Int, evalset: Vector[Example])(using
      RuntimeContext
  ): (P, Vector[CandidateProgram[P]]) =
    val leaf            = ps.inspect(program)(idx)
    val baseInstruction = leaf.layout.instructions.getOrElse("")
    val fieldNames      = leaf.layout.fields.map(_.name)

    // (instruction -> best score) seen for this leaf, plus the whole-program candidates emitted.
    type Attempts = (VectorMap[String, Double], Vector[CandidateProgram[P]])

    def scoreAll(start: Attempts, instructions: Vector[String]): Attempts =
      instructions.foldLeft(start) { case (attempts @ (evaluated, candidates), instruction) =>
        val applied = OptimizerSupport.applyInstruction(program, idx, instruction)
        // A whole-eval failure (timeout / maxErrors) gives None. Skip it: a recorded 0.0 would corrupt selection,
        // because an instruction that really scores 0 could then look "as good".
        scoreProgram(applied, evalset).fold(attempts) { score =>
          val next = CandidateProgram(
            program = applied,
            score = score,
            metadata = Map("optimizable_leaf" -> idx, "instruction" -> instruction)
          )
          (if evaluated.get(instruction).forall(score > _) then evaluated.updated(instruction, score) else evaluated) ->
            (candidates :+ next)
        }
      }

    // ── Round 0: seed breadth-1 fresh candidates + the leaf's own current instruction ──
    val seedProposals =
      generateInstructions(idx, baseInstruction, fieldNames, config.breadth - 1, attempts = Vector.empty, round = 0)
    val round0: Attempts =
      scoreAll((VectorMap.empty, Vector.empty), (seedProposals :+ baseInstruction).filter(_.nonEmpty).distinct)

    // ── Rounds 1..depth-1: refine using past (instruction, score) attempts ──
    val (evaluated, candidates) = (1 until config.depth).foldLeft(round0) { (done, round) =>
      val attempts = done._1.toVector.sortBy(_._2) // ascending score, as upstream presents them
      scoreAll(done, generateInstructions(idx, baseInstruction, fieldNames, config.breadth, attempts, round = round))
    }

    // Lock in the best instruction for this leaf.
    val bestInstruction =
      if evaluated.isEmpty then baseInstruction
      else evaluated.maxBy(_._2)._1
    (OptimizerSupport.applyInstruction(program, idx, bestInstruction), candidates)

  /** Run the whole program on the evalset and return the aggregate metric score (0..100), or `None` when the whole
    * evaluation fails (timeout / maxErrors exceeded). `None` must NOT be collapsed into `0.0` at call sites that select
    * the best candidate — a failed eval is "unknown", not "scored zero".
    */
  private def scoreProgram(program: P, evalset: Vector[Example])(using RuntimeContext): Option[Double] =
    OptimizerSupport.evalScore(program, evalset, config.metric, runner)

  // ── Instruction generation sub-program ──────────────────────────────────

  /** Generate up to `count` distinct candidate instructions by running the generation [[DynamicPredict]] once per
    * candidate, varying `rolloutId` (and seeding temperature into the option bag) so the LM yields distinct proposals.
    * The signature of round 0 (`BasicGenerateInstruction`) is `basic_instruction -> proposed_instruction`. `attempts`
    * (ascending by score) add an `attempted_instructions` input when non-empty (`GenerateInstructionGivenAttempts`).
    * `instructionMarker` is set as the signature instructions so generation prompts are distinguishable from task
    * prompts.
    */
  private def generateInstructions(
      leafIdx        : Int,
      baseInstruction: String,
      fieldNames     : Vector[String],
      count          : Int,
      attempts       : Vector[(String, Double)],
      round          : Int
  )(using RuntimeContext): Vector[String] =
    val attemptsText = attempts.zipWithIndex
      .map { case ((instr, score), i) => s"Instruction #${i + 1}: $instr\nResulting Score #${i + 1}: $score" }
      .mkString("\n")
    val fieldsHint = fieldNames.mkString(", ")
    val inputs     = DynamicValues.recordFromEntries(
      Vector("basic_instruction" := s"$baseInstruction (fields: $fieldsHint)") ++
        Option.when(attempts.nonEmpty)("attempted_instructions" := attemptsText)
    )
    val output = "proposed_instruction"
    val gen    = DynamicPredict(
      layout = OptimizerSupport.generationLayout("GenerateInstruction", inputs, output, config.instructionMarker),
      name = Some("copro_instruct")
    )

    // Deterministic, contiguous rolloutId stream so candidate sampling is reproducible AND spans a
    // predictable window (a scripted/temperature-driven LM yields a distinct proposal per rolloutId). The
    // per-round salt gives EACH round (seed = round 0, plus every refinement round) its own non-overlapping
    // window of `count` ids, so successive refinement rounds don't re-draw the same window (which would yield
    // byte-identical generations via the LM cache); the per-leaf offset likewise keeps HOMOGENEOUS
    // leaves (identical baseInstruction/fields) from drawing the SAME window.
    val roundSalt = round * config.breadth
    val base      = OptimizerSupport.seedBase(config.seed) + leafIdx * 10000 + roundSalt
    Vector.tabulate(count) { i =>
      OptimizerSupport.generateText(gen, inputs, output, config.initTemperature, base + i).toOption.flatten
    }.flatten.distinct
