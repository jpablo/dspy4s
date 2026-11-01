package dspy4s.optimize.propose

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.optimize.OptimizerSupport
import dspy4s.optimize.CandidateCount
import dspy4s.optimize.DatasetSampleSize
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.optimization.OptimizableView

/** Knobs for [[GroundedProposer]], mirroring the relevant slice of upstream's
  * `dspy.propose.grounded_proposer.GroundedProposer.__init__`.
  *
  * @param numInstructions
  *   number of candidate instructions proposed per predictor (upstream's `N`)
  * @param useDatasetSummary
  *   when true, bootstrap a dataset-summary observations string once per [[GroundedProposer.proposeInstructions]] call
  *   and ground every proposal in it (upstream's `use_dataset_summary`)
  * @param useTips
  *   when true, rotate a "tip" hint into each proposal (upstream's `set_tip_randomly` / `use_tip`); the tip varies per
  *   candidate index so distinct candidates get distinct tips
  * @param datasetSampleSize
  *   number of trainset examples sampled into the dataset-summary prompt (upstream's `view_data_batch_size`, but a
  *   single batch — see "Deltas from Python")
  * @param initTemperature
  *   sampling temperature forwarded to the LM option bag for instruction generation
  * @param seed
  *   RNG seed; deterministic for a fixed seed
  * @param summaryMarker
  *   stable sentinel woven into the dataset-summary signature's instructions so a summary prompt is distinguishable
  *   from an instruction-generation prompt (lets offline scripted LMs branch)
  * @param instructionMarker
  *   stable sentinel woven into the instruction-generation signature's instructions so a generation prompt is
  *   distinguishable from a task prompt (lets offline scripted LMs branch)
  */
final case class GroundedProposerConfig(
    numInstructions  : CandidateCount    = CandidateCount(5),
    useDatasetSummary: Boolean           = true,
    useTips          : Boolean           = true,
    datasetSampleSize: DatasetSampleSize = DatasetSampleSize(10),
    initTemperature  : Double            = 1.0,
    seed             : Long              = 0L,
    summaryMarker    : String            =
      "You are a dataset analyst. Summarize the observations that hold across the dataset samples.",
    instructionMarker: String =
      "You are an instruction proposer for large language models. Propose a new instruction grounded in the context."
)

/** GroundedProposer — Phase A of a MIPROv2 port. A reusable component that proposes candidate INSTRUCTIONS per
  * predictor, grounded in the task data and (optionally) bootstrapped demos. A v1 port of the proposer slice of DSPy's
  * `dspy.propose.grounded_proposer` (`grounded_proposer.py`, `dataset_summary_generator.py`).
  *
  * '''What it does.''' For each learnable predictor exposed by [[OptimizableStructure.read]] (in read order), it emits
  * `config.numInstructions` candidate instruction strings via an instruction-generation [[DynamicPredict]] (the
  * `GenerateModuleInstruction` analogue). Each proposal is grounded in:
  *   1. a '''dataset summary''' — a short observations string bootstrapped once per call from a sample of the trainset
  *      via a `examples -> observations` [[DynamicPredict]] (the `create_dataset_summary` analogue), gated on
  *      `useDatasetSummary`; 2. a '''program/module description''' — the predictor's name, current instruction, and
  *      signature field names (dspy4s has no Python source to introspect — see "Deltas from Python"); 3. an optional
  *      '''bootstrapped demo set''' — when `demoCandidates` are supplied, the predictor's demo set is rendered into the
  *      prompt (the `task_demos` analogue); 4. a rotating '''tip''' from a small fixed tip list, gated on `useTips`; 5.
  *      the predictor's '''basic instruction''' (its current instruction).
  *
  * Distinct candidates are sampled by varying the call's `rolloutId` (and seeding temperature into the option bag),
  * mirroring COPRO and upstream's per-proposal `rollout_id` copy of the prompt LM.
  *
  * This phase does NOT implement MIPROv2 itself — only the proposer MIPROv2 will consume.
  *
  * '''Deltas from Python.'''
  *   - '''No program-source `DescribeProgram` / `DescribeModule`.''' Upstream reads the Python source of the program
  *     (`get_dspy_source_code`) and runs two extra LM calls to describe the program and the module. dspy4s programs
  *     have no introspectable source, so the proposal is grounded on the predictor's signature metadata instead: its
  *     name, current instruction, and input/output field names rendered as a `Name(inputs) -> outputs` module string.
  *     The two describe-* LM calls are dropped.
  *   - '''The dataset summary is a single batch, not the iterative refine loop.''' Upstream samples the trainset in
  *     `view_data_batch_size` batches and iteratively refines the observations
  *     (`DatasetDescriptorWithPriorObservations`) before a final `ObservationSummarizer` pass. dspy4s summarizes a
  *     single sample of `datasetSampleSize` examples in one `examples -> observations` call. The summary is cached once
  *     per `proposeInstructions` call.
  *   - '''The tip list is a fixed subset, rotated deterministically by candidate index''' rather than randomly sampled
  *     per proposal (`set_tip_randomly`). Determinism for a fixed seed is preserved.
  *   - '''Candidate sampling uses one `rolloutId`-varied call per candidate''' rather than a batched `n=N` completion
  *     (dspy4s `Predict` returns one completion per call); the effect (distinct proposals) is the same.
  *   - '''Instruction history (`previous_instructions` / `trial_logs`) is omitted.''' That grounding comes from an
  *     optimizer's accumulated attempts; the standalone proposer has none, and MIPROv2 (a later phase) owns that loop.
  *     Only round-0-style proposal is implemented here.
  */
