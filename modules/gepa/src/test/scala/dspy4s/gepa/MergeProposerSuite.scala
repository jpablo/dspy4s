package dspy4s.gepa

import dspy4s.programs.ParameterId
import munit.FunSuite

import scala.util.Random

final class MergeProposerSuite extends FunSuite:

  private val first  = ParameterId("first")
  private val second = ParameterId("second")
  private val seed   = Map(first -> Some("a"), second -> Some("b"))

  test("desirable components require complementary changes") {
    val left  = seed.updated(first, Some("a1"))
    val right = seed.updated(second, Some("b1"))

    assert(MergeProposer.hasDesirableComponents(seed, left, right))
    assert(!MergeProposer.hasDesirableComponents(seed, left, left))
  }

  test("crossover combines complementary improvements") {
    val left              = seed.updated(first, Some("a1"))
    val right             = seed.updated(second, Some("b1"))
    val (merged, sources) = MergeProposer.crossover(seed, 1, left, 2, right, _ => 1.0, new Random(0))

    assertEquals(merged, Map(first -> Some("a1"), second -> Some("b1")))
    assertEquals(sources, Vector(1, 2))
  }
