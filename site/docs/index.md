---
hide:
  - navigation
  - toc
---

<div class="hero" markdown>

# dspy4s { .hero__title }

<p class="hero__tagline">
Programming — not prompting — language models, in <strong>Scala 3</strong>.
A faithful port of <a href="https://dspy.ai/">DSPy</a> with typed signatures the compiler checks for you.
</p>

<div class="hero__actions" markdown>
[Get Started](get-started/installation.md){ .md-button .md-button--primary }
[Signatures](learn/programming/signatures.md){ .md-button }
[GitHub](https://github.com/jpablo/dspy4s){ .md-button }
</div>

</div>

```scala
// A signature is a typed input → output contract. The compiler knows the shape.
val classify = Predict(Signature.fromType[(sentence: String) => (sentiment: Boolean)])

classify.apply((sentence = "it's a charming and often affecting journey.")).map(_.output.sentiment)
// Either[DspyError, Boolean]
```

## Why dspy4s

<div class="grid cards" markdown>

-   :material-shield-check:{ .lg .middle } __Typed, not stringly__

    ---

    Signatures are real Scala types. Inputs and outputs are named tuples, so
    you get dot-access (`_.output.sentiment`) and a compile error when a field
    is wrong — no runtime `KeyError`.

    [:octicons-arrow-right-24: Signatures](learn/programming/signatures.md)

-   :material-language-python:{ .lg .middle } __Faithful to DSPy__

    ---

    The same building blocks — `Predict`, `ChainOfThought`, `ReAct`,
    optimizers (COPRO / MIPROv2 / GEPA) — ported one-to-one from the Python
    framework, with the original snippets kept inline for reference.

-   :material-check-decagram:{ .lg .middle } __Compiler-verified docs__

    ---

    Every code sample on this site is extracted from a module that builds
    under strict flags (`-Werror`, `-Wunused:all`). If a snippet wouldn't
    compile, the site doesn't ship.

-   :material-cog-sync:{ .lg .middle } __Optimizers included__

    ---

    Bootstrap few-shot, COPRO, MIPROv2, and a self-contained GEPA port —
    the prompt-optimization engines that make DSPy DSPy.

</div>

!!! note "Early days"
    dspy4s is a work in progress and not yet published to Maven Central. See
    [Installation](get-started/installation.md) for how to build from source.
