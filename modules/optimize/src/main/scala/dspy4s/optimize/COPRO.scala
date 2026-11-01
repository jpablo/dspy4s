package dspy4s.optimize

import dspy4s.programs.ProgramRunner

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.PythonRepr
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.evaluate.contracts.Metric
import dspy4s.optimize.contracts.CandidateProgram
import dspy4s.optimize.contracts.OptimizationReport
import dspy4s.optimize.contracts.Teleprompter
import dspy4s.programs.strategies.DynamicPredict
import zio.blocks.schema.DynamicValue

import scala.collection.immutable.VectorMap

/** The settings of [[COPRO]] (upstream `COPRO(metric, breadth, depth, init_temperature)`).
  *
  * @param metric
  *   the task metric that scores each whole-program candidate
  * @param breadth
  *   the number of candidate instructions for each predictor in each round (must be > 1)
  * @param depth
  *   the number of rounds. Round 0 starts from the current instruction. Rounds `1..depth-1` use the past `(instruction,
  *   score)` attempts
  * @param initTemperature
  *   the temperature of the instruction generation (it goes to the options of the LM)
  * @param seed
  *   the seed of the rollout ids. A run is repeatable for a seed
  */
final case class COPROConfig(
    metric         : Metric,
    breadth        : CoproBreadth = CoproBreadth(10),
    depth          : RoundCount   = RoundCount(3),
    initTemperature: Double       = 1.4,
    seed           : Long         = 0L
)

/** COPRO, the coordinate-ascent prompt optimizer. A v1 port of `dspy.teleprompt.COPRO`
  * (`dspy/teleprompt/copro_optimizer.py`).
  *
  * '''Algorithm.''' For each predictor of [[OptimizableStructure.read]]:
  *   1. Propose `breadth - 1` candidate instructions (upstream `BasicGenerateInstruction`, from the effective
  *      instructions of the predictor). Each proposal is one call with its own `rolloutId`. The current instruction of
  *      the predictor is one more candidate, as upstream.
  *   1. Evaluate the WHOLE program with each candidate applied to THIS predictor (with
  *      [[OptimizableStructure.replace]]) on the valset, else on the trainset, with [[dspy4s.evaluate.Evaluate]] and
  *      the metric.
  *   1. Run `depth - 1` more rounds. Each round proposes `breadth` candidates from the best `min(attempts, breadth)`
  *      attempts, worst first (upstream `GenerateInstructionGivenAttempts`).
  *   1. Keep the best instruction of the predictor, and go to the next predictor (greedy coordinate ascent).
  *
  * A `Left` of a proposal or of an evaluation is the `Left` of `compile`, as upstream raises. The report key
  * `best_score` is the best score of the last predictor. As upstream, no evaluation comes after the search.
  *
  * '''Deltas from Python.'''
  *   - '''Multi-predictor strategy is greedy and sequential, not joint.''' Python evaluates the candidates of each
  *     predictor against the current best of the others in each depth round. This v1 runs all depth rounds of one
  *     predictor with the others fixed, and then goes to the next predictor. A program with one predictor gets the same
  *     search. A program with more predictors can get a local optimum of the joint search.
  *   - '''Prefix optimization is dropped.''' The signatures of Python also have the output
  *     `proposed_prefix_for_output_field`, and they change the prefix of the last output field. dspy4s applies only the
  *     instruction, so the signatures have no such output and the attempts have no `Prefix #i` item.
  *   - '''`track_stats` is omitted.''' The [[OptimizationReport]] holds the scored candidates and a summary.
  *   - '''One `rolloutId`-varied call for each candidate''', not one `n=breadth` batch (dspy4s `Predict` returns one
  *     completion for each call). The effect (different proposals) is the same.
  *   - '''De-duplication is by instruction string''', not by the `(instruction, prefix)` pair and the last field.
  *   - '''A blank proposal gives no candidate.''' Python would write an empty instruction.
  *   - '''The current instruction stays a candidate that writes nothing.''' A leaf with no instructions keeps `None`,
  *     and a prompt shows its effective instructions. Python reads the default text and writes it back, which gives the
  *     same prompt.
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
      // Greedy coordinate ascent: optimize the instruction of each leaf in turn, with the others fixed.
      val start: Either[DspyError, Searched] = Right(Searched(student, Vector.empty, 0.0))
      (0 until leafCount).foldLeft(start)((done, idx) =>
        done.flatMap(searched =>
          optimizeLeaf(searched.program, idx, evalset).map(found =>
            found.copy(candidates = searched.candidates ++ found.candidates)
          )
        )
      ).map { searched =>
        val sorted = searched.candidates.sortBy(-_.score)
        OptimizationReport(
          bestProgram = searched.program,
          candidates = sorted,
          metadata = Map(
            "num_candidates"     -> sorted.size,
            "best_score"         -> searched.bestScore,
            "optimizable_leaves" -> leafCount
          )
        )
      }

  /** The result of a search: the program with the best instruction of each leaf so far, the candidates, and the best
    * score of the last leaf.
    */
  private final case class Searched(program: P, candidates: Vector[CandidateProgram[P]], bestScore: Double)

  /** The search of one leaf, for all depth rounds, with the rest of the program fixed. The first `Left` of a proposal
    * or of an evaluation stops the search. A candidate is `Some(text)`, or `None` for "keep the current instructions".
    */
  private def optimizeLeaf(program: P, idx: Int, evalset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, Searched] =
    val basic = ps.inspect(program)(idx).layout.effectiveInstructions

    // The best score of each candidate of this leaf, and the whole-program candidates of the report.
    type Attempts = (VectorMap[Option[String], Double], Vector[CandidateProgram[P]])

    def candidates(proposals: Vector[String]): Vector[Option[String]] =
      proposals.map(OptimizerSupport.instructionCandidate(_, basic))

    def scoreAll(start: Attempts, instructions: Vector[Option[String]]): Either[DspyError, Attempts] =
      instructions.foldLeft[Either[DspyError, Attempts]](Right(start)) { (done, instruction) =>
        done.flatMap { (evaluated, scored) =>
          val applied = OptimizerSupport.applyInstruction(program, idx, instruction)
          OptimizerSupport.evalScore(applied, evalset, config.metric, runner).map { score =>
            val next = CandidateProgram(
              program = applied,
              score = score,
              metadata = Map("optimizable_leaf" -> idx, "instruction" -> instruction.getOrElse(basic))
            )
            (if evaluated.get(instruction).forall(score > _) then evaluated.updated(instruction, score)
             else evaluated) -> (scored :+ next)
          }
        }
      }

    /** The input of upstream `GenerateInstructionGivenAttempts`: a list with two items for each of the best attempts,
      * worst first (upstream `copro_optimizer.py`, the loop over `best_predictors`).
      */
    def attempted(evaluated: VectorMap[Option[String], Double]): DynamicValue.Record =
      val shown = evaluated.toVector.sortBy(-_._2).take(config.breadth).reverse
      val items = shown.zipWithIndex.flatMap { case ((candidate, score), i) =>
        Vector(
          s"Instruction #${i + 1}: ${candidate.getOrElse(basic)}",
          s"Resulting Score #${i + 1}: ${PythonRepr.float(score)}"
        )
      }
      DynamicValues.record("attempted_instructions" -> DynamicValues.sequence(items.map(DynamicValues.string)))

    for
      // Round 0: `breadth - 1` proposals, and the current instructions of the leaf as the last candidate.
      proposals <- propose(
                     COPRO.BasicGenerateInstruction,
                     DynamicValues.record("basic_instruction" := basic),
                     config.breadth - 1,
                     idx,
                     round = 0
                   )
      round0 <- scoreAll((VectorMap.empty, Vector.empty), (candidates(proposals) :+ None).distinct)
      // Rounds 1..depth-1: proposals from the past attempts.
      searched <- (1 until config.depth).foldLeft[Either[DspyError, Attempts]](Right(round0)) { (done, round) =>
        done.flatMap { attempts =>
          propose(COPRO.GenerateInstructionGivenAttempts, attempted(attempts._1), config.breadth, idx, round)
            .flatMap(found => scoreAll(attempts, candidates(found)))
        }
      }
    yield
      val (evaluated, scored) = searched
      // `None` always gets a score, so `evaluated` is not empty.
      val (best, bestScore) = evaluated.maxBy(_._2)
      Searched(OptimizerSupport.applyInstruction(program, idx, best), scored, bestScore)

  /** The proposals of one round: one generation call for each proposal. The rollout ids
    * `seedBase + (leafIdx * depth + round) * breadth + i` do not overlap, for any size, so a later round does not get
    * the cached proposals of an earlier round, and two equal leaves do not get the same proposals. A proposal goes
    * through upstream `strip('"').strip()`. The result has no blank text and no repeated text.
    */
  private def propose(
      layout : SignatureLayout,
      inputs : DynamicValue.Record,
      count  : Int,
      leafIdx: Int,
      round  : Int
  )(using RuntimeContext): Either[DspyError, Vector[String]] =
    val predict = DynamicPredict(layout = layout, name = Some("copro_instruct"))
    val base    = OptimizerSupport.seedBase(config.seed) + (leafIdx * config.depth + round) * config.breadth
    Traverse.either(0 until count)(i =>
      OptimizerSupport.generateText(predict, inputs, COPRO.Output, config.initTemperature, base + i)
    ).map(
      _.flatten.map(proposal => PythonText.strip(OptimizerSupport.stripQuotes(proposal))).filter(_.nonEmpty).distinct
    )

