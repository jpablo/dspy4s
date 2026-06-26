# Generating llms.txt for a repository

This example builds an `llms.txt` summary of a code repository by composing four `ChainOfThought` predictors. It demonstrates typed signatures with `List[String]` fields and threading typed predictor outputs through an `Either` for-comprehension.

## Analysis signatures

```scala
--8<-- "tutorials/llms_txt_generation/LlmsTxtGeneration.scala:signatures"
```

Each `Spec` declares its inputs and outputs as typed fields. `List[String]` outputs such as `key_concepts` and `entry_points` map to Scala `List[String]`. The fields of `GenerateLLMsTxt` line up with the combined outputs of the two analysis signatures plus a generated `usage_examples` string.

## Composing the predictors

```scala
--8<-- "tutorials/llms_txt_generation/LlmsTxtGeneration.scala:analyzer"
```

`RepositoryAnalyzer` holds four `ChainOfThought` fields. Three are built from typed signatures via `Signature.of`, and `generateExamples` uses an inline string signature. `forward` runs them in sequence inside a for-comprehension over `Either[DspyError, String]`, so a failure in any step short-circuits. Each predictor's typed `output` feeds the next, and the final step returns the `llms_txt_content` string.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.llms_txt_generation.llmsTxtMain"
```

## Notes

Fetching the file tree, README, and package files over the GitHub API is out of scope. That step is plain HTTP I/O, so supply `fileTree`, `readmeContent`, and `packageFiles` to `forward` however you like.

Full source: [LlmsTxtGeneration.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/llms_txt_generation/LlmsTxtGeneration.scala)
