package dspy

import munit.FunSuite
import dspy.clients._
import dspy.clients.openai.OpenAI
import dspy.utils.{DspyError, Settings}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import sttp.client4._
import sttp.client4.testing.BackendStub

class OpenAIRetrySuite extends FunSuite {
  private def chatOk(body: String): String = {
    // minimal OpenAI-like response
    s"""
       {"choices":[{"message":{"content": ${ujson.Str(body).render()}}}]}
     """.stripMargin
  }

  test("OpenAI fails with HttpError on 404") {
    val backend: Backend[Future] =
      BackendStub.asynchronousFuture
        .whenAnyRequest
        .thenRespondNotFound()

    val lm = new OpenAI(
      model = "gpt-4o-mini",
      settings = Settings(openaiApiKey = Some("test")),
      backend = backend
    )

    val fut = lm.complete(Prompt("Hello"))
    fut.map(_ => fail("expected HttpError")).recover { case e: DspyError.HttpError =>
      assertEquals(e.status, 404)
    }
  }

  test("OpenAI returns ParseError on invalid JSON body") {
    val backend: Backend[Future] =
      BackendStub.asynchronousFuture
        .whenAnyRequest
        .thenRespondOk()

    val lm = new OpenAI(
      model = "gpt-4o-mini",
      settings = Settings(openaiApiKey = Some("test")),
      backend = backend
    )

    val fut = lm.complete(Prompt("Hello"))
    fut.map(_ => fail("expected ParseError")).recover { case _: DspyError.ParseError =>
      assert(true)
    }
  }
}
