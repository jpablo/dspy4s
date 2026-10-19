package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext, RuntimeError}
import dspy4s.core.data.Example
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest, LmResponse, MessageRole}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

import scala.io.Source
import scala.util.Using

// The Scala form of the Python class `TypedSignature` of `scripts/golden/adapter_prompts.py`.
final case class PpQuestion(question: String) derives Schema
final case class PpAnswer(answer: String, count: Int, score: Double, ok: Boolean) derives Schema

/** The prompt of a typed signature with text, number, and bool fields is equal to the upstream prompt for the Python
  * form of the same signature (decision D3, PORT_GAPS G-30). The suite passes the output JSON schema of the signature,
  * as `Predict` does: the prompt must not show it in a block of its own, because upstream has no such block. Run
  * `scripts/golden/adapter_prompts.py` to write the golden data again.
  */
class TypedPromptParitySuite extends FunSuite:

  private final class StubLm extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private val golden: ujson.Value =
    ujson.read(Using.resource(Source.fromResource("golden/typed-prompts.json", getClass.getClassLoader))(_.mkString))

  private val signature = Signature.derived[PpQuestion, PpAnswer]("TypedSignature")

  private def roleName(role: MessageRole): String =
    role match
      case MessageRole.System    => "system"
      case MessageRole.User      => "user"
      case MessageRole.Assistant => "assistant"
      case MessageRole.Tool      => "tool"

  private def messagesOf(adapter: Adapter): Vector[(String, String)] =
    given RuntimeContext = RuntimeContext(lm = Some(StubLm()))
    val inputs           = DynamicValues.record("question" := "Who is Ana?")
    val invocation       = AdapterInvocation(
      layout = signature.layout,
      demos = Vector.empty,
      inputs = Example(values = inputs, inputKeys = Set("question")),
      request = LmRequest(model = "stub"),
      outputJsonSchema = signature.outputShape.jsonSchemaString
    )
    adapter.format(invocation) match
      case Right(prompt) => prompt.messages.map(message => roleName(message.role) -> message.text.getOrElse(""))
      case Left(error)   => fail(s"format failed: $error")

  private val adapters: Map[String, Adapter] = Map("ChatAdapter" -> ChatAdapter(), "JSONAdapter" -> JSONAdapter())

  golden("cases").arr.toVector.foreach { entry =>
    val name = entry("adapter").str
    test(s"$name: the prompt of a typed signature is equal to the upstream prompt") {
      val adapter  = adapters.getOrElse(name, fail(s"no adapter $name"))
      val expected = entry("messages").arr.toVector.map(message => message("role").str -> message("content").str)
      assertEquals(messagesOf(adapter), expected)
    }
  }
