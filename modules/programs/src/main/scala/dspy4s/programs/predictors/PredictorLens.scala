package dspy4s.programs.predictors

import dspy4s.core.contracts.{IsEq, Law, Lens, <->}
import dspy4s.programs.{ChainOfThought, DynamicPredict, Predict}
import dspy4s.typed.OutputAugmentation.PrependField

/** A program that is one learnable predictor (a leaf of the introspection tree).
  *
  * This is a lawful [[dspy4s.core.contracts.Lens Lens]] onto exactly the program's writable [[PredictorState]]: the
  * Get-Put / Put-Get / Put-Put statements are inherited from the `Lens` trait, and the [[frame]] law added here pins
  * what makes the focus exact — writing state can never change the read-only [[PredictorMetadata]], which is what
  * excludes signature structure and execution resources from optimizer replacement. `PredictorStateSuite` executes
  * all four statements per instance.
  */
trait PredictorLens[P] extends Lens[P, PredictorState]:
  def metadata(program: P): PredictorMetadata

  final def inspect(program: P): PredictorView = PredictorView(metadata(program), get(program))

  @Law("frame: writing state never changes the read-only metadata")
  def frame(program: P, updated: PredictorState): IsEq[PredictorMetadata] =
    metadata(set(program, updated)) <-> metadata(program)

object PredictorLens:
  /** A [[DynamicPredict]] is itself a learnable predictor leaf. Defined in the [[PredictorLens]] companion so it is in
    * implicit scope wherever a `PredictorLens[DynamicPredict]` (or its `NotGiven`) is sought.
    */
  given PredictorLens[DynamicPredict] with
    def get(program: DynamicPredict): PredictorState =
      PredictorState(program.layout.instructions, program.demos, program.config)

    def metadata(program: DynamicPredict): PredictorMetadata =
      PredictorMetadata.from(program.layout, program.moduleName)

    def set(program: DynamicPredict, updated: PredictorState): DynamicPredict =
      if updated == get(program) then program
      else
        program.copy(
          layout = program.layout.withInstructions(updated.instructions),
          demos = updated.demos,
          config = updated.config
        )

  /** Leaf [[PredictorLens]] for the typed single-predictor program [[Predict]]. A `Predict` field inside a user composite
    * resolves here (via [[PredictorTraversal.fromPredictorLens]], 1 element) rather than being structurally torn apart
    * by [[PredictorTraversal.derived]], and a standalone `Predict` is introspectable/tunable. Lives in the
    * [[PredictorLens]] companion so it is in implicit scope without an explicit import.
    *
    * State is exactly instructions, demos, and module config. The signature field structure, output shape, name,
    * runtime, bound LM, and tools remain on the original typed program and are exposed only as read-only metadata.
    */
  given predictPredictorLens[I, O]: PredictorLens[Predict[I, O]] with
    def get(program: Predict[I, O]): PredictorState =
      PredictorState(program.signature.layout.instructions, program.demos, program.config)

    def metadata(program: Predict[I, O]): PredictorMetadata =
      PredictorMetadata.from(program.signature.layout, program.moduleName)

    def set(program: Predict[I, O], updated: PredictorState): Predict[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          signature = program.signature.withInstructions(updated.instructions)
        )

  /** Leaf [[PredictorLens]] for the typed single-predictor program [[ChainOfThought]]. Like [[predictPredictorLens]], but
    * the exposed layout is the **augmented** layout CoT actually runs (a leading `reasoning` output field prepended).
    * `ChainOfThought.augmentLayout` returns an `Either`; it is resolved fail-fast here (consistent with the P3
    * hand-written instances), and only fails for layouts that cannot be augmented.
    *
    * State remains instructions, demos, and config. The augmented signature structure is metadata only; writing a state
    * changes the base signature's instructions, from which the same augmented structure is rebuilt.
    */
  given chainOfThoughtPredictorLens[I, O](using
      prepend: PrependField.Of["reasoning", String, O]
  ): PredictorLens[ChainOfThought[I, O]] with
    private def augmented(program: ChainOfThought[I, O]) =
      ChainOfThought
        .augmentLayout(program.signature.layout)
        .fold(
          err =>
            throw new IllegalStateException(
              s"ChainOfThought '${program.moduleName}' has a non-augmentable layout: ${err.message}"
            ),
          identity
        )

    def get(program: ChainOfThought[I, O]): PredictorState =
      PredictorState(program.signature.layout.instructions, program.demos, program.config)

    def metadata(program: ChainOfThought[I, O]): PredictorMetadata =
      PredictorMetadata.from(augmented(program), program.moduleName)

    def set(program: ChainOfThought[I, O], updated: PredictorState): ChainOfThought[I, O] =
      if updated == get(program) then program
      else
        program.copy(
          demos = updated.demos,
          config = updated.config,
          signature = program.signature.withInstructions(updated.instructions)
        )

/** Uniform syntax derived from the lawful [[PredictorLens]] lens. No predictor class needs to duplicate state/view/update
  * methods; every current and third-party leaf receives the same operations from its typeclass instance.
  */
extension [P](program: P)(using predictorLens: PredictorLens[P])
  def predictorState: PredictorState                 = predictorLens.get(program)
  def predictorView: PredictorView                   = predictorLens.inspect(program)
  def withPredictorState(updated: PredictorState): P = predictorLens.set(program, updated)
