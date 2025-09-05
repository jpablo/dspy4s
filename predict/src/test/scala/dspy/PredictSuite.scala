package dspy

import munit.FunSuite
import dspy.signatures._
import dspy.predict.Predict
import dspy.clients._
import dspy.utils.DspyError

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class PredictSuite extends FunSuite {
  private class StubLM(reply: String) extends LM {
    override def complete(prompt: Prompt, params: Map[String, String])(implicit
        ec: ExecutionContext
    ): Future[Completion] = Future.successful(Completion(reply, ujson.Obj()))
  }

  test("Predict extracts JSON object from text output") {
    val sig = Signature(
      inputs = List(Field("question", "q")),
      outputs = List(Field("answer", "a"))
    )
    val lm  = new StubLM("Here you go: {\"answer\": \"Paris\"}")
    val p   = new Predict(sig, lm)
    val fut = p.forward(Map("question" -> "What is the capital of France?"))
    fut.map { pred =>
      assertEquals(pred.getString("answer"), Some("Paris"))
    }
  }

  test("Predict fails when required output keys are missing") {
    val sig = Signature(
      inputs = List(Field("question", "q")),
      outputs = List(Field("answer", "a"))
    )
    val lm = new StubLM("{\"not_answer\": \"Paris\"}")
    val p  = new Predict(sig, lm)
    val fut = p.forward(Map("question" -> "What is the capital of France?"))
    fut.map(_ => fail("expected ParseError for missing output keys")).recover { case e: DspyError.ParseError =>
      assert(clue(e.getMessage).contains("Missing output keys"))
    }
  }
}
