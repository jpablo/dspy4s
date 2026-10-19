package dspy4s.programs

import dspy4s.core.contracts.{
  ReplCodeInterpreter, ReplInterpreterFactory, RuntimeContext, SandboxOutputField, SandboxTool
}
import dspy4s.core.runtime.{LocalPythonInterpreter, RuntimeEnvironment}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.{Predict, RLM}
import dspy4s.signatures.Signature
import munit.FunSuite

/** The action and extract instructions of `RLM` (upstream `RLM._build_signatures` and
  * `_action_signature_for_current_factory` in `dspy/predict/rlm.py`), and the execution text of the interpreter factory
  * (upgrade item I7). The expected texts are upstream texts, with no sub-agent text (upgrade item P8).
  */
class RLMInstructionsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A factory with the execution text `text`. The tests do not run its interpreters. */
  private def factoryWith(text: String): ReplInterpreterFactory =
    new ReplInterpreterFactory:
      override def apply(tools: Vector[SandboxTool], outputFields: Vector[SandboxOutputField]): ReplCodeInterpreter =
        LocalPythonInterpreter.factory()(tools, outputFields)

      override val executionInstructions: String = text

  /** A factory from a function literal: it has no execution text. */
  private val plainFactory: ReplInterpreterFactory =
    (tools, outputFields) => LocalPythonInterpreter.factory()(tools, outputFields)

  private val signature = Signature.fromString("context, query -> answer")

  private def instructionsOf(predict: Predict[RLM.ActionInputs, RLM.ActionStep]): String =
    predict.signature.layout.instructions.getOrElse("")

  private def withInstructions[I, O](rlm: RLM[I, O], text: String): RLM[I, O] =
    rlm.copy(actionPredictOverride =
      Some(rlm.actionPredict.copy(signature = rlm.actionPredict.signature.withInstructions(Some(text))))
    )

  test("with no factory, the action instructions have the execution text of the Deno sandbox") {
    val text = instructionsOf(RLM(baseSignature = signature).actionPredict)
    assert(
      text.contains(
        "This is an iterative process.\n\nExecution environment:\n" +
          RLM.defaultInterpreterFactory.executionInstructions + "\n\nAvailable:\n"
      ),
      text
    )
    assert(!text.contains("Standard libraries"), text)
  }

  test("the construction takes the factory of the runtime context when the program has none") {
    val context = RuntimeContext(interpreterFactory = Some(LocalPythonInterpreter.factory()))
    val rlm     = RuntimeEnvironment.withSettings(context)(RLM(baseSignature = signature))
    assert(instructionsOf(rlm.actionPredict).contains(LocalPythonInterpreter.ExecutionInstructions))
  }

  test("a factory with no execution text gives no part for the execution environment") {
    val text = instructionsOf(RLM(baseSignature = signature, interpreterFactory = Some(plainFactory)).actionPredict)
    assert(!text.contains("Execution environment:"), text)
    assert(text.contains("This is an iterative process.\n\nAvailable:\n"), text)
  }

  test("the output fields, the tool docs, and the extract instructions are the upstream texts") {
    val rlm = RLM(
      baseSignature = Signature.fromString("context -> count: int, flag: bool"),
      tools = Vector(ToolFunction.fromMethod(ProgramPromptTools.search)),
      interpreterFactory = Some(plainFactory)
    )
    val text = instructionsOf(rlm.actionPredict)
    assert(
      text.startsWith(
        "Given the fields `context`, produce the fields `count`, `flag`.\n\n" +
          "You are tasked with producing the following outputs given the inputs `context`:\n" +
          "- {count}        # note: the value you produce must be a single int value\n" +
          "- {flag}        # note: the value you produce must be True or False\n\n"
      ),
      text
    )
    assert(text.contains("- `SUBMIT(count, flag)` - submit final output when done\n"), text)
    assert(
      text.endsWith(
        "When done, call SUBMIT() with your output.\n" +
          "Additional tools available (use these instead of standard library equivalents):\n" +
          "- `search(query: string)` - Search the web for a query."
      ),
      text
    )
    assertEquals(
      rlm.extractSignature.instructions,
      Some(
        "The trajectory was generated with the following objective: \n" +
          "Given the fields `context`, produce the fields `count`, `flag`.\n\n\n" +
          "Based on the REPL trajectory, extract the final outputs now.\n\n" +
          "            Review your trajectory to see what information you gathered and what values you computed, " +
          "then provide the final outputs."
      )
    )
  }

  test("a call on a factory with the text of the construction uses the action predict with no change") {
    val rlm = RLM(baseSignature = signature, interpreterFactory = Some(factoryWith("Text A.")))
    assert(rlm.actionPredictFor(factoryWith("Text A.")) eq rlm.actionPredict)
  }

  test("a call on a factory with an other text replaces the rules and keeps the other changes of an optimizer") {
    val base  = RLM(baseSignature = signature, interpreterFactory = Some(factoryWith("Text A.")))
    val tuned = instructionsOf(base.actionPredict).replace("You are tasked", "Be careful. You are tasked")
    val rlm   = withInstructions(base, tuned)
    assertEquals(
      instructionsOf(rlm.actionPredictFor(factoryWith("Text B."))),
      tuned.replace("\nExecution environment:\nText A.\n", "\nExecution environment:\nText B.\n")
    )
  }

  test("a call on a factory with an other text adds the rules when an optimizer removed them") {
    val base = RLM(baseSignature = signature, interpreterFactory = Some(factoryWith("Text A.")))
    val rlm  = withInstructions(base, "Custom instructions.")
    assertEquals(
      instructionsOf(rlm.actionPredictFor(factoryWith("Text B."))),
      "Custom instructions.\nExecution environment:\nText B.\n"
    )
  }

  test("a call on a factory with no execution text removes the rules") {
    val rlm  = RLM(baseSignature = signature, interpreterFactory = Some(factoryWith("Text A.")))
    val text = instructionsOf(rlm.actionPredictFor(plainFactory))
    assert(!text.contains("Execution environment:"), text)
    assert(text.contains("This is an iterative process.\n\nAvailable:\n"), text)
  }
