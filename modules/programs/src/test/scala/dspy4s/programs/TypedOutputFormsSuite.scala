package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterDemos, ToolSchemaBridge, ToolSpec}
import dspy4s.adapters.{ChatAdapter, JSONAdapter, XMLAdapter}
import dspy4s.core.contracts.{:=, CodeResult, DspyError, DynamicValues, PythonText, ReplCodeInterpreter, RuntimeContext}
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, MessageRole}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.internal.ToolText
import dspy4s.programs.optimization.ProgramPersistence
import dspy4s.programs.strategies.{ChainOfThought, Predict, ReActV2, RLM}
import dspy4s.signatures.{Shape, Signature}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ArrayBuffer

// Top-level fixtures (Mirror derivation needs them outside the suite).
sealed trait TofShape
object TofShape:
  final case class Circle(radius: Double) extends TofShape
  final case class Square(side: Double)   extends TofShape
  given Schema[TofShape] = Schema.derived

enum TofKind:
  case alpha, beta
object TofKind:
  given Schema[TofKind] = Schema.derived

final case class TofInput(question: String) derives Schema

final case class TofOutput(shape: TofShape, pair: (Int, String), at: Instant, id: UUID, kind: TofKind)
object TofOutput:
  given Schema[TofOutput] = Schema.derived

final case class TofRichInput(shape: TofShape, pair: (Int, String), at: Instant, id: UUID)
object TofRichInput:
  given Schema[TofRichInput] = Schema.derived

final case class TofAnswer(answer: String) derives Schema

object TofTuples:
  given pairSchema: Schema[(Int, String)] = Schema.derived

sealed trait TofColor
object TofColor:
  case object Blue  extends TofColor
  sealed trait Warm extends TofColor
  case object Red   extends Warm
  given Schema[TofColor] = Schema.derived

final case class TofNote(text: String, extra: Option[String])
object TofNote:
  given Schema[TofNote] = Schema.derived

sealed trait TofTree
object TofTree:
  final case class Leaf(pair: (Int, TofNote))    extends TofTree
  final case class Node(children: List[TofTree]) extends TofTree
  given Schema[TofTree] = Schema.derived

final case class TofNested(
    pair : (Int, TofNote),
    maybe: Option[(Int, String)],
    tree : TofTree,
    at   : Option[Instant],
    many : List[(Int, Int)],
    color: TofColor,
    note : Option[TofNote]
)
object TofNested:
  given Schema[TofNested] = Schema.derived

final case class TofDemoOutput(shape: TofShape, pair: (Int, String), at: Instant, color: TofColor)
object TofDemoOutput:
  given Schema[TofDemoOutput] = Schema.derived

sealed trait TofMixed
object TofMixed:
  final case class Circle(radius: Double) extends TofMixed
  case object Dot                         extends TofMixed
  given Schema[TofMixed] = Schema.derived

final case class TofMixedOutput(shape: TofMixed)
object TofMixedOutput:
  given Schema[TofMixedOutput] = Schema.derived

final case class TofMaybeMixedOutput(shape: Option[TofMixed])
object TofMaybeMixedOutput:
  given Schema[TofMaybeMixedOutput] = Schema.derived

final case class TofSubmitted(color: TofColor, note: Option[String])
object TofSubmitted:
  given Schema[TofSubmitted] = Schema.derived

final case class TofExact(exact: BigDecimal, big: BigInt)
object TofExact:
  given Schema[TofExact] = Schema.derived

final case class TofSaved(
    shape: TofShape,
    whole: TofShape,
    pair : (Int, String),
    at   : Instant,
    color: TofColor,
    kind : TofKind,
    some : Option[String],
    none : Option[String]
)
object TofSaved:
  given Schema[TofSaved] = Schema.derived

final case class TofOptionalText(note: Option[String])
object TofOptionalText:
  given Schema[TofOptionalText] = Schema.derived

final case class TofOptionalNumber(n: Option[Int])
object TofOptionalNumber:
  given Schema[TofOptionalNumber] = Schema.derived

final case class TofCounted(kinds: Map[TofKind, Int], colors: Map[TofColor, Int])
object TofCounted:
  given Schema[TofCounted] = Schema.derived

final case class TofUserId(value: String)
object TofUserId:
  given Schema[TofUserId] = Schema[String].transform(TofUserId(_), _.value)

final case class TofWrappedOutput(user: TofUserId)
object TofWrappedOutput:
  given Schema[TofWrappedOutput] = Schema.derived

/** The values that the schema of a typed signature offers reach the typed output in each reply format, and the prompt
  * describes them. The reply comes after one model call: these forms need no JSON fallback.
  */
