package dspy

import munit.FunSuite
import dspy.clients._
import dspy.clients.openai.OpenAI
import dspy.utils.{DspyError, Settings}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import sttp.client3._
import sttp.client3.testing.SttpBackendStub

class OpenAIRetrySuite extends FunSuite {
  private def chatOk(body: String): String = {
    // minimal OpenAI-like response
    s"""
       {"choices":[{"message":{"content": ${ujson.Str(body).render()}}}]}
     """.stripMargin
  }

  test("OpenAI fails with HttpError on 404") {
    val backend: SttpBackend[Future, Any] =
      SttpBackendStub.asynchronousFuture
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
    val backend: SttpBackend[Future, Any] =
      SttpBackendStub.asynchronousFuture
        .whenAnyRequest
        .thenRespond("not json")

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
