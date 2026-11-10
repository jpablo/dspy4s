// `Rendering` is `private[repl]`, so the driver of the harness lives in the package of the REPL. Phase 0 of the code
// harness tested this driver (docs/code-harness/CODE_HARNESS_PHASE0.md).
package dotty.tools.repl

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.reporting.Diagnostic

import java.io.PrintStream

/** Records whether an evaluation threw, and cuts the echo of a long val.
  *
  * The REPL shows an uncaught exception as normal output through `renderError`, so this is the one place that sees the
  * fact. The REPL also shows the value of each val that a cell defines. The REPL of the 3.10 nightly has no option that
  * limits the characters of this echo, so `renderVal` cuts it to `FlagRendering.MaxEcho` characters, with a note. The
  * output of `println` and of the guest API does not come through `renderVal`, so it stays whole.
  */
final class FlagRendering(parent: Option[ClassLoader]) extends Rendering(parent):
  @volatile var threw: Boolean = false

  override def renderError(thr: Throwable, d: Denotation)(using Context): Diagnostic =
    threw = true
    super.renderError(thr, d)

  override def renderVal(d: Denotation)(using Context): Either[ReflectiveOperationException, Option[Diagnostic]] =
    super.renderVal(d).map(_.map(shortEcho))

  /** The echo with at most `MaxEcho` characters, and a note with the number of characters that the cut removed. The cut
    * counts code points, so it does not cut a character in two.
    */
  private def shortEcho(echo: Diagnostic): Diagnostic =
    val text = echo.message
    val size = text.codePointCount(0, text.length)
    if size <= FlagRendering.MaxEcho then echo
    else
      val kept = text.substring(0, text.offsetByCodePoints(0, FlagRendering.MaxEcho))
      Diagnostic.Info(
        s"$kept\n[The echo of this value has ${size - FlagRendering.MaxEcho} more characters. " +
          "Print the value, or a part of it, to see them.]",
        echo.pos
      )

object FlagRendering:

  /** The maximum number of characters in the echo of a val. */
  val MaxEcho: Int = 400

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
