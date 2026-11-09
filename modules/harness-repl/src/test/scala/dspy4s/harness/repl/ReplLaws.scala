package dspy4s.harness.repl

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

  test("Hints: a recursive type with no given") {
    assert(hint("Maximal number of successive inlines (32) exceeded").exists(_.contains("Data.derived")))
  }

  test("Hints: a capability in a place that must be pure names the capability") {
    val error = "Found: (x: Files^{files}) ... is not included in the allowed capture set {} of the enclosing object"
    assert(hint(error).exists(_.contains("the capability files")), hint(error).toString)
  }

  test("Hints: a Vector in a pair") {
    val error = "Found: (x$1: (String, Vector[String]{val prefix1: String}))"
    assert(hint(error).exists(_.contains("groupMapReduce")))
  }

  test("Hints: an Array pattern inside collect") {
    assert(hint("Found: ((Array[(String)?]^{any.rd}))?^{x1.rd}").exists(_.contains("case List(x, y)")))
  }

  test("Hints: another error gets no hint") {
    assertEquals(hint("Not found: value foo"), None)
  }
