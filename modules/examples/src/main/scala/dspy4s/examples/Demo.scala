package dspy4s.examples

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.providers.OpenAiLanguageModel

/** Shared runner for the example `@main`s: initialize an OpenAI LM from the environment, install it
  * (plus a `ChatAdapter`) as the active [[RuntimeContext]], and run `body` with that context as the
  * ambient `given`. The model defaults to `$DSPY_MODEL` (or `gpt-5.5`); `OPENAI_API_KEY` must be set.
  *
  * Each ported example file has a uniquely-named `@main` (e.g. `modulesMain`) so that files sharing a
  * package don't collide on a single `main` entry point. Run one with, e.g.:
  *
  *   OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.modulesMain"
  */
object Demo:
  def withLm(body: RuntimeContext ?=> Unit): Unit =
    val model = sys.env.getOrElse("DSPY_MODEL", "gpt-5.5")
    OpenAiLanguageModel.fromEnv(model) match
      case Left(err) => sys.error(s"Could not initialize LM (is OPENAI_API_KEY set?): $err")
      case Right(lm) =>
        RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
          body(using RuntimeEnvironment.current)
        }
