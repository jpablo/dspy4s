package dspy4s.programs.optimization

import dspy4s.programs.strategies.{MultiChainComparison, ReAct, ReActV2, RLM}

/** Hand-written [[OptimizableStructure]] instances for composite programs whose learnable sub-predicts are hoisted to
  * stable, `copy`-reachable members. Inheriting these instances into the [[OptimizableStructure]] companion keeps them
  * in implicit scope without mixing concrete program structure into the core typeclass definition.
  *
  * `replace` writes parameters through each current executable predictor. Unchanged parameters preserve the existing
  * override field exactly; changed parameters create an override with the same signature structure and execution
  * bindings. Thus optimizer replacement cannot swap runtimes, LMs, schemas, tools, or names.
  */
private[optimization] trait CompositeOptimizableStructureInstances:
  given reactOptimizableStructure[I, O]: OptimizableStructure.Of[ReAct[I, O], 2] =
    twoPredictStructure("ReAct", "react", "extractor")(
      _.reactPredict,
      _.reactPredictOverride,
      _.extractorPredict,
      _.extractorPredictOverride,
      (program, nextA, nextB) => program.copy(reactPredictOverride = nextA, extractorPredictOverride = nextB)
    )

  given rlmOptimizableStructure[I, O]: OptimizableStructure.Of[RLM[I, O], 2] =
    twoPredictStructure("RLM", "action", "extract")(
      _.actionPredict,
      _.actionPredictOverride,
      _.extractPredict,
      _.extractPredictOverride,
      (program, nextA, nextB) => program.copy(actionPredictOverride = nextA, extractPredictOverride = nextB)
    )

  /** ReActV2 (experimental): one optimizable part, the step predictor `react` (upstream `ReActV2.react`). `replace`
    * writes the parameters through [[ReActV2.reactPredict]] into `reactPredictOverride`; parameters equal to the
    * current ones give the same program.
    */
  given reactV2OptimizableStructure[I, O]: OptimizableStructure.Of[ReActV2[I, O], 1] with
    def arity(@annotation.unused program: ReActV2[I, O]): Int = 1

    def inspect(program: ReActV2[I, O]): Vector[OptimizableView] =
      Vector(program.reactPredict.optimizableView)

    override def inspectNamed(program: ReActV2[I, O]): Vector[(String, OptimizableView)] =
      Vector("react" -> program.reactPredict.optimizableView)

    def replace(program: ReActV2[I, O], updates: Vector[OptimizableParameters]): ReActV2[I, O] =
      require(updates.size == 1, s"ReActV2 expects exactly 1 update (react), got ${updates.size}")
      if updates == read(program) then program
      else
        program.copy(reactPredictOverride =
          updateOverride(program.reactPredict, program.reactPredictOverride, updates.head)
        )

  given multiChainComparisonOptimizableStructure[I, O]
      : OptimizableStructure.Of[MultiChainComparison[I, O], 1] with
    def arity(@annotation.unused program: MultiChainComparison[I, O]): Int    = 1
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

  /** The shared shape of a two-predict composite instance (ReAct / RLM): two addressable predicts, two `copy`-reachable
    * override fields, and a rebuild via `copy`. The no-op guard (return the SAME program when the updates equal the
    * current parameters) is decided once here, matching the guard the arity-1 instance above already carries.
    */
  private def twoPredictStructure[P, PA: OptimizableLeaf, PB: OptimizableLeaf](
      label: String,
      nameA: String,
      nameB: String
  )(
      predictA : P => PA,
      overrideA: P => Option[PA],
      predictB : P => PB,
      overrideB: P => Option[PB],
      rebuild  : (P, Option[PA], Option[PB]) => P
  ): OptimizableStructure.Of[P, 2] =
    new OptimizableStructure.Of[P, 2]:
      def arity(@annotation.unused program: P): Int    = 2
      def inspect(program: P): Vector[OptimizableView] =
        Vector(predictA(program).optimizableView, predictB(program).optimizableView)

      override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
        Vector(nameA -> predictA(program).optimizableView, nameB -> predictB(program).optimizableView)

      def replace(program: P, updates: Vector[OptimizableParameters]): P =
        require(updates.size == 2, s"$label expects exactly 2 updates ($nameA, $nameB), got ${updates.size}")
        if updates == read(program) then program
        else
          rebuild(
            program,
            updateOverride(predictA(program), overrideA(program), updates(0)),
            updateOverride(predictB(program), overrideB(program), updates(1))
          )

  private def updateOverride[P](
      current : P,
      existing: Option[P],
      updated : OptimizableParameters
  )(using OptimizableLeaf[P]): Option[P] =
    if updated == current.optimizableParameters then existing else Some(current.withOptimizableParameters(updated))
