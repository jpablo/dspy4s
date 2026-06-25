# API reference

The full type-level API is documented by Scaladoc generated from the source.

A hosted Scaladoc site is not published yet. To browse the API locally, generate
it from the repository:

```bash
sbt doc
```

Each module produces its own Scaladoc under
`modules/<module>/target/scala-3.x/api/`. Open the `index.html` in a browser.

The modules, in dependency order:

| Module | Artifact | Covers |
|---|---|---|
| core | `dspy4s-core` | `Example`, `DynamicValue`, `RuntimeContext`. |
| typed | `dspy4s-typed` | `Signature`, `Spec`, `InputField`, `OutputField`. |
| lm | `dspy4s-lm` | `LanguageModel`, `OpenAiLanguageModel`, caching, usage. |
| adapters | `dspy4s-adapters` | `ChatAdapter`, `JSONAdapter`. |
| programs | `dspy4s-modules` | `Predict`, `ChainOfThought`, `ReAct`, `BestOfN`, `Refine`. |
| evaluate | `dspy4s-evaluate` | `Evaluate`, `Metric`, `FunctionMetric`, `SemanticF1`. |
| optimize | `dspy4s-optimize` | `BootstrapFewShot`, `COPRO`, `MIPROv2`, `ProgramPersistence`. |
| gepa | `dspy4s-gepa` | `GEPA`. |
| streaming | `dspy4s-streaming` | `Streamify`, `StreamListener`, `StreamEvent`. |
