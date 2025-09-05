package dspy.retrievers

import dspy.clients.embeddings.Embeddings

import scala.concurrent.{ExecutionContext, Future}

final class SimpleRetriever(emb: Embeddings) {
  private var entries: Vector[(String, String, Array[Float])] = Vector.empty

  def fit(docs: Seq[(String, String)])(implicit ec: ExecutionContext): Future[Unit] = {
    emb.embed(docs.map(_._2)).map { vecs =>
      val normed = vecs.map(SimpleRetriever.normalize)
      entries = docs.zip(normed).map { case ((id, text), vec) => (id, text, vec) }.toVector
    }
  }

  def search(query: String, topK: Int)(implicit ec: ExecutionContext): Future[Seq[(String, String, Double)]] = {
    emb.embed(Seq(query)).map { qs =>
      val q = SimpleRetriever.normalize(qs.headOption.getOrElse(Array.emptyFloatArray))
      val scored = entries.map { case (id, text, v) =>
        (id, text, SimpleRetriever.dot(q, v).toDouble)
      }
      scored.sortBy(-_._3).take(topK)
    }
  }
}

object SimpleRetriever {
  private def l2(vec: Array[Float]): Float = {
    var sum = 0.0
    var i   = 0
    while (i < vec.length) { sum += vec(i) * vec(i); i += 1 }
    math.sqrt(sum).toFloat
  }
  def normalize(vec: Array[Float]): Array[Float] = {
    val n = l2(vec)
    if (n == 0f || n.isNaN) vec
    else {
      val out = new Array[Float](vec.length)
      var i   = 0
      while (i < vec.length) { out(i) = vec(i) / n; i += 1 }
      out
    }
  }
  def dot(a: Array[Float], b: Array[Float]): Float = {
    val len = math.min(a.length, b.length)
    var s   = 0.0f
    var i   = 0
    while (i < len) { s += a(i) * b(i); i += 1 }
    s
  }
}

