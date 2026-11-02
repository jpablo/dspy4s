package dspy4s.optimize.propose

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.PythonText
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.optimize.OptimizerSupport
import dspy4s.optimize.CandidateCount
import dspy4s.optimize.DatasetSampleSize
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableView

/** The settings of [[GroundedProposer]] (a part of upstream
  * `dspy.propose.grounded_proposer.GroundedProposer.__init__`).
  *
  * @param numInstructions
  *   the number of candidate instructions for each predictor (upstream `N`). With demo sets, the number of sets is also
  *   a limit
  * @param useDatasetSummary
  *   when true, make one dataset summary for each [[GroundedProposer.proposeInstructions]] call, and show it in each
  *   proposal (upstream `use_dataset_summary`)
  * @param useTips
  *   when true, show a tip in each proposal (upstream `set_tip_randomly` and `use_tip`). The tip changes with the index
  *   of the candidate
  * @param datasetSampleSize
  *   the number of trainset examples in the prompt of the dataset summary (upstream `view_data_batch_size`, with one
  *   batch: see "Deltas from Python")
  * @param initTemperature
  *   the temperature of the generation calls (it goes to the options of the LM)
  * @param seed
  *   the seed of the rollout ids. A run is repeatable for a seed
  */
final case class GroundedProposerConfig(
    numInstructions  : CandidateCount    = CandidateCount(5),
    useDatasetSummary: Boolean           = true,
    useTips          : Boolean           = true,
    datasetSampleSize: DatasetSampleSize = DatasetSampleSize(10),
    initTemperature  : Double            = 1.0,
    seed             : Long              = 0L
)

/** GroundedProposer proposes candidate INSTRUCTIONS for each predictor from the task data and from bootstrapped demos.
  * A v1 port of a part of upstream `dspy.propose` (`grounded_proposer.py`, `dataset_summary_generator.py`).
  *
  * '''What it does.''' For each predictor of [[OptimizableStructure.read]], in read order, it makes candidate
  * instructions with a [[DynamicPredict]] (upstream `GenerateModuleInstruction`). With no demo sets, a predictor gets
  * `config.numInstructions` candidates. With demo sets, it gets `min(numInstructions, sets)` candidates (upstream
  * `range(num_demos)[:min(N, num_demos)]`). The inputs of each proposal, in the upstream order:
  *   1. `dataset_description`: a dataset summary, made one time for each call from a sample of the trainset (upstream
  *      `create_dataset_summary`), when `useDatasetSummary` is true and the summary did not fail;
  *   1. `module`: the name of the module, and its field names (`name(inputs) -> outputs`);
  *   1. `task_demos`: at most 3 bootstrapped demos of the predictor, or "No task demos provided.". Proposal `i` reads
  *      the demo sets in the order `i, i + 1, ..., the last set, 0, ..., i - 1` (upstream `adjacent_sets`). The first
  *      proposal shows no demos (upstream `demo_set_i == 0`);
  *   1. `basic_instruction`: the effective instructions of the predictor;
  *   1. `tip`: a tip from a fixed list, when `useTips` is true.
  *
  * Each proposal is one call with its own `rolloutId` (upstream: a copy of the LM with a new `rollout_id`). A proposal
  * and the summary go through upstream `strip_prefix`.
  *
  * A failed dataset summary gives no `dataset_description`, as upstream. A failed proposal is a `Left`.
  *
  * '''Deltas from Python.'''
  *   - '''No program-source `DescribeProgram` or `DescribeModule`.''' Upstream reads the Python source of the program
  *     (`get_dspy_source_code`), and makes two more LM calls that describe the program and the module. dspy4s programs
  *     have no source to read, so the proposal shows only `module`, with the `name(inputs) -> outputs` text that
  *     upstream gives to `DescribeModule`.
  *   - '''The dataset summary is a single batch, not the iterative loop.''' Upstream reads the trainset in batches of
  *     `view_data_batch_size`, adds to its observations (`DatasetDescriptorWithPriorObservations`), and makes a last
  *     `ObservationSummarizer` call. dspy4s makes one `DatasetDescriptor` call on `datasetSampleSize` examples. The
  *     examples are `field: value` text, not the `repr` of the Python list.
  *   - '''A demo is `field: value` text''', not the prefix form of upstream `create_example_string`.
  *   - '''The tip list has no empty tip, and the tip follows the index of the candidate''', not a random choice
  *     (`set_tip_randomly`). A run is repeatable for a seed.
  *   - '''One `rolloutId`-varied call for each candidate''', not a random `rollout_id`.
  *   - '''Instruction history (`previous_instructions`, `trial_logs`) is omitted.''' MIPROv2 calls the proposer one
  *     time, before its trials, so the proposer has no past attempts to show.
  */
