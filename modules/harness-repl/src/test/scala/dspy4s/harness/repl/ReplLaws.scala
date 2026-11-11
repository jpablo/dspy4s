package dspy4s.harness.repl

import dspy4s.harness.kernel.{CellOutcome, Entry, Logged, Meta, Source, Taint}
import zio.blocks.schema.json.Json

import java.util.concurrent.{Callable, ConcurrentHashMap, CountDownLatch, Executors, TimeUnit}
import scala.util.Try

/** The top-level names of a cell and the error hints of section 6. */
class ReplLaws extends munit.FunSuite:

  private val cells = Vector(
    "val build = 1"                                                     -> Vector("build"),
    "def testsOf(m: String): Vector[String] = Vector(m + \"Test\")"     -> Vector("testsOf"),
    "case class Point(x: Int, y: Int)\nobject Point:\n  val origin = 1" -> Vector("Point"),
    "enum Color:\n  case Red, Green"                                    -> Vector("Color"),
    "val (left, right) = (1, 2)"                                        -> Vector("left", "right"),
    "val Some(found) = Option(3): @unchecked"                           -> Vector("found"),
    "given named: Ordering[Int] = Ordering.by(identity)"                -> Vector("named"),
    "extension (p: Int) def norm: Int = p * p"                          -> Vector("norm"),
    "type Grid = Vector[Vector[Int]]"                                   -> Vector("Grid"),
    "import scala.collection.immutable.SortedMap"                       -> Vector("SortedMap"),
    "import scala.collection.mutable.{ListBuffer as Buf}"               -> Vector("Buf"),
    "sealed trait Shape\ncase object Circle extends Shape"              -> Vector("Shape", "Circle"),
    "lazy val later = 42"                                               -> Vector("later"),
    "trait T\nclass C\nobject O"                                        -> Vector("T", "C", "O")
  )

  test("Top-level names: val, def, class, object, enum, type, patterns, givens, extensions and imports") {
    for (cell, names) <- cells do assertEquals(topLevelNames(cell).distinct, names, cell)
  }

  test("Top-level names: an expression and an anonymous given define no name that a later cell can use") {
    assertEquals(topLevelNames("1 + 1"), Vector.empty)
    assertEquals(topLevelNames("given Ordering[Int] = Ordering.by(identity)"), Vector.empty)
  }

  test("Top-level names: a cell that does not parse defines nothing") {
    assertEquals(topLevelNames("val broken = ("), Vector.empty)
  }

  /** A journal of four cells. The `Acted` entries have the `seq` 1, 4, 7 and 10. */
  private def cellsJournal(cells: (String, CellOutcome)*): Vector[Logged] =
    val meta    = Meta(0L, Source.Policy, Taint.Trusted, required = true)
    val entries = cells.toVector.flatMap { (code, outcome) =>
      Vector(Entry.StepStarted(0), Entry.Acted("", "", Some(code)), Entry.Observed("", outcome, Map.empty))
    }
    entries.zipWithIndex.map((entry, i) => Logged(i.toLong, meta, entry))

  test("Names: the note lists the names of the cells that completed, with their cells") {
    val journal = cellsJournal(
      "val x = 1\ndef f(n: Int) = n"    -> CellOutcome.Ok,
      "val y: Int = \"no\""             -> CellOutcome.CompileError(Vector("type mismatch")),
      "val z = 3"                       -> CellOutcome.Submitted(Json.Number(3)),
      "case class P(a: Int)\nval x = 2" -> CellOutcome.Ok
    )
    assertEquals(lostNames(journal), Vector("f" -> 1L, "P" -> 10L, "x" -> 10L))
  }

  test("Names: a failed cell and a cell that ended by submit define no name") {
    val journal = cellsJournal(
      "val a = 1" -> CellOutcome.Failed("boom"),
      "val b = 2" -> CellOutcome.TimedOut(1000L),
      "val c = 3" -> CellOutcome.Submitted(Json.Number(3))
    )
    assertEquals(lostNames(journal), Vector.empty)
  }

  test("Names: a cell with no outcome, which a crash cut, defines no name") {
    val meta    = Meta(0L, Source.Policy, Taint.Trusted, required = true)
    val journal = Vector(Entry.StepStarted(0), Entry.Acted("", "", Some("val d = 4"))).zipWithIndex.map((e, i) =>
      Logged(i.toLong, meta, e)
    )
    assertEquals(lostNames(journal), Vector.empty)
  }

  // Two restarts can run at the same time: two children of one `rlmBatched`, or two program calls of a parallel
  // evaluation (6, 9). So the parse of the names must be safe for threads. A pattern goes through the tree accumulator
  // of the compiler, which counts its depth in the state of the parser, so the cells are patterns and other cells.
  test("Names: topLevelNames on 8 threads at the same time gives the result of one thread, also after the run") {
    val nested   = "val (a, (b, (c, (d, (e, f))))) = (1, (2, (3, (4, (5, 6)))))"
    val expected = (cells.map(_._1) :+ nested :+ nested :+ nested).map(cell => cell -> topLevelNames(cell))
    val start    = CountDownLatch(1)
    val failures = ConcurrentHashMap.newKeySet[String]()
    val pool     = Executors.newFixedThreadPool(8)
    try
      val runs = Vector.fill(8) {
        val job: Callable[Int] = () =>
          start.await()
          (0 until 2_000).count { i =>
            val (cell, names) = expected(i % expected.size)
            val found         = Try(topLevelNames(cell))
            found.failed.foreach(e => failures.add(e.getClass.getName): Unit)
            !found.toOption.contains(names)
          }
        pool.submit(job)
      }
      start.countDown()
      val wrong = runs.map(_.get(120, TimeUnit.SECONDS)).sum
      // The same cells again, one at a time on this thread, after the run.
      val after = expected.count((cell, names) => !Try(topLevelNames(cell)).toOption.contains(names))
      assertEquals(
        (wrong, after),
        (0, 0),
        s"wrong results of 16000 calls on 8 threads, and of ${expected.size} calls after the run; failures: $failures"
      )
    finally pool.shutdownNow(): Unit
  }

  test("Hints: a recursive type with no given") {
    assert(hint("Maximal number of successive inlines (32) exceeded").exists(_.contains("Data.derived")))
  }

  test("Hints: a capability in a place that must be pure names the capability") {
    val error = "Found: (x: Files^{files}) ... is not included in the allowed capture set {} of the enclosing object"
    assert(hint(error).exists(_.contains("the capability files")), hint(error).toString)
  }

  test("Hints: a top-level var") {
    val error = "Mutable variable n is defined in a class that does not extend `Stateful` or `Mutable`."
    assert(hint(error).exists(_.contains("locally")))
  }

  test("Hints: a Vector in a pair") {
    val error = "Found: (x$1: (String, Vector[String]{val prefix1: String}))"
    assert(hint(error).exists(_.contains("groupMapReduce")))
  }

  test("Hints: an Array pattern inside collect") {
    assert(hint("Found: ((Array[(String)?]^{any.rd}))?^{x1.rd}").exists(_.contains("case List(x, y)")))
  }

  test("Hints: a top-level val that needs an explicit type, also for an Array") {
    val error = """value xs needs an explicit type because the inferred type does not conform to
                  |the type that is externally visible in other compilation units.
                  | Inferred type          : Array[Int]^{any.rd}""".stripMargin
    assert(hint(error).exists(_.contains("val xs: Array[Int]")), hint(error).toString)
  }

  // The texts come from the REPL: the error of an Array chain after a trigger, and the errors of `case Array(x, y)`.
  test(
    "Hints: the Array symptom of the compiler defect has an Array with a capture set, and an Array pattern is not the symptom"
  ) {
    assert(showsArrayDefect("  |Found:    Array[Int]^{any.rd}\n  |Required: Array[Int]"))
    assert(showsArrayDefect("  |        Found:    Array[Int]^{xs.rd}\n  |        Required: Array[Int]"))
    assert(
      !showsArrayDefect("  |Found:    ((Array[(String)?]^{any.rd}))?^{x1.rd}\n  |Required: Array[(String)?]^{any².rd}")
    )
    assert(!showsArrayDefect("  |Found:    A1^{x5.rd}\n  |Required: Array[String]^{any.rd}"))
    assert(!showsArrayDefect("  |Found:    Array[Int]\n  |Required: Array[String]"))
  }

  // The text comes from the REPL: the error of `println(Array(4, 5).map(_ + 1).sum)` after a cell with
  // `case class I(x: Int)` and `def f(i: I): Int = i.x`. The cell has no Array pattern.
  test("Hints: the Array symptom of the compiler defect gets no hint of an Array pattern") {
    val error =
      """-- [E007] Type Mismatch Error: -------------------------------------------------
        |1 |println(Array(4, 5).map(_ + 1).sum)
        |  |        ^^^^^^^^^^^
        |  |Found:    Array[Int]^{any.rd}
        |  |Required: Array[Int]
        |  |
        |  |Note that capability `any.rd` cannot flow into capture set {}.
        |  |
        |  |where:    any is a root capability classified as Unscoped created in value res0 when instantiating method apply's type (x: Int, xs: Seq[Int]): Array[Int]^{fresh}
        |  |
        |  | longer explanation available when compiling with `-explain`
        |1 error found""".stripMargin
    assert(showsArrayDefect(error), error)
    assert(!hint(error).exists(_.contains("case Array(x, y)")), hint(error).toString)
  }

  test("Hints: a code in a ``` fence, whose first line does not parse") {
    // The parse error of a live session of 2026-11-10 had no hint, and the model did the same thing for five steps.
    val error = "Line 1: empty quoted identifier"
    assert(hint(error).exists(_.contains("```")), hint(error).toString)
  }

  test("Hints: another error gets no hint") {
    assertEquals(hint("Not found: value foo"), None)
  }
