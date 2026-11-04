// `Rendering` is `private[repl]`, so these classes live in the package of the REPL.
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
  * through `Console.withOut` on the evaluation thread, and the diagnostics go to `out`. So the driver needs no global
  * lock.
  */
final class ExpDriver(settings: Array[String], out: PrintStream, parent: ClassLoader)
    extends ReplDriver(settings, out, Some(parent)):
  private val flagRendering = FlagRendering(Some(parent))
  rendering = flagRendering

  override protected def redirectOutput: Boolean = false

  def runParsed(res: ParseResult)(using State): State = runBody(interpret(res))

  def evaluationThrew: Boolean     = flagRendering.threw
  def resetEvaluationThrew(): Unit = flagRendering.threw = false

  /** The class loader of the classes that the REPL defines (null before the first evaluation). */
  def replClassLoader: ClassLoader | Null = rendering.myClassLoader

  /** Sets the stop flag of this REPL only: in the `true` mode, each REPL class loader has its own `StopRepl`. */
  def setStopFlag(stop: Boolean): Unit =
    val loader = replClassLoader
    if loader != null then ReplBytecodeInstrumentation.setStopFlag(loader, stop)
