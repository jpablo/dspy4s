package dspy4s.programs.predictors

import dspy4s.programs.{CodeAct, MultiChainComparison, ProgramOfThought, ReAct, RLM}

/** Hand-written [[PredictorTraversal]] instances for composite typed programs whose learnable sub-predicts are hoisted to
  * stable, `copy`-reachable members. Inheriting these instances into the [[PredictorTraversal]] companion keeps them in
  * implicit scope without mixing concrete program traversal into the core typeclass definition.
  *
  * `replace` writes parameters through each current executable predictor. Unchanged parameters preserve the existing
  * override field exactly; changed parameters create an override with the same signature structure and execution
  * bindings. Thus optimizer replacement cannot swap runtimes, LMs, schemas, tools, or names.
  */
private[predictors] trait CompositePredictorTraversalInstances:
  given reactPredictorTraversal[I, O]: PredictorTraversal[ReAct[I, O]] with
    def inspect(program: ReAct[I, O]): Vector[PredictorView] =
      Vector(program.reactPredict.predictorView, program.extractorPredict.predictorView)

    override def inspectNamed(program: ReAct[I, O]): Vector[(String, PredictorView)] =
      Vector("react" -> program.reactPredict.predictorView, "extractor" -> program.extractorPredict.predictorView)

    def replace(program: ReAct[I, O], updates: Vector[OptimizableParameters]): ReAct[I, O] =
      require(updates.size == 2, s"ReAct expects exactly 2 updates (react, extractor), got ${updates.size}")
      val nextReact = updateOverride(program.reactPredict, program.reactPredictOverride, updates(0))
      val nextExtractor = updateOverride(program.extractorPredict, program.extractorPredictOverride, updates(1))
      program.copy(reactPredictOverride = nextReact, extractorPredictOverride = nextExtractor)

  given codeActPredictorTraversal[I, O]: PredictorTraversal[CodeAct[I, O]] with
    def inspect(program: CodeAct[I, O]): Vector[PredictorView] =
      Vector(program.codeActPredict.predictorView, program.extractorPredict.predictorView)

    override def inspectNamed(program: CodeAct[I, O]): Vector[(String, PredictorView)] =
      Vector("codeact" -> program.codeActPredict.predictorView, "extractor" -> program.extractorPredict.predictorView)

    def replace(program: CodeAct[I, O], updates: Vector[OptimizableParameters]): CodeAct[I, O] =
      require(updates.size == 2, s"CodeAct expects exactly 2 updates (codeact, extractor), got ${updates.size}")
      val nextCodeAct = updateOverride(program.codeActPredict, program.codeActPredictOverride, updates(0))
      val nextExtractor = updateOverride(program.extractorPredict, program.extractorPredictOverride, updates(1))
      program.copy(codeActPredictOverride = nextCodeAct, extractorPredictOverride = nextExtractor)

  given rlmPredictorTraversal[I, O]: PredictorTraversal[RLM[I, O]] with
    def inspect(program: RLM[I, O]): Vector[PredictorView] =
      Vector(program.actionPredict.predictorView, program.extractPredict.predictorView)

    override def inspectNamed(program: RLM[I, O]): Vector[(String, PredictorView)] =
      Vector("action" -> program.actionPredict.predictorView, "extract" -> program.extractPredict.predictorView)

    def replace(program: RLM[I, O], updates: Vector[OptimizableParameters]): RLM[I, O] =
      require(updates.size == 2, s"RLM expects exactly 2 updates (action, extract), got ${updates.size}")
      val nextAction = updateOverride(program.actionPredict, program.actionPredictOverride, updates(0))
      val nextExtract = updateOverride(program.extractPredict, program.extractPredictOverride, updates(1))
      program.copy(actionPredictOverride = nextAction, extractPredictOverride = nextExtract)

  given programOfThoughtPredictorTraversal[I, O]: PredictorTraversal[ProgramOfThought[I, O]] with
    def inspect(program: ProgramOfThought[I, O]): Vector[PredictorView] =
      Vector(
        program.generatorPredict.predictorView,
        program.regeneratorPredict.predictorView,
        program.answererPredict.predictorView
      )

    override def inspectNamed(program: ProgramOfThought[I, O]): Vector[(String, PredictorView)] =
      Vector(
        "generator"   -> program.generatorPredict.predictorView,
        "regenerator" -> program.regeneratorPredict.predictorView,
        "answerer"    -> program.answererPredict.predictorView
      )

    def replace(program: ProgramOfThought[I, O], updates: Vector[OptimizableParameters]): ProgramOfThought[I, O] =
      require(
        updates.size == 3,
        s"ProgramOfThought expects exactly 3 updates (generator, regenerator, answerer), got ${updates.size}"
      )
      if updates == inspect(program).map(_.parameters) then program
      else
        val nextGenerator = updateOverride(program.generatorPredict, program.generatorPredictOverride, updates(0))
        val nextRegenerator =
          updateOverride(program.regeneratorPredict, program.regeneratorPredictOverride, updates(1))
        val nextAnswerer = updateOverride(program.answererPredict, program.answererPredictOverride, updates(2))
        program.copy(
          generatorPredictOverride = nextGenerator,
          regeneratorPredictOverride = nextRegenerator,
          answererPredictOverride = nextAnswerer
        )

  given multiChainComparisonPredictorTraversal[I, O]: PredictorTraversal[MultiChainComparison[I, O]] with
    def inspect(program: MultiChainComparison[I, O]): Vector[PredictorView] =
      Vector(program.comparePredict.predictorView)

    override def inspectNamed(program: MultiChainComparison[I, O]): Vector[(String, PredictorView)] =
      Vector("compare" -> program.comparePredict.predictorView)

    def replace(
        program: MultiChainComparison[I, O],
        updates: Vector[OptimizableParameters]
    ): MultiChainComparison[I, O] =
      require(updates.size == 1, s"MultiChainComparison expects exactly 1 update (compare), got ${updates.size}")
      if updates.head == program.comparePredict.optimizableParameters then program
      else program.copy(comparePredictParametersOverride = Some(updates.head))

  private def updateOverride[P](
      current: P,
      existing: Option[P],
      updated: OptimizableParameters
  )(using PredictorLens[P]): Option[P] =
    if updated == current.optimizableParameters then existing else Some(current.withOptimizableParameters(updated))
