/**
 * Language Models
 *
 * Source:   docs/docs/learn/programming/language_models.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/language_models.md
 * Status:   translated (LM setup, direct calls, use-with-modules, multiple LMs, generation config,
 *           usage/errors). dspy4s ships a single OpenAI-compatible provider ([[OpenAiLanguageModel]]), so
 *           the LiteLLM provider matrix collapses: OpenAI and any OpenAI-compatible endpoint (Azure / Ollama /
 *           vLLM / SGLang / OpenRouter) are supported via the `baseUrl` overload; Gemini / Anthropic / Vertex /
 *           Databricks (LiteLLM-only) are out of scope. Also out of scope: the Responses API (`model_type=
 *           "responses"` — dspy4s only has `LmMode.Chat`), and custom-LM save/load (`dump_state`/`load_state`/
 *           `copy`, since dspy4s has no program persistence).
 *
 * Key shape differences:
 *   - `dspy.configure(lm=…)` → `RuntimeEnvironment.configure(RuntimeContext(lm = Some(lm), adapter = Some(…)))`;
 *     `with dspy.context(lm=…):` → `RuntimeEnvironment.withSettings(ctx.copy(lm = Some(other))) { … }`.
 *   - dspy4s never throws on LM failure: `lm.call` / a program returns `Either[DspyError, …]`.
 *   - There is no global `lm.history`; per-call usage is on `LmResponse.usage`, and aggregate usage is via
 *     `ManagedLanguageModel` + `UsageTracking` (see tutorials/cache/Cache.scala).
 */
package dspy4s.examples.learn.programming

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, :=}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.examples.Demo
import dspy4s.lm.contracts.{LanguageModel, LmRequest, LmUsage, Message, MessageRole}
import dspy4s.lm.providers.{JdkHttpTransport, OpenAiLanguageModel}
import dspy4s.programs.ChainOfThought
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Signature

