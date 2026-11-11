package dspy4s.harness.cli

import dspy4s.harness.{Models, ScriptLm, ThinkingLm}
import dspy4s.lm.contracts.{LmRequest, Message, MessageRole}
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The laws of the trace of the model calls (`DSPY_TRACE`). A live test reads the trace to see the exact prompt and
  * reply of each step.
  */
class TraceLaws extends munit.FunSuite:

  private def request(text: String): LmRequest = LmRequest("fake-model", Vector(Message(MessageRole.User, Some(text))))

  private def lines(file: Path): Vector[String] = Files.readAllLines(file, UTF_8).asScala.toVector

  /** The field `key` of the JSON object `json`. */
  private def field(json: Json, key: String): Option[Json] = json.fields.collectFirst { case (k, v) if k == key => v }

  private def withFile[A](body: Path => A): A =
    val file = Files.createTempFile("dspy4s-trace-", ".jsonl")
    try body(file)
    finally Files.deleteIfExists(file): Unit

  test("Trace: each call writes one JSON line with the number of the call, the messages, the outputs and the time") {
    withFile { file =>
      val model = TracedModel(ScriptLm("FAKE-REPLY-1", "FAKE-REPLY-2"), file, identity)
      Models.withModel(model) {
        assert(model.call(request("FAKE-QUESTION-1")).isRight)
        assert(model.call(request("FAKE-QUESTION-2")).isRight)
      }
      val written = lines(file)
      assertEquals(written.size, 2, written.mkString("\n"))
      written.zipWithIndex.foreach { (line, n) =>
        val json = Json.parseUnsafe(line)
        assertEquals(field(json, "call").map(_.print), Some(n.toString), line)
        assert(field(json, "millis").nonEmpty, line)
        assert(field(json, "messages").exists(_.print.contains(s"FAKE-QUESTION-${n + 1}")), line)
        assert(field(json, "outputs").exists(_.print.contains(s"FAKE-REPLY-${n + 1}")), line)
      }
    }
  }

  test("Trace: a failed call writes its error, and the caller gets the same error") {
    withFile { file =>
      val model  = TracedModel(ScriptLm(), file, identity)
      val result = Models.withModel(model)(model.call(request("FAKE-QUESTION")))
      assert(result.left.exists(_.message.contains("no more replies")), result.toString)
      val written = lines(file)
      assertEquals(written.size, 1, written.mkString("\n"))
      assert(field(Json.parseUnsafe(written.head), "error").exists(_.print.contains("no more replies")), written.head)
    }
  }

  test("Trace: the mask hides a secret in the request and in the reply") {
    withFile { file =>
      val model = TracedModel(ScriptLm("the key is FAKE-SECRET-42"), file, _.replace("FAKE-SECRET-42", "***"))
      Models.withModel(model)(model.call(request("use FAKE-SECRET-42"))): Unit
      val text = Files.readString(file, UTF_8)
      assert(!text.contains("FAKE-SECRET-42"), text)
      assert(text.contains("***"), text)
    }
  }

  test("Trace: the traced model keeps the capabilities of the model, so the prompt does not change") {
    withFile { file =>
      val thinking = ThinkingLm("a fake thought", "FAKE-REPLY")
      val model    = TracedModel(thinking, file, identity)
      assertEquals(
        (model.id, model.mode, model.supportsReasoning, model.supportsFunctionCalling, model.supportsResponseSchema),
        (
          thinking.id,
          thinking.mode,
          thinking.supportsReasoning,
          thinking.supportsFunctionCalling,
          thinking.supportsResponseSchema
        )
      )
      Models.withModel(model)(model.call(request("FAKE-QUESTION"))): Unit
      assert(field(Json.parseUnsafe(lines(file).head), "outputs").exists(_.print.contains("a fake thought")))
    }
  }
