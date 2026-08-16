package dspy4s.programs

import dspy4s.programs.algebra.{Program, SomeProgram}
import dspy4s.programs.compose.Compose
import munit.FunSuite

import scala.compiletime.testing.typeChecks

object CompositionMatrixFixtures:
  val intToString = Compose.lift[Int, String](_.toString)
  val stringToInt = Compose.lift[String, Int](_.length)
  val boolToLong  = Compose.lift[Boolean, Long](if _ then 1L else 0L)

  val packagedIntToString = Program.of(intToString)
  val packagedStringToInt = Program.of(stringToInt)

/** Compile-time matrix for the public composition operations and their invalid cells. */
final class CompositionMatrixSuite extends FunSuite:

  test("module composition matrix accepts only connected input and output types") {
    assert(typeChecks("import dspy4s.programs.*; import dspy4s.programs.CompositionMatrixFixtures.*; intToString >>> stringToInt"))
    assert(!typeChecks("import dspy4s.programs.*; import dspy4s.programs.CompositionMatrixFixtures.*; intToString >>> boolToLong"))
  }

  test("fanout requires a shared input, while tensor accepts independent inputs") {
    assert(typeChecks("import dspy4s.programs.*; import dspy4s.programs.CompositionMatrixFixtures.*; intToString &&& intToString"))
    assert(!typeChecks("import dspy4s.programs.*; import dspy4s.programs.CompositionMatrixFixtures.*; intToString &&& boolToLong"))
    assert(typeChecks("import dspy4s.programs.*; import dspy4s.programs.CompositionMatrixFixtures.*; intToString *** boolToLong"))
  }

  test("packaged composition retains its exact grade without an expected result type") {
    assert(typeChecks(
      "import dspy4s.programs.algebra.Program.given; import dspy4s.programs.CompositionMatrixFixtures.*; packagedIntToString >>> packagedStringToInt"
    ))
    assert(typeChecks(
      "import dspy4s.programs.algebra.Program.given; import dspy4s.programs.CompositionMatrixFixtures.*; val result: Program[Int, Int, 0] = packagedIntToString >>> packagedStringToInt"
    ))
    assert(!typeChecks(
      "import dspy4s.programs.algebra.Program.given; import dspy4s.programs.CompositionMatrixFixtures.*; val result: Program[Int, String, 0] = packagedIntToString >>> packagedStringToInt"
    ))
  }

  test("erased packaged composition remains available when the grade is hidden") {
    val first : SomeProgram[Int, String] = CompositionMatrixFixtures.packagedIntToString
    val second: SomeProgram[String, Int] = CompositionMatrixFixtures.packagedStringToInt
    val category                         = Program.erasedCategory
    import category.*

    val result: SomeProgram[Int, Int] = first >>> second
    assertEquals(result.optimizableParameters.arity(result.program), 0)
  }
