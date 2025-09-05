package dspy.predictors

import munit.FunSuite
import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._
import dspy.predict.predictors.{BestOfN, Retry}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class BestOfNSuite extends FunSuite {
  class CountingLM extends LM {
    @volatile var i = 0
    def complete(prompt: Prompt, params: Map[String, String])(implicit ec: ExecutionContext): Future[Completion] = {
      i += 1
      val conf = i * 10
      Future.successful(Completion(s"{\"answer\":\"x\",\"confidence\":$conf}", ujson.Obj()))
    }
  }

  test("BestOfN selects highest confidence") {
    val sig = Signature(
      inputs = List(Field("q", "q")),
      outputs = List(Field("answer", "a"), Field("confidence", "c", kind = "int"))
    )
    val lm = new CountingLM
    def make(): Predict = new Predict(sig, lm)

    val best = new BestOfN(make, n = 3, select = BestOfN.byConfidence())
    best.forward(Map("q" -> "?"))
      .map { pred =>
        val c = pred.fields.get("confidence").flatMap(_.numOpt).map(_.toInt)
        assertEquals(c, Some(30))
      }
  }
}

