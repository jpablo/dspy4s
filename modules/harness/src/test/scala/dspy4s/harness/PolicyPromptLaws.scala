package dspy4s.harness

import dspy4s.core.contracts.{History, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LmRequest, MessageRole}
import dspy4s.programs.strategies.Predict

import Models.*

/** The laws of the request of a call to the policy (CODE_HARNESS_DESIGN.md, 10.6): what each message holds. A measured
  * request (2026-11-12) gave the rules: the fields had no description, and the large fields came after the history, so
  * the endpoint could reuse only 256 tokens of its cache at each later step.
  */
class PolicyPromptLaws extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val input =
    PolicyInput("FAKE-TASK", "FAKE-API", "FAKE-MEMORY", "FAKE-STATUS", "FAKE-OBSERVATION", History.empty)

  /** The fixed fields of `input`, in the marker form. */
  private val fixed =
    "[[ ## task ## ]]\nFAKE-TASK\n\n[[ ## apiReference ## ]]\nFAKE-API\n\n[[ ## memory ## ]]\nFAKE-MEMORY"

  /** The request of one call to the policy of the harness with `input`, through the adapter of a call to the policy. */
  private def requestOf(input: PolicyInput): LmRequest =
    val model = ScriptLm(policy("FAKE-DONE", None))
    withModel(model) {
      val leaf = Predict(Harness.policySignature(Harness.policyInstructions), name = Some("policy"))
      leaf(input)(using PolicyAdapter.around(summon[RuntimeContext])).fold(e => fail(e.message), identity): Unit
    }
    model.requests.headOption.getOrElse(fail("no request"))

  private def system(request: LmRequest): String =
    request.messages.find(_.role == MessageRole.System).flatMap(_.text).getOrElse(fail("no system message"))

  // ── The fields ──

  test("Prompt: each field of the policy has a description, and the system message names it") {
    val signature = Harness.policySignature(Harness.policyInstructions)
    val fields    = signature.layout.inputFields ++ signature.layout.outputFields
    assertEquals(fields.map(_.name).toSet, Harness.policyFields.keySet)
    val text = system(requestOf(input))
    fields.foreach { field =>
      val description = Harness.policyFields(field.name)
      assert(description.nonEmpty && text.contains(s"`${field.name}` (") && text.contains(description), field.name)
    }
  }

  // ── The order of the messages ──

  test("Prompt: the system message ends with task, apiReference and memory, and the last message has the rest") {
    // So the endpoint can reuse the start of the request from its cache at each step (2026-11-12: 256 tokens of 3,245).
    val request = requestOf(input)
    assert(system(request).endsWith(fixed), system(request))
    assertEquals(request.messages.last.role, MessageRole.User)
    val last = request.messages.last.text.getOrElse("")
    assert(last.startsWith("[[ ## status ## ]]\nFAKE-STATUS\n\n[[ ## observation ## ]]\nFAKE-OBSERVATION"), last)
    val all = request.messages.flatMap(_.text).mkString("\n")
    Vector("FAKE-TASK", "FAKE-API", "FAKE-MEMORY").foreach(value =>
      assertEquals(all.split(value, -1).length - 1, 1, value)
    )
  }

  test("Prompt: the steps of the turn come between the system message and the last message") {
    val step = dspy4s.core.contracts.DynamicValues.record(
      "observation" -> dspy4s.core.contracts.DynamicValues.string("FAKE-EARLIER"),
      "reasoning"   -> dspy4s.core.contracts.DynamicValues.string(""),
      "text"        -> dspy4s.core.contracts.DynamicValues.string("FAKE-STEP"),
      "code"        -> dspy4s.core.contracts.DynamicValues.string("println(1)")
    )
    val request = requestOf(input.copy(history = History(Vector(step))))
    assertEquals(
      request.messages.map(_.role),
      Vector(MessageRole.System, MessageRole.User, MessageRole.Assistant, MessageRole.User)
    )
    assert(request.messages(1).text.exists(_.contains("FAKE-EARLIER")), request.messages.toString)
    assert(request.messages(2).text.exists(_.contains("FAKE-STEP")), request.messages.toString)
    assert(system(request).endsWith(fixed), system(request))
  }

  test("Prompt: an empty memory comes in no message") {
    val request = requestOf(input.copy(memory = ""))
    assert(
      system(request).endsWith("[[ ## task ## ]]\nFAKE-TASK\n\n[[ ## apiReference ## ]]\nFAKE-API"),
      system(request)
    )
    assert(!request.messages.last.text.exists(_.contains("[[ ## memory ## ]]")), request.messages.last.toString)
  }

  test("Prompt: a request whose last message does not start with the fixed fields stays as it is") {
    import dspy4s.adapters.contracts.FormattedPrompt
    import dspy4s.lm.contracts.Message
    val prompt = FormattedPrompt(
      Vector(
        Message(MessageRole.System, Some("FAKE-SYSTEM")),
        Message(MessageRole.User, Some("[[ ## status ## ]]\nFAKE"))
      )
    )
    assertEquals(PolicyAdapter.moved(prompt, "[[ ## task ## ]]\nFAKE-TASK", "[[ ## task ## ]]\nFAKE-TASK"), prompt)
  }
