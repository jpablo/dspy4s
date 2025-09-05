package dspy.examples

import dspy.clients._
import dspy.clients.openai.{OpenAI, OpenAIEmbeddings}
import dspy.retrievers.SimpleRetriever
import dspy.signatures._
import dspy.predict.Predict
import dspy.utils.Settings
import sttp.client4.httpclient.HttpClientFutureBackend

import scala.concurrent.ExecutionContext.Implicits.global

object RAGDemo {
  def main(args: Array[String]): Unit = {
    val settings = Settings.default

    val lmBackend  = HttpClientFutureBackend()
    val embBackend = lmBackend

    val lm  = new OpenAI("gpt-4o-mini", settings, lmBackend)
    val emb = new OpenAIEmbeddings("text-embedding-3-small", settings, embBackend)

    val retr = new SimpleRetriever(emb)
    val corpus = Seq(
      "py"   -> "Python is a popular programming language created by Guido van Rossum.",
      "scala"-> "Scala is a JVM language combining object-oriented and functional programming.",
      "js"   -> "JavaScript runs in browsers and on servers via Node.js."
    )
    val fitF = retr.fit(corpus)

    val question = "What JVM language mixes OO and FP?"
    val runF = for {
      _     <- fitF
      top2  <- retr.search(question, topK = 2)
      ctx    = top2.map(_._2).mkString("\n---\n")
      sig    = Signature.parse("question, context -> answer")
      pred   = new Predict(sig, lm)
      out   <- pred.forward(Map("question" -> question, "context" -> ctx))
    } yield out

    runF.onComplete { r => println(s"RAG Prediction: $r") }
    Thread.sleep(4000)
  }
}

