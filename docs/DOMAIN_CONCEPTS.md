# dspy4s Domain Concepts

This document describes the ideas dspy4s is built around. It is not an API
reference. The goal is to explain the mental model: what the library thinks an
AI program is, what pieces it is made from, and how those pieces relate.

## The Big Idea

dspy4s treats language-model applications as programs, not as one-off prompts.

A program has a task, receives inputs, produces structured outputs, and can be
tested, evaluated, optimized, streamed, and composed with other programs. The
language model is important, but it is one part of the system rather than the
whole system.

The core loop is:

```text
describe the task -> run the model -> parse the result -> check or improve it
```

dspy4s gives names and structure to each part of that loop.

## Task Contracts

A task contract says what information a program needs and what it should
produce.

For example:

```text
question -> answer
```

or:

```text
document -> summary, confidence
```

This contract is not just documentation. It shapes the prompt, tells the parser
what to look for, and gives the rest of the program a stable structure to work
with.

In code, this idea appears as a signature.

## Fields

A field is one named piece of information in a task contract.

Some fields are inputs:

- `question`
- `document`
- `context`

Some fields are outputs:

- `answer`
- `summary`
- `score`
- `reasoning`

Fields let the program talk about model inputs and outputs as structured data
instead of as one undifferentiated string.

## Inputs

Inputs are the facts, requests, or context supplied by the caller.

They are the part of the task the program does not invent. If the task is
question answering, the question is an input. If the task is summarization, the
document is an input. If the task is judging an answer, the answer being judged
is usually an input.

Good inputs are explicit. They make it clear what the model is allowed to use.

## Outputs

Outputs are the structured results the program asks the model to produce.

An output can be simple:

```text
answer
```

or richer:

```text
answer, confidence, evidence
```

The important idea is that the model response is expected to be recoverable as
named values, not merely as prose.

## Predictions

A prediction is a program's answer for one run.

It is more than the final value. It can also carry useful execution details:

- the parsed output fields
- alternative completions
- token usage
- raw model output
- trace information

Conceptually, a prediction is the program saying: "Given these inputs, here is
what I believe the outputs are."

## Examples

An example is a concrete input/output record.

Examples can be used as:

- training data
- evaluation data
- few-shot demonstrations
- regression cases

For example:

```text
question: What is the capital of France?
answer: Paris
```

Examples are how dspy4s connects abstract task contracts to observed behavior.

## Demonstrations

A demonstration is an example shown to the model as part of the prompt.

The purpose is not to train model weights. It is to show the model the shape
and style of the task. Demonstrations answer questions like:

- What kind of answer is expected?
- How detailed should the response be?
- What counts as evidence?
- What should the output format look like?

Optimizers often work by choosing better demonstrations.

## Programs

A program is a reusable unit of AI behavior.

The simplest program takes inputs and predicts outputs. More advanced programs
may call tools, generate code, compare multiple attempts, retry failures, or
run several subprograms in parallel.

The key point is that a program is something you can call repeatedly and reason
about. It has a stable contract and observable behavior.

## Predict

Predict is the basic act of asking a language model to satisfy a task contract.

It combines:

- the task contract
- the caller's inputs
- optional demonstrations
- an adapter that formats and parses the exchange
- a language model that produces text

Most other concepts in dspy4s are built around predict calls.

## Reasoning

Reasoning is an explicit intermediate output that explains how the model got to
its answer.

In chain-of-thought style programs, the task is expanded from:

```text
question -> answer
```

to:

```text
question -> reasoning, answer
```

Reasoning is useful when the intermediate explanation helps the model solve the
task, helps humans inspect the result, or gives downstream code more context.

It is still just an output field. That is the important conceptual move:
chain-of-thought is a task-shaping pattern, not a separate kind of runtime.

## Tools

A tool is something outside the language model that a program can call.

Tools can provide capabilities such as:

- searching
- calculating
- fetching data
- running code
- calling an API

The model decides what it needs, the program invokes the tool, and the tool's
result becomes new context for the next step.

## Agents

An agent is a program that can take multiple steps.

