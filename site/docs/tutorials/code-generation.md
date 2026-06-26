# Code Generation from Documentation

This example learns a library's API from documentation text and generates worked code examples for a set of use cases. It demonstrates list-valued output fields, composing several `ChainOfThought` predictors, and threading their typed outputs through a plain Scala class.

## Signatures with list-valued fields

```scala
--8<-- "tutorials/sample_code_generation/SampleCodeGeneration.scala:signatures"
```

A `Spec` trait declares the input and output fields. Output fields typed as `OutputField[List[String]]` decode to `List[String]`, so a single predictor call returns several structured lists at once. The example defines three such specs: one to analyze documentation, one to generate code for a use case, and one to refine code given feedback.

## Composing ChainOfThought predictors

```scala
--8<-- "tutorials/sample_code_generation/SampleCodeGeneration.scala:agent-predictors"
```

`DocumentationLearningAgent` holds one `ChainOfThought` predictor per signature, built from `Signature.of[T]`. Each predictor is a field on the class; the agent's methods call them and map their typed outputs into the `LibraryInfo` and `GeneratedExample` case classes.

## Threading typed outputs

```scala
--8<-- "tutorials/sample_code_generation/SampleCodeGeneration.scala:learn-and-generate"
```

`learnAndGenerate` runs the full flow inside an `Either` for comprehension. It first analyzes the combined documentation into a `LibraryInfo`, then folds over the use cases, generating one `GeneratedExample` per case and accumulating them in a `Vector`. Any `DspyError` short-circuits the comprehension.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.sample_code_generation.sampleCodeGenerationMain"
```

## Notes

Out of scope: fetching documentation over HTTP, the interactive console session,
and saving results to JSON. `learnFromDocs` takes already-combined documentation
text as input instead of fetching it.

Full source: [SampleCodeGeneration.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/sample_code_generation/SampleCodeGeneration.scala)
