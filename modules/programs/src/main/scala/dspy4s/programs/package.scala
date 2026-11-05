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
    Dimap,
    Identity,
    Lift,
    LiftEither,
    Mode,
    Moded,
    RecoverWith,
    RecoveryPolicy,
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
    DynamicPredict,
    MultiChainComparison,
    MultiChainInput,
    Predict,
    RLM,
    ReAct,
    ReActV2,
    Refine
  }
