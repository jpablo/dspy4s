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

  test("OpenAI retries on 500 and then succeeds") {
    val backend: SttpBackend[Future, Any] =
      SttpBackendStub.asynchronousFuture
        .whenAnyRequest
        .thenRespondServerError()
        .whenAnyRequest
        .thenRespond(chatOk("{\"answer\": \"Paris\"}"))

    val lm = new OpenAI(
      model = "gpt-4o-mini",
      settings = Settings(openaiApiKey = Some("test")),
      backend = backend
    )

    val fut = lm.complete(Prompt("Hello"), Map("max_retries" -> "1", "backoff_ms" -> "0"))
    fut.map { c =>
      assert(c.text.contains("answer"))
    }
  }

  test("OpenAI does not retry on 400 and fails with HttpError") {
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
      assertEquals(e.status, 400)
    }
  }
}
