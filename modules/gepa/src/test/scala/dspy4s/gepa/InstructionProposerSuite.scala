package dspy4s.gepa

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.programs.optimization.OptimizableId
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** The default instruction proposer of GEPA (PORT_GAPS G-45, item O3; upstream b6c8aaf1e,
  * `dspy/teleprompt/gepa/instruction_proposal.py` and `tests/teleprompt/test_gepa_builtin_instruction_proposer.py`).
  *
  * The prompt text of a dspy4s adapter is not equal to the upstream text, so the tests assert the content of a request
  * and not its layout.
  */
class InstructionProposerSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the replies in order and repeats the last one. Records the text of each request. */
  private final class ScriptedLm(replies: Either[DspyError, String]*) extends LanguageModel:
    val requests: ArrayBuffer[String]                                                          = ArrayBuffer.empty
    override val id: String                                                                    = "reflect"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val index = requests.synchronized {
        requests += request.messages.flatMap(_.text).mkString("\n")
        requests.size - 1
      }
      replies(math.min(index, replies.size - 1)).map(text => LmResponse(outputs = Vector(LmOutput(text = text))))

  private def scripted(replies: String*): ScriptedLm = new ScriptedLm(replies.map(Right(_))*)

  import ReflectionFixtures.{reply, shortenedReply}

  private val first  = OptimizableId(0)
  private val second = OptimizableId(1)

  private val candidate: Candidate = Map(first -> Some("Answer the question."), second -> Some("Check the answer."))

  private val record: ReflectiveRecord = ReflectiveRecord(
    inputs = DynamicValues.record("question" := "Capital of France?"),
    generatedOutputs = DynamicValues.record("answer" := "Lyon"),
    feedback = "expected 'Paris', got 'Lyon'"
  )

  private val otherRecord: ReflectiveRecord = ReflectiveRecord(
    inputs = DynamicValues.record("answer" := "Lyon"),
    generatedOutputs = DynamicValues.record("verdict" := "fine"),
    feedback = "the check must reject 'Lyon'"
  )

  private val reflective: Map[OptimizableId, Vector[ReflectiveRecord]] = Map(first -> Vector(record))

  /** Run `proposer` for `components` in a context whose language model is `lm`. The context has no adapter. */
  private def propose(
      proposer  : InstructionProposer,
      lm        : LanguageModel,
      components: Vector[OptimizableId]                        = Vector(first),
      records   : Map[OptimizableId, Vector[ReflectiveRecord]] = reflective,
      from      : Candidate                                    = candidate,
      context   : RuntimeContext                               = RuntimeContext()
  ): Either[DspyError, Map[OptimizableId, String]] =
    RuntimeEnvironment.withSettings(context.copy(lm = Some(lm))) {
      given RuntimeContext = RuntimeEnvironment.current
      proposer.propose(from, records, components)
    }

  private def limit(chars: Int): Option[InstructionCharLimit] = Some(InstructionCharLimit.applyUnsafe(chars))

  private def fieldNames(layout: SignatureLayout): Vector[String] = layout.inputFields.map(_.name)

  private def description(layout: SignatureLayout, name: String): Option[String] =
    (layout.inputFields ++ layout.outputFields).find(_.name == name).flatMap(_.description)

  // ── The JSON text of the records ────────────────────────────────────────────────────────────────────────────

  test("the JSON text of the records has the gepa keys in the gepa order, in the form of Python json.dumps") {
    assertEquals(
      ReflectiveRecord.toJson(Vector(record)),
      """[{"Inputs": {"question": "Capital of France?"}, "Generated Outputs": {"answer": "Lyon"}, """ +
        """"Feedback": "expected 'Paris', got 'Lyon'"}]"""
    )
    assertEquals(ReflectiveRecord.toJson(Vector.empty), "[]")
  }

  test("the JSON text separates the records and keeps the order of the keys of a record") {
    val two = ReflectiveRecord(
      inputs = DynamicValues.record("b" := "2", "a" := "1"),
      generatedOutputs = DynamicValues.record("z" := "26"),
      feedback = "ok"
    )
    assertEquals(
      ReflectiveRecord.toJson(Vector(two, two)),
      """[{"Inputs": {"b": "2", "a": "1"}, "Generated Outputs": {"z": "26"}, "Feedback": "ok"}, """ +
        """{"Inputs": {"b": "2", "a": "1"}, "Generated Outputs": {"z": "26"}, "Feedback": "ok"}]"""
    )
  }

  test("the JSON text keeps a character that is not ASCII and gives an escape to a quote and a control character") {
    val special = ReflectiveRecord(
      inputs = DynamicValues.record("text" := "é猫🙂"),
      // A call that failed has one string as its output.
      generatedOutputs = ReflectionFixtures.string("say \"hi\"\n\ta\\b"),
      feedback = "line 1\nline 2"
    )
    assertEquals(
      ReflectiveRecord.toJson(Vector(special)),
      """[{"Inputs": {"text": "é猫🙂"}, "Generated Outputs": "say \"hi\"\n\ta\\b", "Feedback": "line 1\nline 2"}]"""
    )
  }

  test("the JSON text writes a number, a boolean, a null, and a nested sequence as JSON values") {
    val nested = DynamicValue.Sequence(Chunk(DynamicValues.fromAny(1), DynamicValues.fromAny("x")))
    val values = ReflectiveRecord(
      inputs = DynamicValues.record("count" := 3, "ratio" := 0.5, "ok" := true, "none" -> DynamicValue.Null),
      generatedOutputs = DynamicValues.record("items" -> nested, "inner" -> DynamicValues.record("k" := "v")),
      feedback = "ok"
    )
    assertEquals(
      ReflectiveRecord.toJson(Vector(values)),
      """[{"Inputs": {"count": 3, "ratio": 0.5, "ok": true, "none": null}, """ +
        """"Generated Outputs": {"items": [1, "x"], "inner": {"k": "v"}}, "Feedback": "ok"}]"""
    )
  }

  // ── The signatures ──────────────────────────────────────────────────────────────────────────────────────────

  test("the proposal signature has the upstream fields, descriptions, and instructions") {
    val layout = DefaultInstructionProposer().proposeSignature
    assertEquals(fieldNames(layout), Vector("current_instruction", "examples_with_feedback"))
    assertEquals(layout.outputFields.map(_.name), Vector("new_instruction"))
    assertEquals(layout.instructions, Some(DefaultInstructionProposer.ProposeInstructions))
    assertEquals(
      description(layout, "current_instruction"),
      Some("The instructions I provided to the assistant.")
    )
    assertEquals(
      description(layout, "examples_with_feedback"),
      Some(
        "Task inputs provided to the assistant, the assistant's response for each, and feedback on how the response " +
          "could be better."
      )
    )
    assertEquals(description(layout, "new_instruction"), Some("The new instruction for the assistant."))
    assertEquals(DefaultInstructionProposer().compressSignature, None)
  }

  test("each option adds its input field before the two fixed fields, in the upstream order") {
    val additional = DefaultInstructionProposer(additionalInstructions = Some("Use the imperative voice."))
    assertEquals(
      fieldNames(additional.proposeSignature),
      Vector("additional_instructions", "current_instruction", "examples_with_feedback")
    )
    assertEquals(
      description(additional.proposeSignature, "additional_instructions"),
      Some("Additional requirements from the user for the new instruction. Follow them.")
    )

    val limited = DefaultInstructionProposer(maxChars = limit(50))
    assertEquals(
      fieldNames(limited.proposeSignature),
      Vector("length_limit", "current_instruction", "examples_with_feedback")
    )
    assertEquals(
      description(limited.proposeSignature, "length_limit"),
      Some("Hard limit on the length of the new instruction.")
    )

    val both = additional.copy(maxChars = limit(50))
    assertEquals(
      fieldNames(both.proposeSignature),
      Vector("additional_instructions", "length_limit", "current_instruction", "examples_with_feedback")
    )
    assertEquals(both.proposeSignature.outputFields.map(_.name), Vector("new_instruction"))
  }

  test("the signature that shortens an instruction exists only with a length limit") {
    val layout = DefaultInstructionProposer(maxChars = limit(50)).compressSignature.getOrElse(fail("no signature"))
    assertEquals(fieldNames(layout), Vector("instruction", "length_limit"))
    assertEquals(layout.outputFields.map(_.name), Vector("shortened_instruction"))
    assertEquals(layout.instructions, Some(DefaultInstructionProposer.CompressInstructions))
    assertEquals((layout.inputFields ++ layout.outputFields).flatMap(_.description), Vector.empty)
  }

  test("base instructions replace the instructions and keep the fields") {
    val proposer = DefaultInstructionProposer(baseInstructions = Some("  Rewrite the instruction so it generalizes. "))
    assertEquals(proposer.proposeSignature.instructions, Some("Rewrite the instruction so it generalizes."))
    assertEquals(fieldNames(proposer.proposeSignature), Vector("current_instruction", "examples_with_feedback"))

    val lm = scripted(reply("New text."))
    assertEquals(propose(proposer, lm), Right(Map(first -> "New text.")))
    assert(lm.requests.head.contains("Rewrite the instruction so it generalizes."), lm.requests.head)
    assert(!lm.requests.head.contains("Your task is to write a new instruction for the assistant."), lm.requests.head)
  }

  test("an option with an empty text is the same as no option") {
    val proposer = DefaultInstructionProposer(additionalInstructions = Some("   "), baseInstructions = Some("\n "))
    assertEquals(fieldNames(proposer.proposeSignature), Vector("current_instruction", "examples_with_feedback"))
    assertEquals(proposer.proposeSignature.instructions, Some(DefaultInstructionProposer.ProposeInstructions))
  }

  // ── The proposal call ───────────────────────────────────────────────────────────────────────────────────────

  test("the proposer reads the new instruction from the JSON object of the response") {
    val lm = scripted(reply("Answer with the CITY name only."))
    assertEquals(propose(DefaultInstructionProposer(), lm), Right(Map(first -> "Answer with the CITY name only.")))
    assertEquals(lm.requests.size, 1)
  }

  test("the request holds the upstream instructions, the current instruction, and the JSON text of the records") {
    val lm = scripted(reply("New text."))
    assert(propose(DefaultInstructionProposer(), lm).isRight)
    val request = lm.requests.head
    assert(
      request.contains(
        "Read the inputs carefully and identify the input format and infer detailed task description about the " +
          "task I wish to solve with the assistant."
      ),
      request
    )
    assert(request.contains("Answer the question."), request)
    assert(request.contains(ReflectiveRecord.toJson(Vector(record))), request)
    // The old template asked for a fence. The proposer does not.
    assert(!request.contains("Provide the new instruction within"), request)
  }

  test("the proposer uses its adapter, not the adapter of the context") {
    val lm      = scripted(reply("New text."))
    val context = RuntimeContext(adapter = Some(ChatAdapter()))
    assertEquals(propose(DefaultInstructionProposer(), lm, context = context), Right(Map(first -> "New text.")))
    assert(!lm.requests.head.contains("[[ ## current_instruction ## ]]"), lm.requests.head)
  }

  test("the adapter option selects the adapter of the calls") {
    val lm       = scripted("[[ ## new_instruction ## ]]\nNew text.\n\n[[ ## completed ## ]]")
    val proposer = DefaultInstructionProposer(adapter = ChatAdapter())
    assertEquals(propose(proposer, lm), Right(Map(first -> "New text.")))
    assert(lm.requests.head.contains("[[ ## current_instruction ## ]]"), lm.requests.head)
    // The chat adapter shows the description of each field.
    assert(lm.requests.head.contains(DefaultInstructionProposer.ExamplesWithFeedbackDescription), lm.requests.head)
  }

  test("the new instruction loses the white space at its two ends") {
    val lm = scripted(reply("  Answer briefly.\n"))
    assertEquals(propose(DefaultInstructionProposer(), lm), Right(Map(first -> "Answer briefly.")))
  }

  test("a fence in the new instruction stays unchanged") {
    val instruction = "Use this format:\n```python\nprint(1)\n```\nThen stop."
    val lm          = scripted(reply(instruction))
    assertEquals(propose(DefaultInstructionProposer(), lm), Right(Map(first -> instruction)))
  }

  test("an empty new instruction is a failure") {
    val lm     = scripted(reply("   "))
    val result = propose(DefaultInstructionProposer(), lm)
    assert(result.left.exists(_.isInstanceOf[ValidationError]), result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("a response that is not a JSON object is a failure") {
    val lm     = scripted("Just write better answers.")
    val result = propose(DefaultInstructionProposer(), lm)
    assert(result.isLeft, result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("an error of the language model is a failure") {
    val lm     = new ScriptedLm(Left(RuntimeError("reflect_lm", "the reflection model is down")))
    val result = propose(DefaultInstructionProposer(), lm)
    assert(result.isLeft, result.toString)
    assertEquals(lm.requests.size, 1)
  }

  test("the additional instructions and the length limit are in the request") {
    val lm       = scripted(reply("New text."))
    val proposer =
      DefaultInstructionProposer(additionalInstructions = Some(" Use the imperative voice. "), maxChars = limit(50))
    assertEquals(propose(proposer, lm), Right(Map(first -> "New text.")))
    assert(lm.requests.head.contains("Use the imperative voice."), lm.requests.head)
    assert(lm.requests.head.contains("The new instruction must be at most 50 characters."), lm.requests.head)
  }

  // ── The components ──────────────────────────────────────────────────────────────────────────────────────────

  test("each component gets one call, in the order of the components") {
    val lm      = scripted(reply("First text."), reply("Second text."))
    val records = Map(first -> Vector(record), second -> Vector(otherRecord))
    assertEquals(
      propose(DefaultInstructionProposer(), lm, components = Vector(first, second), records = records),
      Right(Map(first -> "First text.", second -> "Second text."))
    )
    assertEquals(lm.requests.size, 2)
    assert(lm.requests(0).contains("Answer the question."), lm.requests(0))
    assert(lm.requests(0).contains(ReflectiveRecord.toJson(Vector(record))), lm.requests(0))
    assert(lm.requests(1).contains("Check the answer."), lm.requests(1))
    assert(lm.requests(1).contains(ReflectiveRecord.toJson(Vector(otherRecord))), lm.requests(1))
  }

  test("a component with no reflective record gets no call and no entry") {
    val lm = scripted(reply("New text."))
    assertEquals(
      propose(DefaultInstructionProposer(), lm, components = Vector(second, first)),
      Right(Map(first -> "New text."))
    )
    assertEquals(lm.requests.size, 1)
  }

  test("a component that the candidate does not hold gets no call and no entry") {
    val lm      = scripted(reply("New text."))
    val unknown = OptimizableId(7)
    val records = Map(first -> Vector(record), unknown -> Vector(otherRecord))
    assertEquals(
      propose(DefaultInstructionProposer(), lm, components = Vector(unknown, first), records = records),
      Right(Map(first -> "New text."))
    )
    assertEquals(lm.requests.size, 1)
  }

  test("a component with no instruction gives the empty text as its current instruction") {
    val lm   = scripted(reply("New text."))
    val from = candidate.updated(first, None)
    assertEquals(propose(DefaultInstructionProposer(), lm, from = from), Right(Map(first -> "New text.")))
    assert(!lm.requests.head.contains("None"), lm.requests.head)
  }

  test("the failure of one component is the result of the call") {
    val lm      = scripted(reply("First text."), reply(" "))
    val records = Map(first -> Vector(record), second -> Vector(otherRecord))
    val result  = propose(DefaultInstructionProposer(), lm, components = Vector(first, second), records = records)
    assert(result.left.exists(_.isInstanceOf[ValidationError]), result.toString)
    assertEquals(lm.requests.size, 2)
  }

  // ── The length limit ────────────────────────────────────────────────────────────────────────────────────────

  test("an instruction in the limit needs one call, and the limit applies after the trim") {
    val lm = scripted(reply("  abcde  "))
    assertEquals(propose(DefaultInstructionProposer(maxChars = limit(5)), lm), Right(Map(first -> "abcde")))
    assertEquals(lm.requests.size, 1)
  }

  test("the limit counts Unicode code points") {
    // Five code points. The UTF-16 length is 6.
    val lm = scripted(reply("é猫🙂ab"))
    assertEquals(propose(DefaultInstructionProposer(maxChars = limit(5)), lm), Right(Map(first -> "é猫🙂ab")))
    assertEquals(lm.requests.size, 1)
  }

  test("an instruction that is too long gets one call that shortens it") {
    val lm = scripted(reply("abcdefghij"), shortenedReply(" a\nb "))
    assertEquals(propose(DefaultInstructionProposer(maxChars = limit(5)), lm), Right(Map(first -> "a\nb")))
    assertEquals(lm.requests.size, 2)
    val shorten = lm.requests(1)
    assert(shorten.contains("Shorten the instruction to fit within the stated limit."), shorten)
    assert(shorten.contains("abcdefghij"), shorten)
    assert(shorten.contains("The new instruction must be at most 5 characters."), shorten)
  }

  test("a shortened instruction that is too long is a failure, with no truncation") {
    val lm     = scripted(reply("abcdefghij"), shortenedReply("xxxxxxx"))
    val result = propose(DefaultInstructionProposer(maxChars = limit(5)), lm)
    assert(result.left.exists(_.isInstanceOf[ValidationError]), result.toString)
    assertEquals(lm.requests.size, 2)
  }

  test("a shortened instruction that is empty is a failure") {
    val lm     = scripted(reply("abcdefghij"), shortenedReply("  "))
    val result = propose(DefaultInstructionProposer(maxChars = limit(5)), lm)
    assert(result.left.exists(_.isInstanceOf[ValidationError]), result.toString)
    assertEquals(lm.requests.size, 2)
  }

  test("with no limit, a long instruction needs one call") {
    val long = "word " * 400
    val lm   = scripted(reply(long))
    assertEquals(propose(DefaultInstructionProposer(), lm), Right(Map(first -> long.trim)))
    assertEquals(lm.requests.size, 1)
    assert(!lm.requests.head.contains("The new instruction must be at most"), lm.requests.head)
  }

  test("the limit type rejects a value that is not positive") {
    assert(InstructionCharLimit.either(0).isLeft)
    assert(InstructionCharLimit.either(-3).isLeft)
    assert(InstructionCharLimit.either(1).isRight)
  }
