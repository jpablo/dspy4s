package dspy4s.programs

import dspy4s.adapters.contracts.{Adapter, AdapterInvocation, FormattedPrompt, ParsedOutput}
import dspy4s.core.contracts.{DspyError, DynamicValues, RuntimeContext, SignatureLayout}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse, Message, MessageRole}
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.algebra.Program
import dspy4s.programs.optimization.OptimizableTraversal
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The DynamicSignature bundle end-to-end: the path-dependent `predict` constructor, the optimizer surface
  * (`OptimizableTraversal` + `ProgramRunner`) over a packaged bundle program, and the cross-fiber `bridge` (eager
  * compatibility failure; a bridged pipeline composing and running). The unit-law and freshness pins live in
  * `ParameterizedCategoryLawSuite`; this suite is the usability story.
  */
class DynamicSignatureSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  private val qa           = DynamicSignature.parse("question -> answer").toOption.get
  private val judge        = DynamicSignature.parse("answer -> verdict").toOption.get
  private val incompatible = DynamicSignature.parse("context, query -> verdict").toOption.get

  /** Writes the LM's reply into the layout's FIRST output field, whichever signature is running. */
  private object FirstFieldAdapter extends Adapter:
    override val name: String = "first-field"
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("hi")))))
    override def parse(layout: SignatureLayout, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = DynamicValues.record(layout.outputFields.head.name := output.text)))

  private final class FixedLm(id0: String, reply: String) extends LanguageModel:
    override val id: String   = id0
    override val mode: LmMode = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  private def underAdapter[A](body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(adapter = Some(FirstFieldAdapter))) {
      given RuntimeContext = RuntimeEnvironment.current
      body
    }

  private def field(values: DynamicValue.Record, name: String): Option[String] =
    DynamicValues.recordGet(values, name).map(DynamicValues.renderText)

  test("s.predict() runs end-to-end: validating entry in, typed call, outputs on the raw envelope") {
    val program = qa.predict().withLm(new FixedLm("stub", "42"))
    val in      = qa.input(DynamicValues.record("question" := "meaning of life?")).toOption.get
    val out     = underAdapter(program.apply(in)).toOption.get

    assertEquals(field(out.raw.values, "answer"), Some("42"))
    // The validating entry rejects a record missing a declared field, at the boundary.
    assert(qa.input(DynamicValue.Record.empty).isLeft)
  }

  test("the optimizer surface holds over a packaged bundle program (OptimizableTraversal read/replace + record run)") {
    import qa.given
    val packaged = Program.of(qa.predict().withLm(new FixedLm("stub", "7")))
    val P        = summon[OptimizableTraversal[Program[qa.In, qa.Out]]]

    val states = P.read(packaged)
    assertEquals(states.size, 1)
    val tuned = P.replace(packaged, Vector(states.head.copy(instructions = Some("Be terse."))))
    assertEquals(P.read(tuned).head.instructions, Some("Be terse."))

    // The record-boundary run decodes through the bundle's codec, then executes the typed predict.
    val runner = summon[ProgramRunner[Program[qa.In, qa.Out]]]
    val out    = underAdapter(runner.run(packaged, ProgramCall(input = DynamicValues.record("question" := "x"))))
    assertEquals(out.toOption.map(p => field(p.values, "answer")), Some(Some("7")))
    // A record missing the declared input fails at decode, before any LM call.
    assert(underAdapter(runner.run(packaged, ProgramCall(input = DynamicValue.Record.empty))).isLeft)
  }

  test("bridge fails eagerly when the target's inputs are not covered (no base arrow, no lift)") {
    val result = DynamicSignature.bridge(qa, incompatible)
    assert(result.isLeft)
    val message = result.left.toOption.get.message
    assert(message.contains("context"), s"expected the missing fields named, got: $message")
    assert(message.contains("query"), s"expected the missing fields named, got: $message")
  }

  test("a bridged cross-fiber pipeline composes, stays parameter-transparent, and runs end-to-end") {
    import qa.given
    import judge.given
    val b        = DynamicSignature.bridge(qa, judge).toOption.get
    val p1       = Program.of(qa.predict().withLm(new FixedLm("one", "yes")))
    val p2       = Program.of(judge.predict().withLm(new FixedLm("two", "valid")))
    val pipeline = p1 >>> b >>> p2 // Program[qa.In, judge.Out]: expressible ONLY through the bridge

    // The bridge is parameter-free: the pipeline.s parameters are exactly the two predicts'.
    assertEquals(pipeline.params.size, 2)
    // Decoding is object-side: the pipeline's record boundary is qa's codec (its domain object).
    assert(summon[RecordCodec[qa.In]].decode(DynamicValues.record("question" := "?")).isRight)
    assert(summon[RecordCodec[qa.In]].decode(DynamicValue.Record.empty).isLeft)

    val in  = qa.input(DynamicValues.record("question" := "?")).toOption.get
    val out = underAdapter(pipeline.apply(ProgramCall(in))).toOption.get
    assertEquals(field(out.raw.values, "verdict"), Some("valid"))
  }
