package dspy4s.gepa

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.gepa.contracts.FeedbackMetric
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.optimization.OptimizableStructure
import dspy4s.programs.ProgramRunner

/** User-facing GEPA optimizer — the dspy4s analogue of dspy's `GEPA` teleprompter. It wires the [[GepaAdapter]] +
  * [[GepaEngine]] and runs reflective prompt evolution over a student program.
  *
  * Unlike COPRO/MIPROv2 (which take a plain `Metric`), GEPA needs a [[dspy4s.gepa.contracts.FeedbackMetric]] (score
  * PLUS reflection feedback) and a separate `reflectionLm`. See PORT_GAPS G-12.
  *
  * @param metric
  *   the feedback metric — scores examples and yields the reflection feedback
  * @param reflectionLm
  *   the (usually stronger) model that rewrites instructions from reflective feedback
  * @param config
  *   budget + search settings ([[GepaConfig]])
  * @param instructionProposer
  *   writes the new instruction text of each iteration. The default is the built-in proposer
  *   ([[DefaultInstructionProposer]]); give one with options, or an implementation of your own.
  */
final class Gepa[P](
    metric             : FeedbackMetric,
    reflectionLm       : LanguageModel,
    config             : GepaConfig,
    instructionProposer: InstructionProposer = InstructionProposer.default
)(using OptimizableStructure[P], ProgramRunner[P]):

  val name: String = "gepa"

  /** Optimize `student`'s predictor instructions over `trainset` (reflective minibatches) and `valset` (Pareto frontier
    * scoring), returning the best candidate and the program it yields.
    *
    * `Left` when GEPA cannot find the trace entries of each predictor (UPGRADE-3.2.1-to-3.4.0, decision D2):
    *   - Two predictors have the same module name and the same signature structure. The check runs before any LM call.
    *     Give each predictor a name.
    *   - A predictor type records no predictor key in its trace entries.
    *
    * `Left` also when `instructionProposer` gives a text for a component that the program does not hold.
    *
    * `Left` also when [[GepaConfig.frontierType]] is not `Instance` and the metric gives the seed candidate no
    * objective score. The run stops after the seed evaluation.
    */
  def compile(student: P, trainset: Vector[Example], valset: Vector[Example])(using
      RuntimeContext
  ): Either[DspyError, GepaResult[P]] =
    val adapter = new GepaAdapter(student, metric, config.failureScore, config.addFormatFailureAsFeedback, config.seed)
    val engine  = new GepaEngine(adapter, reflectionLm, config, instructionProposer)
    engine.optimize(Candidate.seed(student), trainset, valset)
