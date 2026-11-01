package dspy4s.programs.optimization

import dspy4s.core.contracts.{DspyError, RuntimeContext, TraceEntry}
import dspy4s.core.data.RawPrediction
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import zio.blocks.schema.DynamicValue

/** Runs a program and returns the trace of that one run.
  *
  * An optimizer needs the trace of each example apart from the others (Python: `with dspy.context(trace=[])`). The run
  * uses a copy of the context with an empty trace and an empty history. The trace and the history of the run do not
  * reach the caller's context.
  */
private[dspy4s] object TracedRun:

  /** Run `program` on `call`. `use` gets the result and the trace while the isolated context is still active. Score the
    * run in `use`: a metric that calls a language model then records its trace and its history in the isolated context,
    * and nothing reaches the caller's context. The trace that `use` gets holds the entries of the program only.
    * `captureFailures` also records an entry for a module that fails (see [[dspy4s.core.contracts.TraceEntry]]).
    */
  def runWith[P, A](program: P, call: ProgramCall[DynamicValue.Record], captureFailures: Boolean = false)(
      use: (Either[DspyError, RawPrediction], Vector[TraceEntry]) => RuntimeContext ?=> A
  )(using runner: ProgramRunner[P], ctx: RuntimeContext): A =
    val config = if captureFailures then ctx.config.copy(captureFailureTraces = Some(true)) else ctx.config
    RuntimeEnvironment.isolatedAttempt(ctx.withConfig(config)) {
      val result = runner.run(program, call)
      use(result, RuntimeEnvironment.current.trace)
    }.value
