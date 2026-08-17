/** Async DSPy Programming
  *
  * Source: docs/docs/tutorials/async/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/async/index.md Status: translated.
  *
  * Python's `acall` maps to running the ZIO returned by `ProgramRunner`. The program value is unchanged. ZIO owns
  * scheduling, cancellation, errors, and the explicit service environment.
  */
package dspy4s.examples.tutorials.async

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.programs.{ChainOfThought, PredictionBackend, Program, ProgramRunner}
import dspy4s.signatures.Signature
import zio.{Runtime, Unsafe, ZEnvironment}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

object Async:

  private val answer = Program.predict(
    Signature.fromString("question -> answer")
  )

  // ── Snippet 1 — run one prediction asynchronously ──
  // | predict = dspy.Predict("question->answer")
  // | output = await predict.acall(question="why did a chicken cross the kitchen?")
  // --8<-- [start:ask-async]
  def askAsync(question: String)(using backend: PredictionBackend): Future[Either[DspyError, String]] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.runToFuture(
        ProgramRunner
          .run(answer, (question = question))
          .map(_.output.answer)
          .either
          .provideEnvironment(ZEnvironment(backend))
      )
    }
  // --8<-- [end:ask-async]

  // ── Snippet 4 — compose two asynchronous predictors ──
  // | class MyModule(dspy.Module):
  // |     self.predict1 = dspy.ChainOfThought("question->answer")
  // |     self.predict2 = dspy.ChainOfThought("answer->simplified_answer")
  // |     async def aforward(self, question):
  // |         answer = await self.predict1.acall(question=question)
  // |         return await self.predict2.acall(answer=answer)
  // --8<-- [start:simplifier-module]
  final class SimplifierProgram:
    private val predict1 = ChainOfThought(
      Signature.fromString("question -> answer")
    )
    private val predict2 = ChainOfThought(
      Signature.fromString("answer -> simplified_answer")
    )
    val program = (predict1.map(result => (answer = result.answer)) >>> predict2)
      .map(_.simplified_answer)

    def run(question: String)(using backend: PredictionBackend): Future[Either[DspyError, String]] =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.runToFuture(
          ProgramRunner
            .run(program, (question = question))
            .map(_.output)
            .either
            .provideEnvironment(ZEnvironment(backend))
        )
      }
  // --8<-- [end:simplifier-module]

  // Effectful tools already return ZIO. They do not need a separate `acall` method.

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.async.asyncMain"
@main def asyncMain(): Unit =
  Demo.withLm {
    val question = "Why did a chicken cross the kitchen?"
    println("Async predict: " + Await.result(Async.askAsync(question), 60.seconds))
    println("Async program: " + Await.result(new Async.SimplifierProgram().run(question), 60.seconds))
  }