private object COPRO:

  val Output: String = "proposed_instruction"

  private val ProposedInstruction: FieldSpec =
    FieldSpec(name = Output, description = Some("The improved instructions for the language model"))

  /** Upstream `BasicGenerateInstruction`, with no prefix output. */
  val BasicGenerateInstruction: SignatureLayout = SignatureLayout.of(
    name = "BasicGenerateInstruction",
    inputFields =
      Vector(FieldSpec(name = "basic_instruction", description = Some("The initial instructions before optimization"))),
    outputFields = Vector(ProposedInstruction),
    instructions = Some(
      "You are an instruction optimizer for large language models. I will give you a ``signature`` of fields (inputs " +
        "and outputs) in English. Your task is to propose an instruction that will lead a good language model to " +
        "perform the task well. Don't be afraid to be creative."
    )
  )

  /** Upstream `GenerateInstructionGivenAttempts`, with no prefix output. */
  val GenerateInstructionGivenAttempts: SignatureLayout = SignatureLayout.of(
    name = "GenerateInstructionGivenAttempts",
    inputFields = Vector(FieldSpec(name = "attempted_instructions")),
    outputFields = Vector(ProposedInstruction),
    instructions = Some(
      "You are an instruction optimizer for large language models. I will give some task instructions I've tried, " +
        "along with their corresponding validation scores. The instructions are arranged in increasing order based on " +
        "their scores, where higher scores indicate better quality.\n\nYour task is to propose a new instruction that " +
        "will lead a good language model to perform the task even better. Don't be afraid to be creative."
    )
  )
