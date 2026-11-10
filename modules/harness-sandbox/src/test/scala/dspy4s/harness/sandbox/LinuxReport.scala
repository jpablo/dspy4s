package dspy4s.harness.sandbox

import org.junit.runner.notification.{Failure, RunListener}
import org.junit.runner.{Description, JUnitCore}

import java.nio.file.{Files, Path}
import scala.collection.mutable.ArrayBuffer

/** Runs test classes with JUnit and writes one JUnit XML report, as sbt does. `scripts/sandbox-linux/run.sh` uses it to
  * run the laws in a Linux container, where sbt does not run. The blueprint reads the report with the reports of sbt.
  *
  * The arguments are the report file, then the names of the test classes. The exit code is 0 when no test fails.
  */
object LinuxReport:

  private final case class Case(suite: String, name: String, millis: Long, failure: Option[String], skipped: Boolean)

  /** Keeps the result of each test. JUnit calls it on one thread. */
  private final class Listener extends RunListener:
    val cases                           = ArrayBuffer.empty[Case]
    private var start                   = 0L
    private var failure: Option[String] = None
    private var skipped                 = false

    override def testStarted(description: Description): Unit =
      start = System.nanoTime()
      failure = None
      skipped = false

    override def testFailure(found          : Failure): Unit = failure = Some(found.getTrace)
    override def testAssumptionFailure(found: Failure): Unit = skipped = true
    override def testIgnored(description: Description): Unit =
      cases += Case(description.getClassName, description.getMethodName, 0, None, skipped = true): Unit

    override def testFinished(description: Description): Unit =
      val millis = (System.nanoTime() - start) / 1_000_000
      cases += Case(description.getClassName, description.getMethodName, millis, failure, skipped): Unit

  def main(args: Array[String]): Unit =
    require(args.length >= 2, "usage: LinuxReport REPORT CLASS...")
    val listener = Listener()
    val core     = JUnitCore()
    core.addListener(listener)
    val result = core.run(args.toVector.tail.map(Class.forName)*)
    val cases  = listener.cases.toVector
    Files.writeString(Path.of(args(0)), xml(cases)): Unit
    val failed  = cases.count(_.failure.isDefined)
    val skipped = cases.count(c => c.skipped && c.failure.isEmpty)
    println(s"${cases.size} tests: ${cases.size - failed - skipped} pass, $failed fail, $skipped skip")
    cases.filter(_.failure.isDefined).foreach(c => println(s"FAIL ${c.name}\n${c.failure.getOrElse("")}"))
    sys.exit(if result.wasSuccessful then 0 else 1)

  private def xml(cases: Vector[Case]): String =
    val failed  = cases.count(_.failure.isDefined)
    val skipped = cases.count(c => c.skipped && c.failure.isEmpty)
    val rows    = cases.map { c =>
      val body = c.failure match
        case Some(trace) =>
          val message = text(trace.linesIterator.nextOption().getOrElse(""))
          s"""<failure message="$message">${text(trace)}</failure>"""
        case None if c.skipped => "<skipped/>"
        case None              => ""
      val seconds = c.millis / 1000.0
      s"""  <testcase classname="${text(c.suite)}" name="${text(c.name)}" time="$seconds">$body</testcase>"""
    }
    (Vector(
      "<?xml version='1.0' encoding='UTF-8'?>",
      s"""<testsuite name="linux" tests="${cases.size}" errors="0" failures="$failed" skipped="$skipped">"""
    ) ++ rows :+ "</testsuite>").mkString("", "\n", "\n")

  /** The text as XML content or as an attribute value. XML 1.0 has no other control characters than tab, newline and
    * return, and a message of munit has the escape codes of colors.
    */
  private def text(value: String): String =
    value
      .replaceAll("\u001b\\[[0-9;]*m", "")
      .filter(c => c >= ' ' || c == '\n' || c == '\t')
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
