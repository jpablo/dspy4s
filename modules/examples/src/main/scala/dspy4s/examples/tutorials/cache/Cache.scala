/**
 * Use and Customize DSPy Cache
 *
 * Source:   docs/docs/tutorials/cache/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/cache/index.md
 * Status:   translated (the caching + usage-tracking model, snippets 1/3/4/5/6/7/9). The key shape
 *           difference: dspy4s has no global `dspy.configure_cache(...)` / `dspy.cache` — caching is a
 *           *per-LM composition*. You wrap a `LanguageModel` in `ManagedLanguageModel(delegate, cache =
 *           Some(...))`, choosing the cache implementation: `NoopLmCache` (disable), `InMemoryLmCache`
 *           (memory), `DiskLmCache(dir)` (disk), or your own `LmCache`. Usage tracking (snippet 1's
 *           `track_usage=True`) is `RuntimeContext(trackUsage = Some(true))` read inside a
 *           `UsageTracking.withNewTracker`. The Anthropic `cache_control_injection_points` (snippet 2)
 *           is a provider-specific prompt-caching hint with no dspy4s surface and is out of scope.
 */
package dspy4s.examples.tutorials.cache

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.examples.Demo
import dspy4s.lm.contracts.{LanguageModel, LmCache, LmRequest, LmResponse, LmUsage}
import dspy4s.lm.runtime.{DiskLmCache, InMemoryLmCache, ManagedLanguageModel, NoopLmCache, RequestHash, UsageTracking}
import dspy4s.programs.Predict
import dspy4s.typed.Signature

import java.nio.file.Path
import scala.collection.concurrent.TrieMap

object Cache:

  private val qa = Signature.fromString("question -> answer")

  /** Install `lm` (+ a ChatAdapter) as the active context and answer one question. */
  def ask(lm: LanguageModel, question: String)(using RuntimeContext): Either[DspyError, String] =
    RuntimeEnvironment.withSettings(summon[RuntimeContext].copy(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      Predict(qa).apply((question = question)).map(_.output.answer)
    }

  // ── Snippets 3/4 — disable vs enable caching (≈ dspy.configure_cache) ──
  // | dspy.configure_cache(enable_disk_cache=False, enable_memory_cache=False)   # → NoopLmCache (or no cache)
  // | dspy.configure_cache(enable_disk_cache=True,  enable_memory_cache=True)    # → InMemory / Disk caches
  // --8<-- [start:cache-variants]
  def uncached(lm: LanguageModel): LanguageModel  = ManagedLanguageModel(lm, cache = Some(NoopLmCache))
  def memoryCached(lm: LanguageModel): LanguageModel = ManagedLanguageModel(lm, cache = Some(new InMemoryLmCache()))
  def diskCached(lm: LanguageModel, dir: Path): LanguageModel =
    ManagedLanguageModel(lm, cache = Some(new DiskLmCache(dir)))
  // --8<-- [end:cache-variants]

  // ── Snippet 1 — track token usage across calls; a cache hit reports no new usage ──
  // | dspy.configure(lm=..., track_usage=True); result.get_lm_usage()
  // The second call hits the memory cache, so it's fast and contributes no usage — exactly the snippet's point.
  // --8<-- [start:cache-usage]
  def usageAcrossCachedCalls(lm: LanguageModel, question: String)(using RuntimeContext)
      : Either[DspyError, Map[String, LmUsage]] =
    val managed = memoryCached(lm)
    UsageTracking.withNewTracker { tracker =>
      RuntimeEnvironment.withSettings(
        summon[RuntimeContext].copy(lm = Some(managed), adapter = Some(ChatAdapter()), trackUsage = Some(true))
      ) {
        given RuntimeContext = RuntimeEnvironment.current
        for
          _ <- Predict(qa).apply((question = question)) // miss: records usage
          _ <- Predict(qa).apply((question = question)) // hit: fast, no new usage
        yield tracker.totalUsage
      }
    }
  // --8<-- [end:cache-usage]

  // ── Snippets 5/6/7/9 — a custom cache: subclass `dspy.clients.Cache`, override the cache key ──
  // | class CustomCache(dspy.clients.Cache):
  // |     def cache_key(self, request, ...): return sha256(orjson.dumps(request["messages"], sort_keys)).hexdigest()
  // | dspy.cache = CustomCache(...)
  // The dspy4s analogue is implementing `LmCache`. This one keys *only on the messages* (ignoring model and
  // sampling options), so the same prompt to a different model still hits the cache.
  final class MessagesOnlyCache extends LmCache:
    private val store = TrieMap.empty[String, LmResponse]
    private def key(request: LmRequest): String = RequestHash.forRequest(LmRequest(model = "", messages = request.messages))
    override def get(request: LmRequest): Option[LmResponse] = store.get(key(request))
    override def put(request: LmRequest, response: LmResponse): Unit = { store.update(key(request), response); () }

  def customCached(lm: LanguageModel): LanguageModel = ManagedLanguageModel(lm, cache = Some(new MessagesOnlyCache))

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.cache.cacheMain"
@main def cacheMain(): Unit = Demo.withLm {
  // Demo installs a base LM in the context; rewrap it with a memory cache and ask twice.
  // Demo installs a base LM in the context; rewrap it with each cache and ask.
  RuntimeEnvironment.current.lm match
    case Some(base: LanguageModel) =>
      println("Memory-cached: " + Cache.ask(Cache.memoryCached(base), "Who is the GOAT of basketball?"))
      println("Custom-cached: " + Cache.ask(Cache.customCached(base), "Who is the GOAT of basketball?"))
      println("Usage:         " + Cache.usageAcrossCachedCalls(base, "Who is the GOAT of basketball?"))
    case _ => println("No LanguageModel in context.")
}