object LanguageModels:

  // ── Setup (lines 9–13) — configure a default LM ──
  // | lm = dspy.LM('openai/gpt-4o-mini')
  // | dspy.configure(lm=lm)
  // `dspy.LM('openai/<model>')` → `OpenAiLanguageModel.fromEnv("<model>")` (reads OPENAI_API_KEY). Installing it
  // globally is `RuntimeEnvironment.configure(RuntimeContext(lm = …, adapter = …))`. dspy4s needs an adapter too;
  // ChatAdapter is the default. (Demo.withLm does exactly this for the runnable examples here.)
  def configureDefault(model: String = "gpt-4o-mini"): Either[DspyError, Unit] =
    for
      lm <- OpenAiLanguageModel.fromEnv(model)
      _  <- RuntimeEnvironment.configure(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter())))
    yield ()

  // ── A few different LMs (lines 15–146) ──
  // | lm = dspy.LM('openai/gpt-4o-mini', api_key='YOUR_OPENAI_API_KEY')      # OpenAI
  // | lm = dspy.LM('openai/your-model', api_key='…', api_base='YOUR_URL')    # any OpenAI-compatible endpoint
  // dspy4s has ONE provider type — OpenAiLanguageModel — that speaks the /chat/completions shape, so it covers
  // OpenAI and every OpenAI-compatible server (Azure, Ollama, vLLM, SGLang, LM Studio, OpenRouter, …):
  def openAi(model: String, apiKey: String): LanguageModel =
    OpenAiLanguageModel(model, apiKey)

  // | # Local (Ollama / vLLM / SGLang) and other OpenAI-compatible providers: point base_url elsewhere.
  // | lm = dspy.LM("openai/meta-llama/Meta-Llama-3-8B-Instruct", api_base="http://localhost:7501/v1", api_key="")
  def openAiCompatible(model: String, apiKey: String, baseUrl: String): LanguageModel =
    OpenAiLanguageModel(model, apiKey, baseUrl, new JdkHttpTransport(timeoutMillis = 60000))

  // Gemini / Anthropic / Vertex AI / Databricks (and the rest of the LiteLLM matrix) have no dspy4s provider
  // and are out of scope — only OpenAI-compatible endpoints are supported.

  // ── Calling the LM directly (lines 148–155) ──
  // | lm("Say this is a test!", temperature=0.7)
  // | lm(messages=[{"role": "user", "content": "Say this is a test!"}])
  // The unified entry point is `LanguageModel.call(LmRequest)`, returning `Either[DspyError, LmResponse]`; read
  // the text off `response.outputs`. Per-call generation params go in the `options` bag (provider-bound) — see
  // `askWithConfig` below; omitted here so the call works regardless of the model's accepted `temperature` range.
  def callDirect(prompt: String)(using ctx: RuntimeContext): Either[DspyError, String] =
    ctx.lm match
      case Some(lm: LanguageModel) =>
        val request = LmRequest(
          model    = lm.id,
          messages = Vector(Message(role = MessageRole.User, text = Some(prompt)))
        )
        lm.call(request).map(_.outputs.headOption.map(_.text).getOrElse(""))
      case _ => Left(dspy4s.core.contracts.ConfigurationError("no LanguageModel configured"))

  // ── Using the LM with DSPy modules (lines 157–168) ──
  // | qa = dspy.ChainOfThought('question -> answer')
  // | response = qa(question="How many floors are in the castle David Gregory inherited?")
  // | print(response.answer)
  private def qa = ChainOfThought(Signature.fromString("question -> answer"))

  def ask(question: String)(using RuntimeContext): Either[DspyError, String] =
    qa.apply((question = question)).map(_.output.answer)

  // ── Using multiple LMs (lines 174–195) ──
  // | dspy.configure(lm=dspy.LM('openai/gpt-4o-mini'))           # global default
  // | with dspy.context(lm=dspy.LM('openai/gpt-3.5-turbo')):     # scoped override (thread-safe)
  // |     response = qa(question=…)
  // `dspy.context(lm=…)` is `RuntimeEnvironment.withSettings(ctx.copy(lm = Some(other))) { … }`: it overrides the
  // active context just for the block. Returns the question answered under each model.
  def askWithOverride(question: String, overrideModel: String)(using ctx: RuntimeContext)
      : Either[DspyError, (String, String)] =
    for
      base  <- ask(question) // current/global LM
      other <- OpenAiLanguageModel.fromEnv(overrideModel)
      scoped <- RuntimeEnvironment.withSettings(ctx.copy(lm = Some(other))) {
                  given RuntimeContext = RuntimeEnvironment.current
                  ask(question)
                }
    yield (base, scoped)

  // ── Configuring LM generation (lines 197–235) ──
  // | gpt = dspy.LM('openai/gpt-4o-mini', temperature=0.9, max_tokens=3000, stop=None, cache=False)
  // Init-time defaults live in `OpenAiLanguageModel.defaultOptions` (merged into every request; per-call
  // `LmRequest.options` win):
  def withGenerationDefaults(model: String): Either[DspyError, LanguageModel] =
    OpenAiLanguageModel.fromEnv(model).map(
      _.copy(defaultOptions = DynamicValues.record("temperature" := 0.9, "max_tokens" := 3000))
    )

  // | predict(question="What is 1 + 52?", config={"rollout_id": 5, "temperature": 1.0})
  // Per-call: pass an `options`/config bag for provider params (`temperature`, …), and use the typed `rolloutId`
  // for cache-busting — in dspy4s `rollout_id` is a first-class field on `TypedCall`, not a magic config key.
  def askWithConfig(question: String, temperature: Double, rolloutId: Int)(using RuntimeContext)
      : Either[DspyError, String] =
    qa.apply(TypedCall(
      input     = (question = question),
      config    = DynamicValues.record("temperature" := temperature),
      rolloutId = Some(rolloutId)
    )).map(_.output.answer)

  // ── Inspecting output and usage metadata (lines 238–251) ──
  // | len(lm.history); lm.history[-1].keys()  # global per-LM history with usage/cost/metadata
  // dspy4s has no global `lm.history`. Per-call usage rides on `LmResponse.usage`; aggregate usage across calls
  // is via `ManagedLanguageModel` + `UsageTracking` (see tutorials/cache/Cache.scala). Here we read the usage of
  // a single direct call:
  def callWithUsage(prompt: String)(using ctx: RuntimeContext): Either[DspyError, (String, Option[LmUsage])] =
    ctx.lm match
      case Some(lm: LanguageModel) =>
        lm.call(LmRequest(model = lm.id, messages = Vector(Message(MessageRole.User, Some(prompt)))))
          .map(r => (r.outputs.headOption.map(_.text).getOrElse(""), r.usage))
      case _ => Left(dspy4s.core.contracts.ConfigurationError("no LanguageModel configured"))

  // ── Handling LM errors (lines 253–269) ──
  // | try: answer = qa(question="…")
  // | except dspy.ContextWindowExceededError / dspy.LMRateLimitError / dspy.LMError as e: …
  // dspy4s never throws for an LM failure — every call returns `Either[DspyError, …]`, and `DspyError` carries a
  // stable `code` + `message`. (There are no dedicated rate-limit / context-window subclasses yet; provider
  // failures surface as `RuntimeError`.) Handle by matching the `Left`:
  def askHandlingErrors(question: String)(using RuntimeContext): String =
    ask(question) match
      case Right(answer) => answer
      case Left(err)     => s"LM failed: code=${err.code}, message=${err.message}"

  // ── Responses API (lines 271–298) / Advanced custom LMs (lines 301–345) ──
  // Out of scope: dspy4s has only `LmMode.Chat` (no `model_type="responses"`). You can write a custom provider
  // by implementing the `LanguageModel` trait, but there is no `dump_state`/`load_state`/`copy` persistence —
  // dspy4s programs aren't serialized.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.languageModelsMain"
@main def languageModelsMain(): Unit = Demo.withLm {
  val q = "How many floors are in the castle David Gregory inherited?"
  println("Direct call:   " + LanguageModels.callDirect("Say this is a test!"))
  println("Via module:    " + LanguageModels.ask(q))
  println("With config:   " + LanguageModels.askWithConfig(q, temperature = 1.0, rolloutId = 5))
  println("Usage:         " + LanguageModels.callWithUsage("Say this is a test!").map(_._2))
  println("Error-handled: " + LanguageModels.askHandlingErrors(q))
}
