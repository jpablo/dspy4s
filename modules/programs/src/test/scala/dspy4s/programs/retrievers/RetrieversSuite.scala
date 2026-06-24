package dspy4s.programs.retrievers

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.Embedder
import munit.FunSuite

class RetrieversSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  /** Deterministic test embedder: looks up a fixed vector per text (fails loudly on an unmapped text, so the tests
    * also pin the exact serialization the retrievers produce). */
  private def mappedEmbedder(mapping: Map[String, Vector[Float]]): Embedder =
    Embedder.fromFunction("mapped") { texts =>
      texts.map(t => mapping.getOrElse(t, throw new IllegalArgumentException(s"unmapped text: '$t'")))
    }

  private def ex(q: String, a: String): Example =
    Example(DynamicValues.record("question" := q, "answer" := a), inputKeys = Set("question"))

  // ── KNN ─────────────────────────────────────────────────────────────────────────────────────────────────────

  test("KNN serializes INPUT fields only, as 'key: value | key2: value2'") {
    val example = Example(
      DynamicValues.record("question" := "q1", "hint" := "h1", "answer" := "SECRET"),
      inputKeys = Set("question", "hint")
    )
    assertEquals(KNN.serialize(example.inputs), "question: q1 | hint: h1") // answer (a label) is excluded
  }

  test("KNN retrieves the k nearest trainset examples by dot product, best first") {
    // Two clusters: q1/q2 near the x-axis, q3/q4 near the y-axis.
    val embedder = mappedEmbedder(Map(
      "question: q1" -> Vector(1.0f, 0.0f),
      "question: q2" -> Vector(0.9f, 0.1f),
      "question: q3" -> Vector(0.0f, 1.0f),
      "question: q4" -> Vector(0.1f, 0.9f),
      "question: near-x" -> Vector(1.0f, 0.05f)
    ))
    val trainset = Vector(ex("q1", "a1"), ex("q2", "a2"), ex("q3", "a3"), ex("q4", "a4"))
    val knn      = KNN.create(k = 2, trainset, embedder).toOption.get

    val nearest = knn.retrieve(DynamicValues.record("question" := "near-x")).toOption.get
    assertEquals(nearest.map(_.get("answer").map(DynamicValues.renderText).getOrElse("")), Vector("a1", "a2"))
  }

  test("KNN.create fails fast when the embedder fails (eager trainset embedding)") {
    val failing = Embedder.fromFunction("partial") { texts =>
      texts.map(t => if t == "question: q1" then Vector(1.0f) else throw new IllegalArgumentException(t))
    }
    intercept[IllegalArgumentException] {
      val _ = KNN.create(k = 1, Vector(ex("q1", "a1"), ex("q2", "a2")), failing)
    }
  }

  test("KNN.retrieve returns Left (not a NoSuchElementException) when the embedder yields no query row") {
    // A misbehaving embedder returns proper rows for the eager trainset embed but no row for the single query.
    val embedder = Embedder.fromFunction("empty-query") { texts =>
      if texts.sizeIs == 1 then Vector.empty else texts.map(_ => Vector(1.0f, 0.0f))
    }
    val knn    = KNN.create(k = 1, Vector(ex("q1", "a1"), ex("q2", "a2")), embedder).toOption.get
    val result = knn.retrieve(DynamicValues.record("question" := "q1"))
    assert(result.isLeft, s"empty embedder rows must surface as Left, not throw: $result")
  }

  // ── EmbeddingsRetriever ─────────────────────────────────────────────────────────────────────────────────────

  test("EmbeddingsRetriever returns top-k passages with indices and scores, best first") {
    val embedder = mappedEmbedder(Map(
      "alpha" -> Vector(1.0f, 0.0f),
      "beta"  -> Vector(0.0f, 1.0f),
      "gamma" -> Vector(0.7f, 0.7f),
      "query" -> Vector(1.0f, 0.1f)
    ))
    val retriever = EmbeddingsRetriever.create(Vector("alpha", "beta", "gamma"), embedder, k = 2).toOption.get
    val result    = retriever.search("query").toOption.get
    assertEquals(result.passages, Vector("alpha", "gamma"))
    assertEquals(result.indices, Vector(0, 2))
    assert(result.scores(0) >= result.scores(1), result.scores.toString)
  }

  test("normalization (the default) ranks by cosine; normalize=false ranks by raw dot (magnitude wins)") {
    // 'big' has a large magnitude along y; 'unit' points exactly at the query.
    val embedder = mappedEmbedder(Map(
      "big"   -> Vector(1.0f, 10.0f),
      "unit"  -> Vector(0.0f, 1.0f),
      "query" -> Vector(0.0f, 1.0f)
    ))
    val cosine = EmbeddingsRetriever.create(Vector("big", "unit"), embedder, k = 1).toOption.get
    assertEquals(cosine.search("query").toOption.get.passages, Vector("unit")) // perfect cosine match wins

    val rawDot = EmbeddingsRetriever.create(Vector("big", "unit"), embedder, k = 1, normalize = false).toOption.get
    assertEquals(rawDot.search("query").toOption.get.passages, Vector("big")) // 10.0 > 1.0 unnormalized
  }

  test("EmbeddingsRetriever.search returns Left (not a NoSuchElementException) when the embedder yields no query row") {
    val embedder = Embedder.fromFunction("empty-query") { texts =>
      if texts.sizeIs == 1 then Vector.empty else texts.map(_ => Vector(1.0f, 0.0f))
    }
    val retriever = EmbeddingsRetriever.create(Vector("alpha", "beta"), embedder, k = 1).toOption.get
    assert(retriever.search("query").isLeft, "empty embedder rows must surface as Left, not throw")
  }
