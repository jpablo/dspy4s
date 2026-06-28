package dspy4s.optimize

import dspy4s.programs.Predictors

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicValues, Example, FieldRole, FieldSpec, RuntimeContext, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.programs.DynamicPredict
import munit.FunSuite

/** Offline InferRules suite. One scripted LM serves two sub-tasks through the ambient model:
  *
  *   1. Rule induction. The induction `DynamicPredict` asks for `natural_language_rules`; the LM hands back a rule
  *      block carrying [[RuleToken]].
  *   2. The actual task. The task `DynamicPredict` answers a `question`. The instruction-aware adapter renders the
  *      ACTIVE instruction into the prompt, so the LM returns the gold answer ONLY once InferRules has appended the
  *      induced rules (the instruction then carries [[RuleToken]]); otherwise it returns a wrong answer.
  */
class InferRulesSuite extends FunSuite:

  private val RuleToken = "RULE_TOKEN"
  private val gold: Map[String, String] = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3", "q4" -> "a4")

  /** Renders the active instruction + input fields into one message, tagged with the requested output field so the
    * LM can tell rule-induction (`natural_language_rules`) from task (`answer`). */
  private object InstructionAwareAdapter extends Adapter:
    override val name: String = "instruction-aware"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val instr  = invocation.layout.instructions.getOrElse("")
      val out    = invocation.layout.outputFields.map(_.name).mkString(",")
      val inputs = invocation.layout.inputFields
        .map(f => s"${f.name}=[${DynamicValues.recordGet(invocation.inputs.values, f.name).map(DynamicValues.renderText).getOrElse("")}]")
        .mkString(" ")
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some(s"OUT=[$out] INSTRUCTION=[$instr] $inputs")))))

    override def parse(layout: SignatureLayout, output: LmOutput)(using RuntimeContext): Either[DspyError, ParsedOutput] =
      val outField = layout.outputFields.headOption.map(_.name).getOrElse("answer")
      Right(ParsedOutput(values = DynamicValues.record(outField := output.text)))

  private final class ScriptedLm extends LanguageModel:
    override val id: String   = "scripted-infer-rules"
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val text = request.messages.lastOption.flatMap(_.text).getOrElse("")
      val out =
        if text.contains("natural_language_rules") then s"Always answer with the gold label. $RuleToken"
        else
          val q = extractBetween(text, "question=[", "]")
          if text.contains(RuleToken) then gold.getOrElse(q, "unknown") else "WRONG"
      Right(LmResponse(outputs = Vector(LmOutput(text = out))))

  private def extractBetween(s: String, start: String, end: String): String =
    val i = s.indexOf(start)
    if i < 0 then "" else { val from = i + start.length; val j = s.indexOf(end, from); if j < 0 then "" else s.substring(from, j) }

  private def settings: RuntimeContext = RuntimeContext(lm = Some(new ScriptedLm), adapter = Some(InstructionAwareAdapter))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private val taskLayout: SignatureLayout =
    SignatureLayout.of(
      name = "QA",
      fields = Vector(FieldSpec("question", FieldRole.Input), FieldSpec("answer", FieldRole.Output)),
      instructions = Some("BASELINE: answer the question.")
    )

  private def ex(q: String): Example = Example(DynamicValues.record("question" := q, "answer" := gold(q)), inputKeys = Set("question"))
  private val metric = new dspy4s.evaluate.metrics.ExactMatch(answerField = "answer")

  private def instructionOf(report: dspy4s.optimize.contracts.OptimizationReport[DynamicPredict]): String =
    summon[Predictors[DynamicPredict]].read(report.bestProgram).head.layout.instructions.getOrElse("")

  test("InferRules induces rules, appends them to the instruction, and the rule-augmented program wins") {
    val student   = DynamicPredict(layout = taskLayout)
    val optimizer = new InferRules[DynamicPredict](InferRulesConfig(metric = metric, numCandidates = 2, numRules = 5))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset = Vector(ex("q1"), ex("q2")), valset = Some(Vector(ex("q3"), ex("q4"))))
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      val report = result.toOption.get

      val instr = instructionOf(report)
      assert(instr.contains(RuleToken), s"the winning instruction must carry the induced rules; got: $instr")
      assert(instr.contains("Please adhere to the following rules"), instr)
      assert(instr.startsWith("BASELINE: answer the question."), "rules are appended to the ORIGINAL instruction")
      assertEquals(report.metadata.get("best_score"), Some(100.0)) // gold on every val example once rules apply
    }
  }

  test("InferRules splits the trainset 50/50 when no valset is given") {
    val student   = DynamicPredict(layout = taskLayout)
    val optimizer = new InferRules[DynamicPredict](InferRulesConfig(metric = metric, numCandidates = 1, numRules = 3))
    RuntimeEnvironment.withSettings(settings) {
      given RuntimeContext = RuntimeEnvironment.current
      val result = optimizer.compile(student, trainset = Vector(ex("q1"), ex("q2"), ex("q3"), ex("q4")))
      assert(result.isRight, s"compile failed: ${result.left.toOption}")
      assert(instructionOf(result.toOption.get).contains(RuleToken))
    }
  }