class TypedOutputFormsSuite extends FunSuite:

  private class Script(replies: String*) extends LanguageModel:
    override val id: String              = "scripted"
    override val mode: LmMode            = LmMode.Chat
    val requests: ArrayBuffer[LmRequest] = ArrayBuffer.empty

    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      Right(LmResponse(outputs = Vector(LmOutput(text = replies(requests.size - 1)))))

  private def withModel[A](model: LanguageModel, adapter: Adapter)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(adapter))) {
      body(using RuntimeEnvironment.current)
    }

  private def signature: Signature[TofInput, TofOutput] =
    Signature.derived[TofInput, TofOutput](name = "Forms", instructions = "Give the values.")

  private def program: Predict[TofInput, TofOutput] = Predict(signature)

  private def expected: TofOutput =
    TofOutput(
      shape = TofShape.Circle(1.5),
      pair = (1, "a"),
      at = Instant.parse("2024-01-02T03:04:05Z"),
      id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
      kind = TofKind.beta
    )

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  test("the chat format gives each value after one call") {
    val model = Script(
      "[[ ## shape ## ]]\n{\"Circle\": {\"radius\": 1.5}}\n\n[[ ## pair ## ]]\n[1, \"a\"]\n\n" +
        "[[ ## at ## ]]\n2024-01-02T03:04:05Z\n\n[[ ## id ## ]]\n123e4567-e89b-12d3-a456-426614174000\n\n" +
        "[[ ## kind ## ]]\nbeta\n\n[[ ## completed ## ]]"
    )
    assertEquals(withModel(model, ChatAdapter())(program(TofInput("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("the JSON format gives each value after one call") {
    val model = Script(
      """{"shape": {"Circle": {"radius": 1.5}}, "pair": [1, "a"], "at": "2024-01-02T03:04:05Z",
        |"id": "123e4567-e89b-12d3-a456-426614174000", "kind": "beta"}""".stripMargin
    )
    assertEquals(withModel(model, JSONAdapter())(program(TofInput("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("the XML format gives each value after one call") {
    val model = Script(
      """<shape>{"Circle": {"radius": 1.5}}</shape><pair>[1, "a"]</pair><at>2024-01-02T03:04:05Z</at>
        |<id>123e4567-e89b-12d3-a456-426614174000</id><kind>beta</kind>""".stripMargin
    )
    assertEquals(withModel(model, XMLAdapter())(program(TofInput("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("a time value that the model writes as a quoted text in the chat format decodes") {
    val model = Script(
      "[[ ## shape ## ]]\n{\"Circle\": {\"radius\": 1.5}}\n\n[[ ## pair ## ]]\n[1, \"a\"]\n\n" +
        "[[ ## at ## ]]\n\"2024-01-02T03:04:05Z\"\n\n[[ ## id ## ]]\n\"123e4567-e89b-12d3-a456-426614174000\"\n\n" +
        "[[ ## kind ## ]]\nbeta\n\n[[ ## completed ## ]]"
    )
    assertEquals(withModel(model, ChatAdapter())(program(TofInput("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 1)
  }

  test("the strict response format takes the schema of each form") {
    val model = new Script(
      """{"shape": {"Circle": {"radius": 1.5}}, "pair": [1, "a"], "at": "2024-01-02T03:04:05Z",
        |"id": "123e4567-e89b-12d3-a456-426614174000", "kind": "beta"}""".stripMargin
    ):
      override def supportsResponseSchema: Boolean = true
    assertEquals(withModel(model, JSONAdapter())(program(TofInput("q"))).map(_.output), Right(expected))
    val format = DynamicValues.recordGet(model.requests.head.options, "response_format").getOrElse(fail("no format"))
    val text   = PythonText.json(format)
    assert(text.contains("\"type\": \"json_schema\""), text)
    assert(text.contains("\"anyOf\""), text)
    assert(!text.contains("\"oneOf\""), text)
    assert(text.contains("\"prefixItems\""), text)
    assert(text.contains("\"items\": false"), text)
    assert(text.contains("\"minItems\": 2"), text)
    assert(text.contains("\"maxItems\": 2"), text)
    assert(text.contains("\"date-time\""), text)
  }

  /** Each object schema in `value` is closed, and it has each of its properties in `required`. */
  private def assertClosed(value: DynamicValue): Unit =
    value match
      case record: DynamicValue.Record =>
        val isObject = DynamicValues.recordGet(record, "type").exists {
          case types: DynamicValue.Sequence => types.elements.exists(DynamicValues.asString(_).contains("object"))
          case single                       => DynamicValues.asString(single).contains("object")
        }
        if isObject then
          assertEquals(DynamicValues.recordGet(record, "additionalProperties").map(PythonText.json(_)), Some("false"))
          val names = DynamicValues.recordGet(record, "properties").collect { case found: DynamicValue.Record =>
            PythonText.json(DynamicValues.sequence(DynamicValues.recordKeys(found).map(DynamicValues.string)))
          }
          assertEquals(DynamicValues.recordGet(record, "required").map(PythonText.json(_)), names)
        record.fields.foreach((_, inner) => assertClosed(inner))
      case sequence: DynamicValue.Sequence => sequence.elements.foreach(assertClosed)
      case _                               => ()

  test("the strict shape closes each object in a tuple, in a case, in an Option, and in a definition") {
    val nested = Signature.derived[TofInput, TofNested](name = "Nested", instructions = "Give the values.")
    val model  = new Script(
      """{"pair": [1, {"text": "t", "extra": null}], "maybe": null,
        |"tree": {"Node": {"children": [{"Leaf": {"pair": [2, {"text": "u", "extra": "e"}]}}]}},
        |"at": null, "many": [[3, 4]], "color": "Red", "note": {"text": "v", "extra": null}}""".stripMargin
    ):
      override def supportsResponseSchema: Boolean = true
    assertEquals(
      withModel(model, JSONAdapter())(Predict(nested)(TofInput("q"))).map(_.output),
      Right(TofNested(
        (1, TofNote("t", None)),
        None,
        TofTree.Node(List(TofTree.Leaf((2, TofNote("u", Some("e")))))),
        None,
        List((3, 4)),
        TofColor.Red,
        Some(TofNote("v", None))
      ))
    )
    val format = DynamicValues.recordGet(model.requests.head.options, "response_format").getOrElse(fail("no format"))
    assert(PythonText.json(format).contains("\"json_schema\""), PythonText.json(format))
    assert(PythonText.json(format).contains("\"type\": [\"null\", \"object\"]"), PythonText.json(format))
    assertClosed(format)
  }

  test("a tuple as the output type is a JSON object of its fields in the prompt, in a demo, and in the request") {
    val tuple = Signature.derived[TofInput, (Int, String)](name = "Tuple", instructions = "Give the values.")
    val demo  = Example("question" := "q", "_1" := 7, "_2" := "z")
    val model = new Script("""{"_1": 1, "_2": "a"}"""):
      override def supportsResponseSchema: Boolean = true
    assertEquals(
      withModel(model, JSONAdapter())(Predict(tuple, demos = Vector(demo))(TofInput("q"))).map(_.output),
      Right((1, "a"))
    )
    val text = promptOf(model.requests.head)
    assert(text.contains(""""_1": "{_1}"""), text)
    assert(text.contains(""""_1": 7"""), text)
    val format = DynamicValues.recordGet(model.requests.head.options, "response_format").getOrElse(fail("no format"))
    val schema = PythonText.json(format)
    assert(schema.contains("\"_1\": {\"type\": \"integer\"}"), schema)
  }

  private def demoSignature: Signature[TofInput, TofDemoOutput] =
    Signature.derived[TofInput, TofDemoOutput](name = "Demo", instructions = "Give the values.")

  private def demoOutput: TofDemoOutput =
    TofDemoOutput(TofShape.Circle(1.5), (1, "a"), Instant.parse("2024-01-02T03:04:05Z"), TofColor.Red)

  private def demoReply: String =
    """{"shape": {"Circle": {"radius": 1.5}}, "pair": [1, "a"], "at": "2024-01-02T03:04:05Z", "color": "Red"}"""

  private def demo: Example =
    Example(("question" := "q") +: demoSignature.outputShape.encode(demoOutput).fields.toVector*)

  test("a demo shows each value in the form of its schema, in the chat format and in the JSON format") {
    // The chat format reads no section in the reply, so the JSON fallback reads the second reply.
    val chat = Script(demoReply, demoReply)
    assertEquals(
      withModel(chat, ChatAdapter())(Predict(demoSignature, demos = Vector(demo))(TofInput("q"))).map(_.output),
      Right(demoOutput)
    )
    val chatText = promptOf(chat.requests.head)
    assert(chatText.contains("[[ ## pair ## ]]\n[1, \"a\"]"), chatText)
    assert(chatText.contains("[[ ## shape ## ]]\n{\"Circle\": {\"radius\": 1.5}}"), chatText)
    assert(chatText.contains("[[ ## at ## ]]\n2024-01-02T03:04:05Z"), chatText)
    assert(chatText.contains("[[ ## color ## ]]\nRed"), chatText)

    val json = Script(demoReply)
    assertEquals(
      withModel(json, JSONAdapter())(Predict(demoSignature, demos = Vector(demo))(TofInput("q"))).map(_.output),
      Right(demoOutput)
    )
    val assistant = json.requests.head.messages.filter(_.role == MessageRole.Assistant).flatMap(_.text).head
    assertEquals(PythonText.parseJson(assistant), PythonText.parseJson(demoReply))
  }

  private def savedSignature: Signature[TofInput, TofSaved] =
    Signature.derived[TofInput, TofSaved](name = "Saved", instructions = "Give the values.")

  private def savedDemo(value: TofSaved): Example =
    Example(("question" := "q") +: savedSignature.outputShape.encode(value).fields.toVector*)

  private def savedDemos: Vector[Example] =
    Vector(
      savedDemo(TofSaved(
        TofShape.Circle(1.5),
        TofShape.Square(2.0),
        (1, "a"),
        Instant.parse("2024-01-02T03:04:05Z"),
        TofColor.Red,
        TofKind.beta,
        Some("x"),
        None
      ))
    )

  private def promptsBeforeAndAfterLoad(adapter: Adapter): (String, String) =
    val trained = Predict(savedSignature, demos = savedDemos, name = Some("ask"))
    val loaded  = ProgramPersistence
      .loadJson(Predict(savedSignature, name = Some("ask")), ProgramPersistence.dumpJson(trained))
      .fold(error => fail(error.message), identity)
    val before = Script("{}", "{}")
    val after  = Script("{}", "{}")
    val _      = withModel(before, adapter)(trained(TofInput("q")))
    val _      = withModel(after, adapter)(loaded(TofInput("q")))
    (promptOf(before.requests.head), promptOf(after.requests.head))

  test("a saved demo with each form shows the same prompt after a load, in the chat format") {
    val (before, after) = promptsBeforeAndAfterLoad(ChatAdapter())
    assertEquals(after, before)
    assert(before.contains("[[ ## color ## ]]\nRed"), before)
    assert(before.contains("[[ ## pair ## ]]\n[1, \"a\"]"), before)
    assert(before.contains("[[ ## kind ## ]]\nbeta"), before)
    assert(before.contains("[[ ## some ## ]]\nx"), before)
    assert(before.contains("[[ ## none ## ]]\nNone"), before)
    assert(before.contains("[[ ## whole ## ]]\n{\"Square\": {\"side\": 2.0}}"), before)
  }

  test("a saved demo with each form shows the same prompt after a load, in the JSON format") {
    val (before, after) = promptsBeforeAndAfterLoad(JSONAdapter())
    assertEquals(after, before)
    assert(before.contains("\"color\": \"Red\""), before)
    assert(before.contains("\"none\": null"), before)
  }

  test("an Option input with no value shows None, and a demo with a null is incomplete, as upstream") {
    val rich   = Signature.derived[TofSubmitted, TofAnswer](name = "Rich", instructions = "Answer.")
    val model  = Script("[[ ## answer ## ]]\nok\n\n[[ ## completed ## ]]")
    val result = withModel(model, ChatAdapter())(Predict(rich)(TofSubmitted(TofColor.Blue, None)))
    assert(result.isRight, result.toString)
    assert(promptOf(model.requests.head).contains("[[ ## note ## ]]\nNone"), promptOf(model.requests.head))
    assert(DynamicValues.recordGet(savedDemos.head.values, "none").exists(DynamicValues.isNull))
    val (before, _) = promptsBeforeAndAfterLoad(ChatAdapter())
    assert(before.contains(AdapterDemos.IncompletePrefix), before)
  }

  test("an Option of a text keeps a reply text that is a null text, in each format, as upstream") {
    val signature          = Signature.derived[TofInput, TofOptionalText](name = "Text", instructions = "Give the note.")
    def chat(text: String) =
      val model = Script(s"[[ ## note ## ]]\n$text\n\n[[ ## completed ## ]]")
      withModel(model, ChatAdapter())(Predict(signature)(TofInput("q"))).map(_.output.note)
    def json(text: String) =
      val model = Script(s"""{"note": $text}""")
      withModel(model, JSONAdapter())(Predict(signature)(TofInput("q"))).map(_.output.note)
    def xml(text: String) =
      val model = Script(s"<note>$text</note>")
      withModel(model, XMLAdapter())(Predict(signature)(TofInput("q"))).map(_.output.note)
    Vector("none", "None", "null", "n/a").foreach { text =>
      assertEquals(chat(text), Right(Some(text)))
      assertEquals(json(s"\"$text\""), Right(Some(text)))
      assertEquals(xml(text), Right(Some(text)))
    }
    assertEquals(json("null"), Right(None))
  }

  test("an Option of a number takes a null text in the reply, as a null") {
    val signature = Signature.derived[TofInput, TofOptionalNumber](name = "Number", instructions = "Give the number.")
    Vector("none", "None", "null", "n/a").foreach { text =>
      val model = Script(s"[[ ## n ## ]]\n$text\n\n[[ ## completed ## ]]")
      assertEquals(withModel(model, ChatAdapter())(Predict(signature)(TofInput("q"))).map(_.output.n), Right(None))
    }
  }

  test("a demo with an enum key of a map survives a save and a load, with the same prompt") {
    val signature = Signature.derived[TofInput, TofCounted](name = "Counted", instructions = "Give the counts.")
    val demo      = Example(
      ("question" := "q") +:
        signature.outputShape.encode(TofCounted(Map(TofKind.beta -> 1), Map(TofColor.Red -> 2))).fields.toVector*
    )
    val trained = Predict(signature, demos = Vector(demo), name = Some("ask"))
    val loaded  = ProgramPersistence
      .loadJson(Predict(signature, name = Some("ask")), ProgramPersistence.dumpJson(trained))
      .fold(error => fail(error.message), identity)
    val before = Script("{}", "{}")
    val after  = Script("{}", "{}")
    val _      = withModel(before, ChatAdapter())(trained(TofInput("q")))
    val _      = withModel(after, ChatAdapter())(loaded(TofInput("q")))
    assertEquals(promptOf(after.requests.head), promptOf(before.requests.head))
    assert(promptOf(before.requests.head).contains("[[ ## kinds ## ]]\n{\"beta\": 1}"), promptOf(before.requests.head))
    assert(promptOf(before.requests.head).contains("[[ ## colors ## ]]\n{\"Red\": 2}"), promptOf(before.requests.head))
  }

  test("a tool with two arguments of one recursive type has one definition") {
    def both(a: TofTree, b: TofTree): String = s"$a $b"
    val schema                               = ToolFunction.fromMethod(both).inputSchema.getOrElse(fail("no input schema"))
    val found                                = DynamicValues.recordGet(schema, "$defs").flatMap(DynamicValues.asRecord).getOrElse(fail("no $defs"))
    assertEquals(DynamicValues.recordKeys(found), Vector("TofTree"))
  }

  test("an enum argument of a tool has the type of a text and its allowed values") {
    def pick(kind: TofKind, maybe: Option[TofKind]): String = s"$kind $maybe"
    val tool                                                = ToolFunction.fromMethod(pick)
    // An `Optional[...]` has no top-level `type` in upstream too, so its text is `Any`.
    assertEquals(ToolText.parameters(tool), "kind: string, maybe: Any")
    val kind = DynamicValues.recordGet(ToolText.args(tool), "kind").map(PythonText.json(_)).getOrElse(fail("no kind"))
    assert(kind.contains("\"type\": \"string\""), kind)
    assert(kind.contains("\"enum\": ["), kind)
    assert(kind.contains("alpha") && kind.contains("beta"), kind)
    val maybe =
      DynamicValues.recordGet(ToolText.args(tool), "maybe").map(PythonText.json(_)).getOrElse(fail("no maybe"))
    assert(maybe.contains("\"type\": \"string\""), maybe)
  }

  test("ChainOfThought keeps each form behind the field that it puts first") {
    val model = Script(
      "[[ ## reasoning ## ]]\nr\n\n[[ ## shape ## ]]\n{\"Circle\": {\"radius\": 1.5}}\n\n[[ ## pair ## ]]\n[1, \"a\"]\n\n" +
        "[[ ## at ## ]]\n2024-01-02T03:04:05Z\n\n[[ ## id ## ]]\n123e4567-e89b-12d3-a456-426614174000\n\n" +
        "[[ ## kind ## ]]\nbeta\n\n[[ ## completed ## ]]"
    )
    val result = withModel(model, ChatAdapter())(ChainOfThought(signature)(TofInput("q")))
    assertEquals(result.map(_.output.shape), Right(expected.shape))
    assertEquals(result.map(_.output.pair), Right(expected.pair))
    assertEquals(result.map(_.output.at), Right(expected.at))
    assertEquals(result.flatMap(_.raw.asString("kind")), Right("beta"))
  }

  test("the submit tool of a list output has items, as upstream has for a list") {
    val listed = Signature.fromStringDynamic("question -> items: list").fold(error => fail(error.message), identity)
    val submit =
      ReActV2(listed, Vector.empty).allTools.find(_.name == ReActV2.SubmitToolName).getOrElse(fail("no submit"))
    assertEquals(PythonText.json(ToolText.args(submit)), """{"items": {"items": {}, "type": "array"}}""")
  }

  test("ReActV2 gives the text of the wire form for an enum and an Option in the raw prediction") {
    val submitted = Signature.derived[TofInput, TofSubmitted](name = "Submitted", instructions = "Give the values.")
    val agent     = ReActV2(submitted, Vector.empty)
    val model     = Script(
      "[[ ## next_thought ## ]]\nt\n\n[[ ## tool_calls ## ]]\n" +
        """{"tool_calls": [{"name": "submit", "args": {"color": "Red", "note": "n"}}]}""" +
        "\n\n[[ ## completed ## ]]"
    )
    val result = withModel(model, ChatAdapter())(agent(TofInput("q")))
    assertEquals(result.map(_.output), Right(TofSubmitted(TofColor.Red, Some("n"))))
    assertEquals(result.flatMap(_.raw.asString("color")), Right("Red"))
    assertEquals(result.flatMap(_.raw.asString("note")), Right("n"))
  }

  test("the result of a tool is in wire form") {
    val color: TofColor = TofColor.Red
    assertEquals(ToolFunction.result(color), DynamicValues.string("Red"))
    assertEquals(ToolFunction.result(Option("x")), DynamicValues.string("x"))
    assertEquals(PythonText.json(ToolFunction.result(TofUserId("u"))), "\"u\"")
  }

  // ── A case with no fields of a sealed trait with fields ───────────────────────────────────────────────────────

  private def mixed: Signature[TofInput, TofMixedOutput] =
    Signature.derived[TofInput, TofMixedOutput](name = "Mixed", instructions = "Give the shape.")

  private def maybeMixed: Signature[TofInput, TofMaybeMixedOutput] =
    Signature.derived[TofInput, TofMaybeMixedOutput](name = "MaybeMixed", instructions = "Give the shape.")

  test("a case name and a case object of a sealed trait with fields decode in the chat format") {
    def run(value: String) =
      val model = Script(s"[[ ## shape ## ]]\n$value\n\n[[ ## completed ## ]]")
      val out   = withModel(model, ChatAdapter())(Predict(mixed)(TofInput("q"))).map(_.output.shape)
      assertEquals(model.requests.size, 1)
      out
    assertEquals(run("Dot"), Right(TofMixed.Dot))
    assertEquals(run("{\"Dot\": {}}"), Right(TofMixed.Dot))
    assertEquals(run("{\"Circle\": {\"radius\": 1.5}}"), Right(TofMixed.Circle(1.5)))
  }

  test("a case name and a case object of a sealed trait with fields decode in the JSON format") {
    def run(value: String) =
      val model = Script(s"""{"shape": $value}""")
      val out   = withModel(model, JSONAdapter())(Predict(mixed)(TofInput("q"))).map(_.output.shape)
      assertEquals(model.requests.size, 1)
      out
    assertEquals(run("\"Dot\""), Right(TofMixed.Dot))
    assertEquals(run("{\"Dot\": {}}"), Right(TofMixed.Dot))
    assertEquals(run("{\"Circle\": {\"radius\": 1.5}}"), Right(TofMixed.Circle(1.5)))
  }

  test("a case name and a case object of a sealed trait with fields decode in the XML format") {
    def run(value: String) =
      val model = Script(s"<shape>$value</shape>")
      val out   = withModel(model, XMLAdapter())(Predict(mixed)(TofInput("q"))).map(_.output.shape)
      assertEquals(model.requests.size, 1)
      out
    assertEquals(run("Dot"), Right(TofMixed.Dot))
    assertEquals(run("{\"Circle\": {\"radius\": 1.5}}"), Right(TofMixed.Circle(1.5)))
  }

  test("an Option of a sealed trait with fields reads a case name, an object, and a null, in each format") {
    def chat(value: String) =
      val model = Script(s"[[ ## shape ## ]]\n$value\n\n[[ ## completed ## ]]")
      withModel(model, ChatAdapter())(Predict(maybeMixed)(TofInput("q"))).map(_.output.shape)
    def json(value: String) =
      val model = Script(s"""{"shape": $value}""")
      withModel(model, JSONAdapter())(Predict(maybeMixed)(TofInput("q"))).map(_.output.shape)
    def xml(value: String) =
      val model = Script(s"<shape>$value</shape>")
      withModel(model, XMLAdapter())(Predict(maybeMixed)(TofInput("q"))).map(_.output.shape)
    assertEquals(chat("Dot"), Right(Some(TofMixed.Dot)))
    assertEquals(chat("null"), Right(None))
    assertEquals(chat("n/a"), Right(None))
    assertEquals(json("\"Dot\""), Right(Some(TofMixed.Dot)))
    assertEquals(json("null"), Right(None))
    assertEquals(json("{\"Circle\": {\"radius\": 1.5}}"), Right(Some(TofMixed.Circle(1.5))))
    assertEquals(xml("Dot"), Right(Some(TofMixed.Dot)))
    assertEquals(xml("None"), Right(None))
  }

  test("a text that names no case of a sealed trait with fields gets the JSON fallback") {
    val model = Script("[[ ## shape ## ]]\nTriangle\n\n[[ ## completed ## ]]", """{"shape": "Dot"}""")
    assertEquals(
      withModel(model, ChatAdapter())(Predict(mixed)(TofInput("q"))).map(_.output.shape),
      Right(TofMixed.Dot)
    )
    assertEquals(model.requests.size, 2)
  }

  test("a dict field of a string signature keeps its error for a text that is not JSON") {
    val dict  = Signature.fromStringDynamic("question -> data: dict").fold(error => fail(error.message), identity)
    val model = Script("[[ ## data ## ]]\nDot\n\n[[ ## completed ## ]]", """{"data": "Dot"}""")
    val out   = withModel(model, ChatAdapter())(Predict(dict)(DynamicValues.record("question" := "q")))
    assert(out.left.exists(_.message.contains("could not be parsed as JSON")), out.toString)
    assertEquals(model.requests.size, 2)
  }

  // ── Exact numbers ─────────────────────────────────────────────────────────────────────────────────────────────

  private def exactSignature: Signature[TofInput, TofExact] =
    Signature.derived[TofInput, TofExact](name = "Exact", instructions = "Give the numbers.")

  test("a BigDecimal and a BigInt keep each digit in the chat format") {
    def run(exact: String, big: String) =
      val model = Script(s"[[ ## exact ## ]]\n$exact\n\n[[ ## big ## ]]\n$big\n\n[[ ## completed ## ]]")
      withModel(model, ChatAdapter())(Predict(exactSignature)(TofInput("q"))).map(_.output)
    assertEquals(
      run("12345678901234567890", "123456789012345678901"),
      Right(TofExact(BigDecimal("12345678901234567890"), BigInt("123456789012345678901")))
    )
    assertEquals(
      run("1.23456789012345678901", "1"),
      Right(TofExact(BigDecimal("1.23456789012345678901"), BigInt(1)))
    )
  }

  test("a BigDecimal keeps each digit of an integer in the JSON format, and a fraction has the digits of a float") {
    def run(exact: String, big: String) =
      val model = Script(s"""{"exact": $exact, "big": $big}""")
      withModel(model, JSONAdapter())(Predict(exactSignature)(TofInput("q"))).map(_.output)
    assertEquals(
      run("12345678901234567890", "123456789012345678901"),
      Right(TofExact(BigDecimal("12345678901234567890"), BigInt("123456789012345678901")))
    )
    assertEquals(run("1.5", "1"), Right(TofExact(BigDecimal("1.5"), BigInt(1))))
    assertEquals(run("1.23456789012345678901", "1"), Right(TofExact(BigDecimal("1.2345678901234567"), BigInt(1))))
    assertEquals(
      run("\"1.23456789012345678901\"", "1"),
      Right(TofExact(BigDecimal("1.23456789012345678901"), BigInt(1)))
    )
  }

  test("the prompt shows the type name of a BigDecimal and no Python type for a Scala type") {
    val model = Script("{}")
    val _     = withModel(model, JSONAdapter())(Predict(exactSignature)(TofInput("q")))
    val text  = promptOf(model.requests.head)
    assert(text.contains("`exact` (BigDecimal)"), text)
    assert(text.contains("`exact` (must be formatted as a valid BigDecimal)"), text)
    assert(text.contains("`big` (must be formatted as a valid Python int)"), text)
    val forms = Script("{}")
    val _     = withModel(forms, JSONAdapter())(program(TofInput("q")))
    val other = promptOf(forms.requests.head)
    assert(other.contains("`at` (must be formatted as a valid Instant)"), other)
    assert(other.contains("`shape` (must be formatted as a valid union)"), other)
  }

  // ── Tool arguments ────────────────────────────────────────────────────────────────────────────────────────────

  test("a tool of a method gives the JSON schema of each argument that is not a text, a number, or a bool") {
    import TofTuples.pairSchema
    def use(
        tags : List[String],
        pair : (Int, String),
        shape: TofShape,
        at   : Instant,
        kind : TofKind,
        city : String,
        n    : Int,
        ok   : Boolean
    ): String                  = s"$tags $pair $shape $at $kind $city $n $ok"
    val tool                   = ToolFunction.fromMethod(use)
    val args                   = ToolText.args(tool)
    def argument(name: String) =
      DynamicValues.recordGet(args, name).map(PythonText.json(_)).getOrElse(fail(s"no $name"))
    assert(argument("tags").contains("\"items\": {\"type\": \"string\"}"), argument("tags"))
    assert(argument("pair").contains("prefixItems"), argument("pair"))
    assert(argument("shape").contains("oneOf"), argument("shape"))
    assert(argument("at").contains("\"format\": \"date-time\""), argument("at"))
    assert(argument("kind").contains("alpha"), argument("kind"))
    assertEquals(argument("city"), """{"type": "string"}""")
    assertEquals(argument("n"), """{"type": "integer"}""")
    assertEquals(argument("ok"), """{"type": "boolean"}""")
    val schema = tool.inputSchema.getOrElse(fail("no input schema"))
    assertEquals(DynamicValues.recordGet(schema, "type"), Some(DynamicValues.string("object")))
    assertEquals(
      DynamicValues.recordGet(schema, "required").map(PythonText.json(_)),
      Some("""["tags", "pair", "shape", "at", "kind", "city", "n", "ok"]""")
    )
    assert(ToolSpec("use", None, schema).text.contains("prefixItems"), ToolSpec("use", None, schema).text)
    assert(ToolText.describe(tool).contains("prefixItems"), ToolText.describe(tool))
    val native = PythonText.json(ToolSchemaBridge.toOpenAiToolsDynamic(Vector(ToolSpec("use", None, schema))))
    assert(native.contains("\"parameters\": {\"type\": \"object\""), native)
    assert(native.contains("\"items\": {\"type\": \"string\"}"), native)
    assert(native.contains("prefixItems"), native)
    assert(native.contains("oneOf"), native)
    assert(native.contains("date-time"), native)
    given RuntimeContext = RuntimeContext()
    val invoke           = PythonText.parseJson(
      """{"tags": ["t"], "pair": [1, "a"], "shape": {"Square": {"side": 2}}, "at": "2024-01-02T03:04:05Z",
        |"kind": "beta", "city": "c", "n": 3, "ok": true}""".stripMargin
    ) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"expected a record, got $other")
    assert(tool.invoke(invoke).isRight, tool.invoke(invoke).toString)
  }

  test("a tool of a method with only texts, numbers, and bools keeps the text of its wire types") {
    def plain(city: String, n: Int, x: Double, ok: Boolean): String = s"$city $n $x $ok"
    val tool                                                        = ToolFunction.fromMethod(plain)
    assertEquals(tool.inputSchema, None)
    assertEquals(
      PythonText.json(ToolText.args(tool)),
      """{"city": {"type": "string"}, "n": {"type": "integer"}, "x": {"type": "number"}, "ok": {"type": "boolean"}}"""
    )
  }

  test("a tool with a recursive argument has the definitions at the root of its schema") {
    def walk(tree: TofTree): String = tree.toString
    val tool                        = ToolFunction.fromMethod(walk)
    val schema                      = tool.inputSchema.getOrElse(fail("no input schema"))
    val text                        = PythonText.json(schema)
    assert(DynamicValues.recordGet(schema, "$defs").isDefined, text)
    assert(text.contains("\"$ref\": \"#/$defs/TofTree\""), text)
  }

  test("a hand tool with a list argument has items, as upstream has for a list") {
    val tool =
      ToolFunction("t", "T.", Vector("xs" -> dspy4s.core.contracts.TypeRef.list))(_ => Right(DynamicValue.Null))
    assertEquals(PythonText.json(ToolText.args(tool)), """{"xs": {"items": {}, "type": "array"}}""")
  }

  test("the text of a tool keeps an argument with the field name of a tuple") {
    val schema = DynamicValues.record(
      "type"       := "object",
      "properties" -> DynamicValues.record("_1" -> DynamicValues.record("type" := "string"))
    )
    val text = ToolSpec("t", None, schema).text
    assert(text.contains("""It takes arguments {"_1": {"type": "string"}}."""), text)
  }

  test("the submit tool of ReActV2 reads and describes each form") {
    val agent  = ReActV2(signature, Vector.empty)
    val submit = agent.allTools.find(_.name == ReActV2.SubmitToolName).getOrElse(fail("no submit tool"))
    val args   = PythonText.parseJson(
      """{"shape": {"Circle": {"radius": 1.5}}, "pair": [1, "a"], "at": "2024-01-02T03:04:05Z",
        |"id": "123e4567-e89b-12d3-a456-426614174000", "kind": "beta"}""".stripMargin
    ) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"expected a record, got $other")
    given RuntimeContext = RuntimeContext()
    assertEquals(submit.invoke(args), Right(signature.outputShape.encode(expected)))
    val schema = PythonText.json(ToolText.args(submit))
    assert(schema.contains("oneOf"), schema)
    assert(schema.contains("prefixItems"), schema)
    assert(schema.contains("date-time"), schema)
  }

  test("RLM reads each form from SUBMIT, and the sandbox gets the Python type of each output") {
    final class Repl extends ReplCodeInterpreter:
      override def execute(code: String): Either[DspyError, CodeResult]                                       = execute(code, Map.empty)
      override def execute(code: String, variables: Map[String, DynamicValue]): Either[DspyError, CodeResult] =
        Right(CodeResult(
          "",
          "",
          0,
          finalOutput = Some(
            """{"shape": {"Circle": {"radius": 1.5}}, "pair": [1, "a"], "at": "2024-01-02T03:04:05Z",
              |"id": "123e4567-e89b-12d3-a456-426614174000", "kind": "beta"}""".stripMargin
          )
        ))
      override def close(): Unit = ()
    val types = ArrayBuffer.empty[Vector[(String, Option[String])]]
    val rlm   = RLM(
      baseSignature = signature,
      interpreterFactory = Some { (_, outputs) =>
        types += outputs.map(output => output.name -> output.pythonType)
        new Repl
      }
    )
    val model = Script("[[ ## reasoning ## ]]\nr\n\n[[ ## code ## ]]\nSUBMIT()\n\n[[ ## completed ## ]]")
    assertEquals(withModel(model, ChatAdapter())(rlm(TofInput("q"))).map(_.output), Right(expected))
    assertEquals(
      types.toVector,
      Vector(Vector(
        "shape" -> None,
        "pair"  -> Some("list"),
        "at"    -> None,
        "id"    -> None,
        "kind"  -> Some("str")
      ))
    )
  }

  test("the prompt describes each value with its type name and its schema") {
    val model = Script("{}")
    val _     = withModel(model, JSONAdapter())(program(TofInput("q")))
    val text  = promptOf(model.requests.head)
    assert(text.contains("`shape` (union)"), text)
    assert(text.contains("oneOf"), text)
    assert(text.contains("`pair` (list)"), text)
    assert(text.contains("prefixItems"), text)
    assert(text.contains("`at` (Instant)"), text)
    assert(text.contains("date-time"), text)
    assert(text.contains("`id` (UUID)"), text)
    // The JSON format writes each note in a JSON string, as upstream does, so the quotes of the schema have an escape.
    assert(text.contains("\\\"uuid\\\""), text)
    assert(text.contains("one of: alpha; beta"), text)
    assert(!text.contains("one of: Circle"), text)
  }

  test("a typed input shows each value in the form of its schema") {
    val rich   = Signature.derived[TofRichInput, TofAnswer](name = "Rich", instructions = "Answer.")
    val model  = Script("[[ ## answer ## ]]\nok\n\n[[ ## completed ## ]]")
    val result = withModel(model, ChatAdapter()) {
      Predict(rich)(TofRichInput(
        TofShape.Square(2.0),
        (1, "a"),
        Instant.parse("2024-01-02T03:04:05Z"),
        UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
      ))
    }
    assert(result.isRight, result.toString)
    val text = promptOf(model.requests.head)
    assert(text.contains("""{"Square": {"side": 2.0}}"""), text)
    assert(text.contains("""[1, "a"]"""), text)
    assert(!text.contains("_1"), text)
    assert(text.contains("2024-01-02T03:04:05Z"), text)
    assert(text.contains("123e4567-e89b-12d3-a456-426614174000"), text)
    assert(!text.contains("Instant("), text)
    assert(!text.contains("UUID("), text)
  }

  test("a bad text of a typed value gets the JSON fallback") {
    val model = Script(
      "[[ ## shape ## ]]\n{\"Circle\": {\"radius\": 1.5}}\n\n[[ ## pair ## ]]\n[1, \"a\"]\n\n" +
        "[[ ## at ## ]]\nsoon\n\n[[ ## id ## ]]\n123e4567-e89b-12d3-a456-426614174000\n\n" +
        "[[ ## kind ## ]]\nbeta\n\n[[ ## completed ## ]]",
      """{"shape": {"Circle": {"radius": 1.5}}, "pair": [1, "a"], "at": "2024-01-02T03:04:05Z",
        |"id": "123e4567-e89b-12d3-a456-426614174000", "kind": "beta"}""".stripMargin
    )
    assertEquals(withModel(model, ChatAdapter())(program(TofInput("q"))).map(_.output), Right(expected))
    assertEquals(model.requests.size, 2)
  }

  test("a transform of a text reaches a typed output through a given schema, from a bare text") {
    val wrapped = Signature.fromShapes[TofInput, TofWrappedOutput](
      "Wrapped",
      "Give the user.",
      Shape.derived[TofInput],
      Shape.derived[TofWrappedOutput]
    )
    val model  = Script("[[ ## user ## ]]\nabc\n\n[[ ## completed ## ]]")
    val result = withModel(model, ChatAdapter())(Predict(wrapped)(TofInput("q")))
    assertEquals(result.map(_.output), Right(TofWrappedOutput(TofUserId("abc"))))
    assertEquals(model.requests.size, 1)
  }

  test("a tool argument takes the same forms") {
    import TofTuples.pairSchema
    def use(at: Instant, pair: (Int, String), shape: TofShape): String = s"$at $pair $shape"
    val tool                                                           = ToolFunction.fromMethod(use)
    val args                                                           = PythonText.parseJson(
      """{"at": "2024-01-02T03:04:05Z", "pair": [1, "a"], "shape": {"Square": {"side": 2}}}"""
    ) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"expected a record, got $other")
    given RuntimeContext = RuntimeContext()
    assertEquals(
      tool.invoke(args).map(DynamicValues.renderText),
      Right("2024-01-02T03:04:05Z (1,a) Square(2.0)")
    )
  }
