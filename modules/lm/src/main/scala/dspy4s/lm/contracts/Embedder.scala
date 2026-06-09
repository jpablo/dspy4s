package dspy4s.lm.contracts

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext

import scala.collection.mutable

/** Embedding-model abstraction (a port of `dspy.Embedder`, PORT_GAPS G-10) — the vectorization counterpart of
  * [[LanguageModel]]. One batched contract: a vector of texts in, one embedding row per text out (aligned by
  * index). Hosted providers (e.g. [[dspy4s.lm.providers.OpenAiEmbedder]]) and custom local models (via
  * [[Embedder.fromFunction]]) both implement it.
  *
  * Upstream's `Embedder` is one class that switches on `model: str | Callable` (litellm-hosted vs custom
  * function) and bakes in batching + request caching. dspy4s splits those concerns: this trait is the contract,
  * providers own their batching (they know their API limits), and [[Embedder.cached]] adds memoization. */
trait Embedder:
  /** Stable identifier (model name or a label for a custom function), for diagnostics. */
  def id: String

  /** Embed `texts`, returning one embedding row per input (aligned by index). */
  def embed(texts: Vector[String])(using RuntimeContext): Either[DspyError, Vector[Vector[Float]]]

object Embedder:

  /** Lift a plain batch function into an [[Embedder]] — upstream's `Embedder(callable)` path (e.g. a local
    * sentence-transformers encoder bridged into the JVM, or a deterministic test vectorizer). */
  def fromFunction(name: String)(f: Vector[String] => Vector[Vector[Float]]): Embedder = new Embedder:
    def id: String = name
    def embed(texts: Vector[String])(using RuntimeContext): Either[DspyError, Vector[Vector[Float]]] =
      Right(f(texts))

  /** Memoize `underlying` per TEXT (not per batch): a batch embeds only its cache misses and stitches the rest
    * from cache — finer-grained than upstream's whole-request cache (`caching=True`), and what retrieval
    * workloads want (the same corpus/query texts recur across calls). Not thread-safe; intended for the
    * single-threaded optimizer/retriever paths. */
  def cached(underlying: Embedder): Embedder = new Embedder:
    private val cache = mutable.HashMap.empty[String, Vector[Float]]
    def id: String    = s"${underlying.id}+cached"
    def embed(texts: Vector[String])(using RuntimeContext): Either[DspyError, Vector[Vector[Float]]] =
      val misses = texts.distinct.filterNot(cache.contains)
      val filled: Either[DspyError, Unit] =
        if misses.isEmpty then Right(())
        else underlying.embed(misses).map(rows => misses.iterator.zip(rows.iterator).foreach(cache.update))
      filled.map(_ => texts.map(cache))
