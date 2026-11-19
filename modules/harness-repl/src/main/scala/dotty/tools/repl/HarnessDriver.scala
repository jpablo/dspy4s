// `Rendering` is `private[repl]`, so the driver of the harness lives in the package of the REPL. Phase 0 of the code
// harness tested this driver (docs/code-harness/CODE_HARNESS_PHASE0.md).
package dotty.tools.repl

import dotty.tools.dotc.cc.{CaptureAnnotation, CaptureSet, CapturingType, isRetainsLike, refiningGetterNamed}
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Denotations.Denotation
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.Symbols.{ClassSymbol, defn}
import dotty.tools.dotc.core.Types.{AnnotatedType, FlexibleType, RefinedType, Type, TypeMap}
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.repl.Rendering.showUser

import java.io.PrintStream
import scala.util.control.NonFatal

/** Records the exception that an evaluation threw, shows the plain type of a val, and cuts the echo of a long val.
  *
  * The REPL shows an uncaught exception as normal output through `renderError`, so this is the one place that sees the
  * fact. `error` keeps the first line of that text: the class and the message of the exception. A cell can print lines
  * before it throws, so the first line of the full output can be a printed line and not the exception.
  *
  * The REPL also shows each val that a cell defines, as `val name: Type = value`. Capture checking and explicit nulls
  * put marks in this type that a cell cannot write, for example `Vector[String]{val prefix1: Array[Object^{}]^}` and
  * `((Array[(String)?]^{}))?^{}`. `renderVal` shows the plain type of `FlagRendering.PlainType` in their place, for
  * example `Vector[String]` and `Array[String]`. If the plain type fails, the echo keeps the type of the REPL.
  *
  * The REPL of 3.10 has no option that limits the characters of the echo, so `renderVal` cuts it to
  * `FlagRendering.MaxEcho` characters, with a note. The output of `println` and of the guest API does not come through
  * `renderVal`, so it stays whole.
  */
final class FlagRendering(parent: Option[ClassLoader]) extends Rendering(parent):

  /** The first line of the exception of the evaluation, or `None` when the evaluation did not throw. */
  @volatile var error: Option[String] = None

  override def renderError(thr: Throwable, d: Denotation)(using Context): Diagnostic =
    val shown = super.renderError(thr, d)
    if error.isEmpty then error = Some(shown.message.linesIterator.find(_.trim.nonEmpty).getOrElse("an exception"))
    shown

  override def renderVal(d: Denotation)(using Context): Either[ReflectiveOperationException, Option[Diagnostic]] =
    super.renderVal(plainDenotation(d)).map(_.map(shortEcho))

  /** A copy of the symbol of `d` with the plain type, or `d` when the type has no marks or the plain type fails.
    *
    * The REPL prints the declaration from the symbol, so a copy with the plain type keeps each form of the declaration,
    * for example `lazy val`. The copy has the owner and the name of the symbol, so the REPL reads the same value. The
    * copy is not in a scope, so the next cells do not see it. The copy prints its declaration here once: a type that
    * the printer cannot show then fails here, before the REPL reads the value, and the echo keeps the type of the REPL.
    */
  private def plainDenotation(d: Denotation)(using Context): Denotation =
    try
      val sym   = d.symbol
      val plain = FlagRendering.PlainType()(sym.info)
      if plain eq sym.info then d
      else
        val copy = sym.copy(info = plain, coord = sym.coord)
        val _    = copy.showUser
        copy.denot
    catch case NonFatal(_) => d

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

  /** Removes from a type the marks that capture checking and explicit nulls add, for the echo of a val.
    *
    *   - A capture set, for example `^{}` or `^{any.rd}`, goes away, and a `retains` annotation too. A function type
    *     with a capture set that is not empty gets the universal capture set, so it shows as `A => B`. A function type
    *     with an empty capture set shows as the pure function type `A -> B`.
    *   - A flexible type of explicit nulls, `(T)?`, becomes `T`. A type that the cell writes, for example
    *     `String | Null`, is not a flexible type, so it stays.
    *   - A refinement `C{val x: T}` goes away when `x` is a field of a constructor parameter of the class `C`, the
    *     capture checker refines this field (`refiningGetterNamed`), and `x` is not `tracked`. The capture checker adds
    *     this refinement to an inferred class type, for example `Vector[String]{val prefix1: Array[Object^{}]^}`. A
    *     cell can write such a refinement too, and the echo then shows the class type without it. This is rare, and the
    *     class type is a supertype of the refinement, so the echo stays correct.
    *
    * Each other type stays as it is.
    */
  final class PlainType(using Context) extends TypeMap:
    def apply(tp: Type): Type =
      tp match
        case FlexibleType(nonNull)                                      => apply(nonNull)
        case AnnotatedType(parent, annot) if annot.symbol.isRetainsLike =>
          val plain = apply(parent)
          if defn.isFunctionType(plain) && !capturesNothing(tp) then impure(plain) else plain
        case refined: RefinedType if isCaptureRefinement(refined) => apply(refined.parent)
        case _                                                    => mapOver(tp)

    /** True when the capture set of `tp` is empty. An unknown set gives false, and the function shows `=>`. */
    private def capturesNothing(tp: Type): Boolean =
      CapturingType.decomposeCapturingType(tp, alsoRetains = true).exists((_, refs) => refs.elems.isEmpty)

    /** The function type `fn` with the universal capture set, which the printer shows as `=>`. */
    private def impure(fn: Type): Type =
      AnnotatedType(fn, CaptureAnnotation(CaptureSet.universal, boxed = false)(defn.RetainsAnnot))

    /** True for a refinement that the capture checker adds: the rule of the third item above. */
    private def isCaptureRefinement(tp: RefinedType): Boolean =
      tp.refinedName.isTermName &&
        (tp.parent.classSymbol match
          case cls: ClassSymbol =>
            val getter = cls.refiningGetterNamed(tp.refinedName)
            getter.exists && !getter.is(Flags.Tracked)
          case _ => false)

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

  /** The first line of the exception that the last evaluation threw: the class and the message. It is `None` when the
    * evaluation did not throw.
    */
  def evaluationError: Option[String] = flagRendering.error
  def resetEvaluationError(): Unit    = flagRendering.error = None

  /** The class loader of the classes that the REPL defines. It is null before the first evaluation. */
  def replClassLoader: ClassLoader | Null = rendering.myClassLoader

  /** Sets the stop flag of this REPL only: with the interrupt instrumentation, each REPL loader has its own flag. */
  def setStopFlag(stop: Boolean): Unit =
    val loader = replClassLoader
    if loader != null then ReplBytecodeInstrumentation.setStopFlag(loader, stop)
