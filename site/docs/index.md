---
hide:
  - navigation
  - toc
---

<div class="hero" markdown>

# dspy4s { .hero__title }

<p class="hero__tagline">
A Scala 3 library for building language model programs with typed signatures.
Inputs and outputs are ordinary Scala types, so the compiler checks them for you.
</p>

<div class="hero__actions" markdown>
[Get Started](get-started/installation.md){ .md-button .md-button--primary }
[Signatures](programs/signatures.md){ .md-button }
[GitHub](https://github.com/jpablo/dspy4s){ .md-button }
</div>

</div>

```scala
// A signature declares typed inputs and outputs.
val classify = Predict(Signature.fromType[(sentence: String) => (sentiment: Boolean)])

classify.apply((sentence = "it's a charming and often affecting journey.")).map(_.output.sentiment)
// Either[DspyError, Boolean]
```

## Why dspy4s

<div class="grid cards" markdown>

-   :material-shield-check:{ .lg .middle } __Typed signatures__

    ---

    Signatures are ordinary Scala types. Inputs and outputs are named tuples, so
    you get dot-access (`_.output.sentiment`) and a compile error when a field is
    wrong, instead of a runtime lookup failure.

    [:octicons-arrow-right-24: Signatures](programs/signatures.md)

-   :material-cube-outline:{ .lg .middle } __Composable modules__

    ---

    Build programs from `Predict`, `ChainOfThought`, and `ReAct`, then compose
    them like any other Scala value.

-   :material-check-decagram:{ .lg .middle } __Compiler-verified docs__

    ---

    Every code sample on this site is extracted from a module that builds under
    strict flags (`-Werror`, `-Wunused:all`). A snippet that would not compile
    fails the build, so the docs stay correct.

-   :material-cog-sync:{ .lg .middle } __Optimizers__

    ---

    Bootstrap few-shot, COPRO, MIPROv2, and a self-contained GEPA implementation
    for optimizing prompts and few-shot demonstrations.

</div>

!!! note "Early days"
    dspy4s is a work in progress and not yet published to Maven Central. See
    [Installation](get-started/installation.md) for how to build from source.
