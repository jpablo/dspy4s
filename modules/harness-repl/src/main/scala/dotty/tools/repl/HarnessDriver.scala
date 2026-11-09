// `Rendering` is `private[repl]`, so the driver of the harness lives in the package of the REPL. Phase 0 of the code
// harness tested this driver (docs/code-harness/CODE_HARNESS_PHASE0.md).
package dotty.tools.repl

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.reporting.Diagnostic

import java.io.PrintStream

/** Records whether an evaluation threw. The REPL shows an uncaught exception as normal output through `renderError`, so
  * this is the one place that sees the fact.
  */
final class FlagRendering(parent: Option[ClassLoader]) extends Rendering(parent):
  @volatile var threw: Boolean = false

  override def renderError(thr: Throwable, d: Denotation)(using Context): Diagnostic =
    threw = true
    super.renderError(thr, d)

/** A `ReplDriver` that does not swap `System.out` and `System.err` (`redirectOutput = false`). The guest output goes
  * through `Console.withOut` on the thread of the cell, so the driver needs no lock for the full JVM.
  */
final class HarnessDriver(settings: Array[String], out: PrintStream, parent: ClassLoader)
    extends ReplDriver(settings, out, Some(parent)):
  private val flagRendering = FlagRendering(Some(parent))
  rendering = flagRendering

  override protected def redirectOutput: Boolean = false

  /** Compiles and runs a cell that the host already parsed. */
  def runParsed(parsed: ParseResult)(using State): State = runBody(interpret(parsed))

  def evaluationThrew: Boolean     = flagRendering.threw
  def resetEvaluationThrew(): Unit = flagRendering.threw = false

  /** The class loader of the classes that the REPL defines. It is null before the first evaluation. */
  def replClassLoader: ClassLoader | Null = rendering.myClassLoader

  /** Sets the stop flag of this REPL only: with the interrupt instrumentation, each REPL loader has its own flag. */
  def setStopFlag(stop: Boolean): Unit =
    val loader = replClassLoader
    if loader != null then ReplBytecodeInstrumentation.setStopFlag(loader, stop)