Instead of producing the final answer immediately, it may:

1. think about what to do next
2. choose a tool
3. observe the tool result
4. repeat
5. produce a final answer

In dspy4s, ReAct-style programs are agents in this sense. They combine
reasoning, actions, observations, and final answers.

## Code as Reasoning

Some tasks are easier if the model writes code and the system executes it.

This is useful when the task involves:

- arithmetic
- data transformation
- parsing
- simulation
- step-by-step computation

The generated code is not the final answer by itself. It is an intermediate
artifact that helps produce the final structured output.

## Evaluation

Evaluation asks: how well did the program do?

It requires:

- examples to run against
- a metric or judge
- a way to aggregate results

Evaluation turns program behavior into feedback. That feedback can be used by
humans, tests, or optimizers.

## Optimization

Optimization improves a program without hand-editing every prompt.

In dspy4s, optimization usually means finding better demonstrations or prompt
configurations. An optimizer runs candidate programs, scores them, and keeps
the version that performs best.

This is one of DSPy's central ideas: prompts can be treated as program
parameters that are selected by feedback, not only by manual writing.

## Adapters

An adapter translates between structured task contracts and model text.

Before the model call, it decides how to present fields, instructions, and
examples. After the model call, it extracts structured outputs from the text.

Different adapters represent different communication styles:

- conversational sections
- JSON
- XML

The program's conceptual contract stays the same even if the adapter changes.

## Language Models

A language model is the text-generating engine used by a program.

dspy4s treats the model as swappable infrastructure. The program should not
have to know every provider detail. It asks for a prediction; the runtime sends
messages to the configured model and receives outputs.

This keeps the program focused on the task rather than the provider.

## Runtime Context

Runtime context is the environment a program runs in.

It answers questions like:

- Which language model should be used?
- Which adapter should format the prompt?
- Which callbacks are listening?
- Should calls be traced?
- What settings apply to this run?

Context keeps those choices out of the task definition itself.

## Traces and History

A trace is a record of what happened during execution.

It is useful for:

- debugging
- evaluation
- optimization
- explaining behavior

History is the broader execution log. Together, traces and history make AI
programs less opaque: they let you inspect not just the final answer, but the
path taken to get there.

## Streaming

Streaming means observing output as it is produced, rather than waiting for the
whole prediction to finish.

In a structured AI program, streaming is field-aware. Tokens can be associated
with fields like `reasoning` or `answer` as they arrive.

This matters for responsive user interfaces and for long-running agentic
programs where intermediate progress is useful.

## Errors

Errors are part of the domain because model programs have many contracts that
can fail:

- required inputs may be missing
- model output may not parse
- a value may have the wrong type
- a tool may fail
- runtime settings may be incomplete
- generated code may error

dspy4s tries to make expected failures explicit values rather than unexpected
crashes.

## Conceptual Boundaries

The main boundaries are:

- Task contracts describe what should happen.
- Programs decide how to carry out the task.
- Adapters translate between structure and model text.
- Language models generate candidate text.
- Predictions capture structured results.
- Examples and metrics provide feedback.
- Optimizers use feedback to improve programs.
- Runtime context supplies execution settings.

Keeping these boundaries clear is what makes the system composable. A better
adapter, a different model, a new optimizer, or a richer program strategy can
be introduced without changing the meaning of the task itself.

## Names You Will See in Code

The codebase uses concrete names for the concepts above:

| Concept | Common code name |
|---|---|
| Task contract | `Signature` |
| Runtime task contract | `SignatureLayout` |
| Field | `FieldSpec` |
| Program call | `ProgramCall` |
| Basic prediction program | `Predict` |
| Internal erased prediction program | `DynamicPredict` |
| Structured result | `Prediction` |
| Runtime structured result | `DynamicPrediction` |
| Demonstration/example | `Example` |
| Prompt/text translator | `Adapter` |
| Model interface | `LanguageModel` |
| Execution environment | `RuntimeContext` |
| Tool-using program | `ReAct` |
| Reasoning-augmented prediction | `ChainOfThought` |
