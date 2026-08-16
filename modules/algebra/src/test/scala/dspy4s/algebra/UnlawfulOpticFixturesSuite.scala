package dspy4s.algebra

import munit.FunSuite

/** Negative fixtures prove that the Lens law statements reject invalid implementations. */
final class UnlawfulOpticFixturesSuite extends FunSuite:

  private final case class Versioned(value: Int, version: Int)

  private val versionChangingLens = new Lens[Versioned, Int]:
    def get(source: Versioned): Int = source.value
    def set(source: Versioned, value: Int): Versioned = source.copy(value = value, version = source.version + 1)

  test("modify identity rejects a setter that changes data outside its focus") {
    val equation = versionChangingLens.modifyIdentity(Versioned(1, 0))
    assertNotEquals(equation.lhs, equation.rhs)
  }

  test("modify composition rejects a setter that changes data once per write") {
    val equation = versionChangingLens.modifyComposition(Versioned(1, 0), _ + 1, _ * 2)
    assertNotEquals(equation.lhs, equation.rhs)
  }

  test("set-modify consistency rejects an effectful getter") {
    var reads = 0
    val effectful = new Lens[Versioned, Int]:
      def get(source: Versioned): Int =
        reads += 1
        source.value
      def set(source: Versioned, value: Int): Versioned = source.copy(value = value, version = reads)

    val equation = effectful.consistentSetModify(Versioned(1, 0), 2)
    assertNotEquals(equation.lhs, equation.rhs)
  }
