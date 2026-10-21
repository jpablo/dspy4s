package dspy4s.adapters

import dspy4s.adapters.contracts.Adapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.ParseError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.TypeRef
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** The allowed values of an output field (decision D7; upstream `parse_value` with a `Literal` or an `Enum` annotation
  * in `dspy/adapters/utils.py`). A value outside the allowed values is a parse error in each parser.
  *
  * Upstream compares the text with the members, case-sensitive, after these steps: it strips the whitespace, removes a
  * `Literal[...]` or `str[...]` wrapper, and removes one pair of quotes. Then `json_repair` reads the text, and else
  * `ast.literal_eval` reads it: a JSON string or a Python string literal, with whitespace around it, gives its text.
  * The cases come from runs of upstream `parse_value`.
  */
class AllowedValuesSuite extends FunSuite:

  private given RuntimeContext = RuntimeContext()

  /** Gives the replies in order and repeats the last one. Records each request. */
  private final class ScriptedLm(replies: String*) extends LanguageModel:
    val requests: ArrayBuffer[LmRequest]                                                       = ArrayBuffer.empty
    override val id: String                                                                    = "extractor"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests += request
      val text = replies(math.min(requests.size - 1, replies.size - 1))
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  /** `text -> label: Literal['pos', 'neg'], note`: two outputs, so no plain-text rule for one output applies. */
  private val literalLayout: SignatureLayout =
    SignatureLayout.parse("text -> label: Literal['pos', 'neg'], note").toOption.get

  /** The layout that a typed signature gives a Scala enum output: a string field with the case names as enum values. */
  private val enumLayout: SignatureLayout = SignatureLayout.create(
    name = "Ticket",
    inputFields = Vector(FieldSpec("text")),
    outputFields = Vector(
      FieldSpec("label", enumValues = Vector("order_confirmation", "support_request")),
      FieldSpec("note")
    )
  ).toOption.get

  /** The reply with the value `label` of the field `label`, in the form of each adapter. */
  private val formats: Vector[(String, Adapter, String => String)] = Vector(
    (
      "ChatAdapter",
      ChatAdapter(),
      label => s"[[ ## label ## ]]\n$label\n\n[[ ## note ## ]]\nx\n\n[[ ## completed ## ]]"
    ),
    ("JSONAdapter", JSONAdapter(), label => s"""{"label": ${ujson.write(ujson.Str(label))}, "note": "x"}"""),
    ("XMLAdapter", XMLAdapter(), label => s"<label>$label</label>\n<note>x</note>")
  )

  /** Values that upstream `parse_value` rejects for `Literal['pos', 'neg']`. */
  private val rejected: Vector[String] =
    Vector("maybe", "Pos", "POS", "positive", "pos.", "`pos`", "' pos '", "Literal[ pos ]", "pos neg", "")

  /** Values that upstream `parse_value` accepts for `Literal['pos', 'neg']`, with the member that it gives. */
  private val accepted: Vector[(String, String)] = Vector(
    "pos"             -> "pos",
    "'pos'"           -> "pos",
    "\"neg\""         -> "neg",
    "Literal['pos']"  -> "pos",
    "Literal[neg]"    -> "neg",
    "str[pos]"        -> "pos",
    "str['neg']"      -> "neg",
    "''pos''"         -> "pos",
    "\"'neg'\""       -> "neg",
    "Literal[ 'pos']" -> "pos"
  )

  /** Values that upstream reads in the last step, with the member that it gives: `json_repair.loads` decodes the JSON
    * escapes of a value in double quotes, and `ast.literal_eval` reads a Python string literal, also with a `u`, `U`,
    * `r`, or `R` prefix and with the Python escapes.
    */
  private val literalForms: Vector[(String, String)] = Vector(
    "u'pos'"           -> "pos",
    "U\"neg\""         -> "neg",
    "r'pos'"           -> "pos",
    "R\"neg\""         -> "neg",
    "'\"p\\u006fs\"'"  -> "pos",
    "'\"\\u006eeg\"'"  -> "neg",
    "'\"n\\u0065g\" '" -> "neg",
    "\"'\\x70os'\""    -> "pos",
    "u'p\\x6fs'"       -> "pos",
    "u'\\u006eeg'"     -> "neg",
    "u'p\\157s'"       -> "pos",
    "Literal[u'neg']"  -> "neg",
    "\"u'pos'\""       -> "pos"
  )

  /** Escape and literal forms that upstream `parse_value` rejects for `Literal['pos', 'neg']`. */
  private val rejectedLiteralForms: Vector[String] = Vector(
    "\"p\\u006fs\"",
    "'\u00a0\"pos\"\u00a0'",
    "'\"p\"os\"'",
    "ur'pos'",
    "b'pos'",
    "u'pos",
    "'\"pos\\\"'",
    "u'p\\N{LATIN SMALL LETTER O}s'",
    "u'p\\x6s'",
    "u'po\ns'",
    "'\"\\/pos\"'"
  )

  private def parse(adapter: Adapter, layout: SignatureLayout, text: String): Either[DspyError, DynamicValue.Record] =
    adapter.parse(layout, LmOutput(text = text)).map(_.values)

  private def label(record: DynamicValue.Record): Option[String] =
    DynamicValues.recordGet(record, "label").flatMap(DynamicValues.asString)

  private def assertParseError(result: Either[DspyError, DynamicValue.Record], clue: String): Unit =
    result match
      case Left(error: ParseError) => assert(error.message.contains("label"), s"$clue: ${error.message}")
      case other                   => fail(s"$clue: expected a parse error, got $other")

  test("a value outside the allowed values of a string-signature Literal is a parse error in each parser") {
    for (name, adapter, reply) <- formats; value <- rejected do
      assertParseError(parse(adapter, literalLayout, reply(value)), s"$name, value '$value'")
  }

  test("a value in the allowed values passes, also after the normalization of upstream parse_value") {
    for (name, adapter, reply) <- formats; (value, member) <- accepted do
      val result = parse(adapter, literalLayout, reply(value))
      assertEquals(result.map(label), Right(Some(member)), s"$name, value '$value'")
  }

  test("a JSON escape in a quoted value and a Python string literal give the member, as json_repair and literal_eval") {
    for (name, adapter, reply) <- formats; (value, member) <- literalForms do
      val result = parse(adapter, literalLayout, reply(value))
      assertEquals(result.map(label), Right(Some(member)), s"$name, value '$value'")
  }

  test("the escape and literal forms that upstream rejects are a parse error in each parser") {
    for (name, adapter, reply) <- formats; value <- rejectedLiteralForms do
      assertParseError(parse(adapter, literalLayout, reply(value)), s"$name, value '$value'")
  }

  test("JSON whitespace around a member goes, as upstream strips the text") {
    val result = parse(JSONAdapter(), literalLayout, """{"label": "  neg\n", "note": "x"}""")
    assertEquals(result.map(label), Right(Some("neg")))
  }

  test("a JSON value that is not a string is a parse error for a field with allowed values") {
    // Upstream `parse_value` validates a value that is not a string against the `Literal` of strings, and fails.
    Vector("1", "true", "null", """["pos"]""", """{"value": "pos"}""").foreach { value =>
      assertParseError(parse(JSONAdapter(), literalLayout, s"""{"label": $value, "note": "x"}"""), s"value $value")
    }
  }

  test("a typed enum output follows the same rule as a string-signature Literal") {
    for (name, adapter, reply) <- formats do
      assertParseError(parse(adapter, enumLayout, reply("Support Request")), s"$name, a label of a person")
      assertParseError(parse(adapter, enumLayout, reply("support")), s"$name, a prefix")
      assertEquals(
        parse(adapter, enumLayout, reply("'support_request'")).map(label),
        Right(Some("support_request")),
        name
      )
  }

  test("an optional field with allowed values takes a null text and rejects another value") {
    val optional = SignatureLayout.create(
      name = "Probe",
      inputFields = Vector(FieldSpec("text")),
      outputFields = Vector(FieldSpec("label", enumValues = Vector("pos", "neg"), optional = true), FieldSpec("note"))
    ).toOption.get
    for (name, adapter, reply) <- formats do
      // Upstream `parse_value("None", Optional[Literal['pos', 'neg']])` gives `None`.
      assertEquals(
        parse(adapter, optional, reply("None")).map(DynamicValues.recordGet(_, "label")),
        Right(Some(DynamicValue.Null)),
        name
      )
      assertParseError(parse(adapter, optional, reply("maybe")), name)
      assertEquals(parse(adapter, optional, reply("pos")).map(label), Right(Some("pos")), name)
  }

  test("the parsers do not check the levels and options of the decision types") {
    // The enum values of a `Score` or a `Choice` decision output are its levels or options, not allowed texts.
    Vector(TypeRef.score, TypeRef.choice, TypeRef.noul).foreach { typeRef =>
      val layout = SignatureLayout.create(
        name = "Probe",
        inputFields = Vector(FieldSpec("text")),
        outputFields =
          Vector(FieldSpec("label", typeRef = typeRef, enumValues = Vector("low", "high")), FieldSpec("note"))
      ).toOption.get
      val reply = "[[ ## label ## ]]\nsomething else\n\n[[ ## note ## ]]\nx\n\n[[ ## completed ## ]]"
      assertEquals(parse(ChatAdapter(), layout, reply).map(label), Right(Some("something else")), typeRef.repr)
    }
  }

  test("the plain-text rule for one output checks the allowed values in each parser") {
    val single = SignatureLayout.parse("text -> label: Literal['pos', 'neg']").toOption.get
    Vector("ChatAdapter" -> ChatAdapter(), "JSONAdapter" -> JSONAdapter(), "XMLAdapter" -> XMLAdapter()).foreach {
      (name, adapter) =>
        assertParseError(parse(adapter, single, "maybe"), name)
        assertEquals(parse(adapter, single, "'neg'").map(label), Right(Some("neg")), name)
    }
  }

  test("the two-step extraction fails when the chat format and its JSON fallback give a value outside the set") {
    val extraction = new ScriptedLm(
      "[[ ## label ## ]]\nmaybe\n\n[[ ## note ## ]]\nx\n\n[[ ## completed ## ]]",
      """{"label": "maybe", "note": "x"}"""
    )
    assertParseError(parse(TwoStepAdapter(extraction), literalLayout, "It could be either."), "TwoStepAdapter")
    assertEquals(extraction.requests.size, 2)
  }

  test("the two-step extraction takes the JSON fallback after a value outside the set in the chat format") {
    val extraction = new ScriptedLm(
      "[[ ## label ## ]]\nmaybe\n\n[[ ## note ## ]]\nx\n\n[[ ## completed ## ]]",
      """{"label": "'neg'", "note": "x"}"""
    )
    assertEquals(parse(TwoStepAdapter(extraction), literalLayout, "It is negative.").map(label), Right(Some("neg")))
    assertEquals(extraction.requests.size, 2)
  }
