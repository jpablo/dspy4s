/** Streaming
  *
  * Source: docs/docs/tutorials/streaming/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/streaming/index.md Status: translated.
  *
  * Python's `streamify` wrapper maps to `ProgramEventStream.run`. The stream contains typed interpreter events and one
  * final `Prediction`. A live backend emits `PredictionChunk` events when its model supports streaming.
  */
package dspy4s.examples.tutorials.streaming

import dspy4s.examples.Demo
import dspy4s.programs.{ParameterId, PredictionBackend, Program, ProgramEvent, RunOptions}
import dspy4s.signatures.Signature
import dspy4s.streaming.{ProgramEventStream, ProgramStreamItem}
import zio.{Runtime, Unsafe, ZEnvironment}
import zio.blocks.schema.Schema

final case class StreamingQuestion(question: String) derives Schema
final case class StreamingAnswer(answer: String) derives Schema

object Streaming:

  val program = Program.predict(
    ParameterId("streaming/answer"),
    Signature.derived[StreamingQuestion, StreamingAnswer](
      "StreamingAnswer",
      "Answer the question in a short paragraph."
    )
  )

  // ── Snippets 1–3 — stream a program and consume its events ──
  // | stream_program = dspy.streamify(program)
  // | async for value in stream_program(question="..."):
  // |     print(value)
  // --8<-- [start:stream-program]
  def collect(question: String)(using backend: PredictionBackend): Vector[ProgramStreamItem[StreamingAnswer]] =
    val effect = ProgramEventStream
      .run(program, StreamingQuestion(question), RunOptions(traceEnabled = true))
      .runCollect
      .provideEnvironment(ZEnvironment(backend))
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure().toVector }
  // --8<-- [end:stream-program]

  // ── Snippets 4–7 — field listeners and status messages ──
  // Python listener callbacks become ordinary pattern matches over `ProgramEvent.OutputChunk`, `Started`, `Completed`,
  // and `Failed`. They can be transformed with the normal ZStream operators.
  def render(item: ProgramStreamItem[StreamingAnswer]): String = item match
    case ProgramStreamItem.Event(ProgramEvent.OutputChunk(_, _, component, chunk, parameterId)) =>
      s"chunk component=$component parameter=$parameterId field=${chunk.fieldName} text=${chunk.text}"
    case ProgramStreamItem.Event(event) => s"event $event"
    case ProgramStreamItem.Result(prediction) => s"result ${prediction.output.answer}"

  // ── Snippets 8/9 — cancellation and errors ──
  // ZStream owns resource cleanup and interruption. Stream failures use the typed `DspyError` channel.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.streaming.streamingMain"
@main def streamingMain(): Unit =
  Demo.withLm {
    Streaming.collect("Why is streaming useful for interactive applications?").foreach(item =>
      println(Streaming.render(item))
    )
  }
