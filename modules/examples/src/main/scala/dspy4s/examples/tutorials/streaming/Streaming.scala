/**
 * Streaming
 *
 * Source:   docs/docs/tutorials/streaming/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/streaming/index.md
 * Status:   translated (the streamify surface + listeners + status provider, snippets 1–9).
 *
 * The big shape difference: dspy4s `streamify` is *synchronous*. It returns a
 * `DynamicValue.Record => ClosableIterator[StreamEvent]`, so there is no `asyncio` / `async for`
 * and no `async_streaming` flag — every snippet's "consume the stream" loop is the same `while
 * iterator.hasNext` over a sealed `StreamEvent` ADT (`TokenEvent` / `StatusEvent` / `PredictionEvent`
 * / `ErrorEvent`) rather than Python's `isinstance(chunk, StreamResponse | Prediction | StatusMessage)`.
 * Snippets 1/2/3/9 therefore collapse onto one example, as do the async/sync variants.
 *
 * `dspy.streaming.StreamListener(signature_field_name=..., predict=..., predict_name=..., allow_reuse=...)`
 * becomes `StreamListener(signatureFieldName, predictName, allowReuse)` (the predictor is selected by name,
 * not by object identity). A composite `dspy.Module` becomes a `DynamicModule` whose `forward` threads
 * named `DynamicPredict`s. The LM must be a `StreamingLanguageModel` (the OpenAI provider is one).
 */
package dspy4s.examples.tutorials.streaming

import dspy4s.core.contracts.{
  ClosableIterator, DspyError, DynamicPrediction, DynamicValues, RuntimeContext, :=
}
import dspy4s.examples.Demo
import dspy4s.programs.{DynamicPredict, ReAct}
import dspy4s.programs.contracts.{DynamicModule, ProgramCall, ToolFunction, description}
import dspy4s.streaming.{StatusMessageProvider, Streamify}
import dspy4s.streaming.contracts.{ErrorEvent, PredictionEvent, StatusEvent, StreamEvent, StreamListener, TokenEvent}
import dspy4s.typed.Signature
import zio.blocks.schema.{DynamicValue, Schema}

