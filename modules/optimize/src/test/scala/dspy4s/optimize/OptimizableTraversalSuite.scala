package dspy4s.optimize

import dspy4s.programs.optimization.optimizableParameters

import dspy4s.programs.optimization.{FixedArityOptimizableTraversal, OptimizableParameters, OptimizableTraversal}

import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.core.signatures.SignatureDsl
import dspy4s.programs.DynamicPredict
import munit.FunSuite

class OptimizableTraversalSuite extends FunSuite:

  private val sigA = SignatureDsl.parse("question: str -> answer: str").toOption.get
  private val sigB = SignatureDsl.parse("text: str -> summary: str").toOption.get

  // A composite of two predictors plus a non-predictor field.
  final case class Pipe(a: DynamicPredict, b: DynamicPredict, n: Int)
  object Pipe:
    given FixedArityOptimizableTraversal.Aux[Int, 0] = OptimizableTraversal.empty
    given OptimizableTraversal[Pipe]                 = OptimizableTraversal.derived

  test("OptimizableLeaf lifts to a 1-element OptimizableTraversal via fromOptimizableLeaf") {
    val p  = DynamicPredict(layout = sigA)
    val ps = summon[OptimizableTraversal[DynamicPredict]]
    assertEquals(ps.read(p).size, 1)
    assertEquals(ps.read(p).head, p.optimizableParameters)
    val updated = p.optimizableParameters.copy(instructions = Some("updated"))
    val replaced = ps.replace(p, Vector(updated))
    assertEquals(replaced.optimizableParameters, updated)
    assertEquals(replaced.layout.fields, sigA.fields)
  }

  test("derived read concatenates fields left-to-right, honoring explicitly parameter-free fields") {
    val a    = DynamicPredict(layout = sigA, name = Some("a"))
    val b    = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe = Pipe(a, b, 7)
    val ps   = summon[OptimizableTraversal[Pipe]]
    val views = ps.inspect(pipe)
    assertEquals(views.size, 2)
    assertEquals(views(0).moduleName, "a")
    assertEquals(views(1).moduleName, "b")
  }

  test("derived replace round-trips to identity: replace(p, read(p)) == p") {
    val a    = DynamicPredict(layout = sigA, name = Some("a"))
    val b    = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe = Pipe(a, b, 7)
    val ps   = summon[OptimizableTraversal[Pipe]]
    assertEquals(ps.replace(pipe, ps.read(pipe)), pipe)
  }

  test("derived replace swaps the right field positionally") {
    val a       = DynamicPredict(layout = sigA, name = Some("a"))
    val b       = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe    = Pipe(a, b, 7)
    val ps      = summon[OptimizableTraversal[Pipe]]
    val newDemo = Vector(Example(rec("question" := "q", "answer" := "x")))
    val editedA = a.optimizableParameters.copy(demos = newDemo)
    val out     = ps.replace(pipe, Vector(editedA, b.optimizableParameters))
    assertEquals(out.a.demos, newDemo)
    assertEquals(out.b, b)
    assertEquals(out.n, 7)
  }

  test("empty is the identity instance: reads nothing, replace returns the program") {
    val empty = OptimizableTraversal.empty[Int]
    assertEquals(empty.read(42), Vector.empty[OptimizableParameters])
    assertEquals(empty.replace(42, Vector.empty), 42)
  }

  test("derived rejects a field without OptimizableTraversal evidence instead of silently treating it as empty") {
    val errors = compileErrors("""
      import dspy4s.programs.DynamicPredict
      import dspy4s.programs.optimization.OptimizableTraversal

      final class Opaque
      final case class Broken(predict: DynamicPredict, opaque: Opaque)
      given OptimizableTraversal[Broken] = OptimizableTraversal.derived
    """)

    assert(errors.contains("Cannot derive OptimizableTraversal"), errors)
    assert(errors.contains("OptimizableTraversal.empty"), errors)
  }

  test("given priority: leaf vs structural derivation resolve distinctly") {
    // A leaf type (DynamicPredict has OptimizableLeaf and is a Product) -> fromOptimizableLeaf.
    assertEquals(
      summon[OptimizableTraversal[DynamicPredict]].getClass.getName,
      "dspy4s.programs.optimization.OptimizableTraversal$fromOptimizableLeaf"
    )
    // A single-leaf program with an OptimizableLeaf instance -> fromOptimizableLeaf (not torn into fields).
    assertEquals(
      summon[OptimizableTraversal[ScriptedPredictProgram]].getClass.getName,
      "dspy4s.programs.optimization.OptimizableTraversal$fromOptimizableLeaf"
    )
    // A plain composite with no leaf instance -> structural derivation.
    assertEquals(
      summon[OptimizableTraversal[Pipe]].getClass.getName,
      "dspy4s.programs.optimization.OptimizableTraversal$DerivedOptimizableTraversal"
    )
  }

  test("OptimizableLeaf leaf program is length-1 and round-trips demos through the leaf set") {
    val student = ScriptedPredictProgram(Map.empty, sigA)
    val ps      = summon[OptimizableTraversal[ScriptedPredictProgram]]
    assertEquals(ps.read(student).size, 1)
    assertEquals(ps.inspect(student).head.layout, sigA)

    val demos   = Vector(Example(rec("question" := "q", "answer" := "x")))
    val updated = ps.replace(student, ps.read(student).map(_.copy(demos = demos)))
    assertEquals(updated.demos, demos)
    // round-trip identity when re-reading then replacing the same predictors
    assertEquals(ps.replace(updated, ps.read(updated)), updated)
  }

  test("derived attaches demos to every contained predictor (multi-predictor)") {
    val a    = DynamicPredict(layout = sigA, name = Some("a"))
    val b    = DynamicPredict(layout = sigB, name = Some("b"))
    val pipe = Pipe(a, b, 7)
    val ps   = summon[OptimizableTraversal[Pipe]]

    val demos    = Vector(Example(rec("question" := "q", "answer" := "x")))
    val attached = ps.replace(pipe, ps.read(pipe).map(_.copy(demos = demos)))
    assertEquals(attached.a.demos, demos)
    assertEquals(attached.b.demos, demos)
    assertEquals(attached.n, 7)
  }
