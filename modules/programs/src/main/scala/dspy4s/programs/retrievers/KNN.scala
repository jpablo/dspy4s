package dspy4s.programs.retrievers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.Embedder
import zio.blocks.schema.DynamicValue

/** k-nearest-neighbors retrieval over a trainset (a port of `dspy.predict.knn.KNN`, PORT_GAPS G-10): each example's
  * INPUT fields are serialized to `"key: value | key2: value2"` and embedded once at construction; a query record
  * is serialized the same way and scored against every trainset vector by raw dot product (upstream uses plain
  * `np.dot`, deliberately unnormalized), returning the `k` highest-scoring examples, best first.
  *
  * Construction embeds eagerly (like upstream's `__init__`), so it is effectful — build via [[KNN.create]]. */
final class KNN private (
    val k: Int,
    val trainset: Vector[Example],
    embedder: Embedder,
    trainVectors: Vector[Vector[Float]]
):
  /** The `k` trainset examples nearest to `inputs` (the query's input fields), best first. Ties break by the
    * earlier trainset index, deterministically. */
  def retrieve(inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, Vector[Example]] =
    embedder.embed(Vector(KNN.serialize(inputs))).map { queryRows =>
      val query  = queryRows.head
      val scored = trainVectors.zipWithIndex.map { case (row, i) => (Similarity.dot(query, row), i) }
      scored.sortBy { case (score, i) => (-score, i) }.take(k).map { case (_, i) => trainset(i) }
    }

object KNN:
  /** Embed every trainset example's input fields and assemble the retriever. */
  def create(k: Int, trainset: Vector[Example], embedder: Embedder)(using
      RuntimeContext
  ): Either[DspyError, KNN] =
    require(k > 0, "KNN k must be > 0")
    require(trainset.nonEmpty, "KNN needs a non-empty trainset")
    embedder.embed(trainset.map(ex => serialize(ex.inputs))).map(new KNN(k, trainset, embedder, _))

  /** Upstream's example-to-text casting: `"key: value | key2: value2"` over the record's fields, in field order.
    * Callers pass the INPUT projection (`example.inputs`), matching upstream's `_input_keys` filter. */
  private[retrievers] def serialize(record: DynamicValue.Record): String =
    record.fields.iterator.map { case (key, value) => s"$key: ${DynamicValues.renderText(value)}" }.mkString(" | ")

/** Tiny shared vector math for the retrievers (corpora here are small; no BLAS needed). */
private[retrievers] object Similarity:
  def dot(a: Vector[Float], b: Vector[Float]): Double =
    var acc = 0.0
    var i   = 0
    val n   = math.min(a.size, b.size)
    while i < n do
      acc += a(i).toDouble * b(i).toDouble
      i += 1
    acc

  /** L2-normalize, leaving all-zero vectors untouched (upstream divides by `max(norm, eps)`). */
  def normalize(v: Vector[Float]): Vector[Float] =
    val norm = math.sqrt(dot(v, v))
    if norm <= 1e-10 then v else v.map(x => (x / norm).toFloat)
