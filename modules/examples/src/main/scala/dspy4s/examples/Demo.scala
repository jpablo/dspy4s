package dspy4s.examples

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.programs.contracts.Prediction
import dspy4s.programs.{LivePredictionBackend, PredictionBackend, Program, ProgramRunner, ProgramWithEnv, RunOptions}
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

/** Shared runner for the example `@main`s. It creates the explicit prediction backend used by the functional program
  * interpreter. It also supplies a compatibility [[RuntimeContext]] to examples that call the low-level LM API.
  *
  * Each ported example file has a uniquely-named `@main` (e.g. `modulesMain`) so that files sharing a package don't
  * collide on a single `main` entry point. Run one with, e.g.:
  *
  * OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.learn.programming.modulesMain"
  */
object Demo:
  def withLm(body: (PredictionBackend, RuntimeContext) ?=> Unit): Unit =
    val model = sys.env.getOrElse("DSPY_MODEL", "gpt-5.5")
    OpenAiLanguageModel.fromEnv(model) match
      case Left(err) => sys.error(s"Could not initialize LM (is OPENAI_API_KEY set?): $err")
      case Right(lm) =>
        given context: RuntimeContext    = RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))
        given backend: PredictionBackend = new LivePredictionBackend(lm, ChatAdapter(), context)
        body

  def run[I, O](program: Program[I, O], input: I, options: RunOptions = RunOptions())(using
      backend: PredictionBackend
  ): Either[DspyError, Prediction[O]] =
    runEffect(ProgramRunner.run(program, input, options))

  def runWith[I, O, R](
      program    : ProgramWithEnv[I, O, R],
      input      : I,
      environment: ZEnvironment[R],
      options    : RunOptions = RunOptions()
  ): Either[DspyError, Prediction[O]] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(ProgramRunner.run(program, input, options).provideEnvironment(environment).either)
        .getOrThrowFiberFailure()
    }

  def runEffect[E, A](effect: ZIO[PredictionBackend, E, A])(using backend: PredictionBackend): Either[E, A] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(effect.provideEnvironment(ZEnvironment(backend)).either)
        .getOrThrowFiberFailure()
    }
