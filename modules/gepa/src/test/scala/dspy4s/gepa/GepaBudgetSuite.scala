package dspy4s.gepa

import munit.FunSuite

/** The budget of a GEPA run (PORT_GAPS G-45, the facade; upstream `dspy/teleprompt/gepa/gepa.py` `auto_budget`,
  * `AUTO_RUN_SETTINGS`, and `compile`). The expected values come from the upstream formula, run with Python 3.12.
  */
class GepaBudgetSuite extends FunSuite:

  test("the auto formula gives the upstream values") {
    assertEquals(GepaBudget.auto(components = 1, candidates = 6, valsetSize = 4), 396)
    assertEquals(GepaBudget.auto(components = 1, candidates = 12, valsetSize = 50), 940)
    assertEquals(GepaBudget.auto(components = 1, candidates = 18, valsetSize = 300), 3135)
    assertEquals(GepaBudget.auto(components = 2, candidates = 6, valsetSize = 50), 1030)
    assertEquals(GepaBudget.auto(components = 3, candidates = 18, valsetSize = 300), 5440)
    assertEquals(GepaBudget.auto(components = 5, candidates = 12, valsetSize = 10), 2705)
    assertEquals(GepaBudget.auto(components = 1, candidates = 6, valsetSize = 0), 380)
  }

  test("each level of effort has the upstream number of candidates") {
    assertEquals(AutoBudget.values.toVector.map(_.candidates), Vector(6, 12, 18))
  }

  test("a number of metric calls resolves to itself") {
    assertEquals(GepaBudget.MetricCalls(MetricCallCount(40)).resolve(1, trainsetSize = 10, valsetSize = Some(5)), 40)
  }

  test("full evaluations count the trainset and the validation set that the caller gave") {
    val twice = GepaBudget.FullEvals(FullEvalCount(2))
    assertEquals(twice.resolve(components = 1, trainsetSize = 10, valsetSize = Some(5)).toInt, 30)
    assertEquals(twice.resolve(components = 1, trainsetSize = 10, valsetSize = None).toInt, 20)
    // An empty validation set that the caller gave counts as 0, as upstream (`len(valset) if valset is not None`).
    assertEquals(twice.resolve(components = 1, trainsetSize = 10, valsetSize = Some(0)).toInt, 20)
    assert(compileErrors("dspy4s.gepa.FullEvalCount(0)").nonEmpty)
    assert(FullEvalCount.either(0).isLeft)
  }

  test("a budget larger than the largest Int gives the largest Int, and not an overflow") {
    val many = GepaBudget.FullEvals(FullEvalCount(Int.MaxValue))
    assertEquals(many.resolve(components = 1, trainsetSize = 10, valsetSize = Some(5)).toInt, Int.MaxValue)
    assertEquals(GepaBudget.auto(components = 1, candidates = 6, valsetSize = Int.MaxValue), Int.MaxValue)
    assertEquals(GepaBudget.auto(components = Int.MaxValue, candidates = 18, valsetSize = 10), Int.MaxValue)
  }

  test("an auto budget uses the validation set, or the trainset when the caller gave no validation set") {
    val light = GepaBudget.Auto(AutoBudget.Light)
    assertEquals(light.resolve(components = 1, trainsetSize = 10, valsetSize = Some(4)).toInt, 396)
    assertEquals(light.resolve(components = 1, trainsetSize = 10, valsetSize = None).toInt, 420)
    // A program with no component counts as one component, as upstream (`max(num_components, 1)`).
    assertEquals(light.resolve(components = 0, trainsetSize = 10, valsetSize = Some(4)).toInt, 396)
    assertEquals(
      GepaBudget.Auto(AutoBudget.Heavy).resolve(components = 3, trainsetSize = 1000, valsetSize = Some(300)).toInt,
      5440
    )
  }
