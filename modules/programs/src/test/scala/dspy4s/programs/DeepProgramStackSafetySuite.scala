package dspy4s.programs

import dspy4s.algebra.AnyObject
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.algebra.{Program, SomeProgram}
import dspy4s.programs.compose.Compose
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

final class DeepProgramStackSafetySuite extends FunSuite:

  private given RuntimeContext = RuntimeEnvironment.current

  private def chain(size: Int): SomeProgram[Int, Int] =
    val category = Program.erasedCategory
    import category.*

    val increment: SomeProgram[Int, Int] = Program.of(Compose.lift[Int, Int](_ + 1))
    var result                           = category.id[Int](using summon[AnyObject[Int]])
    var index                            = 0
    while index < size do
      result = result >>> increment
      index += 1
    result

  test("20,000 sequential programs execute without using the JVM call stack") {
    val deep = chain(20_000)
    assertEquals(deep(ProgramCall(0)).map(_.output), Right(20_000))
  }

  test("20,000 sequential programs support inspect and replace without using the JVM call stack") {
    val deep      = chain(20_000)
    val structure = deep.optimizableParameters

    assertEquals(structure.inspect(deep.program), Vector.empty)
    assertEquals(structure.inspectNamed(deep.program), Vector.empty)

    val replaced = structure.replace(deep.program, Vector.empty)
    assertEquals(structure.inspect(replaced), Vector.empty)
  }
