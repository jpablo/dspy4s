package dspy4s.optimize.propose

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.optimize.OptimizerSupport
import dspy4s.programs.Predictors
import dspy4s.programs.DynamicPredict
import dspy4s.programs.contracts.ProgramCall

/** Knobs for [[GroundedProposer]], mirroring the relevant slice of upstream's
  * `dspy.propose.grounded_proposer.GroundedProposer.__init__`.
  *
  * @param numInstructions    number of candidate instructions proposed per predictor (upstream's `N`)
  * @param useDatasetSummary  when true, bootstrap a dataset-summary observations string once per
  *                           [[GroundedProposer.proposeInstructions]] call and ground every proposal in it
  *                           (upstream's `use_dataset_summary`)
  * @param useTips            when true, rotate a "tip" hint into each proposal (upstream's `set_tip_randomly` /
  *                           `use_tip`); the tip varies per candidate index so distinct candidates get distinct tips
  * @param datasetSampleSize  number of trainset examples sampled into the dataset-summary prompt (upstream's
  *                           `view_data_batch_size`, but a single batch — see "Deltas from Python")
  * @param initTemperature    sampling temperature forwarded to the LM option bag for instruction generation
  * @param seed               RNG seed; deterministic for a fixed seed
  * @param summaryMarker      stable sentinel woven into the dataset-summary signature's instructions so a summary
  *                           prompt is distinguishable from an instruction-generation prompt (lets offline scripted
  *                           LMs branch)
  * @param instructionMarker  stable sentinel woven into the instruction-generation signature's instructions so a
  *                           generation prompt is distinguishable from a task prompt (lets offline scripted LMs branch)
  */
final case class GroundedProposerConfig(
    numInstructions: Int = 5,
    useDatasetSummary: Boolean = true,
    useTips: Boolean = true,
    datasetSampleSize: Int = 10,
    initTemperature: Double = 1.0,
    seed: Long = 0L,
    summaryMarker: String =
      "You are a dataset analyst. Summarize the observations that hold across the dataset samples.",
    instructionMarker: String =
      "You are an instruction proposer for large language models. Propose a new instruction grounded in the context."
):
  require(numInstructions > 0, "GroundedProposer numInstructions must be greater than 0")

/** GroundedProposer — Phase A of a MIPROv2 port. A reusable component that proposes candidate INSTRUCTIONS per
  * predictor, grounded in the task data and (optionally) bootstrapped demos. A v1 port of the proposer slice of
  * DSPy's `dspy.propose.grounded_proposer` (`grounded_proposer.py`, `dataset_summary_generator.py`).
  *
  * '''What it does.''' For each learnable predictor exposed by [[Predictors.read]] (in read order), it emits
  * `config.numInstructions` candidate instruction strings via an instruction-generation [[DynamicPredict]]
  * (the `GenerateModuleInstruction` analogue). Each proposal is grounded in:
  *   1. a '''dataset summary''' — a short observations string bootstrapped once per call from a sample of the
  *      trainset via a `examples -> observations` [[DynamicPredict]] (the `create_dataset_summary` analogue),
  *      gated on `useDatasetSummary`;
  *   2. a '''program/module description''' — the predictor's name, current instruction, and signature field names
  *      (dspy4s has no Python source to introspect — see "Deltas from Python");
  *   3. an optional '''bootstrapped demo set''' — when `demoCandidates` are supplied, the predictor's demo set is
  *      rendered into the prompt (the `task_demos` analogue);
  *   4. a rotating '''tip''' from a small fixed tip list, gated on `useTips`;
  *   5. the predictor's '''basic instruction''' (its current instruction).
  *
  * Distinct candidates are sampled by varying the call's `rolloutId` (and seeding temperature into the option bag),
  * mirroring COPRO and upstream's per-proposal `rollout_id` copy of the prompt LM.
  *
  * This phase does NOT implement MIPROv2 itself — only the proposer MIPROv2 will consume.
  *
  * '''Deltas from Python.'''
  *   - '''No program-source `DescribeProgram` / `DescribeModule`.''' Upstream reads the Python source of the
  *     program (`get_dspy_source_code`) and runs two extra LM calls to describe the program and the module. dspy4s
  *     programs have no introspectable source, so the proposal is grounded on the predictor's signature metadata
  *     instead: its name, current instruction, and input/output field names rendered as a `Name(inputs) -> outputs`
  *     module string. The two describe-* LM calls are dropped.
  *   - '''The dataset summary is a single batch, not the iterative refine loop.''' Upstream samples the trainset in
  *     `view_data_batch_size` batches and iteratively refines the observations (`DatasetDescriptorWithPriorObservations`)
  *     before a final `ObservationSummarizer` pass. dspy4s summarizes a single sample of `datasetSampleSize` examples
  *     in one `examples -> observations` call. The summary is cached once per `proposeInstructions` call.
  *   - '''The tip list is a fixed subset, rotated deterministically by candidate index''' rather than randomly
  *     sampled per proposal (`set_tip_randomly`). Determinism for a fixed seed is preserved.
  *   - '''Candidate sampling uses one `rolloutId`-varied call per candidate''' rather than a batched `n=N`
  *     completion (dspy4s `Predict` returns one completion per call); the effect (distinct proposals) is the same.
  *   - '''Instruction history (`previous_instructions` / `trial_logs`) is omitted.''' That grounding comes from an
  *     optimizer's accumulated attempts; the standalone proposer has none, and MIPROv2 (a later phase) owns that
  *     loop. Only round-0-style proposal is implemented here.
  */
