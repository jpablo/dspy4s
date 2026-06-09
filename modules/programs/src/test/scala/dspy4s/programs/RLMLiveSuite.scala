package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.util.control.NonFatal

/** Live RLM end-to-end against the REAL Deno+Pyodide sandbox (assume-skipped without `deno`): a scripted action
  * LM drives real Python through the default interpreter, proving the whole tower in one flow — input variables
  * injected into the REPL, state persisting across iterations, the `llm_query` host-tool bridge calling the
  * sub-LM from INSIDE sandboxed code, and `SUBMIT` carrying a value computed from all three back out. */
class RLMLiveSuite extends FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(180, "s")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private lazy val denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  private final class ScriptedLm(responses: Vector[String]) extends LanguageModel:
    private val idx = new AtomicInteger(0)
    override val id: String = "scripted-rlm-live"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val i = idx.getAndIncrement()
      val text = if i < responses.size then responses(i) else ""
      Right(LmResponse(outputs = Vector(LmOutput(text = text))))

  private object ActionAdapter extends Adapter:
    override val name: String = "rlm-live-adapter"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("ignored")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val names = layout.outputFields.map(_.name)
      if names.contains("code") then
        val parts = output.text.split("\\|\\|", 2)
        Right(ParsedOutput(values = rec("reasoning" := parts(0).trim, "code" := (if parts.length > 1 then parts(1) else ""))))
      else Right(ParsedOutput(values = rec(names.map(_ := output.text)*)))

  test("live: RLM explores an injected variable, calls llm_query from sandboxed code, and SUBMITs the result") {
    assume(denoAvailable, "deno not installed — skipping live RLM test")

    val subLm: LanguageModel = new LanguageModel:
      override val id: String = "sub-lm"
      override val mode: LmMode = LmMode.Chat
      override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
        Right(LmResponse(outputs = Vector(LmOutput(text = "SUB"))))

    // Step 1: real Python over the INJECTED `context` variable (REPL state: defines n).
    // Step 2: calls llm_query — the host-tool bridge — from inside the sandbox (REPL state: defines hint).
    // Step 3: SUBMITs a value computed from both, proving state persisted across all three executions.
    val actionLm = new ScriptedLm(Vector(
      "measure||```python\nn = len(context)\nprint(n)\n```",
      "ask the sub-lm||```python\nhint = llm_query(prompt='summarize: ' + context)\nprint(hint)\n```",
      "combine||```python\nSUBMIT(answer=f\"{n}-{hint}\")\n```"
    ))

    val program = RLM(
      baseSignature = Signature.fromString("context -> answer"),
      maxIterations = 5,
      verbose = true, // step-by-step stderr log — this suite doubles as a by-hand diagnostic run
      subLm = Some(subLm)
    )

    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(actionLm), adapter = Some(ActionAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = program.apply((context = "0123456789"))
      assert(result.isRight, result.toString)
      val pred = result.toOption.get
      assertEquals(pred.output.answer, "10-SUB") // len("0123456789") + the sub-LM's reply, both via REPL state
      val traj = pred.raw.asString("trajectory").toOption.getOrElse("")
      assert(traj.contains("=== Step 1 ===") && traj.contains("=== Step 3 ==="), traj)
    }
  }
