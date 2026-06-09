package dspy4s.optimize

import dspy4s.programs.Predictors

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.Example
import dspy4s.core.signatures.SignatureDsl
import dspy4s.programs.DynamicPredict
import munit.FunSuite

class PredictorsSuite extends FunSuite:

  private val sigA = SignatureDsl.parse("question: str -> answer: str").toOption.get
  private val sigB = SignatureDsl.parse("text: str -> summary: str").toOption.get

  // A composite of two predictors plus a non-predictor field.
  final case class Pipe(a: DynamicPredict, b: DynamicPredict, n: Int)
  object Pipe:
    given Predictors[Pipe] = Predictors.derived

  test("Predictor lifts to a 1-element Predictors via fromPredictor") {
    val p   = DynamicPredict(layout = sigA)
    val ps  = summon[Predictors[DynamicPredict]]
    assertEquals(ps.read(p).size, 1)
    assertEquals(ps.read(p).head, p)
    val updated = DynamicPredict(layout = sigB)
    assertEquals(ps.replace(p, Vector(updated)), updated)
  }

  test("derived read concatenates fields left-to-right, skipping non-predictor fields") {
    val a    = DynamicPredict(layout = sigA, name = Some("a"))
    val b    = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe = Pipe(a, b, 7)
    val ps   = summon[Predictors[Pipe]]
    val read = ps.read(pipe)
    assertEquals(read.size, 2)
    assertEquals(read(0).name, Some("a"))
    assertEquals(read(1).name, Some("b"))
  }

  test("derived replace round-trips to identity: replace(p, read(p)) == p") {
    val a    = DynamicPredict(layout = sigA, name = Some("a"))
    val b    = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe = Pipe(a, b, 7)
    val ps   = summon[Predictors[Pipe]]
    assertEquals(ps.replace(pipe, ps.read(pipe)), pipe)
  }

  test("derived replace swaps the right field positionally") {
    val a       = DynamicPredict(layout = sigA, name = Some("a"))
    val b       = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe    = Pipe(a, b, 7)
    val ps      = summon[Predictors[Pipe]]
    val newDemo = Vector(Example(rec("question" := "q", "answer" := "x")))
    val editedA = a.copy(demos = newDemo)
    val out     = ps.replace(pipe, Vector(editedA, b))
    assertEquals(out.a.demos, newDemo)
    assertEquals(out.b, b)
    assertEquals(out.n, 7)
  }

  test("empty is the identity instance: reads nothing, replace returns the program") {
    val empty = Predictors.empty[Int]
    assertEquals(empty.read(42), Vector.empty[DynamicPredict])
    assertEquals(empty.replace(42, Vector.empty), 42)
  }

  test("given priority: leaf vs structural derivation resolve distinctly") {
    // A leaf type (DynamicPredict has Predictor and is a Product) -> fromPredictor.
    assertEquals(
      summon[Predictors[DynamicPredict]].getClass.getName,
      "dspy4s.programs.Predictors$fromPredictor"
    )
    // A single-predictor program with a Predictor leaf instance -> fromPredictor (not torn into fields).
    assertEquals(
      summon[Predictors[ScriptedPredictProgram]].getClass.getName,
      "dspy4s.programs.Predictors$fromPredictor"
    )
    // A plain composite with no leaf instance -> structural derivation.
    assertEquals(
      summon[Predictors[Pipe]].getClass.getName,
      "dspy4s.programs.Predictors$DerivedPredictors"
    )
  }

  test("Predictor leaf program is length-1 and round-trips demos through the leaf set") {
    val student = ScriptedPredictProgram(Map.empty, sigA)
    val ps      = summon[Predictors[ScriptedPredictProgram]]
    assertEquals(ps.read(student).size, 1)
    assertEquals(ps.read(student).head.layout, sigA)

    val demos    = Vector(Example(rec("question" := "q", "answer" := "x")))
    val updated  = ps.replace(student, ps.read(student).map(_.copy(demos = demos)))
    assertEquals(updated.demos, demos)
    // round-trip identity when re-reading then replacing the same predictors
    assertEquals(ps.replace(updated, ps.read(updated)), updated)
  }

  test("derived attaches demos to every contained predictor (multi-predictor)") {
    val a    = DynamicPredict(layout = sigA, name = Some("a"))
    val b    = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe = Pipe(a, b, 7)
    val ps   = summon[Predictors[Pipe]]

    val demos    = Vector(Example(rec("question" := "q", "answer" := "x")))
    val attached = ps.replace(pipe, ps.read(pipe).map(_.copy(demos = demos)))
    assertEquals(attached.a.demos, demos)
    assertEquals(attached.b.demos, demos)
    assertEquals(attached.n, 7)
  }