object Streaming:

  // ── Shared consumer — the dspy4s analogue of every snippet's `async for chunk in output` loop ──
  // dspy4s streams synchronously, so this one sync loop replaces Python's asyncio variants. It prints
  // each token + status message and returns the final prediction (the lone `PredictionEvent`).
  // --8<-- [start:stream-consume]
  def consume(stream: ClosableIterator[StreamEvent]): Option[DynamicPrediction] =
    var finalPrediction: Option[DynamicPrediction] = None
    while stream.hasNext do
      stream.next() match
        case t: TokenEvent      => println(s"Output token of field ${t.fieldName}: ${t.chunk}")
        case s: StatusEvent     => println(s.message)
        case p: PredictionEvent => finalPrediction = Some(p.prediction)
        case e: ErrorEvent      => println(s"Error: ${e.error.message}")
    finalPrediction
  // --8<-- [end:stream-consume]

  private def textField(rec: DynamicValue.Record, field: String): String =
    DynamicValues.recordGet(rec, field).map(DynamicValues.renderText).getOrElse("")

  // ── Snippets 1/2/3/9 — stream a single `Predict`'s `answer` field ──
  // | predict = dspy.Predict("question->answer")
  // | stream_predict = dspy.streamify(predict, stream_listeners=[StreamListener(signature_field_name="answer")])
  // | output = stream_predict(question="why did a chicken cross the kitchen?")  # async or sync — same here
  // --8<-- [start:stream-basic]
  def streamAnswer(question: String)(using RuntimeContext): Option[DynamicPrediction] =
    val predict = DynamicPredict(layout = Signature.fromString("question -> answer").layout)
    val streamPredict = Streamify.streamify(
      program         = predict,
      streamListeners = Vector(StreamListener("answer"))
    )
    consume(streamPredict(DynamicValues.recordFromEntries(Vector("question" := question))))
  // --8<-- [end:stream-basic]

  // ── Snippet 4 — a composite module, listeners on two different fields ──
  // | class MyModule(dspy.Module):
  // |     self.predict1 = dspy.Predict("question->answer")
  // |     self.predict2 = dspy.Predict("answer->simplified_answer")
  // |     def forward(self, question): return self.predict2(answer=self.predict1(question=question))
  // --8<-- [start:compose-module]
  final class SimplifyModule extends DynamicModule:
    override val moduleName: String = "simplify_module"
    private val predict1 = DynamicPredict(Signature.fromString("question -> answer").layout, name = Some("predict1"))
    private val predict2 =
      DynamicPredict(Signature.fromString("answer -> simplified_answer").layout, name = Some("predict2"))

    override protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      for
        step1 <- predict1.apply(call)
        answer = textField(step1.values, "answer")
        step2 <- predict2.apply(ProgramCall(inputs = DynamicValues.recordFromEntries(Vector("answer" := answer))))
      yield step2
  // --8<-- [end:compose-module]

  def streamSimplify(question: String)(using RuntimeContext): Option[DynamicPrediction] =
    val streamPredict = Streamify.streamify(
      program         = new SimplifyModule,
      streamListeners = Vector(StreamListener("answer"), StreamListener("simplified_answer"))
    )
    consume(streamPredict(DynamicValues.recordFromEntries(Vector("question" := question))))

  // ── Snippet 5 — stream a ReAct agent's built-in `next_thought` field ──
  // | def fetch_user_info(user_name): """Get user information like name, birthday, etc."""
  // | def get_sports_news(year): """Get sports news for a given year."""
  // | react = dspy.ReAct("question->answer", tools=[fetch_user_info, get_sports_news])
  // | stream_react = dspy.streamify(react, stream_listeners=[StreamListener("next_thought", allow_reuse=True)])
  final case class UserInfo(name: String, birthday: String) derives Schema

  @description("Get user information like name, birthday, etc.")
  def fetch_user_info(user_name: String): UserInfo = UserInfo(name = user_name, birthday = "2009-05-16")

  @description("Get sports news for a given year.")
  def get_sports_news(year: Int): String =
    if year == 2009 then "Usain Bolt broke the world record in the 100m race." else "No news found."

  def streamReactThoughts(question: String)(using RuntimeContext): Option[DynamicPrediction] =
    val react = ReAct(
      baseSignature = Signature.fromString("question -> answer"),
      tools         = Vector(ToolFunction.fromMethod(fetch_user_info), ToolFunction.fromMethod(get_sports_news))
    )
    // ReAct's per-step predictor emits `next_thought`; allowReuse=true keeps the listener firing each iteration.
    val streamReact = Streamify.streamify(
      program         = react,
      streamListeners = Vector(StreamListener("next_thought", allowReuse = true))
    )
    consume(streamReact(DynamicValues.recordFromEntries(Vector("question" := question))))

  // ── Snippet 6 — two predictors emitting the SAME field name, disambiguated by predict_name ──
  // | self.predict1 = dspy.Predict("question->answer"); self.predict2 = dspy.Predict("question, answer->answer, score")
  // | stream_listeners = [StreamListener("answer", predict_name="predict1"), StreamListener("answer", predict_name="predict2")]
  // dspy4s requires unique field names across a layout, so predict2's input is renamed `draft` (it can't also
  // be named `answer` like its output); both predictors still emit an `answer` field, the point of the snippet.
  final class ScoringModule extends DynamicModule:
    override val moduleName: String = "scoring_module"
    private val predict1 = DynamicPredict(Signature.fromString("question -> answer").layout, name = Some("predict1"))
    private val predict2 =
      DynamicPredict(Signature.fromString("question, draft -> answer, score").layout, name = Some("predict2"))

    override protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
      for
        step1 <- predict1.apply(call)
        question = textField(call.inputs, "question")
        answer   = textField(step1.values, "answer")
        step2 <- predict2.apply(ProgramCall(inputs =
                   DynamicValues.recordFromEntries(Vector("question" := question, "draft" := answer))
                 ))
      yield step2

  def streamScoring(question: String)(using RuntimeContext): Option[DynamicPrediction] =
    val streamPredict = Streamify.streamify(
      program = new ScoringModule,
      streamListeners = Vector(
        StreamListener("answer", predictName = Some("predict1")),
        StreamListener("answer", predictName = Some("predict2"))
      )
    )
    consume(streamPredict(DynamicValues.recordFromEntries(Vector("question" := question))))

  // ── Snippets 7/8 — a custom status-message provider + a tool + a reasoning field ──
  // | class MyStatusMessageProvider(dspy.streaming.StatusMessageProvider):
  // |     def tool_start_status_message(self, instance, inputs): return f"Calling Tool {instance.name} ..."
  // |     def tool_end_status_message(self, outputs): return f"Tool finished with output: {outputs}!"
  final class MyStatusMessageProvider extends StatusMessageProvider:
    override def toolStart(toolName: String, args: DynamicValue.Record): Option[String] =
      Some(s"Calling Tool $toolName with inputs ${DynamicValues.recordKeys(args).mkString(", ")}...")
    override def toolEnd(toolName: String, output: Either[DspyError, DynamicValue]): Option[String] =
      Some(s"Tool finished with output: ${output.fold(_.message, DynamicValues.renderText)}!")

  @description("Double the number.")
  def double_the_number(x: Int): Int = 2 * x

  def streamReasoningWithTool(question: String)(using RuntimeContext): Option[DynamicPrediction] =
    // ChainOfThought's built-in `reasoning` field is modelled by naming it in the layout; the tool runs in
    // `forward`, so its start/end status messages flow through the custom provider.
    val tool = ToolFunction.fromMethod(double_the_number)
    val program = new DynamicModule:
      override val moduleName: String = "reasoning_module"
      private val predict =
        DynamicPredict(Signature.fromString("question, doubled -> reasoning, answer").layout, name = Some("predict"))
      override protected def forward(call: ProgramCall)(using RuntimeContext): Either[DspyError, DynamicPrediction] =
        for
          doubled <- tool.invoke(DynamicValues.recordFromEntries(Vector("x" := 21)))
          out <- predict.apply(ProgramCall(inputs = DynamicValues.recordFromEntries(
                   Vector("question" := question, "doubled" := DynamicValues.renderText(doubled))
                 )))
        yield out

    val streamPredict = Streamify.streamify(
      program               = program,
      statusMessageProvider = Some(new MyStatusMessageProvider),
      streamListeners       = Vector(StreamListener("reasoning"))
    )
    consume(streamPredict(DynamicValues.recordFromEntries(Vector("question" := question))))

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.streaming.streamingMain"
@main def streamingMain(): Unit = Demo.withLm {
  println("=== single Predict, streaming `answer` ===")
  val out = Streaming.streamAnswer("Why did a chicken cross the kitchen?")
  println("Final output: " + out)
}
