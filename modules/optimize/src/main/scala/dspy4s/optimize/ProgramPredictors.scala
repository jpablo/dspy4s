package dspy4s.optimize

import dspy4s.programs.ChainOfThought
import dspy4s.programs.CodeAct
import dspy4s.programs.DynamicPredict
import dspy4s.programs.MultiChainComparison
import dspy4s.programs.Predict
import dspy4s.programs.ReAct
import dspy4s.typed.OutputAugmentation

/** Hand-written [[Predictors]] instances for the composite typed programs whose learnable sub-predicts are now
  * hoisted to stable, `copy`-reachable members ([[ReAct]], [[CodeAct]], [[MultiChainComparison]]).
  *
  * These programs are typed (`Module[TypedCall[I], Prediction[O]]`), not `DynamicModule`, so they are not yet
  * consumed by the optimizers (that arrives in P5, which drops the `DynamicModule` bound). The instances exist so
  * the contained predicts are addressable and immutably replaceable today, with the [[Predictors]] invariants:
  *   - `read` enumerates the contained predicts in a stable order;
  *   - `replace` rebuilds the program immutably via the per-predict `*Override` fields;
  *   - `replace(p, read(p)) == p` (round-trip identity).
  *
  * Round-trip identity is preserved without overriding case-class equality (the build runs `-language:strictEquality`,
  * and `DynamicPredict` carries no `CanEqual`): `replace` only rewrites an override field when the incoming update is
  * not the program's current effective predict, compared by reference (`eq`). Since `read` returns the exact member
  * objects, `replace(p, read(p))` leaves every override field untouched and yields `p`; an edited `copy` is a fresh
  * object, so it is wrapped into the `*Override` field instead.
  */
object ProgramPredictors:

  given reactPredictors[I, O]: Predictors[ReAct[I, O]] with
    def read(program: ReAct[I, O]): Vector[DynamicPredict] =
      Vector(program.reactPredict, program.extractorPredict)

    def replace(program: ReAct[I, O], updates: Vector[DynamicPredict]): ReAct[I, O] =
      require(updates.size == 2, s"ReAct expects exactly 2 updates (react, extractor), got ${updates.size}")
      val nextReact     = if updates(0) eq program.reactPredict then program.reactPredictOverride else Some(updates(0))
      val nextExtractor = if updates(1) eq program.extractorPredict then program.extractorPredictOverride
                          else Some(updates(1))
      program.copy(reactPredictOverride = nextReact, extractorPredictOverride = nextExtractor)

  given codeActPredictors[I, O]: Predictors[CodeAct[I, O]] with
    def read(program: CodeAct[I, O]): Vector[DynamicPredict] =
      Vector(program.codeActPredict, program.extractorPredict)

    def replace(program: CodeAct[I, O], updates: Vector[DynamicPredict]): CodeAct[I, O] =
      require(updates.size == 2, s"CodeAct expects exactly 2 updates (codeact, extractor), got ${updates.size}")
      val nextCodeAct   = if updates(0) eq program.codeActPredict then program.codeActPredictOverride
                          else Some(updates(0))
      val nextExtractor = if updates(1) eq program.extractorPredict then program.extractorPredictOverride
                          else Some(updates(1))
      program.copy(codeActPredictOverride = nextCodeAct, extractorPredictOverride = nextExtractor)

  given multiChainComparisonPredictors[I, O]: Predictors[MultiChainComparison[I, O]] with
    def read(program: MultiChainComparison[I, O]): Vector[DynamicPredict] =
      Vector(program.comparePredict)

    def replace(program: MultiChainComparison[I, O], updates: Vector[DynamicPredict]): MultiChainComparison[I, O] =
      require(updates.size == 1, s"MultiChainComparison expects exactly 1 update (compare), got ${updates.size}")
      val nextCompare = if updates(0) eq program.comparePredict then program.comparePredictOverride
                        else Some(updates(0))
      program.copy(comparePredictOverride = nextCompare)

  /** Leaf [[Predictor]] for the typed single-predictor program [[Predict]]. A `Predict` field inside a user
    * composite resolves here (via [[Predictors.fromPredictor]], 1 element) rather than being structurally torn
    * apart by [[Predictors.derived]], and a standalone `Predict` is introspectable/tunable.
    *
    * `get` projects the program's learnable state into the [[DynamicPredict]] the program actually runs on:
    * the layout is `signature.layout` (the exact layout the inner [[dspy4s.programs.runtime.PredictEngine]]
    * executes), with the program's `demos`, name, and output JSON schema.
    *
    * `set` is **demos-only** (v1): it returns `program.copy(demos = updated.demos)` and deliberately does NOT
    * swap the layout back into the typed signature. Writing the layout back would desync `signature.outputShape`
    * (which still decodes the original `O`) from `signature.layout`; instruction/layout editing is deferred to
    * when instruction-optimizers land. Demos-only keeps the invariant `set(p, get(p)) == p`. */
  given predictPredictor[I, O]: Predictor[Predict[I, O]] with
    def get(program: Predict[I, O]): DynamicPredict =
      DynamicPredict(
        layout           = program.signature.layout,
        demos            = program.demos,
        name             = Some(program.moduleName),
        outputJsonSchema = program.signature.outputShape.jsonSchemaString
      )

    def set(program: Predict[I, O], updated: DynamicPredict): Predict[I, O] =
      // v1: demos only. See class comment on `predictPredictor` -- layout/instruction editing is deferred.
      program.copy(demos = updated.demos)

  /** Leaf [[Predictor]] for the typed single-predictor program [[ChainOfThought]]. Like [[predictPredictor]],
    * but the exposed layout is the **augmented** layout CoT actually runs (a leading `reasoning` output field
    * prepended). `ChainOfThought.augmentLayout` returns an `Either`; it is resolved fail-fast here (consistent
    * with the P3 hand-written instances), and only fails for layouts that cannot be augmented.
    *
    * `set` is demos-only for the same reasons as [[predictPredictor]]. The `prepend` evidence is required to
    * reconstruct the program via `copy` (it is part of `ChainOfThought`'s secondary parameter list). */
  given chainOfThoughtPredictor[I, O](using
      prepend: OutputAugmentation.PrependField.Aux["reasoning", String, O, ChainOfThought.WithReasoning[O]]
  ): Predictor[ChainOfThought[I, O]] with
    def get(program: ChainOfThought[I, O]): DynamicPredict =
      val augmented = ChainOfThought
        .augmentLayout(program.signature.layout)
        .fold(err => throw new IllegalStateException(
          s"ChainOfThought '${program.moduleName}' has a non-augmentable layout: ${err.message}"
        ), identity)
      DynamicPredict(
        layout           = augmented,
        demos            = program.demos,
        name             = Some(program.moduleName),
        outputJsonSchema = program.signature.outputShape.jsonSchemaString
      )

    def set(program: ChainOfThought[I, O], updated: DynamicPredict): ChainOfThought[I, O] =
      // v1: demos only. See class comment on `chainOfThoughtPredictor` -- layout/instruction editing is deferred.
      program.copy(demos = updated.demos)
