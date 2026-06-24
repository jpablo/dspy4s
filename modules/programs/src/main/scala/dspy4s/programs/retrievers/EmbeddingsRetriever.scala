package dspy4s.programs.retrievers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.Embedder

/** In-memory embedding-similarity retriever over a passage corpus (a port of `dspy.retrievers.Embeddings`,
  * PORT_GAPS G-10): the corpus is embedded once at construction (L2-normalized when `normalize`, the upstream
  * default — cosine similarity); a query is embedded, scored against every passage, and the top `k` are returned
  * with their corpus indices and scores, best first.
  *
  * ==Deltas from Python==
  *   - '''Brute force only.''' Upstream builds a FAISS IVFPQ index for corpora ≥ 20k passages; there is no JVM
  *     FAISS without a native dependency, and below that threshold upstream itself brute-forces — which this port
  *     does at any size (document-scale corpora belong in a real vector store anyway).
  *   - '''Synchronous, single-query `search`.''' Upstream wraps queries in `Unbatchify` (a background micro-batching
  *     thread); a concurrency optimization, not semantics.
  *
  * Construction embeds eagerly — build via [[EmbeddingsRetriever.create]]. */
final class EmbeddingsRetriever private (
    val corpus: Vector[String],
    embedder: Embedder,
    val k: Int,
    normalize: Boolean,
    corpusVectors: Vector[Vector[Float]]
):
  /** The top-`k` passages most similar to `query`, best first, with corpus indices and similarity scores. */
  def search(query: String)(using RuntimeContext): Either[DspyError, EmbeddingsRetriever.Result] =
    embedder.embed(Vector(query)).flatMap { rows =>
      rows.headOption.toRight(RuntimeError("embeddings_retriever", "embedder returned no rows for the query")).map { row =>
        val q      = if normalize then Similarity.normalize(row) else row
        val scored = corpusVectors.zipWithIndex.map { case (row, i) => (Similarity.dot(q, row), i) }
        val top    = scored.sortBy { case (score, i) => (-score, i) }.take(k)
        EmbeddingsRetriever.Result(
          passages = top.map { case (_, i) => corpus(i) },
          indices = top.map(_._2),
          scores = top.map(_._1)
        )
      }
    }

object EmbeddingsRetriever:
  /** Top-k search outcome; `passages`, `indices`, and `scores` are aligned, best first. */
  final case class Result(passages: Vector[String], indices: Vector[Int], scores: Vector[Double])

  /** Embed the corpus and assemble the retriever. */
  def create(corpus: Vector[String], embedder: Embedder, k: Int = 5, normalize: Boolean = true)(using
      RuntimeContext
  ): Either[DspyError, EmbeddingsRetriever] =
    require(k > 0, "EmbeddingsRetriever k must be > 0")
    require(corpus.nonEmpty, "EmbeddingsRetriever needs a non-empty corpus")
    embedder.embed(corpus).map { rows =>
      val vectors = if normalize then rows.map(Similarity.normalize) else rows
      new EmbeddingsRetriever(corpus, embedder, k, normalize, vectors)
    }
