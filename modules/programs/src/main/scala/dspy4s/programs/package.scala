package dspy4s

/** Public façade for the built-in executable strategies. Their implementations live in the focused
  * [[dspy4s.programs.strategies]] package while existing `dspy4s.programs.*` imports remain source-compatible.
  */
package object programs:
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
