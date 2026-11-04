package dspy4s.examples.learn

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext, ToolCall, TraceEntry}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.examples.Cheatsheet
import dspy4s.examples.learn.evaluation.Metrics
import dspy4s.examples.learn.programming.{Adapters, DogPictureExample, LanguageModels, Tools}
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt

/** Behavior of the guide examples, with a scripted model. */
class GuideBehaviorSuite extends FunSuite:

  private final class Script(reply: LmRequest => LmOutput, functionCalling: Boolean = true) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "script"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsFunctionCalling: Boolean                                              = functionCalling
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      synchronized { requests = requests :+ request }
      Right(LmResponse(outputs = Vector(reply(request))))

  private def promptOf(request: LmRequest): String = request.messages.flatMap(_.text).mkString("\n")

  private def contextOf(lm: LanguageModel): RuntimeContext =
    RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))

  private def aTrace: Vector[TraceEntry] =
    Vector(TraceEntry("predict", DynamicValue.Record.empty, DynamicValue.Record.empty))

  private def answering(text: String): Script =
    Script(_ => LmOutput(s"[[ ## reasoning ## ]]\nKnown.\n\n[[ ## answer ## ]]\n$text\n\n[[ ## completed ## ]]"))

  // ── validateContextAndAnswer ──

  private def gold: Example = Metrics.example("Who wrote Hamlet?", "Shakespeare")

  private def predicted(answer: String, context: String*): RawPrediction =
    RawPrediction(values = DynamicValues.record("answer" := answer, "context" := context.toVector))

  private def contextScore(pred: RawPrediction, trace: Vector[TraceEntry] = Vector.empty): Option[Double] =
    Metrics.validateContextAndAnswer.score(gold, pred, trace)(using RuntimeContext()).toOption

  test("validateContextAndAnswer: in an evaluation, the answer rule and the context rule each give a half") {
    assertEquals(contextScore(predicted("shakespeare", "written by shakespeare")), Some(1.0))
    assertEquals(contextScore(predicted("shakespeare", "no match")), Some(0.5))
    assertEquals(contextScore(predicted("marlowe", "a play by marlowe")), Some(0.5))
    assertEquals(contextScore(predicted("marlowe", "no match")), Some(0.0))
  }

  test("validateContextAndAnswer: with a trace, the two rules must pass") {
    assertEquals(contextScore(predicted("shakespeare", "written by shakespeare"), aTrace), Some(1.0))
    assertEquals(contextScore(predicted("shakespeare", "no match"), aTrace), Some(0.0))
    assertEquals(contextScore(predicted("marlowe", "a play by marlowe"), aTrace), Some(0.0))
  }

  test("validateContextAndAnswer: only the answer becomes lowercase, as upstream") {
    assertEquals(contextScore(predicted("Shakespeare", "Written by Shakespeare")), Some(0.5))
  }

  test("validateContextAndAnswer: a prediction with no context or no answer is an error") {
    def scoreOf(pred: RawPrediction) = Metrics.validateContextAndAnswer.score(gold, pred)(using RuntimeContext())
    assert(scoreOf(RawPrediction(values = DynamicValues.record("answer" := "Shakespeare"))).isLeft)
    assert(scoreOf(RawPrediction(values = DynamicValues.record("context" := Vector("written by Shakespeare")))).isLeft)
  }

  // ── the tweet metric ──

  private def judge(correct: Boolean, engaging: Boolean): Script =
    Script { request =>
      val answer = if promptOf(request).contains("The text should answer") then correct else engaging
      LmOutput(s"[[ ## assessment_answer ## ]]\n${if answer then "True" else "False"}\n\n[[ ## completed ## ]]")
    }

  private def tweetScore(lm: LanguageModel, tweet: String, trace: Vector[TraceEntry] = Vector.empty) =
    val pred = RawPrediction(values = DynamicValues.record("output" := tweet))
    Metrics.tweetMetric.score(Metrics.example("What is the capital of France?", "Paris"), pred, trace)(using
      contextOf(lm)
    )

  test("tweetMetric: a short tweet with a judge that says yes has the full score") {
    val lm = judge(correct = true, engaging = true)
    assertEquals(tweetScore(lm, "Paris!").toOption, Some(1.0))
    assertEquals(lm.requests.size, 2)
    assert(promptOf(lm.requests.head).contains("Assess the quality of a tweet"), promptOf(lm.requests.head))
    assert(promptOf(lm.requests.head).contains("answer `What is the capital of France?` with `Paris`"))
  }

  test("tweetMetric: a correct tweet that is not engaging has half of the score") {
    assertEquals(tweetScore(judge(correct = true, engaging = false), "Paris.").toOption, Some(0.5))
  }

  test("tweetMetric: a wrong tweet has a score of 0") {
    assertEquals(tweetScore(judge(correct = false, engaging = true), "Lyon!").toOption, Some(0.0))
  }

  test("tweetMetric: a tweet over 280 characters has a score of 0") {
    assertEquals(tweetScore(judge(correct = true, engaging = true), "x" * 281).toOption, Some(0.0))
  }

  test("tweetMetric: the limit counts code points, as Python len") {
    assertEquals(tweetScore(judge(correct = true, engaging = true), "x" * 280).toOption, Some(1.0))
    assertEquals(tweetScore(judge(correct = true, engaging = true), "😀" * 280).toOption, Some(1.0))
  }

  test("tweetMetric: with a trace, only the full score passes") {
    assertEquals(tweetScore(judge(correct = true, engaging = true), "Paris!", aTrace).toOption, Some(1.0))
    assertEquals(tweetScore(judge(correct = true, engaging = false), "Paris.", aTrace).toOption, Some(0.0))
  }

  test("tweetMetric: a failed judge call is an error, not a score of 0") {
    val lm = Script(_ => LmOutput("this reply has no fields"))
    assert(tweetScore(lm, "Paris!").isLeft)
  }

  test("tweetMetric: a prediction with no output is an error, and the judge does not run") {
    val lm     = judge(correct = true, engaging = true)
    val result =
      Metrics.tweetMetric.score(gold, RawPrediction(values = DynamicValues.record("answer" := "Paris")))(using
        contextOf(lm)
      )
    assert(result.isLeft, result.toString)
    assertEquals(lm.requests.size, 0)
  }

  // ── the tools guide ──

  test("the manual tool path runs the tool call of a scripted model") {
    val lm = Script(_ =>
      LmOutput("", toolCalls = Vector(ToolCall("weather", DynamicValues.record("city" := "New York"), Some("call_1"))))
    )
    val calls = Tools.ManualToolExample.call("What's the weather in New York?")(using contextOf(lm))
      .fold(error => fail(error.message), identity)
    assertEquals(calls.map((call, _) => call.name), Vector("weather"))
    assertEquals(
      calls.map((_, result) => result.map(DynamicValues.renderText).toOption),
      Vector(Some("The weather in New York is sunny"))
    )
    assert(DynamicValues.recordGet(lm.requests.head.options, "tools").isDefined, "the request has no provider tools")
    assert(promptOf(lm.requests.head).contains("Signature for manual tool handling."), promptOf(lm.requests.head))
  }

  test("the manual tool path shows the tools in the prompt of a model with no function calling") {
    val lm = Script(
      _ =>
        LmOutput(
          "[[ ## outputs ## ]]\n{\"tool_calls\": [{\"name\": \"weather\", \"args\": {\"city\": \"New York\"}}]}" +
            "\n\n[[ ## completed ## ]]"
        ),
      functionCalling = false
    )
    val calls = Tools.ManualToolExample.call("What's the weather in New York?")(using contextOf(lm))
      .fold(error => fail(error.message), identity)
    assertEquals(
      calls.map((_, result) => result.map(DynamicValues.renderText).toOption),
      Vector(Some("The weather in New York is sunny"))
    )
    assert(DynamicValues.recordGet(lm.requests.head.options, "tools").isEmpty, "the request has provider tools")
    val prompt = promptOf(lm.requests.head)
    assert(prompt.contains("weather, whose description is"), prompt)
    // The description also has the word "city", so the test reads the arguments in the form of the JSON list.
    assert(prompt.contains("""It takes arguments {\"city\": {\"type\": \"string\"}}."""), prompt)
  }

  test("the prompt of a model with no function calling shows the arguments of each tool of the manual path") {
    val lm = Script(
      _ => LmOutput("[[ ## outputs ## ]]\n{\"tool_calls\": []}\n\n[[ ## completed ## ]]"),
      functionCalling = false
    )
    assert(Tools.ManualToolExample.call("What's the weather?")(using contextOf(lm)).isRight)
    val prompt = promptOf(lm.requests.head)
    assert(prompt.contains("""It takes arguments {\"city\": {\"type\": \"string\"}}."""), prompt)
    assert(prompt.contains("""It takes arguments {\"expression\": {\"type\": \"string\"}}."""), prompt)
  }

  test("the manual tool path gives an error for a name that is not a tool") {
    given RuntimeContext = RuntimeContext()
    assert(Tools.ManualToolExample.execute(ToolCall("search", DynamicValue.Record.empty)).isLeft)
  }

  test("the calculator of the manual tool path adds numbers") {
    assertEquals(Tools.ManualToolExample.calculator("1 + 2.5"), "The result is 3.5")
    assertEquals(Tools.ManualToolExample.calculator("one"), "Invalid expression")
  }

  test("a tool has the properties of snippet 4") {
    val (name, description, arguments) = Tools.ToolPropertiesExample.properties
    assertEquals(name, "my_function")
    assertEquals(description, "A sample function with parameters.")
    assertEquals(arguments.map((argument, _) => argument), Vector("param1", "param2"))
  }

  test("the good tool lists its arguments, and `units` has a default") {
    given RuntimeContext = RuntimeContext()
    val tool             = Tools.GoodToolExample.goodTool
    assertEquals(tool.argSchema.map((argument, _) => argument), Vector("city", "units"))
    def text(args: DynamicValue.Record): Option[String] = tool.invoke(args).map(DynamicValues.renderText).toOption
    assertEquals(text(DynamicValues.record("city" := "Paris")), Some("Weather in Paris: 25°C, sunny"))
    assertEquals(
      text(DynamicValues.record("city" := "Paris", "units" := "fahrenheit")),
      Some("Weather in Paris: 25°F, sunny")
    )
    assertEquals(text(DynamicValues.record("city" := " ")), Some("Error: City name cannot be empty"))
  }

  // ── the cheatsheet ──

  test("asyncified gives the answer of a scripted model") {
    val answer = Await.result(
      Cheatsheet.asyncified("What is DSPy")(using contextOf(answering("A framework")), ExecutionContext.global),
      30.seconds
    )
    assertEquals(answer.toOption, Some("A framework"))
  }

  test("the BasicQA program of the cheatsheet sends its instructions") {
    val lm = answering("blue")
    assertEquals(Cheatsheet.chainOfThought("What is the color of the sky?")(using contextOf(lm)).toOption, Some("blue"))
    assert(promptOf(lm.requests.head).contains("short factoid answers"), promptOf(lm.requests.head))
  }

  test("the BestOfN program of the cheatsheet has no instructions of BasicQA, as upstream") {
    val lm = answering("B")
    assertEquals(Cheatsheet.bestOfN("What is the capital of Belgium?")(using contextOf(lm)).toOption, Some("B"))
    assert(!promptOf(lm.requests.head).contains("short factoid answers"), promptOf(lm.requests.head))
  }

  // ── the adapters guide ──

  private def newsJson: String = """[{"text": "A result", "scientists_involved": ["Ada"]}]"""

  test("newsWithChatAdapter runs under ChatAdapter, with the instructions of NewsQA") {
    val lm = Script(_ => LmOutput(s"[[ ## news ## ]]\n$newsJson\n\n[[ ## completed ## ]]"))
    assertEquals(Adapters.newsWithChatAdapter(using contextOf(lm)).toOption.map(_.map(_.text)), Some(List("A result")))
    assert(promptOf(lm.requests.head).contains("Get news about the given science field"), promptOf(lm.requests.head))
  }

  test("newsWithJsonAdapter runs under JSONAdapter, also when the context has ChatAdapter") {
    val lm = Script(_ => LmOutput(s"""{"news": $newsJson}"""))
    assertEquals(Adapters.newsWithJsonAdapter(using contextOf(lm)).toOption.map(_.map(_.text)), Some(List("A result")))
    assert(promptOf(lm.requests.head).contains("JSON object"), promptOf(lm.requests.head))
  }

  test("askThenInspect gives the answer and the history of the call") {
    val lm                = Script(_ => LmOutput("[[ ## answer ## ]]\nParis\n\n[[ ## completed ## ]]"))
    val (answer, history) = Adapters.askThenInspect("What is the capital of France?")(using contextOf(lm))
      .fold(error => fail(error.message), identity)
    assertEquals(answer, "Paris")
    assert(history.contains("Paris"), history)
  }

  test("askThenInspect with no LM is an error") {
    assert(Adapters.askThenInspect("What is the capital of France?")(using RuntimeContext()).isLeft)
  }

  // ── the language models guide ──

  test("callDirect gives the text of the model, and no LM is an error") {
    assertEquals(
      LanguageModels.callDirect("Say this")(using contextOf(Script(_ => LmOutput("This")))).toOption,
      Some("This")
    )
    assert(LanguageModels.callDirect("Say this")(using RuntimeContext()).isLeft)
  }

  // ── the signatures guide ──

  test("the dog picture example sends the URL as text, and no message has an image part") {
    val lm     = Script(_ => LmOutput("[[ ## answer ## ]]\nLabrador\n\n[[ ## completed ## ]]"))
    val url    = "https://picsum.photos/id/237/200/300"
    val answer = DogPictureExample.call(url)(using contextOf(lm))
    assertEquals(answer.toOption, Some("Labrador"))
    assert(lm.requests.head.messages.forall(_.parts.isEmpty), lm.requests.head.messages.toString)
    assert(promptOf(lm.requests.head).contains(url), promptOf(lm.requests.head))
  }
