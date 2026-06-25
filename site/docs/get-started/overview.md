# How it fits together

This page is the map. It names every piece of dspy4s once and shows how they
compose, so the rest of the documentation can go deep on one piece at a time
without re-explaining the whole.

## The pieces

dspy4s has a small number of concepts. Each builds on the one before it.

| Concept | What it is |
|---|---|
| **Signature** | A typed declaration of a task's inputs and outputs. It describes *what*, not *how*. |
| **Module** | A strategy for answering a signature by calling a language model: `Predict`, `ChainOfThought`, `ReAct`. |
| **Program** | A module you run, or several modules composed into a larger one. Programs are ordinary Scala values. |
| **Adapter** | Turns a signature plus inputs into prompt messages, and parses the reply back into the typed output: `ChatAdapter`, `JSONAdapter`. |
| **Language model** | The backend an adapter sends messages to: `OpenAiLanguageModel` and OpenAI-compatible servers. |
| **Runtime context** | The ambient configuration a program runs under: which language model, which adapter, and any callbacks. |
| **Example** | A labeled data point: some inputs and their expected outputs. |
| **Metric** | A function that scores a program's output against an `Example`. |
| **Optimizer** | Takes a program, a set of `Example`s, and a `Metric`, and returns an improved program. |

## Running a program

When you call a program, the work flows in one direction and comes back typed:

<div class="flow-context">
  <span class="flow-context__label">runtime context · language model · adapter · callbacks</span>
  <div class="flow">
    <div class="flow-step">Signature<small>typed in/out</small></div>
    <div class="flow-arrow">→</div>
    <div class="flow-step flow-step--accent">Module<small>Predict · CoT · ReAct</small></div>
    <div class="flow-arrow">→</div>
    <div class="flow-step">Adapter<small>format + parse</small></div>
    <div class="flow-arrow">→</div>
    <div class="flow-step">Language model</div>
    <div class="flow-arrow">→</div>
    <div class="flow-step">Typed output<small>Either[err, out]</small></div>
  </div>
</div>

1. You call `program.apply(inputs)` inside a runtime context.
2. The module asks the adapter to format the signature, the inputs, and any
   few-shot demonstrations into messages.
3. The adapter sends the messages to the language model.
4. The adapter parses the reply back into the signature's output type.
5. You get an `Either[DspyError, Output]`, where `Output` is a named tuple with
   typed dot-access.

The [Quickstart](quickstart.md) shows this end to end in a few lines.

## Improving a program

Optimization is a separate, offline step. You do not change your program's code;
an optimizer searches for better few-shot demonstrations and instructions and
hands back a new program with the same type:

<div class="flow">
  <div class="flow-step">Program</div>
  <div class="flow-step">Examples</div>
  <div class="flow-step">Metric</div>
  <div class="flow-arrow">→</div>
  <div class="flow-step flow-step--accent">Optimizer</div>
  <div class="flow-arrow">→</div>
  <div class="flow-step">Improved program</div>
</div>

The improved program can be saved to disk and loaded later, so optimization runs
once and the result ships with your application.

## The map

Read top to bottom, or jump to what you need:

- **Programs** define and run the work:
  [Signatures](../programs/signatures.md), [Modules](../programs/modules.md),
  [Tools & ReAct](../programs/tools-and-react.md),
  [Composing programs](../programs/composing.md).
- **[Language models](../language-models/configuring.md)** are the backend
  programs call, plus adapters, caching, and usage.
- **[Evaluation](../evaluation/examples-and-data.md)** measures a program with
  `Example`s and a `Metric`.
- **[Optimization](../optimization/index.md)** improves a program from that
  measurement.
- **[Runtime](../runtime/runtime-context.md)** covers configuration,
  observability, persistence, and streaming.

If you already know Python DSPy, [Coming from DSPy](../coming-from-dspy.md) maps
the two libraries.
