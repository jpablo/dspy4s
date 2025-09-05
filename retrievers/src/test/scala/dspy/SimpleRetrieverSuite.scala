package dspy

import munit.FunSuite
import dspy.clients.embeddings.Embeddings
import dspy.retrievers.SimpleRetriever

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class SimpleRetrieverSuite extends FunSuite {
  class MockEmb extends Embeddings {
    def embed(texts: Seq[String])(implicit ec: ExecutionContext): Future[Seq[Array[Float]]] = Future.successful {
      texts.map { t =>
        if (t.toLowerCase.contains("alpha")) Array(1f, 0f)
        else if (t.toLowerCase.contains("beta")) Array(0f, 1f)
        else if (t.toLowerCase.contains("both")) Array(0.7f, 0.7f)
        else Array(0f, 0f)
      }
    }
  }

  test("search ranks by cosine similarity") {
    val retr = new SimpleRetriever(new MockEmb)
    val docs = Seq(
      "d1" -> "alpha doc",
      "d2" -> "beta doc",
      "d3" -> "both doc"
    )
    for {
      _ <- retr.fit(docs)
      res <- retr.search("alpha query", topK = 3)
    } yield {
      val ids = res.map(_._1)
      assertEquals(ids, Seq("d1", "d3", "d2"))
      assert(res.head._3 > res.last._3)
    }
  }
}

