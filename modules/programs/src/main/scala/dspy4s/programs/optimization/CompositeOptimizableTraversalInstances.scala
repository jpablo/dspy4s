package dspy4s.programs.optimization

import dspy4s.programs.{CodeAct, MultiChainComparison, ProgramOfThought, ReAct, RLM}

/** Hand-written [[OptimizableTraversal]] instances for composite typed programs whose learnable sub-predicts are hoisted to
  * stable, `copy`-reachable members. Inheriting these instances into the [[OptimizableTraversal]] companion keeps them in
  * implicit scope without mixing concrete program traversal into the core typeclass definition.
  *
  * `replace` writes parameters through each current executable predictor. Unchanged parameters preserve the existing
  * override field exactly; changed parameters create an override with the same signature structure and execution
  * bindings. Thus optimizer replacement cannot swap runtimes, LMs, schemas, tools, or names.
  */
private[optimization] trait CompositeOptimizableTraversalInstances:
  given reactOptimizableTraversal[I, O]: FixedArityOptimizableTraversal.Of[ReAct[I, O], 2] with
    val arity: Int = 2
    def inspect(program: ReAct[I, O]): Vector[OptimizableView] =
      Vector(program.reactPredict.optimizableView, program.extractorPredict.optimizableView)

    override def inspectNamed(program: ReAct[I, O]): Vector[(String, OptimizableView)] =
      Vector("react" -> program.reactPredict.optimizableView, "extractor" -> program.extractorPredict.optimizableView)

    def replace(program: ReAct[I, O], updates: Vector[OptimizableParameters]): ReAct[I, O] =
      require(updates.size == 2, s"ReAct expects exactly 2 updates (react, extractor), got ${updates.size}")
      val nextReact = updateOverride(program.reactPredict, program.reactPredictOverride, updates(0))
      val nextExtractor = updateOverride(program.extractorPredict, program.extractorPredictOverride, updates(1))
      program.copy(reactPredictOverride = nextReact, extractorPredictOverride = nextExtractor)

  given codeActOptimizableTraversal[I, O]: FixedArityOptimizableTraversal.Of[CodeAct[I, O], 2] with
    val arity: Int = 2
    def inspect(program: CodeAct[I, O]): Vector[OptimizableView] =
      Vector(program.codeActPredict.optimizableView, program.extractorPredict.optimizableView)

    override def inspectNamed(program: CodeAct[I, O]): Vector[(String, OptimizableView)] =
      Vector("codeact" -> program.codeActPredict.optimizableView, "extractor" -> program.extractorPredict.optimizableView)

    def replace(program: CodeAct[I, O], updates: Vector[OptimizableParameters]): CodeAct[I, O] =
      require(updates.size == 2, s"CodeAct expects exactly 2 updates (codeact, extractor), got ${updates.size}")
      val nextCodeAct = updateOverride(program.codeActPredict, program.codeActPredictOverride, updates(0))
      val nextExtractor = updateOverride(program.extractorPredict, program.extractorPredictOverride, updates(1))
      program.copy(codeActPredictOverride = nextCodeAct, extractorPredictOverride = nextExtractor)

  given rlmOptimizableTraversal[I, O]: FixedArityOptimizableTraversal.Of[RLM[I, O], 2] with
    val arity: Int = 2
    def inspect(program: RLM[I, O]): Vector[OptimizableView] =
      Vector(program.actionPredict.optimizableView, program.extractPredict.optimizableView)

    override def inspectNamed(program: RLM[I, O]): Vector[(String, OptimizableView)] =
      Vector("action" -> program.actionPredict.optimizableView, "extract" -> program.extractPredict.optimizableView)

    def replace(program: RLM[I, O], updates: Vector[OptimizableParameters]): RLM[I, O] =
      require(updates.size == 2, s"RLM expects exactly 2 updates (action, extract), got ${updates.size}")
      val nextAction = updateOverride(program.actionPredict, program.actionPredictOverride, updates(0))
      val nextExtract = updateOverride(program.extractPredict, program.extractPredictOverride, updates(1))
      program.copy(actionPredictOverride = nextAction, extractPredictOverride = nextExtract)

  given programOfThoughtOptimizableTraversal[I, O]: FixedArityOptimizableTraversal.Of[ProgramOfThought[I, O], 3] with
    val arity: Int = 3
    def inspect(program: ProgramOfThought[I, O]): Vector[OptimizableView] =
      Vector(
        program.generatorPredict.optimizableView,
        program.regeneratorPredict.optimizableView,
        program.answererPredict.optimizableView
      )

    override def inspectNamed(program: ProgramOfThought[I, O]): Vector[(String, OptimizableView)] =
      Vector(
        "generator"   -> program.generatorPredict.optimizableView,
        "regenerator" -> program.regeneratorPredict.optimizableView,
        "answerer"    -> program.answererPredict.optimizableView
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

  given multiChainComparisonOptimizableTraversal[I, O]
      : FixedArityOptimizableTraversal.Of[MultiChainComparison[I, O], 1] with
    val arity: Int = 1
    def inspect(program: MultiChainComparison[I, O]): Vector[OptimizableView] =
      Vector(program.comparePredict.optimizableView)

    override def inspectNamed(program: MultiChainComparison[I, O]): Vector[(String, OptimizableView)] =
      Vector("compare" -> program.comparePredict.optimizableView)

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
  )(using OptimizableLeaf[P]): Option[P] =
    if updated == current.optimizableParameters then existing else Some(current.withOptimizableParameters(updated))
