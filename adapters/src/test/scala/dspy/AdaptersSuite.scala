package dspy

import munit.FunSuite
import dspy.adapters.chat.ChatAdapter
import dspy.adapters.json.JsonAdapter
import dspy.signatures._
import dspy.clients._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class AdaptersSuite extends FunSuite {
  class CapturingLM(reply: String) extends LM {
    @volatile var lastPrompt: String = ""
    def complete(prompt: Prompt, params: Map[String, String])(implicit ec: ExecutionContext): Future[Completion] = {
      lastPrompt = prompt.content
      Future.successful(Completion(reply, ujson.Obj()))
    }
  }

  test("ChatAdapter renders instructions, inputs and demos") {
    val sig = Signature(
      inputs = List(Field("x", "x desc")),
      outputs = List(Field("y", "y desc")),
      instructions = Some("Do it.")
    )
    val lm   = new CapturingLM("{}")
    val chat = new ChatAdapter
    chat.call(lm, sig, Map("x" -> "1"), demos = List(Map("x" -> "0", "y" -> "zero")))
    val p = lm.lastPrompt
    assert(p.contains("Instructions:"))
    assert(p.contains("Inputs:"))
    assert(p.contains("Demos:"))
  }

  test("JsonAdapter extracts JSON object from surrounding text") {
    val sig = Signature.parse("q -> a")
    val lm  = new CapturingLM("Sure: {\"a\": \"ok\"}")
    val js  = new JsonAdapter
    js.call(lm, sig, Map("q" -> "?"))
      .map(m => assertEquals(m.get("a").flatMap(_.strOpt), Some("ok")))
  }
}

