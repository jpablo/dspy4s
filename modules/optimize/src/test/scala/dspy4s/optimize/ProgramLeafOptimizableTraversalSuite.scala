package dspy4s.optimize

import dspy4s.programs.optimization.{OptimizableLeaf, OptimizableTraversal}

import dspy4s.core.contracts.:=
import dspy4s.core.data.Example
import dspy4s.programs.strategies.ChainOfThought
import dspy4s.programs.strategies.Predict
import dspy4s.typed.Signature
import munit.FunSuite

import ProgramLeafOptimizableTraversalSuite.Pipe2

/** P4: leaf [[OptimizableLeaf]] instances for the typed single-predictor programs [[Predict]] and [[ChainOfThought]].
  *
  * A `Predict`/`ChainOfThought` field inside a composite must resolve to the 1-element leaf (via
  * [[OptimizableTraversal.fromOptimizableLeaf]]) rather than being structurally torn apart by
  * [[OptimizableTraversal.derived]].
  */
class ProgramLeafOptimizableTraversalSuite extends FunSuite:

  /** Resolves the right [[OptimizableLeaf]]/[[OptimizableTraversal]] from the program's *static* type, so the `[I, O]`
    * of the given are inferred at the call site rather than pinned to `Nothing`.
    */
  private def predictorOf[P](@annotation.unused program: P)(using leaf: OptimizableLeaf[P]): OptimizableLeaf[P] = leaf
  private def predictorsOf[P](@annotation.unused program: P)(using
      ps: OptimizableTraversal[P]
  ): OptimizableTraversal[P] = ps

  // A concrete typed signature: (question: String) -> (answer: String).
  private val qaSignature = Signature.fromString("question -> answer")

  private val demo = Vector(Example(rec("question" := "q", "answer" := "x")))

  // ── Predict ─────────────────────────────────────────────────────────────

  test("OptimizableLeaf[Predict] separates writable state from read-only metadata") {
    val predict = Predict(qaSignature, demos = demo, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val state   = leaf.get(predict)
    val view    = leaf.inspect(predict)
    assertEquals(view.layout, predict.signature.layout)
    assertEquals(state.demos, demo)
    assertEquals(view.moduleName, "ask")
  }

  test("OptimizableLeaf[Predict]: set(p, get(p)) round-trips to identity") {
    val predict = Predict(qaSignature, demos = demo, name = Some("ask"))
    val leaf    = predictorOf(predict)
    assertEquals(leaf.set(predict, leaf.get(predict)), predict)
  }

  test("OptimizableLeaf[Predict]: set with edited demos is reflected (demos-only)") {
    val predict = Predict(qaSignature, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val edited  = leaf.get(predict).copy(demos = demo)
    val out     = leaf.set(predict, edited)
    assertEquals(out.demos, demo)
    // re-reading reflects the edit
    assertEquals(leaf.get(out).demos, demo)
  }

  test("OptimizableLeaf[Predict]: set writes back instructions (COPRO/MIPRO enabler)") {
    val predict = Predict(qaSignature, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val cur     = leaf.get(predict)
    val out     = leaf.set(predict, cur.copy(instructions = Some("Think carefully.")))
    assertEquals(out.signature.instructions, Some("Think carefully."))
    assertEquals(leaf.get(out).instructions, Some("Think carefully."))
  }

  test("OptimizableLeaf[Predict]: set writes back module config") {
    val predict = Predict(qaSignature, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val cfg     = rec("temperature" := 0.3)
    val cur     = leaf.get(predict)
    val out     = leaf.set(predict, cur.copy(config = cfg))
    assertEquals(out.config, cfg)
    assertEquals(leaf.get(out).config, cfg)
  }

  // ── ChainOfThought ───────────────────────────────────────────────────────

  test("OptimizableLeaf[ChainOfThought] exposes augmented read-only metadata plus writable state") {
    val cot   = ChainOfThought(qaSignature, demos = demo, name = Some("think"))
    val leaf  = predictorOf(cot)
    val state = leaf.get(cot)
    val view  = leaf.inspect(cot)
    // the exposed layout is the augmented one containing the leading `reasoning` output field
    assertEquals(view.layout.outputFields.head.name, "reasoning")
    assert(view.layout.outputFields.exists(_.name == "answer"))
    assertEquals(state.demos, demo)
    assertEquals(view.moduleName, "think")
  }

  test("OptimizableLeaf[ChainOfThought]: set(p, get(p)) round-trips to identity") {
    val cot  = ChainOfThought(qaSignature, demos = demo, name = Some("think"))
    val leaf = predictorOf(cot)
    assertEquals(leaf.set(cot, leaf.get(cot)), cot)
  }

  test("OptimizableLeaf[ChainOfThought]: set with edited demos is reflected (demos-only)") {
    val cot    = ChainOfThought(qaSignature, name = Some("think"))
    val leaf   = predictorOf(cot)
    val edited = leaf.get(cot).copy(demos = demo)
    val out    = leaf.set(cot, edited)
    assertEquals(out.demos, demo)
    assertEquals(leaf.get(out).demos, demo)
  }

  test("OptimizableLeaf[ChainOfThought]: set writes back instructions") {
    val cot  = ChainOfThought(qaSignature, name = Some("think"))
    val leaf = predictorOf(cot)
    val cur  = leaf.get(cot)
    val out  = leaf.set(cot, cur.copy(instructions = Some("Reason step by step.")))
    assertEquals(out.baseSignature.instructions, Some("Reason step by step."))
  }

  // ── Resolution priority: leaf, not structural derivation ─────────────────

  test("OptimizableTraversal[Predict] resolves to the leaf fromOptimizableLeaf instance, not derived") {
    val predict = Predict(qaSignature, name = Some("ask"))
    assertEquals(
      predictorsOf(predict).getClass.getName,
      "dspy4s.programs.optimization.OptimizableTraversal$fromOptimizableLeaf"
    )
  }

  test("OptimizableTraversal[ChainOfThought] resolves to the leaf fromOptimizableLeaf instance, not derived") {
    val cot = ChainOfThought(qaSignature, name = Some("think"))
    assertEquals(
      predictorsOf(cot).getClass.getName,
      "dspy4s.programs.optimization.OptimizableTraversal$fromOptimizableLeaf"
    )
  }

  // ── Composite: leaf instances are picked up inside derivation ─────────────

  test("a composite of Predict + ChainOfThought reads exactly 2 predictors and round-trips") {
    val pipe = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = ChainOfThought(qaSignature, name = Some("think"))
    )
    val ps    = summon[OptimizableTraversal[Pipe2]]
    val views = ps.inspect(pipe)
    assertEquals(views.size, 2)
    assertEquals(views(0).moduleName, "ask")
    assertEquals(views(1).moduleName, "think")
    // the CoT field exposes the augmented layout (reasoning prepended)
    assertEquals(views(1).layout.outputFields.head.name, "reasoning")
    // round-trip identity
    assertEquals(ps.replace(pipe, ps.read(pipe)), pipe)
  }

  test("a composite of Predict + ChainOfThought: edited demos round-trip through both leaves") {
    val pipe = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = ChainOfThought(qaSignature, name = Some("think"))
    )
    val ps       = summon[OptimizableTraversal[Pipe2]]
    val attached = ps.replace(pipe, ps.read(pipe).map(_.copy(demos = demo)))
    assertEquals(attached.a.demos, demo)
    assertEquals(attached.b.demos, demo)
  }

object ProgramLeafOptimizableTraversalSuite:

  // A composite holding a typed Predict and a typed ChainOfThought, both concrete (question -> answer).
  final case class Pipe2(
      a: Predict[(question: String), (answer: String)],
      b: ChainOfThought[(question: String), (answer: String)]
  )

  object Pipe2:
    given OptimizableTraversal[Pipe2] = OptimizableTraversal.derived