final class GroundedProposer[P](config: GroundedProposerConfig)(using ps: OptimizableStructure[P]):

  /** The candidate instructions of EACH optimizable leaf of `program`, in [[OptimizableStructure.read]] order:
    * `config.numInstructions` candidates with no demo sets, else `min(numInstructions, sets)` candidates.
    *
    * @param program
    *   the program whose leaves get proposals
    * @param trainset
    *   the task data. A sample of it goes to the dataset summary
    * @param demoCandidates
    *   the demo sets, indexed by set and then by leaf in read order. Empty (the default) means that the proposals show
    *   no demos
    * @return
    *   for each leaf, in read order, the proposals, or the first [[DspyError]]. A blank proposal is an empty text
    */
  def proposeInstructions(
      program       : P,
      trainset      : Vector[Example],
      demoCandidates: Vector[Vector[Vector[Example]]] = Vector.empty
  )(using RuntimeContext): Either[DspyError, Vector[Vector[String]]] =
    val summary = datasetSummary(trainset)
    Traverse.either(ps.inspect(program).zipWithIndex) { (leaf, idx) =>
      proposeForLeaf(leaf, idx, summary, demoCandidates.map(_.lift(idx).getOrElse(Vector.empty)))
    }

  // ── Dataset summary (upstream `create_dataset_summary`) ─────────────────────────────────────────────────────────────

  /** The dataset summary from a sample of `trainset`. `None` when `useDatasetSummary` is false or the trainset is empty
    * (no LM call), when the model gives a blank text, and when the call fails. A failed summary gives a warning, and
    * the proposals continue with no grounding, as upstream `GroundedProposer.__init__`.
    */
  private def datasetSummary(trainset: Vector[Example])(using RuntimeContext): Option[String] =
    if !config.useDatasetSummary || trainset.isEmpty then None
    else
      val sample  = trainset.take(config.datasetSampleSize)
      val inputs  = DynamicValues.record("examples" := sample.map(renderExample).mkString("\n\n"))
      val predict = DynamicPredict(layout = GroundedProposer.DatasetDescriptor, name = Some("grounded_summary"))
      val seed    = OptimizerSupport.seedBase(config.seed)
      OptimizerSupport.generateText(predict, inputs, "observations", config.initTemperature, seed) match
        case Right(text) => text.map(OptimizerSupport.stripPrefix).filter(_.nonEmpty)
        case Left(error) =>
          RuntimeEnvironment.warn(
            "GroundedProposer",
            s"Error getting data summary: ${error.message}. Running without data aware proposer."
          )
          None

  // ── The proposals of one leaf (upstream `GenerateModuleInstruction`) ────────────────────────────────────────────────

  /** The proposals of one leaf: `numInstructions` proposals with no demo sets, else `min(numInstructions, sets)`.
    * `demoSets` has the demos of this leaf in each set. `i` is less than `numInstructions`, so the rollout ids
    * `seedBase + 1 + idx * numInstructions + i` do not overlap, for any size, and the dataset summary has `seedBase`.
    * As upstream `forward`, the first proposal shows no demos (`demo_set_i == 0`), and also a proposal with no
    * bootstrapped demo. As upstream, a proposal goes through `strip_prefix` two times: in `forward` and in
    * `propose_instruction_for_predictor`.
    */
  private def proposeForLeaf(
      leaf    : OptimizableView,
      idx     : Int,
      summary : Option[String],
      demoSets: Vector[Vector[Example]]
  )(using RuntimeContext): Either[DspyError, Vector[String]] =
    val count: Int =
      if demoSets.isEmpty then config.numInstructions else math.min(config.numInstructions, demoSets.size)
    val base    = OptimizerSupport.seedBase(config.seed) + 1 + idx * config.numInstructions
    val predict = DynamicPredict(
      layout = GroundedProposer.instructionLayout(useSummary = summary.isDefined, useTip = config.useTips),
      name = Some("grounded_instruct")
    )

    Traverse.either((0 until count).toVector) { i =>
      // Upstream `adjacent_sets` and `gather_examples_from_sets`.
      val adjacent  = demoSets.drop(i) ++ demoSets.take(i)
      val demos     = adjacent.flatten.filter(_.augmented).take(GroundedProposer.NumDemosInContext).map(renderExample)
      val taskDemos =
        if i == 0 || demos.isEmpty then GroundedProposer.NoTaskDemos else demos.mkString("", "\n\n", "\n\n")
      val tip    = Option.when(config.useTips)(GroundedProposer.tips(i % GroundedProposer.tips.size))
      val inputs = DynamicValues.recordFromEntries(
        summary.map("dataset_description" := _).toVector ++
          Vector(
            "module"            := describeModule(leaf),
            "task_demos"        := taskDemos,
            "basic_instruction" := leaf.effectiveInstructions
          ) ++
          tip.map("tip" := _)
      )
      OptimizerSupport
        .generateText(predict, inputs, GroundedProposer.Output, config.initTemperature, base + i)
        .map(_.fold("")(text => OptimizerSupport.stripPrefix(OptimizerSupport.stripPrefix(text))))
    }

  /** The `name(inputs) -> outputs` text of a leaf (upstream `module_code`). */
  private def describeModule(leaf: OptimizableView): String =
    val layout  = leaf.layout
    val inputs  = layout.inputFields.map(_.name).mkString(", ")
    val outputs = layout.outputFields.map(_.name).mkString(", ")
    val name    = leaf.moduleName
    s"$name($inputs) -> $outputs"

  /** The `field: value` text of an [[Example]]. A value has its Python `str` text. */
  private def renderExample(example: Example): String =
    DynamicValues.recordEntries(example.values)
      .map { case (k, v) => s"$k: ${PythonText.str(v)}" }
      .mkString("\n")

