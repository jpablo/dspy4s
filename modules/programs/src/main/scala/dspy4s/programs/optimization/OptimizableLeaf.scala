package dspy4s.programs.optimization

import dspy4s.algebra.{IsEq, Law, Lens, <->}
import dspy4s.core.contracts.PredictorKey
import dspy4s.programs.strategies.{ChainOfThought, DynamicPredict, Predict}
import dspy4s.signatures.OutputAugmentation.PrependField

/** A program that is one independently optimizable leaf of the introspection tree.
  *
  * This is a lawful [[dspy4s.algebra.Lens Lens]] onto exactly the program's [[OptimizableParameters]]: six get, put,
  * and modify statements are inherited from the `Lens` trait, and the [[frame]] law added here pins what makes the
  * focus exact: writing parameters can never change the read-only metadata (a [[PredictorKey]]), which excludes
  * signature structure and execution resources from optimizer replacement. `OptimizableParametersSuite` executes all
  * seven statements per instance.
  *
  * '''Trace key.''' The metadata of a leaf is the key by which an optimizer finds the calls of the leaf. The module of
  * a leaf type must record that key in its trace entries: give it the lifecycle
  * `ModuleLifecycle.predictor(shape, key)`, or `ModuleLifecycle.dynamicPredictor(key)` for a dynamic module. `Predict`,
  * `ChainOfThought`, and `DynamicPredict` do this. An optimizer returns an error for a leaf type that records no key,
  * and for a program in which two leaves have the same key (see [[PredictorTraces]]).
  */
trait OptimizableLeaf[P] extends Lens[P, OptimizableParameters]:
  def metadata(program: P): PredictorKey

  /** The text of a leaf whose default text differs from the default text of its structure. `None` means that the
    * default text of the structure is correct.
    */
  def defaultInstructions(program: P): Option[String] = None

  final def inspect(program: P): OptimizableView =
    OptimizableView(metadata(program), get(program), defaultInstructions(program))

  @Law("frame: writing parameters never changes the read-only metadata")
  def frame(program: P, updated: OptimizableParameters): IsEq[PredictorKey] =
    metadata(set(program, updated)) <-> metadata(program)

object OptimizableLeaf:
  /** A [[DynamicPredict]] is itself an optimizable leaf. Defined in the [[OptimizableLeaf]] companion so it is in
    * implicit scope wherever an `OptimizableLeaf[DynamicPredict]` (or its `NotGiven`) is sought.
    */
  given OptimizableLeaf[DynamicPredict] with
    def get(program: DynamicPredict): OptimizableParameters =
      OptimizableParameters(program.layout.instructions, program.demos, program.config, program.decisions)

    def metadata(program: DynamicPredict): PredictorKey =
      PredictorKey.from(program.moduleName, program.layout)

    def set(program: DynamicPredict, updated: OptimizableParameters): DynamicPredict =
      if updated == get(program) then program
      else
        program.copy(
          layout = program.layout.withInstructions(updated.instructions),
          demos = updated.demos,
          config = updated.config,
          decisions = updated.decisions
        )

  /** [[OptimizableLeaf]] for the single-leaf program [[Predict]]. A `Predict` field inside a user composite resolves
    * here (via [[OptimizableStructure.fromOptimizableLeaf]], 1 element) rather than being structurally torn apart by
    * [[OptimizableStructure.derived]], and a standalone `Predict` is introspectable/tunable. Lives in the
    * [[OptimizableLeaf]] companion so it is in implicit scope without an explicit import.
    *
    * Optimizable parameters are exactly instructions, demos, module config, and the decision settings. The signature
    * field structure, output shape, name, runtime, bound LM, and tools remain on the original program and are exposed
    * only as read-only metadata.
    */
  given predictOptimizableLeaf[I, O]: OptimizableLeaf[Predict[I, O]] with
    def get(program: Predict[I, O]): OptimizableParameters =
      OptimizableParameters(program.signature.layout.instructions, program.demos, program.config, program.decisions)

    def metadata(program: Predict[I, O]): PredictorKey =
      PredictorKey.from(program.moduleName, program.signature.layout)

    def set(program: Predict[I, O], updated: OptimizableParameters): Predict[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          decisions = updated.decisions,
          signature = program.signature.withInstructions(updated.instructions)
        )

  /** [[OptimizableLeaf]] for the single-leaf program [[ChainOfThought]]. Like [[predictOptimizableLeaf]], but the
    * exposed layout is the **augmented** layout CoT actually runs (a leading `reasoning` output field prepended).
    *
    * Optimizable parameters remain instructions, demos, config, and the decision settings. The augmented signature
    * structure is metadata only; writing parameters changes the base signature's instructions, from which the same
    * augmented structure is rebuilt.
    *
    * The default text of this leaf is the text of the base signature, because the augmented signature keeps that text
    * (upstream `signature.prepend`). The default text of the augmented structure also names `reasoning`, and the
    * program does not run it.
    */
  given chainOfThoughtOptimizableLeaf[I, O](using
      prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
  ): OptimizableLeaf[ChainOfThought[I, O]] with
    def get(program: ChainOfThought[I, O]): OptimizableParameters =
      OptimizableParameters(program.baseSignature.layout.instructions, program.demos, program.config, program.decisions)

    def metadata(program: ChainOfThought[I, O]): PredictorKey =
      PredictorKey.from(program.moduleName, program.signature.layout)

    override def defaultInstructions(program: ChainOfThought[I, O]): Option[String] =
      Some(program.baseSignature.layout.effectiveInstructions)

    def set(program: ChainOfThought[I, O], updated: OptimizableParameters): ChainOfThought[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          decisions = updated.decisions,
          baseSignature = program.baseSignature.withInstructions(updated.instructions)
        )

/** Uniform syntax derived from the lawful [[OptimizableLeaf]] lens. Every current and third-party leaf receives the
  * same parameter and inspection operations from its typeclass instance.
  */
extension [P](program: P)(using optimizableLeaf: OptimizableLeaf[P])
  def optimizableParameters: OptimizableParameters                 = optimizableLeaf.get(program)
  def optimizableView: OptimizableView                             = optimizableLeaf.inspect(program)
  def withOptimizableParameters(updated: OptimizableParameters): P = optimizableLeaf.set(program, updated)
