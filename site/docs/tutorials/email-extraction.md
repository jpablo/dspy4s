# Email extraction

This example builds an email-analysis pipeline that classifies an email, pulls out structured entities, summarizes it, and decides what follow-up actions are needed. It demonstrates typed signatures over enums and case classes, and composing four `ChainOfThought` steps into one program that returns a single typed result.

## Typed enums and models

```scala
--8<-- "tutorials/email_extraction/EmailExtraction.scala:email-type"
```

An `enum` that derives `Schema` crosses the typed boundary as a wire string. `EmailType` and `UrgencyLevel` are produced as outputs by one step and consumed as inputs by later steps, so the value stays a checked Scala type the whole way through. Case classes such as `ExtractedEntity` derive `Schema` the same way and travel as JSON.

## Signatures

```scala
--8<-- "tutorials/email_extraction/EmailExtraction.scala:signatures"
```

Each signature is a trait extending `Spec` with `InputField` and `OutputField` members. Field types carry the structure: outputs can be enums (`EmailType`), lists of case classes (`List[ExtractedEntity]`), or optional values (`Option[Double]`). The pipeline uses four signatures: `ClassifyEmail`, `ExtractEntities`, `SummarizeEmail`, and `GenerateActionItems`.

## Composing the steps

```scala
--8<-- "tutorials/email_extraction/EmailExtraction.scala:processor"
```

`EmailProcessor` holds one `ChainOfThought` per signature, built with `Signature.of[...]`. Each call returns `Either[DspyError, ...]`, so `forward` threads the steps through a for-comprehension: a `Left` from any step short-circuits, and the success path reads typed fields off each step's `output` to assemble the final `EmailAnalysis`. The classification result feeds entity extraction, both feed the summary, and the three together feed action generation.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.email_extraction.emailExtractionMain"
```

## Notes

Integration with an external experiment-tracking server is out of scope.
Per-field description hints are not carried on the `Spec` surface, so any
field-level descriptions are dropped.

Full source: [EmailExtraction.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/email_extraction/EmailExtraction.scala)
