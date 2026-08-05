package dspy4s

/** Public façade for composition primitives and built-in executable strategies. Their implementations live in the
  * focused [[dspy4s.programs.compose]] and [[dspy4s.programs.strategies]] packages while existing `dspy4s.programs.*`
  * imports remain source-compatible.
  */
package object programs:
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
