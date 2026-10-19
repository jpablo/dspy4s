package dspy4s.programs

import dspy4s.adapters.ChatAdapter
import dspy4s.adapters.JSONAdapter
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.support.DecodeFixtures
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.Schema

final case class OfQuestion(question: String) derives Schema
final case class OfPlace(city: String) derives Schema
final case class OfAnswer(count: Option[Int], ratio: Option[Double], place: Option[OfPlace]) derives Schema

/** An `Option` field has the type of its element (`OptionTypeRefSuite`). The real adapters and the typed decode still
  * read each form of such a field: the null texts, a number, a lenient number, and a JSON object.
  */
class OptionFieldDecodeSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val signature = Signature.derived[OfQuestion, OfAnswer](name = "OptionDecode")

  private def chat(count: String, ratio: String, place: String): String =
    s"[[ ## count ## ]]\n$count\n\n[[ ## ratio ## ]]\n$ratio\n\n[[ ## place ## ]]\n$place\n\n[[ ## completed ## ]]"

  private def decodeChat(completion: String) =
    DecodeFixtures.decodeCompletion(signature, ChatAdapter(), OfQuestion("Q?"), completion)

  test("the chat adapter reads a number, a lenient number, and a JSON object") {
    assertEquals(
      decodeChat(chat("3", "$2,399.00", """{"city": "Paris"}""")),
      Right(OfAnswer(Some(3), Some(2399.0), Some(OfPlace("Paris"))))
    )
  }

  test("the chat adapter reads the null texts as None") {
    assertEquals(decodeChat(chat("None", "null", "None")), Right(OfAnswer(None, None, None)))
    assertEquals(decodeChat(chat("N/A", "none", "null")), Right(OfAnswer(None, None, None)))
  }

  test("the JSON adapter reads a null, a number, and the text None") {
    val completion = """{"count": null, "ratio": 0.5, "place": "None"}"""
    assertEquals(
      DecodeFixtures.decodeCompletion(signature, JSONAdapter(), OfQuestion("Q?"), completion),
      Right(OfAnswer(None, Some(0.5), None))
    )
  }
