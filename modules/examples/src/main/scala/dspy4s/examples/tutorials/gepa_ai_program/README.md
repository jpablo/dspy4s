# Reflective Prompt Evolution with GEPA

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/gepa_ai_program/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/gepa_ai_program/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

🚫 **Not ported.** GEPA is a reflective prompt optimizer: it uses an LM to reflect on a program's execution
trajectory — what worked, what didn't, what to change — and evolves a tree of candidate prompts, optionally
using rich textual feedback rather than only a scalar metric. In DSPy it's `dspy.GEPA` (introduced in
[*GEPA: Reflective Prompt Evolution Can Outperform Reinforcement Learning*](https://arxiv.org/abs/2507.19457),
wrapping [gepa-ai/gepa](https://github.com/gepa-ai/gepa)).

dspy4s has no GEPA optimizer, so the GEPA tutorials (AIME, enterprise extraction, PAPILLON, code-backdoor
classification) have no dspy4s counterpart. For the optimization that *is* available, see
[`learn/optimization`](../../learn/optimization/README.md) (`BootstrapFewShot*`).
