# Learning dspy4s: An Overview

> Adapted for **dspy4s** from the DSPy docs page
> [`learn/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

dspy4s, like DSPy, has a small core API but is used through an iterative development loop. You compose
typed modules and design patterns to optimize for *your* objective. The same three stages apply:

1. **Programming** — define the task (its inputs and outputs), sketch an initial pipeline out of modules,
   and try a handful of examples by hand. → [`programming/`](./programming/README.md)
2. **Evaluation** — collect a small dev set, define a metric, and use them to iterate systematically rather
   than by eyeballing. → [`evaluation/`](./evaluation/README.md)
3. **Optimization** — once you can measure quality, let an optimizer tune the prompts/demos in your program.
   → [`optimization/`](./optimization/README.md)

Work in this order: optimizing a poorly-designed program against a weak metric is wasted effort. Get the
program and the metric reasonable first, then optimize.

dspy4s keeps the typed boundary throughout — programs are `Module[I, O]` values, signatures are typed
(`Signature[I, O]`), and results decode into typed outputs — so the "write code, not strings" philosophy is
enforced by the Scala compiler.
