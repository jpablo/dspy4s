package dspy4s.programs

import dspy4s.adapters.contracts.Adapter
import dspy4s.adapters.contracts.AdapterInvocation
import dspy4s.adapters.contracts.FormattedPrompt
import dspy4s.adapters.contracts.ParsedOutput
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ThreadCount
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.LanguageModel
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmRequest
import dspy4s.lm.contracts.LmResponse
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.ParallelExecutor
import dspy4s.programs.runtime.ToolExecutor
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger

/** The program of a call gets the effective context as its `using` value (UPGRADE-3.2.1-to-3.4.0, decision D8;
  * PORT_GAPS G-50). The effective context is the given context, filled from the installed one. Thus the program
  * resolves its model and its adapter from the same context as the callbacks and the workers.
  *
  * The class gives a context with a language model only. Each test installs an adapter.
  */
class EffectiveContextSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** Gives the text "Paris" for each call. */
  private object ParisLm extends LanguageModel:
    override val id: String                                                                    = "paris"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Right(LmResponse(outputs = Vector(LmOutput(text = "Paris"))))

  /** Gives the text of the model as the value of each output field, and counts its `format` calls. */
  private final class CountingAdapter(override val name: String) extends Adapter:
    val formats: AtomicInteger                                                                                   = AtomicInteger(0)
    override def format(invocation: AdapterInvocation)(using RuntimeContext): Either[DspyError, FormattedPrompt] =
      val _ = formats.incrementAndGet()
      Right(FormattedPrompt(messages = Vector(Message(role = MessageRole.User, text = Some("question")))))
    override def parse(invocation: AdapterInvocation, output: LmOutput)(using
        RuntimeContext
    ): Either[DspyError, ParsedOutput] =
      Right(ParsedOutput(values = rec(invocation.layout.outputFields.map(_.name := output.text)*)))

  private given RuntimeContext = RuntimeContext(lm = Some(ParisLm))

  private val layout = SignatureDsl.parse("question -> answer").toOption.get

  private def question: DynamicValue.Record = rec("question" := "Capital of France?")

  private def installing[A](adapter: Adapter)(body: => A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(adapter = Some(adapter)))(body)

  // ── A field that the given context does not set ──────────────────────────────────────────────────────────────

  test("a Predict call with a given model only uses the adapter of the installed context") {
    val installed = CountingAdapter("installed")

    val result = installing(installed) {
      Predict(Signature.fromString("question -> answer"))((question = "Capital of France?"))
    }

    assertEquals(result.map(_.output.answer), Right("Paris"))
    assertEquals(installed.formats.get, 1)
  }

  test("a parallel worker with a given model only uses the adapter of the installed context") {
    val installed = CountingAdapter("installed")
    val program   = DynamicPredict(layout)

    val result = installing(installed) {
      ParallelExecutor(numThreads = ThreadCount(2)).executeEither(
        task = (_: Int) => program(ProgramCall(question)).map(prediction => lookupString(prediction.output, "answer")),
        data = Vector(1, 2, 3)
      )
    }

    assertEquals(result.map(_.results.flatten), Right(Vector.fill(3)("Paris")))
    assertEquals(installed.formats.get, 3)
  }

  test("Parallel.run with a given model only uses the adapter of the installed context") {
    val installed = CountingAdapter("installed")
    val program   = DynamicPredict(layout)

    val result = installing(installed) {
      Parallel(numThreads = Some(ThreadCount(2))).run(Vector.fill(3)(program -> ProgramCall(question)))
    }

    assertEquals(
      result.map(_.results.flatten.map(raw => lookupString(raw.values, "answer"))),
      Right(Vector.fill(3)("Paris"))
    )
    assertEquals(installed.formats.get, 3)
  }

  test("a tool gets the effective context as its using value") {
    val installed = CountingAdapter("installed")
    val tool      = ToolFunction("sees_installed_adapter") { _ =>
      Right(DynamicValues.fromAny(summon[RuntimeContext].adapter.exists(_ eq installed)))
    }

    val result = installing(installed)(ToolExecutor.run(tool, DynamicValue.Record.empty))

    assertEquals(result, Right(DynamicValues.fromAny(true)))
  }

  // ── A field that the given context sets ──────────────────────────────────────────────────────────────────────

  test("the adapter of the given context wins over the installed adapter in a call and in a parallel worker") {
    val givenAdapter     = CountingAdapter("given")
    val installedAdapter = CountingAdapter("installed")
    val context          = RuntimeContext(lm = Some(ParisLm), adapter = Some(givenAdapter))
    val program          = DynamicPredict(layout)

    val (single, parallel) = installing(installedAdapter) {
      val call   = program(ProgramCall(question))(using context)
      val worker = ParallelExecutor(numThreads = ThreadCount(2)).executeEither(
        task = (_: Int) => program(ProgramCall(question))(using context).map(_.output),
        data = Vector(1, 2)
      )(using context)
      (call, worker)
    }

    assertEquals(single.map(prediction => lookupString(prediction.output, "answer")), Right("Paris"))
    assertEquals(parallel.map(_.results.flatten.size), Right(2))
    assertEquals(givenAdapter.formats.get, 3)
    assertEquals(installedAdapter.formats.get, 0)
  }
