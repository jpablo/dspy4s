package dspy4s.programs.optimization

import dspy4s.core.algebra.{IsEq, Law, Lens, <->}
import dspy4s.programs.{ChainOfThought, DynamicPredict, Predict}
import dspy4s.typed.OutputAugmentation.PrependField

/** A program that is one independently optimizable leaf of the introspection tree.
  *
  * This is a lawful [[dspy4s.core.algebra.Lens Lens]] onto exactly the program's [[OptimizableParameters]]: the
  * Get-Put / Put-Get / Put-Put statements are inherited from the `Lens` trait, and the [[frame]] law added here pins
  * what makes the focus exact — writing parameters can never change the read-only [[OptimizableMetadata]], which
  * excludes signature structure and execution resources from optimizer replacement. `OptimizableParametersSuite`
  * executes all four statements per instance.
  */
trait OptimizableLeaf[P] extends Lens[P, OptimizableParameters]:
  def metadata(program: P): OptimizableMetadata

  final def inspect(program: P): OptimizableView = OptimizableView(metadata(program), get(program))

  @Law("frame: writing parameters never changes the read-only metadata")
  def frame(program: P, updated: OptimizableParameters): IsEq[OptimizableMetadata] =
    metadata(set(program, updated)) <-> metadata(program)

object OptimizableLeaf:
  /** A [[DynamicPredict]] is itself an optimizable leaf. Defined in the [[OptimizableLeaf]] companion so it is in
    * implicit scope wherever an `OptimizableLeaf[DynamicPredict]` (or its `NotGiven`) is sought.
    */
  given OptimizableLeaf[DynamicPredict] with
    def get(program: DynamicPredict): OptimizableParameters =
      OptimizableParameters(program.layout.instructions, program.demos, program.config)

    def metadata(program: DynamicPredict): OptimizableMetadata =
      OptimizableMetadata.from(program.layout, program.moduleName)

    def set(program: DynamicPredict, updated: OptimizableParameters): DynamicPredict =
      if updated == get(program) then program
      else
        program.copy(
          layout = program.layout.withInstructions(updated.instructions),
          demos = updated.demos,
          config = updated.config
        )

  /** [[OptimizableLeaf]] for the typed single-leaf program [[Predict]]. A `Predict` field inside a user composite
    * resolves here (via [[OptimizableTraversal.fromOptimizableLeaf]], 1 element) rather than being structurally torn apart
    * by [[OptimizableTraversal.derived]], and a standalone `Predict` is introspectable/tunable. Lives in the
    * [[OptimizableLeaf]] companion so it is in implicit scope without an explicit import.
    *
    * Optimizable parameters are exactly instructions, demos, and module config. The signature field structure, output
    * shape, name, runtime, bound LM, and tools remain on the original typed program and are exposed only as read-only
    * metadata.
    */
  given predictOptimizableLeaf[I, O]: OptimizableLeaf[Predict[I, O]] with
    def get(program: Predict[I, O]): OptimizableParameters =
      OptimizableParameters(program.signature.layout.instructions, program.demos, program.config)

    def metadata(program: Predict[I, O]): OptimizableMetadata =
      OptimizableMetadata.from(program.signature.layout, program.moduleName)

    def set(program: Predict[I, O], updated: OptimizableParameters): Predict[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          signature = program.signature.withInstructions(updated.instructions)
        )

  /** [[OptimizableLeaf]] for the typed single-leaf program [[ChainOfThought]]. Like [[predictOptimizableLeaf]], but
    * the exposed layout is the **augmented** layout CoT actually runs (a leading `reasoning` output field prepended).
    *
    * Optimizable parameters remain instructions, demos, and config. The augmented signature structure is metadata only;
    * writing parameters changes the base signature's instructions, from which the same augmented structure is rebuilt.
    */
  given chainOfThoughtOptimizableLeaf[I, O](using
      prepend: PrependField.Of[ChainOfThought.ReasoningName, String, O]
  ): OptimizableLeaf[ChainOfThought[I, O]] with
    private def augmented(program: ChainOfThought[I, O]) =
      ChainOfThought.augmentLayout(program.signature.layout)

    def get(program: ChainOfThought[I, O]): OptimizableParameters =
      OptimizableParameters(program.signature.layout.instructions, program.demos, program.config)

    def metadata(program: ChainOfThought[I, O]): OptimizableMetadata =
      OptimizableMetadata.from(augmented(program), program.moduleName)

    def set(program: ChainOfThought[I, O], updated: OptimizableParameters): ChainOfThought[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          signature = program.signature.withInstructions(updated.instructions)
        )

/** Uniform syntax derived from the lawful [[OptimizableLeaf]] lens. Every current and third-party leaf receives the same
  * parameter and inspection operations from its typeclass instance.
  */
extension [P](program: P)(using optimizableLeaf: OptimizableLeaf[P])
  def optimizableParameters: OptimizableParameters                    = optimizableLeaf.get(program)
  def optimizableView: OptimizableView                                = optimizableLeaf.inspect(program)
  def withOptimizableParameters(updated: OptimizableParameters): P = optimizableLeaf.set(program, updated)
