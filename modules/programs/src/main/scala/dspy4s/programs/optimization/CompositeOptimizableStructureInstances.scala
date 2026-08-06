package dspy4s.programs.optimization

import dspy4s.programs.strategies.{CodeAct, MultiChainComparison, ProgramOfThought, ReAct, RLM}

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

  given codeActOptimizableStructure[I, O]: OptimizableStructure.Of[CodeAct[I, O], 2] =
    twoPredictStructure("CodeAct", "codeact", "extractor")(
      _.codeActPredict,
      _.codeActPredictOverride,
      _.extractorPredict,
      _.extractorPredictOverride,
      (program, nextA, nextB) => program.copy(codeActPredictOverride = nextA, extractorPredictOverride = nextB)
    )

  given rlmOptimizableStructure[I, O]: OptimizableStructure.Of[RLM[I, O], 2] =
    twoPredictStructure("RLM", "action", "extract")(
      _.actionPredict,
      _.actionPredictOverride,
      _.extractPredict,
      _.extractPredictOverride,
      (program, nextA, nextB) => program.copy(actionPredictOverride = nextA, extractPredictOverride = nextB)
    )

  given programOfThoughtOptimizableStructure[I, O]: OptimizableStructure.Of[ProgramOfThought[I, O], 3] with
    def arity(@annotation.unused program: ProgramOfThought[I, O]): Int    = 3
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
        val nextGenerator   = updateOverride(program.generatorPredict, program.generatorPredictOverride, updates(0))
        val nextRegenerator = updateOverride(program.regeneratorPredict, program.regeneratorPredictOverride, updates(1))
        val nextAnswerer    = updateOverride(program.answererPredict, program.answererPredictOverride, updates(2))
        program.copy(
          generatorPredictOverride = nextGenerator,
          regeneratorPredictOverride = nextRegenerator,
          answererPredictOverride = nextAnswerer
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

  /** The shared shape of a two-predict composite instance (ReAct / CodeAct / RLM): two addressable predicts, two
    * `copy`-reachable override fields, and a rebuild via `copy`. The no-op guard (return the SAME program when the
    * updates equal the current parameters) is decided once here, matching the guards the arity-3 and arity-1 instances
    * below already carry.
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
