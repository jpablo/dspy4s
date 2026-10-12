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
  * uses a copy of the context with an empty trace. The trace and the history of the run do not reach the caller's
  * context.
  */
private[dspy4s] object TracedRun:

  /** Run `program` on `call`. `captureFailures` also records an entry for a module that fails (see
    * [[dspy4s.core.contracts.TraceEntry]]).
    */
  def run[P](program: P, call: ProgramCall[DynamicValue.Record], captureFailures: Boolean = false)(using
      runner: ProgramRunner[P],
      ctx   : RuntimeContext
  ): (Either[DspyError, RawPrediction], Vector[TraceEntry]) =
    runWith(program, call, captureFailures)((result, trace) => (result, trace))

  /** Like [[run]], but `use` gets the result and the trace while the isolated context is still active. Score the run
    * in `use`: a metric that calls a language model then records its trace and its history in the isolated context,
    * and nothing reaches the caller's context. The trace that `use` gets holds the entries of the program only.
    */
  def runWith[P, A](program: P, call: ProgramCall[DynamicValue.Record], captureFailures: Boolean = false)(
      use: (Either[DspyError, RawPrediction], Vector[TraceEntry]) => RuntimeContext ?=> A
  )(using runner: ProgramRunner[P], ctx: RuntimeContext): A =
    val isolated = ctx
      .withConfig(ctx.config.copy(captureFailureTraces = captureFailures || ctx.config.captureFailureTraces))
      .withDelta(ctx.delta.copy(trace = Vector.empty))
    RuntimeEnvironment.withContext(isolated) {
      given RuntimeContext = RuntimeEnvironment.current
      val result           = runner.run(program, call)
      use(result, RuntimeEnvironment.current.trace)
    }
