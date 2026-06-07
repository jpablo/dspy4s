package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.Example
import dspy4s.programs.ChainOfThought
import dspy4s.programs.Predict
import dspy4s.typed.Signature
import munit.FunSuite

import ProgramLeafPredictorsSuite.Pipe2

/** P4: leaf [[Predictor]] instances for the typed single-predictor programs [[Predict]] and [[ChainOfThought]].
  *
  * A `Predict`/`ChainOfThought` field inside a composite must resolve to the 1-element leaf (via
  * [[Predictors.fromPredictor]]) rather than being structurally torn apart by [[Predictors.derived]]. */
class ProgramLeafPredictorsSuite extends FunSuite:

  /** Resolves the right [[Predictor]]/[[Predictors]] from the program's *static* type, so the `[I, O]` of the
    * given are inferred at the call site rather than pinned to `Nothing`. */
  private def predictorOf[P](@annotation.unused program: P)(using leaf: Predictor[P]): Predictor[P]    = leaf
  private def predictorsOf[P](@annotation.unused program: P)(using ps: Predictors[P]): Predictors[P]   = ps

  // A concrete typed signature: (question: String) -> (answer: String).
  private val qaSignature = Signature.fromString("question -> answer")

  private val demo = Vector(Example(rec("question" := "q", "answer" := "x")))

  // ── Predict ─────────────────────────────────────────────────────────────

  test("Predictor[Predict].get exposes the program's layout, demos, name and output schema") {
    val predict = Predict(qaSignature, demos = demo, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val dp      = leaf.get(predict)
    assertEquals(dp.layout, predict.signature.layout)
    assertEquals(dp.demos, demo)
    assertEquals(dp.name, Some("ask"))
    assertEquals(dp.outputJsonSchema, predict.signature.outputShape.jsonSchemaString)
  }

  test("Predictor[Predict]: set(p, get(p)) round-trips to identity") {
    val predict = Predict(qaSignature, demos = demo, name = Some("ask"))
    val leaf    = predictorOf(predict)
    assertEquals(leaf.set(predict, leaf.get(predict)), predict)
  }

  test("Predictor[Predict]: set with edited demos is reflected (demos-only)") {
    val predict = Predict(qaSignature, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val edited  = leaf.get(predict).copy(demos = demo)
    val out     = leaf.set(predict, edited)
    assertEquals(out.demos, demo)
    // re-reading reflects the edit
    assertEquals(leaf.get(out).demos, demo)
  }

  test("Predictor[Predict]: set writes back instructions (COPRO/MIPRO enabler)") {
    val predict = Predict(qaSignature, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val cur     = leaf.get(predict)
    val out     = leaf.set(predict, cur.copy(layout = cur.layout.withInstructions(Some("Think carefully."))))
    assertEquals(out.signature.instructions, Some("Think carefully."))
    assertEquals(leaf.get(out).layout.instructions, Some("Think carefully."))
  }

  test("Predictor[Predict]: set writes back module config") {
    val predict = Predict(qaSignature, name = Some("ask"))
    val leaf    = predictorOf(predict)
    val cfg     = rec("temperature" := 0.3)
    val cur     = leaf.get(predict)
    val out     = leaf.set(predict, cur.copy(config = cfg))
    assertEquals(out.config, cfg)
    assertEquals(leaf.get(out).config, cfg)
  }

  // ── ChainOfThought ───────────────────────────────────────────────────────

  test("Predictor[ChainOfThought].get exposes the AUGMENTED layout (reasoning prepended) + demos") {
    val cot  = ChainOfThought(qaSignature, demos = demo, name = Some("think"))
    val leaf = predictorOf(cot)
    val dp   = leaf.get(cot)
    // the exposed layout is the augmented one containing the leading `reasoning` output field
    assertEquals(dp.layout.outputFields.head.name, "reasoning")
    assert(dp.layout.outputFields.exists(_.name == "answer"))
    assertEquals(dp.demos, demo)
    assertEquals(dp.name, Some("think"))
  }

  test("Predictor[ChainOfThought]: set(p, get(p)) round-trips to identity") {
    val cot  = ChainOfThought(qaSignature, demos = demo, name = Some("think"))
    val leaf = predictorOf(cot)
    assertEquals(leaf.set(cot, leaf.get(cot)), cot)
  }

  test("Predictor[ChainOfThought]: set with edited demos is reflected (demos-only)") {
    val cot    = ChainOfThought(qaSignature, name = Some("think"))
    val leaf   = predictorOf(cot)
    val edited = leaf.get(cot).copy(demos = demo)
    val out    = leaf.set(cot, edited)
    assertEquals(out.demos, demo)
    assertEquals(leaf.get(out).demos, demo)
  }

  test("Predictor[ChainOfThought]: set writes back instructions") {
    val cot  = ChainOfThought(qaSignature, name = Some("think"))
    val leaf = predictorOf(cot)
    val cur  = leaf.get(cot)
    val out  = leaf.set(cot, cur.copy(layout = cur.layout.withInstructions(Some("Reason step by step."))))
    assertEquals(out.signature.instructions, Some("Reason step by step."))
  }

  // ── Resolution priority: leaf, not structural derivation ─────────────────

  test("Predictors[Predict] resolves to the leaf fromPredictor instance, not derived") {
    val predict = Predict(qaSignature, name = Some("ask"))
    assertEquals(
      predictorsOf(predict).getClass.getName,
      "dspy4s.optimize.Predictors$fromPredictor"
    )
  }

  test("Predictors[ChainOfThought] resolves to the leaf fromPredictor instance, not derived") {
    val cot = ChainOfThought(qaSignature, name = Some("think"))
    assertEquals(
      predictorsOf(cot).getClass.getName,
      "dspy4s.optimize.Predictors$fromPredictor"
    )
  }

  // ── Composite: leaf instances are picked up inside derivation ─────────────

  test("a composite of Predict + ChainOfThought reads exactly 2 predictors and round-trips") {
    val pipe = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = ChainOfThought(qaSignature, name = Some("think"))
    )
    val ps   = summon[Predictors[Pipe2]]
    val read = ps.read(pipe)
    assertEquals(read.size, 2)
    assertEquals(read(0).name, Some("ask"))
    assertEquals(read(1).name, Some("think"))
    // the CoT field exposes the augmented layout (reasoning prepended)
    assertEquals(read(1).layout.outputFields.head.name, "reasoning")
    // round-trip identity
    assertEquals(ps.replace(pipe, ps.read(pipe)), pipe)
  }

  test("a composite of Predict + ChainOfThought: edited demos round-trip through both leaves") {
    val pipe = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = ChainOfThought(qaSignature, name = Some("think"))
    )
    val ps       = summon[Predictors[Pipe2]]
    val attached = ps.replace(pipe, ps.read(pipe).map(_.copy(demos = demo)))
    assertEquals(attached.a.demos, demo)
    assertEquals(attached.b.demos, demo)
  }

object ProgramLeafPredictorsSuite:

  // A composite holding a typed Predict and a typed ChainOfThought, both concrete (question -> answer).
  final case class Pipe2(
      a: Predict[(question: String), (answer: String)],
      b: ChainOfThought[(question: String), (answer: String)]
  )

  object Pipe2:
    given Predictors[Pipe2] = Predictors.derived
