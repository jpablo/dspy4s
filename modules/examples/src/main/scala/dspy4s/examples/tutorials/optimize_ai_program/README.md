# Optimize AI Programs with dspy4s

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/optimize_ai_program/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/optimize_ai_program/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

DSPy's optimization tutorials (math reasoning, classification finetuning, advanced tool use, finetuning agents)
are mostly notebooks built on optimizers and finetuning, with heavy datasets, so the specific tutorials on this
DSPy page don't have a runnable dspy4s counterpart yet — but most of the optimizers themselves are ported.

What dspy4s offers today: few-shot demo optimization (`BootstrapFewShot` / `BootstrapFewShotWithRandomSearch` /
`LabeledFewShot`), instruction/joint optimization (`COPRO` / `MIPROv2`), `Ensemble`, `KNNFewShot`, `InferRules`,
and reflective prompt evolution ([`GEPA`](../gepa_ai_program/README.md)). See
[`learn/optimization/Optimizers.scala`](../../learn/optimization/Optimizers.scala) and the
[optimization overview](../../learn/optimization/README.md).

Not yet ported: `SIMBA`, `BetterTogether`, and finetuning-based optimizers (`BootstrapFinetune` / `GRPO`).