object GroundedProposer:

  /** The texts of upstream `TIPS`, with no `"none"` entry (`useTips` gives "no tip"). The tip of a proposal follows the
    * index of the candidate.
    */
  val tips: Vector[String] = Vector(
    "Don't be afraid to be creative when creating the new instruction!",
    "Keep the instruction clear and concise.",
    "Make sure your instruction is very informative and descriptive.",
    "The instruction should include a high stakes scenario in which the LM must solve the task!",
    "Include a persona that is relevant to the task in the instruction (ie. \"You are a ...\")"
  )

  private val Output: String = "proposed_instruction"

  /** Upstream `num_demos_in_context`. */
  private val NumDemosInContext: Int = 3

  /** The value of `task_demos` when a proposal has no demos (upstream `GenerateModuleInstruction.forward`). */
  private val NoTaskDemos: String = "No task demos provided."

  private def field(name: String, description: String): FieldSpec =
    FieldSpec(name = name, description = Some(description))

  /** Upstream `DatasetDescriptor`. */
  private val DatasetDescriptor: SignatureLayout = SignatureLayout.of(
    name = "DatasetDescriptor",
    inputFields = Vector(field("examples", "Sample data points from the dataset")),
    outputFields = Vector(field("observations", "Somethings that holds true for most or all of the data you observed")),
    instructions = Some(
      "Given several examples from a dataset please write observations about trends that hold for most or all of the " +
        "samples. Some areas you may consider in your observations: topics, content, syntax, conciseness, etc. It will " +
        "be useful to make an educated guess as to the nature of the task this dataset will enable. Don't be afraid to " +
        "be creative"
    )
  )

  /** Upstream `generate_instruction_class`, with its inputs in its order. `task_demos` has no condition, as upstream.
    */
  private def instructionLayout(useSummary: Boolean, useTip: Boolean): SignatureLayout =
    SignatureLayout.of(
      name = "GenerateSingleModuleInstruction",
      inputFields = Option.when(useSummary)(field(
        "dataset_description",
        "A description of the dataset that we are using."
      )).toVector ++
        Vector(
          field("module", "The module to create an instruction for."),
          field("task_demos", "Example inputs/outputs of our module."),
          field("basic_instruction", "Basic instruction.")
        ) ++
        Option.when(useTip)(field("tip", "A suggestion for how to go about generating the new instruction.")),
      outputFields = Vector(
        field(Output, "Propose an instruction that will be used to prompt a Language Model to perform this task.")
      ),
      instructions = Some(
        "Use the information below to learn about a task that we are trying to solve using calls to an LM, then " +
          "generate a new instruction that will be used to prompt a Language Model to better solve the task."
      )
    )