final class GroundedProposer[P](config: GroundedProposerConfig)(using ps: Predictors[P]):

  /** Propose `config.numInstructions` candidate instruction strings for EACH predictor of `program` (in
    * [[Predictors.read]] order), grounded in `trainset` and (optionally) `demoCandidates`.
    *
    * @param program        the program whose predictors get fresh instruction proposals
    * @param trainset       task data; a sample grounds the dataset-summary step
    * @param demoCandidates per-predictor bootstrapped demo sets (outer index aligns with [[Predictors.read]] order);
    *                       empty (the default) means no demos are rendered into proposals
    * @return for each predictor (in read order) a vector of `numInstructions` candidate instruction strings, or the
    *         first [[DspyError]] encountered
    */
  def proposeInstructions(
      program: P,
      trainset: Vector[Example],
      demoCandidates: Vector[Vector[Example]] = Vector.empty
  )(using RuntimeContext): Either[DspyError, Vector[Vector[String]]] =
    val predictors = ps.read(program)
    for
      summary  <- datasetSummary(trainset)
      perPred  <- traverse(predictors.zipWithIndex.toVector) { case (predictor, idx) =>
                    val demoSet = demoCandidates.lift(idx).getOrElse(Vector.empty)
                    proposeForPredictor(predictor, idx, summary, demoSet)
                  }
    yield perPred

  // ── Dataset summary (the create_dataset_summary analogue) ──────────────────

  /** The dataset-summary signature: `examples -> observations`. `summaryMarker` is set as the signature
    * instructions so summary prompts are distinguishable from instruction-generation / task prompts. */
  private def summaryLayout: SignatureLayout =
    SignatureLayout.of(
      name = "SummarizeDataset",
      fields = Vector(
        FieldSpec(name = "examples", role = FieldRole.Input),
        FieldSpec(name = "observations", role = FieldRole.Output)
      ),
      instructions = Some(config.summaryMarker)
    )

  /** Bootstrap a dataset-summary observations string from a sample of `trainset` (cached: computed once per
    * `proposeInstructions` call by the caller). Returns `None` when `useDatasetSummary` is false (and makes no LM
    * call), or when the trainset is empty. */
  private def datasetSummary(trainset: Vector[Example])(using RuntimeContext): Either[DspyError, Option[String]] =
    if !config.useDatasetSummary || trainset.isEmpty then Right(None)
    else
      val sample   = trainset.take(math.max(1, config.datasetSampleSize))
      val rendered = sample.iterator.map(renderExample).mkString("\n\n")
      val gen      = DynamicPredict(layout = summaryLayout, name = Some("grounded_summary"))
      val call = ProgramCall(
        inputs    = DynamicValues.record("examples" := rendered),
        config    = DynamicValues.record("temperature" := config.initTemperature),
        rolloutId = Some(OptimizerSupport.seedBase(config.seed))
      )
      // The dataset summary is best-effort grounding: a failed summary LM call degrades to "no grounding"
      // (Right(None)) rather than aborting the whole proposeInstructions / MIPROv2 compile.
      gen.apply(call) match
        case Right(pred) =>
          Right(
            DynamicValues.recordGet(pred.values, "observations")
              .map(DynamicValues.renderText)
              .map(_.trim)
              .filter(_.nonEmpty)
          )
        case Left(_) => Right(None)

  // ── Per-predictor instruction generation (GenerateModuleInstruction analogue) ──

  /** The instruction-generation signature. Inputs are gated by config + availability:
    * `dataset_description?`, `program_description`, `basic_instruction`, `task_demos?`, `tip?` ->
    * `proposed_instruction`. `instructionMarker` is set as the signature instructions so generation prompts are
    * distinguishable from task prompts. */
  private def instructionGenLayout(withSummary: Boolean, withDemos: Boolean, withTip: Boolean): SignatureLayout =
    val inputs =
      (if withSummary then Vector(FieldSpec(name = "dataset_description", role = FieldRole.Input)) else Vector.empty) ++
        Vector(
          FieldSpec(name = "program_description", role = FieldRole.Input),
          FieldSpec(name = "basic_instruction", role = FieldRole.Input)
        ) ++
        (if withDemos then Vector(FieldSpec(name = "task_demos", role = FieldRole.Input)) else Vector.empty) ++
        (if withTip then Vector(FieldSpec(name = "tip", role = FieldRole.Input)) else Vector.empty)
    SignatureLayout.of(
      name = "GenerateModuleInstruction",
      fields = inputs :+ FieldSpec(name = "proposed_instruction", role = FieldRole.Output),
      instructions = Some(config.instructionMarker)
    )

  /** Generate `config.numInstructions` candidate instructions for one predictor, grounded in the (cached) dataset
    * summary, the predictor's signature-derived description, the optional demo set, and a rotating tip. */
  private def proposeForPredictor(
      predictor: DynamicPredict,
      idx: Int,
      summary: Option[String],
      demoSet: Vector[Example]
  )(using RuntimeContext): Either[DspyError, Vector[String]] =
    val basicInstruction   = predictor.layout.instructions.getOrElse("")
    val programDescription = describeModule(predictor)
    val withSummary        = summary.isDefined
    val withDemos          = demoSet.nonEmpty
    val demosText          = if withDemos then demoSet.iterator.map(renderExample).mkString("\n\n") else ""

    // Deterministic, contiguous rolloutId stream per predictor so candidate sampling is reproducible AND each
    // predictor draws a distinct window (so distinct predictors need not collide). A scripted/temperature-driven
    // LM yields a distinct proposal per rolloutId.
    val base = OptimizerSupport.seedBase(config.seed) + (idx + 1) * 64

    traverse((0 until config.numInstructions).toVector) { i =>
      val withTip   = config.useTips
      val tip       = if withTip then Some(GroundedProposer.tips(i % GroundedProposer.tips.size)) else None
      val layout    = instructionGenLayout(withSummary, withDemos, withTip)
      val gen       = DynamicPredict(layout = layout, name = Some("grounded_instruct"))
      val rolloutId = base + i

      val entries: Vector[(String, zio.blocks.schema.DynamicValue)] =
        (summary.map(s => "dataset_description" := s).toVector) ++
          Vector(
            "program_description" := programDescription,
            "basic_instruction"   := basicInstruction
          ) ++
          (if withDemos then Vector("task_demos" := demosText) else Vector.empty) ++
          (tip.map(t => "tip" := t).toVector)

      val call = ProgramCall(
        inputs    = DynamicValues.recordFromEntries(entries),
        config    = DynamicValues.record("temperature" := config.initTemperature),
        rolloutId = Some(rolloutId)
      )
      gen.apply(call).map { pred =>
        DynamicValues.recordGet(pred.values, "proposed_instruction")
          .map(DynamicValues.renderText)
          .map(_.trim)
          .getOrElse("")
      }
    }

  /** The signature-derived module description (the `DescribeModule` analogue): the predictor's name plus a
    * `Name(inputs) -> outputs` field-name rendering. dspy4s has no program source to introspect. */
  private def describeModule(predictor: DynamicPredict): String =
    val layout  = predictor.layout
    val inputs  = layout.inputFields.map(_.name).mkString(", ")
    val outputs = layout.outputFields.map(_.name).mkString(", ")
    val name    = predictor.name.getOrElse(layout.name)
    s"$name($inputs) -> $outputs"

  /** Render an [[Example]] as a `field: value` block for grounding prompts. */
  private def renderExample(example: Example): String =
    DynamicValues.recordEntries(example.values)
      .map { case (k, v) => s"$k: ${DynamicValues.renderText(v)}" }
      .mkString("\n")

  /** Either-aware traversal: applies `f` left-to-right, short-circuiting on the first [[DspyError]]. */
  private def traverse[A, B](xs: Vector[A])(f: A => Either[DspyError, B]): Either[DspyError, Vector[B]] =
    xs.foldLeft[Either[DspyError, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
      for
        bs <- acc
        b  <- f(a)
      yield bs :+ b
    }

object GroundedProposer:

  /** A small fixed subset of upstream's `TIPS` dict (the `"none"` entry is dropped — gating handles "no tip").
    * Rotated deterministically by candidate index. */
  val tips: Vector[String] = Vector(
    "Don't be afraid to be creative when creating the new instruction!",
    "Keep the instruction clear and concise.",
    "Make sure your instruction is very informative and descriptive.",
    "The instruction should include a high stakes scenario in which the LM must solve the task!",
    "Include a persona that is relevant to the task in the instruction (ie. \"You are a ...\")"
  )