final class GroundedProposer[P](config: GroundedProposerConfig)(using ps: OptimizableStructure[P]):

  /** Propose `config.numInstructions` candidate instruction strings for EACH optimizable leaf of `program` (in
    * [[OptimizableStructure.read]] order), grounded in `trainset` and (optionally) `demoCandidates`.
    *
    * @param program
    *   the program whose optimizable leaves get fresh instruction proposals
    * @param trainset
    *   task data; a sample grounds the dataset-summary step
    * @param demoCandidates
    *   per-leaf bootstrapped demo sets (outer index aligns with [[OptimizableStructure.read]] order); empty (the
    *   default) means no demos are rendered into proposals
    * @return
    *   for each leaf (in read order) a vector of `numInstructions` candidate instruction strings, or the first
    *   [[DspyError]] encountered
    */
  def proposeInstructions(
      program       : P,
      trainset      : Vector[Example],
      demoCandidates: Vector[Vector[Example]] = Vector.empty
  )(using RuntimeContext): Either[DspyError, Vector[Vector[String]]] =
    val leaves = ps.inspect(program)
    for
      summary <- datasetSummary(trainset)
      perLeaf <- Traverse.either(leaves.zipWithIndex.toVector) { case (leaf, idx) =>
                   val demoSet = demoCandidates.lift(idx).getOrElse(Vector.empty)
                   proposeForLeaf(leaf, idx, summary, demoSet)
                 }
    yield perLeaf

  // ── Dataset summary (the create_dataset_summary analogue) ──────────────────

  /** Bootstrap a dataset-summary observations string from a sample of `trainset` (cached: computed once per
    * `proposeInstructions` call by the caller). The signature is `examples -> observations`. `summaryMarker` is set as
    * the signature instructions so summary prompts are distinguishable from instruction-generation / task prompts.
    * Returns `None` when `useDatasetSummary` is false (and makes no LM call), or when the trainset is empty.
    */
  private def datasetSummary(trainset: Vector[Example])(using RuntimeContext): Either[DspyError, Option[String]] =
    if !config.useDatasetSummary || trainset.isEmpty then Right(None)
    else
      val sample   = trainset.take(config.datasetSampleSize)
      val rendered = sample.iterator.map(renderExample).mkString("\n\n")
      val inputs   = DynamicValues.record("examples" := rendered)
      val output   = "observations"
      val gen      = DynamicPredict(
        layout = OptimizerSupport.generationLayout("SummarizeDataset", inputs, output, config.summaryMarker),
        name = Some("grounded_summary")
      )
      // The dataset summary is best-effort grounding: a failed summary LM call degrades to "no grounding"
      // (Right(None)) rather than aborting the whole proposeInstructions / MIPROv2 compile.
      OptimizerSupport.generateText(gen, inputs, output, config.initTemperature, OptimizerSupport.seedBase(config.seed))
        .orElse(Right(None))

  // ── Per-leaf instruction generation (GenerateModuleInstruction analogue) ──

  /** Generate `config.numInstructions` candidate instructions for one leaf, grounded in the (cached) dataset summary,
    * the leaf's signature-derived description, the optional demo set, and a rotating tip. The inputs of the generation
    * signature are gated by config + availability: `dataset_description?`, `program_description`, `basic_instruction`,
    * `task_demos?`, `tip?` -> `proposed_instruction`. `instructionMarker` is set as the signature instructions so
    * generation prompts are distinguishable from task prompts.
    */
  private def proposeForLeaf(
      leaf   : OptimizableView,
      idx    : Int,
      summary: Option[String],
      demoSet: Vector[Example]
  )(using RuntimeContext): Either[DspyError, Vector[String]] =
    val basicInstruction   = leaf.layout.instructions.getOrElse("")
    val programDescription = describeModule(leaf)
    val demosText          = Option.when(demoSet.nonEmpty)(demoSet.iterator.map(renderExample).mkString("\n\n"))
    val output             = "proposed_instruction"

    // Deterministic, contiguous rolloutId stream per leaf so candidate sampling is reproducible AND each leaf draws a
    // distinct window (so distinct leaves need not collide). A scripted/temperature-driven
    // LM yields a distinct proposal per rolloutId.
    val base = OptimizerSupport.seedBase(config.seed) + (idx + 1) * 64

    Traverse.either((0 until config.numInstructions).toVector) { i =>
      val tip    = Option.when(config.useTips)(GroundedProposer.tips(i % GroundedProposer.tips.size))
      val inputs = DynamicValues.recordFromEntries(
        summary.map("dataset_description" := _).toVector ++
          Vector(
            "program_description" := programDescription,
            "basic_instruction"   := basicInstruction
          ) ++
          demosText.map("task_demos" := _) ++
          tip.map("tip" := _)
      )
      val gen = DynamicPredict(
        layout =
          OptimizerSupport.generationLayout("GenerateModuleInstruction", inputs, output, config.instructionMarker),
        name = Some("grounded_instruct")
      )
      OptimizerSupport.generateText(gen, inputs, output, config.initTemperature, base + i).map(_.getOrElse(""))
    }

  /** The signature-derived module description (the `DescribeModule` analogue): the leaf's module name plus a
    * `Name(inputs) -> outputs` field-name rendering. dspy4s has no program source to introspect.
    */
  private def describeModule(leaf: OptimizableView): String =
    val layout  = leaf.layout
    val inputs  = layout.inputFields.map(_.name).mkString(", ")
    val outputs = layout.outputFields.map(_.name).mkString(", ")
    val name    = leaf.moduleName
    s"$name($inputs) -> $outputs"

  /** Render an [[Example]] as a `field: value` block for grounding prompts. */
  private def renderExample(example: Example): String =
    DynamicValues.recordEntries(example.values)
      .map { case (k, v) => s"$k: ${DynamicValues.renderText(v)}" }
      .mkString("\n")

object GroundedProposer:

  /** A small fixed subset of upstream's `TIPS` dict (the `"none"` entry is dropped — gating handles "no tip"). Rotated
    * deterministically by candidate index.
    */
  val tips: Vector[String] = Vector(
    "Don't be afraid to be creative when creating the new instruction!",
    "Keep the instruction clear and concise.",
    "Make sure your instruction is very informative and descriptive.",
    "The instruction should include a high stakes scenario in which the LM must solve the task!",
    "Include a persona that is relevant to the task in the instruction (ie. \"You are a ...\")"
  )
