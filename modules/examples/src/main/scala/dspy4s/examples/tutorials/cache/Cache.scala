/** Use and Customize DSPy Cache
  *
  * Source: docs/docs/tutorials/cache/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/cache/index.md Status: translated (the caching +
  * usage-tracking model, snippets 1/8/9/10/11/12/14; the numbers count the Python blocks of the page). The key shape
  * difference: dspy4s has no global `dspy.configure_cache(...)` / `dspy.cache`. The cache is a parameter of an LM
  * wrapper: you wrap a `LanguageModel` in `ManagedLanguageModel(delegate, cache = Some(...))`, and you select the cache
  * implementation: `NoopLmCache` (disable), `InMemoryLmCache` (memory), `DiskLmCache(dir)` (disk), or your own
  * `LmCache`; and a custom key with the `cacheKey` function. Usage tracking (snippet 1's `track_usage=True`) is a
  * `UsageTracking.withNewTracker` block: each model call of a program in the block counts, also with no
  * `ManagedLanguageModel`. `trackUsage` is on by default, and `RuntimeContext(trackUsage = Some(false))` turns off the
  * trackers.
  *
  * Three parts of the page are not in this file. The provider prompt caching (`prompt_cache` and
  * `cache_control_injection_points`, snippets 2/3/4) has no dspy4s surface. `restrict_pickle` and `safe_types`
  * (snippets 5/6/7) do not apply, because `DiskLmCache` writes JSON. Snippet 13 is snippet 14 with no custom cache, and
  * the main does not repeat it.
  */
package dspy4s.examples.tutorials.cache

import dspy4s.core.contracts.{DspyError, LmUsage, RuntimeContext}
import dspy4s.examples.Demo
import dspy4s.lm.contracts.{CacheKey, LanguageModel, LmCache, LmMode, LmRequest, NoopLmCache}
import dspy4s.lm.runtime.{DiskLmCache, InMemoryLmCache, ManagedLanguageModel, RequestHash, UsageTracking}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue

import java.nio.file.Path

object Cache:

  private val qa = Signature.fromString("question -> answer")

  /** Answer one question with `lm`. The result also has the usage of the call, and a cache hit has no usage. */
  def ask(lm: LanguageModel, question: String)(using
      RuntimeContext
  ): Either[DspyError, (answer: String, usage: Option[LmUsage])] =
    Predict(qa).withLm(lm)((question = question)).map(result =>
      (answer = result.output.answer, usage = result.raw.lmUsage)
    )

  // ── Snippets 8/9: disable and enable the caches (≈ dspy.configure_cache) ──
  // | dspy.configure_cache(enable_disk_cache=False, enable_memory_cache=False)   # → NoopLmCache (or no cache)
  // | dspy.configure_cache(enable_disk_cache=True,  enable_memory_cache=True)    # → InMemory / Disk caches
  // --8<-- [start:cache-variants]
  def uncached(lm    : LanguageModel): LanguageModel          = ManagedLanguageModel(lm, cache = Some(NoopLmCache))
  def memoryCached(lm: LanguageModel): LanguageModel          = ManagedLanguageModel(lm, cache = Some(new InMemoryLmCache()))
  def diskCached(lm: LanguageModel, dir: Path): LanguageModel =
    ManagedLanguageModel(lm, cache = Some(new DiskLmCache(dir)))
  // --8<-- [end:cache-variants]

  // ── Snippet 1: the usage of each of two calls. A cache hit reports no usage ──
  // | dspy.configure(lm=..., track_usage=True); result.get_lm_usage()
  // The second call hits the memory cache, so its usage is `None`. The total of the tracker has the first call only.
  // --8<-- [start:cache-usage]
  def usageAcrossCachedCalls(lm: LanguageModel, question: String)(using
      RuntimeContext
  ): Either[DspyError, (first: Option[LmUsage], second: Option[LmUsage], total: Map[String, LmUsage])] =
    val predict = Predict(qa).withLm(memoryCached(lm))
    UsageTracking.withNewTracker { tracker =>
      for
        first  <- predict((question = question)) // miss: the model gives the usage
        second <- predict((question = question)) // hit: no model call, so no usage
      yield (first = first.raw.lmUsage, second = second.raw.lmUsage, total = tracker.totalUsage)
    }
  // --8<-- [end:cache-usage]

  // ── Snippets 10/11/12/14: a custom cache: subclass `dspy.clients.Cache`, override the cache key ──
  // | class CustomCache(dspy.clients.Cache):
  // |     def cache_key(self, request, ...): return sha256(orjson.dumps(request["messages"], sort_keys)).hexdigest()
  // | dspy.cache = CustomCache(...)
  // The dspy4s analogue is the `cacheKey` function of `ManagedLanguageModel`. This key has only the messages and the
  // mode of the model. It does not have the model name or the sampling options.
  val messagesOnlyKey: (LmRequest, LmMode, DynamicValue.Record) => Either[DspyError, CacheKey] =
    (request, mode, _) => RequestHash.forRequest(LmRequest(model = "", messages = request.messages), mode)

  /** `lm` with the store `cache` and the key [[messagesOnlyKey]]. Two models that get one store share their answers. */
  def customCached(lm: LanguageModel, cache: LmCache): LanguageModel =
    ManagedLanguageModel(lm, cache = Some(cache), cacheKey = messagesOnlyKey)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.cache.cacheMain"
@main def cacheMain(): Unit =
  Demo.withOpenAi() { base =>
    // | result1 = predict(question="Who is the GOAT of basketball?")
    // | result2 = predict(question="Who is the GOAT of basketball?")
    Cache.usageAcrossCachedCalls(base, "Who is the GOAT of basketball?") match
      case Right(usage) =>
        println("First call usage:  " + usage.first)
        println("Second call usage: " + usage.second)
        println("Tracker total:     " + usage.total)
      case Left(error) => println("The usage example failed: " + error.message)

    // | result1 = predict(question="Who is the GOAT of volleyball?")
    // | with dspy.context(lm=dspy.LM("openai/gpt-4.1-mini")):
    // |     result2 = predict(question="Who is the GOAT of volleyball?")
    // The key has no model name, so the second model gets the answer of the first model, and it makes no call.
    val shared   = new InMemoryLmCache()
    val question = "Who is the GOAT of volleyball?"
    println("First model:  " + Cache.ask(Cache.customCached(base, shared), question))
    println("Second model: " + Cache.ask(Cache.customCached(base.copy(model = "gpt-4.1-mini"), shared), question))
  }
