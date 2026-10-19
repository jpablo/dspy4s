package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.core.contracts.Constraint
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.History
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.data.Example
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import scala.io.Source
import scala.util.Using

/** The prompts of the Chat, JSON, XML, and two-step adapters are equal to the upstream prompts, character for character
  * (decision D3, PORT_GAPS G-30). The expected messages come from upstream `Adapter.format`: run
  * `scripts/golden/adapter_prompts.py` to write them again. Each case of the golden data gives the signature, the
  * demos, and the inputs, so this suite builds each case from the data.
  *
  * The cases stay inside the boundary rule of D3: text, number, and bool fields, bare `list` and `dict` outputs,
  * descriptions, constraints, demos, the history, and tool calls. `PortTypeTextSuite` covers the text of the other
  * types. The XML data comes from the A6 form of the XML adapter (JSON inside the tag). No case in the scope differs
  * from upstream by A6; `differs_from_upstream_by_a6` marks such a case.
  */
class AdapterPromptParitySuite extends FunSuite:

  private final class StubLm extends LanguageModel:
    override val id: String                                                                    = "stub"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("stub", "unused in adapter tests"))

  private val golden: ujson.Value =
    ujson.read(Using.resource(Source.fromResource("golden/adapter-prompts.json", getClass.getClassLoader))(_.mkString))

  /** An extraction model that keeps the messages of each request and fails the call. */
  private final class CapturingLm extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "extraction-model"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests = requests :+ request
      Left(RuntimeError("stub", "the test reads the request only"))

  private def adapterOf(name: String, extraction: CapturingLm): Adapter =
    name match
      case "ChatAdapter"    => ChatAdapter()
      case "JSONAdapter"    => JSONAdapter()
      case "XMLAdapter"     => XMLAdapter()
      case "TwoStepAdapter" => TwoStepAdapter(extractionModel = extraction)
      case other            => fail(s"no adapter $other")

  /** The value of the typed form that the generator writes: `{"t": <type>, "v": <value>}`. */
  private def decode(value: ujson.Value): DynamicValue =
    value("t").str match
      case "none"    => DynamicValue.Null
      case "bool"    => DynamicValue.Primitive(PrimitiveValue.Boolean(value("v").bool))
      case "int"     => DynamicValue.Primitive(PrimitiveValue.Int(value("v").num.toInt))
      case "float"   => DynamicValue.Primitive(PrimitiveValue.Double(value("v").num))
      case "str"     => DynamicValue.Primitive(PrimitiveValue.String(value("v").str))
      case "list"    => DynamicValue.Sequence(Chunk.from(value("v").arr.map(decode)))
      case "dict"    => record(value)
      case "history" => summon[Schema[History]].toDynamicValue(History(value("v").arr.map(record).toVector))
      case other     => fail(s"no value of the type $other")

  private def record(value: ujson.Value): DynamicValue.Record =
    value("t").str match
      case "dict" => DynamicValue.Record(Chunk.from(value("v").arr.map(pair => pair(0).str -> decode(pair(1)))))
      case other  => fail(s"expected a dict, got $other")

  private def constraint(pair: ujson.Value): Constraint =
    val number = pair(1).num
    pair(0).str match
      case "gt"          => Constraint.Gt(number)
      case "ge"          => Constraint.Ge(number)
      case "lt"          => Constraint.Lt(number)
      case "le"          => Constraint.Le(number)
      case "min_length"  => Constraint.MinLength(number.toInt)
      case "max_length"  => Constraint.MaxLength(number.toInt)
      case "multiple_of" => Constraint.MultipleOf(number)
      case other         => fail(s"no constraint of the kind $other")

  private def layoutOf(entry: ujson.Value): SignatureLayout =
    val parsed                              = SignatureDsl.parse(entry("dsl").str).fold(error => fail(error.message), identity)
    val descriptions                        = entry("descriptions").obj.map((name, text) => name -> text.str).toMap
    val constraints                         = entry("constraints").obj.map((name, pairs) => name -> pairs.arr.map(constraint).toVector).toMap
    def update(field: FieldSpec): FieldSpec =
      field.copy(
        description = descriptions.get(field.name).orElse(field.description),
        constraints = constraints.getOrElse(field.name, field.constraints)
      )
    val instructions = entry("instructions").strOpt.orElse(parsed.instructions)
    SignatureLayout
      .create(
        name = parsed.name,
        inputFields = parsed.inputFields.map(update),
        outputFields = parsed.outputFields.map(update),
        instructions = instructions
      )
      .fold(error => fail(error.message), identity)

  private def roleName(role: MessageRole): String =
    role match
      case MessageRole.System    => "system"
      case MessageRole.User      => "user"
      case MessageRole.Assistant => "assistant"
      case MessageRole.Tool      => "tool"

  private def messagesOf(entry: ujson.Value, extraction: CapturingLm = CapturingLm()): Vector[(String, String)] =
    val layout     = layoutOf(entry)
    val inputNames = layout.inputFields.map(_.name).toSet
    val demos      = entry("demos").arr.toVector.map { demo =>
      val values = record(demo)
      Example(values = values, inputKeys = values.fields.map(_._1).filter(inputNames.contains).toSet)
    }
    val inputs     = record(entry("inputs"))
    val invocation = AdapterInvocation(
      layout = layout,
      demos = demos,
      inputs = Example(values = inputs, inputKeys = inputs.fields.map(_._1).toSet),
      request = LmRequest(model = "stub")
    )
    given RuntimeContext = RuntimeContext(lm = Some(StubLm()))
    adapterOf(entry("adapter").str, extraction).format(invocation) match
      case Right(prompt) => textsOf(prompt.messages)
      case Left(error)   => fail(s"format failed: $error")

  private def textsOf(messages: Vector[Message]): Vector[(String, String)] =
    messages.map(message => roleName(message.role) -> message.text.getOrElse(""))

  private def expectedOf(entry: ujson.Value, key: String): Vector[(String, String)] =
    entry(key).arr.toVector.map(message => message("role").str -> message("content").str)

  test("the golden data has each case for each adapter") {
    val cases = golden("cases").arr.toVector
    assertEquals(
      cases.map(_("adapter").str).toSet,
      Set("ChatAdapter", "JSONAdapter", "XMLAdapter", "TwoStepAdapter")
    )
    assert(cases.size >= 28, s"expected at least 28 cases, got ${cases.size}")
  }

  golden("cases").arr.toVector.foreach { entry =>
    val id      = entry("id").str
    val adapter = entry("adapter").str
    test(s"$adapter: the messages of the case '$id' are equal to the upstream messages") {
      assertEquals(messagesOf(entry), expectedOf(entry, "messages"))
    }
  }

  golden("cases").arr.toVector.filter(_("adapter").str == "TwoStepAdapter").foreach { entry =>
    val id = entry("id").str
    test(s"TwoStepAdapter: the extraction prompt of the case '$id' is equal to the upstream prompt") {
      val extraction       = CapturingLm()
      val layout           = layoutOf(entry)
      given RuntimeContext = RuntimeContext(lm = Some(StubLm()))
      val parsed           = TwoStepAdapter(extractionModel = extraction).parse(layout, LmOutput(entry("completion").str))
      assert(parsed.isLeft, s"expected the failure of the stub call, got $parsed")
      assertEquals(
        extraction.requests.map(request => textsOf(request.messages)),
        Vector(expectedOf(entry, "extraction_messages"))
      )
    }
  }
