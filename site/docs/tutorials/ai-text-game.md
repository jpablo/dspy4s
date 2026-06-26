# Text-based AI game module

This example builds the core of a text adventure: three typed signatures for scene generation, NPC dialogue, and action resolution, composed into a single `GameAI` class that returns typed results.

## Signatures

```scala
--8<-- "tutorials/ai_text_game/AiTextGame.scala:signatures"
```

A `Spec` trait declares the inputs and outputs of a generation step. Fields are typed: `InputField[String]`, `OutputField[Int]`, `OutputField[List[String]]`, and `OutputField[Map[String, Int]]` all map to the corresponding Scala types, so the output structure is known at compile time. The example defines three such traits: `StoryGenerator`, `DialogueGenerator`, and `ActionResolver`.

## Composing the module

```scala
--8<-- "tutorials/ai_text_game/AiTextGame.scala:module"
```

`GameAI` holds one `ChainOfThought` predictor per signature, each built from `Signature.of[T]`. The class threads the predictors' outputs into its own methods rather than extending a base module type.

## Typed results

```scala
--8<-- "tutorials/ai_text_game/AiTextGame.scala:generate-scene"
```

Each method calls a predictor with a named-tuple of inputs and maps the result into a plain case class (`Scene`, `Dialogue`, or `ActionOutcome`). The predictor returns `Either[DspyError, Out]`, and `s.output` exposes the declared output fields with their declared types. `RuntimeContext` is passed implicitly.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.ai_text_game.aiTextGameMain"
```

## Notes

This page covers the typed signatures and the `GameAI` module that composes them. The surrounding game plumbing is out of scope: the `Player` and `GameContext` save/load JSON, and the console rendering, menus, character creation, and input game loop. Minimal `Player` and `GameContext` carriers are kept so the formatted-string inputs match what the signatures expect. Drive `GameAI` from your own loop, threading the returned `Scene`, `Dialogue`, and `ActionOutcome` values back into state.

Full source: [AiTextGame.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/ai_text_game/AiTextGame.scala)
