# Configuring a language model

A program needs a language model to run. dspy4s ships one provider,
`OpenAiLanguageModel`, which speaks the OpenAI `/chat/completions` shape. That
covers OpenAI and every OpenAI-compatible server (Azure, Ollama, vLLM, SGLang,
LM Studio, OpenRouter).

## Constructing a model

Pass a model name and an API key:

```scala
--8<-- "learn/programming/LanguageModels.scala:lm-openai"
```

For a local server that does not check credentials, use `local` with the
server's base URL:

```scala
--8<-- "learn/programming/LanguageModels.scala:lm-local"
```

`OpenAiLanguageModel.fromEnv(model)` reads `OPENAI_API_KEY` from the environment,
which is what the bundled examples use.

## Installing it

A model becomes active by putting it in the [runtime
context](../runtime/runtime-context.md). The [Quickstart](../get-started/quickstart.md)
shows the full wiring with `RuntimeEnvironment.withSettings`.

## Calling a model directly

Most of the time a module calls the model for you. When you need the raw call,
use `LanguageModel.call`, which returns `Either[DspyError, LmResponse]`:

```scala
--8<-- "learn/programming/LanguageModels.scala:lm-direct"
```

## Per-call generation settings

Generation parameters such as `temperature` go in a per-call config bag.
`rolloutId` is a typed field used to bust the cache for an otherwise-identical
call:

```scala
--8<-- "learn/programming/LanguageModels.scala:lm-config"
```

## Errors

dspy4s never throws on a model failure. Every call returns an `Either`, and
`DspyError` carries a stable `code` and `message`:

```scala
--8<-- "learn/programming/LanguageModels.scala:lm-errors"
```

Next: [Adapters](adapters.md).
