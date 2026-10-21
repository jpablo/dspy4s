package dspy4s.programs

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.RLM
import dspy4s.programs.strategies.RLMReplProtocol
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The name checks of RLM (upstream `_normalize_tools` and `_validate_namespace` in `dspy/predict/rlm.py`, 74194bfab),
  * its truncation with an odd limit (35ef21f16), and the text of its iteration input.
  */
class RLMNamespaceSuite extends FunSuite:

  private def tool(toolName: String): ToolFunction =
    new ToolFunction:
      override val name: String                                            = toolName
      override def invoke(args: DynamicValue.Record)(using RuntimeContext) = Right(DynamicValues.fromAny("x"))

  private val qaSignature = Signature.fromString("context, query -> answer")

  /** `build` throws an `IllegalArgumentException`. `label` names the case in a failure. */
  private def rejects(label: String)(build: => Any): Unit =
    val thrown = scala.util.Try(build).failed.toOption
    assert(thrown.exists(_.isInstanceOf[IllegalArgumentException]), s"$label: expected a rejection, got $thrown")

  // ── Tools ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("two tools with the same name are rejected") {
    val error = intercept[IllegalArgumentException] {
      val _ = RLM(baseSignature = qaSignature, tools = Vector(tool("search"), tool("search")))
    }
    assert(error.getMessage.contains("search"), error.getMessage)
  }

  test("a tool name that is not a Python identifier, or that is a Python keyword, is rejected") {
    Vector("get-weather", "1tool", "has space", "", "class", "lambda", "None").foreach { name =>
      rejects(name)(RLM(baseSignature = qaSignature, tools = Vector(tool(name))))
    }
  }

  test("a tool name that the sandbox facade owns is rejected") {
    Vector("dspy", "_dspy_state", "__dspy_budget").foreach { name =>
      rejects(name)(RLM(baseSignature = qaSignature, tools = Vector(tool(name))))
    }
  }

  test("valid tool names are accepted") {
    val program =
      RLM(baseSignature = qaSignature, tools = Vector(tool("get_weather"), tool("search2"), tool("_helper")))
    assertEquals(program.tools.map(_.name), Vector("get_weather", "search2", "_helper"))
  }

  // ── Fields ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("an input field with a reserved name is rejected") {
    rejects("print")(RLM(baseSignature = Signature.fromString("print, query -> answer")))
    rejects("llm_query")(RLM(baseSignature = Signature.fromString("llm_query -> answer")))
    rejects("dspy")(RLM(baseSignature = Signature.fromString("dspy, query -> answer")))
  }

  test("an input field with the name of a tool is rejected") {
    val error = intercept[IllegalArgumentException] {
      val _ = RLM(baseSignature = Signature.fromString("lookup, query -> answer"), tools = Vector(tool("lookup")))
    }
    assert(error.getMessage.contains("lookup"), error.getMessage)
  }

  test("an output field named trajectory or final_reasoning is rejected") {
    rejects("trajectory")(RLM(baseSignature = Signature.fromString("query -> trajectory")))
    rejects("final_reasoning")(RLM(baseSignature = Signature.fromString("query -> answer, final_reasoning")))
  }

  // ── Truncation ──────────────────────────────────────────────────────────────────────────────────────────────

  test("an output block with an odd limit keeps exactly that many characters") {
    assertEquals(
      RLMReplProtocol.formatOutputBlock("abcdef", OutputCharLimit(5)),
      "Output (6 chars):\nab\n\n... (1 characters omitted) ...\n\ndef"
    )
    assertEquals(
      RLMReplProtocol.formatOutputBlock("abcdef", OutputCharLimit(1)),
      "Output (6 chars):\n\n\n... (5 characters omitted) ...\n\nf"
    )
  }

  test("a variable preview with an odd limit keeps exactly that many characters") {
    def preview(limit: Int): String =
      RLM.ReplVariable.fromValue("value", DynamicValues.fromAny("abcdef"), None, limit).preview
    assertEquals(preview(5), "ab...def")
    assertEquals(preview(1), "...f")
    assertEquals(preview(6), "abcdef")
  }

  // ── Prompt text ─────────────────────────────────────────────────────────────────────────────────────────────

  test("the iteration input says max_iters, as upstream") {
    val iteration = RLM(baseSignature = qaSignature).actionSignature.inputFields.find(_.name == "iteration")
    assertEquals(iteration.flatMap(_.description), Some("Current iteration number (1-indexed) out of max_iters"))
  }
