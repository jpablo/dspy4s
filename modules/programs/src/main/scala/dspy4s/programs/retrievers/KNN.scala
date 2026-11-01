package dspy4s.programs.retrievers

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.data.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.lm.contracts.Embedder
import zio.blocks.schema.DynamicValue

/** k-nearest-neighbors retrieval over a trainset (a port of `dspy.predict.knn.KNN`, PORT_GAPS G-10): each example's
  * INPUT fields are serialized to `"key: value | key2: value2"` and embedded once at construction; a query record is
  * serialized the same way and scored against every trainset vector by raw dot product (upstream uses plain `np.dot`,
  * deliberately unnormalized), returning the `k` highest-scoring examples, best first.
  *
  * Construction embeds eagerly (like upstream's `__init__`), so it is effectful — build via [[KNN.create]].
  */
final class KNN private (
    val k       : NeighborCount,
    val trainset: NonEmptyTrainset,
    embedder    : Embedder,
    trainVectors: Array[Array[Float]]
):
  /** The `k` trainset examples nearest to `inputs` (the query's input fields), best first. Ties break by the earlier
    * trainset index, deterministically.
    */
  def retrieve(inputs: DynamicValue.Record)(using RuntimeContext): Either[DspyError, Vector[Example]] =
    Similarity.topK(embedder, "knn", KNN.serialize(inputs), trainVectors, k).map(_.map((_, i) => trainset(i)))

object KNN:
  /** Embed every trainset example's input fields and assemble the retriever. */
  def create(k: NeighborCount, trainset: NonEmptyTrainset, embedder: Embedder)(using
      RuntimeContext
  ): Either[DspyError, KNN] =
    embedder.embed(trainset.map(ex => serialize(ex.inputs)))
      .map(rows => new KNN(k, trainset, embedder, Similarity.toMatrix(rows)))

  /** Upstream's example-to-text casting: `"key: value | key2: value2"` over the record's fields, in field order.
    * Callers pass the INPUT projection (`example.inputs`), matching upstream's `_input_keys` filter.
    */
  private[retrievers] def serialize(record: DynamicValue.Record): String =
    record.fields.iterator.map { case (key, value) => s"$key: ${DynamicValues.renderText(value)}" }.mkString(" | ")

/** Tiny shared vector math for the retrievers (corpora here are small; no BLAS needed). Vectors are held as primitive
  * `Array[Float]` internally — the retriever matrices are long-lived and on the per-query path, where boxed
  * `Vector[Float]` costs several times the memory plus an unboxing per multiply.
  */
private[retrievers] object Similarity:
  /** Convert embedder rows into the primitive matrix the retrievers store. */
  def toMatrix(rows: Vector[Vector[Float]]): Array[Array[Float]] =
    rows.iterator.map(_.toArray).toArray

  /** Embed `query` and score it against each row of `matrix`. The result has the `k` best rows as `(score, row index)`,
    * best first. A tie goes to the earlier row. With `normalizeQuery`, the function first normalizes the query vector
    * (L2). `component` names the retriever in the error of an embedder that returns no rows.
    */
  def topK(
      embedder      : Embedder,
      component     : String,
      query         : String,
      matrix        : Array[Array[Float]],
      k             : NeighborCount,
      normalizeQuery: Boolean = false
  )(using RuntimeContext): Either[DspyError, Vector[(Double, Int)]] =
    embedder.embed(Vector(query)).flatMap { rows =>
      rows.headOption.toRight(RuntimeError(component, "embedder returned no rows for the query")).map { row =>
        val q      = if normalizeQuery then normalize(row.toArray) else row.toArray
        val scored = Vector.tabulate(matrix.length)(i => (dot(q, matrix(i)), i))
        scored.sortBy { case (score, i) => (-score, i) }.take(k)
      }
    }

  def dot(a: Array[Float], b: Array[Float]): Double =
    var acc = 0.0
    var i   = 0
    val n   = math.min(a.length, b.length)
    while i < n do
      acc += a(i).toDouble * b(i).toDouble
      i += 1
    acc

  /** L2-normalize, leaving all-zero vectors untouched (upstream divides by `max(norm, eps)`). */
  def normalize(v: Array[Float]): Array[Float] =
    val norm = math.sqrt(dot(v, v))
    if norm <= 1e-10 then v else v.map(x => (x / norm).toFloat)
