package dspy.predictors

import munit.FunSuite
import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._
import dspy.predict.predictors.Retry

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class RetrySuite extends FunSuite {
  class FlakyLM(var remainingFailures: Int, good: String) extends LM {
    def complete(prompt: Prompt, params: Map[String, String])(implicit ec: ExecutionContext): Future[Completion] = {
      if (remainingFailures > 0) { remainingFailures -= 1; Future.successful(Completion("not json", ujson.Obj())) }
      else Future.successful(Completion(good, ujson.Obj()))
    }
  }

  test("Retry recovers from transient parse error and succeeds") {
    val sig = Signature.parse("q -> a")
    val lm  = new FlakyLM(remainingFailures = 1, good = "{\"a\":\"ok\"}")
    val p   = new Predict(sig, lm)
    val r   = new Retry(p, maxRetries = 2)
    r.forward(Map("q" -> "?"))
      .map { pred => assertEquals(pred.getString("a"), Some("ok")) }
  }
}

