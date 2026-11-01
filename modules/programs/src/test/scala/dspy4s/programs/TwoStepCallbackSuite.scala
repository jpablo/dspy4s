package dspy4s.programs

import dspy4s.adapters.TwoStepAdapter
import dspy4s.core.contracts.{
  AdapterEndEvent, AdapterStartEvent, CallbackEvent, CallbackHandler, DspyError, DynamicValues, LmServerError,
  LmStartEvent, ModuleStartEvent, ParseError, RuntimeContext
}
import dspy4s.core.runtime.{RuntimeEnvironment, StreamProgress}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.programs.support.ScriptedLm
import dspy4s.signatures.Signature
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** The callback events of a `Predict` with a `TwoStepAdapter`. Upstream `TwoStepAdapter.parse` calls
  * `ChatAdapter()(lm=extraction_model, ...)` (`dspy/adapters/two_step_adapter.py`). Thus the extraction step sends the
  * adapter events of `format` and `parse` (`with_callbacks` in `dspy/adapters/base.py`) and the LM events of its call
  * (`dspy/clients/base_lm.py`), inside the scope of the `parse` of the two-step adapter.
  */
class TwoStepCallbackSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** An extraction model. Each call gives the value of `reply`. */
  private final class Extractor(reply: => Either[DspyError, LmResponse]) extends LanguageModel:
    val calls: AtomicInteger                                                                   = AtomicInteger(0)
    override val id: String                                                                    = "extractor"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val _ = calls.incrementAndGet()
      reply

  private def text(reply: String): Either[DspyError, LmResponse] =
    Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  private val completion = "The capital is Paris."

  private def main: LanguageModel = new ScriptedLm(Vector(completion), id = "main")

  private val qa = Signature.fromString("question -> answer")

  /** Two outputs, so that the chat parser has no plain-text rule for one output. */
  private val qaNote = Signature.fromString("question -> answer, note")

  /** A reply that neither the chat parser nor the JSON parser can read. */
  private val unparseable = "no fields here"

  /** The result of `body` with a `TwoStepAdapter(extractor)`, and the events of the call. */
  private def run[A](extractor: LanguageModel)(body: RuntimeContext ?=> A): (A, Vector[CallbackEvent]) =
    val events   = ArrayBuffer.empty[CallbackEvent]
    val callback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = events += event
    val context = RuntimeContext(lm = Some(main), adapter = Some(TwoStepAdapter(extractor)))
    val result  = RuntimeEnvironment.withSettings(context) {
      RuntimeEnvironment.withCallbacks(Vector(callback)) {
        body(using RuntimeEnvironment.current)
      }
    }
    (result, events.toVector)

  /** An adapter scope as its name, its phase, and the other input of that phase: `signature` for a format, and `index`
    * for a parse.
    */
  private def adapterScope(start: AdapterStartEvent): String =
    def input(name: String) = DynamicValues.recordGet(start.inputs, name).map(DynamicValues.renderText).getOrElse("-")
    input("phase") match
      case "format" => s"${start.adapterName} format ${input("signature")}"
      case phase    => s"${start.adapterName} $phase ${input("index")}"

  /** One line for each event: `start` or `end`, the scope of the event, and the scope of its parent id. The fold keeps
    * the open scopes as a stack, and compares each id with that stack: the suites of this module run in parallel, and
    * `resetForTests` of another suite can give two scopes of one call the same id. A parent id that is not the id of
    * the enclosing scope, or an end event that is not the end of the innermost scope, shows as `wrong`.
    */
  private def lines(events: Vector[CallbackEvent]): Vector[String] =
    def parent(event: CallbackEvent, enclosing: List[(String, String)]): String =
      (event.parentCallId, enclosing.headOption) match
        case (None, None)                                      => "root"
        case (Some(id), Some((callId, scope))) if id == callId => scope
        case (other, _)                                        => s"wrong parent $other"
    val (_, result) = events.foldLeft((List.empty[(String, String)], Vector.empty[String])) { (state, event) =>
      val (open, out)            = state
      def started(scope: String) = ((event.callId -> scope) :: open, out :+ s"start $scope < ${parent(event, open)}")
      event match
        case start: ModuleStartEvent  => started(s"module ${start.moduleName}")
        case start: LmStartEvent      => started(s"lm ${start.modelId}")
        case start: AdapterStartEvent => started(adapterScope(start))
        case end                      =>
          val scope = open.headOption.collect { case (callId, name) if callId == end.callId => name }
          (open.drop(1), out :+ s"end ${scope.getOrElse("wrong scope")} < ${parent(end, open.drop(1))}")
    }
    result

  /** The outputs of the end events of the adapter `name`, in order. */
  private def adapterOutputs(events: Vector[CallbackEvent], name: String): Vector[Either[DspyError, Any]] =
    events.collect { case end: AdapterEndEvent if end.adapterName == name => end.output }

  /** The events of a `Predict` call before the extraction step. */
  private val beforeExtraction = Vector(
    "start module predict < root",
    "start two_step format StringSignature < module predict",
    "end two_step format StringSignature < module predict",
    "start lm main < module predict",
    "end lm main < module predict",
    "start two_step parse 0 < module predict"
  )

  /** The events of a `Predict` call after the extraction step. */
  private val afterExtraction = Vector(
    "end two_step parse 0 < module predict",
    "end module predict < root"
  )

  /** The events of one attempt of the extraction step with the adapter `adapter`, inside the parse of the two-step
    * adapter.
    */
  private def extractionAttempt(adapter: String) =
    Vector(
      s"start $adapter format StringSignatureExtractor < two_step parse 0",
      s"end $adapter format StringSignatureExtractor < two_step parse 0",
      "start lm extractor < two_step parse 0",
      "end lm extractor < two_step parse 0",
      s"start $adapter parse 0 < two_step parse 0",
      s"end $adapter parse 0 < two_step parse 0"
    )

  test("the extraction step sends the chat format, LM, and chat parse events inside the two-step parse") {
    val extractor        = Extractor(text("[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))
    val (result, events) = run(extractor)(Predict(qa)((question = "capital?")))

    assertEquals(result.map(_.output.answer), Right("Paris"))
    assertEquals(lines(events), beforeExtraction ++ extractionAttempt("chat") ++ afterExtraction)
  }

  test("after a parse error of the chat format, the JSON events follow inside the same two-step parse") {
    val extractor        = Extractor(text("""{"answer": "Paris", "note": "the capital"}"""))
    val (result, events) = run(extractor)(Predict(qaNote)((question = "capital?")))

    assertEquals(result.map(_.output.answer), Right("Paris"))
    assertEquals(extractor.calls.get(), 2)
    assertEquals(
      lines(events),
      beforeExtraction ++ extractionAttempt("chat") ++ extractionAttempt("json") ++ afterExtraction
    )
    adapterOutputs(events, "chat").last match
      case Left(_: ParseError) => ()
      case other               => fail(s"expected a parse error of the chat parse, got $other")
  }

  test("after a parse error of the JSON fallback, the two-step parse ends with the wrapper error") {
    val extractor        = Extractor(text(unparseable))
    val (result, events) = run(extractor)(Predict(qaNote)((question = "capital?")))

    assertEquals(extractor.calls.get(), 2)
    assertEquals(
      lines(events),
      beforeExtraction ++ extractionAttempt("chat") ++ extractionAttempt("json") ++ afterExtraction
    )
    adapterOutputs(events, "two_step").last match
      case Left(error: ParseError) =>
        assert(error.message.startsWith("Failed to parse response from the original completion: "), error.message)
        assertEquals(error.raw, Some(completion))
      case other => fail(s"expected the wrapper error, got $other")
    assert(result.isLeft, result.toString)
  }

  test("a reply with no outputs from the extraction model gets no second call, and gives the wrapper error") {
    val extractor        = Extractor(Right(LmResponse(outputs = Vector.empty)))
    val (result, events) = run(extractor)(Predict(qa)((question = "capital?")))

    assertEquals(extractor.calls.get(), 1)
    // Upstream `ChatAdapter.__call__` parses each output, so an empty reply is no parse error.
    assertEquals(lines(events), beforeExtraction ++ extractionAttempt("chat").take(4) ++ afterExtraction)
    result match
      case Left(error: ParseError) =>
        assertEquals(
          error.message,
          "Failed to parse response from the original completion: Extraction model returned no output"
        )
        assertEquals(error.raw, Some(completion))
      case other => fail(s"expected the wrapper error, got $other")
  }

  test("an error of the extraction model gets no second call, and stays as it is") {
    val extractor        = Extractor(Left(LmServerError("the provider is down")))
    val (result, events) = run(extractor)(Predict(qa)((question = "capital?")))

    assertEquals(extractor.calls.get(), 1)
    assertEquals(lines(events), beforeExtraction ++ extractionAttempt("chat").take(4) ++ afterExtraction)
    result match
      case Left(_: LmServerError) => ()
      case other                  => fail(s"expected the error of the model, got $other")
  }

  test("an extraction call that streamed output to a listener gets no JSON call") {
    // Upstream `ChatAdapter.__call__`: `if progress.emitted ... raise`.
    val extractor = Extractor {
      StreamProgress.markEmitted()
      text(unparseable)
    }
    val (result, events) = run(extractor)(Predict(qaNote)((question = "capital?")))

    assertEquals(extractor.calls.get(), 1)
    assertEquals(lines(events), beforeExtraction ++ extractionAttempt("chat") ++ afterExtraction)
    result match
      case Left(error: ParseError) => assertEquals(error.raw, Some(completion))
      case other                   => fail(s"expected the wrapper error, got $other")
  }
