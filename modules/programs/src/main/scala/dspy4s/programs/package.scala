package dspy4s

/** Public façade for composition primitives and built-in executable strategies. Their implementations live in the
  * focused [[dspy4s.programs.compose]] and [[dspy4s.programs.strategies]] packages while existing `dspy4s.programs.*`
  * imports remain source-compatible.
  */
package object programs:
  /** Primary functional program API. Implementations remain in `plan` until the legacy source tree is removed. */
  type Program[I, O]                 = plan.Program[I, O]
  type ProgramWithEnv[I, O, R]       = plan.ProgramWithEnv[I, O, R]
  type RecordProgram[I, O]           = plan.RecordProgram[I, O]
  type RecordProgramWithEnv[I, O, R] = plan.RecordProgramWithEnv[I, O, R]
  type PredictionBackend             = plan.PredictionBackend
  type PredictionRequest             = plan.PredictionRequest
  type PredictionChunk               = plan.PredictionChunk
  type ProgramEvent                  = plan.ProgramEvent
  type ProgramObserver               = plan.ProgramObserver
  type RunOptions                    = plan.RunOptions
  type ParameterId                   = plan.ParameterId
  type ParameterStore                = plan.ParameterStore
  type ParameterBinding              = plan.ParameterBinding
  type ProgramGraph                  = plan.ProgramGraph
  type ProgramGraphNode              = plan.ProgramGraphNode
  type ProgramGraphEdge              = plan.ProgramGraphEdge

  val Program: plan.Program.type                     = plan.Program
  val ProgramRunner: plan.ProgramRunner.type         = plan.ProgramRunner
  val ProgramObserver: plan.ProgramObserver.type     = plan.ProgramObserver
  val PredictionRequest: plan.PredictionRequest.type = plan.PredictionRequest
  val PredictionChunk: plan.PredictionChunk.type     = plan.PredictionChunk
  val ProgramEvent: plan.ProgramEvent.type           = plan.ProgramEvent
  val RunOptions: plan.RunOptions.type               = plan.RunOptions
  val ParameterId: plan.ParameterId.type             = plan.ParameterId
  val ParameterStore: plan.ParameterStore.type       = plan.ParameterStore
  val ParameterBinding: plan.ParameterBinding.type   = plan.ParameterBinding
  val ProgramGraph: plan.ProgramGraph.type           = plan.ProgramGraph
  val ProgramGraphNode: plan.ProgramGraphNode.type   = plan.ProgramGraphNode
  val ProgramGraphEdge: plan.ProgramGraphEdge.type   = plan.ProgramGraphEdge

  export compose.{
    &&&,
    ***,
    >>>,
    AndThen,
    Both,
    Compose,
    ContramapInput,
    Copy,
    Dimap,
    Discard,
    Identity,
    Lift,
    LiftEither,
    MapOutput,
    Mode,
    Moded,
    RecoverWith,
    RecoveryPolicy,
    Swap,
    Tensor,
    andThen,
    contramapInput,
    dimap,
    fanout,
    mapOutput,
    mode,
    recoverWith,
    split
  }
  export strategies.{
    BestOfN,
    ChainOfThought,
    CodeAct,
    DynamicPredict,
    MultiChainComparison,
    MultiChainInput,
    Predict,
    ProgramOfThought,
    RLM,
    ReAct,
    Refine
  }
