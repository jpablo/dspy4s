package dspy4s.optimize

import dspy4s.programs.optimization.optimizableParameters

import dspy4s.programs.optimization.OptimizableStructure

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.Example
import dspy4s.programs.AttemptCount
import dspy4s.programs.strategies.MultiChainComparison
import dspy4s.programs.strategies.ReAct
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.ToolFunction
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

class ProgramOptimizableStructureSuite extends FunSuite:

  /** Resolves the right [[OptimizableStructure]] instance from the program's *static* type, so the `[I, O]` of the
    * given are inferred at the call site (accessing the `given ... with` object directly would pin them to `Nothing`).
    */
  private def predictorsOf[P](@annotation.unused program: P)(using
      ps: OptimizableStructure[P]
  ): OptimizableStructure[P] = ps

  private val qaSignature = Signature.fromString("question -> answer")

  private val search: ToolFunction = new ToolFunction:
    override val name: String                                                                             = "search"
    override val description: String                                                                      = "look something up"
    override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
      Right(ToolFunction.result("ok"))

  private val demo = Vector(Example(rec("question" := "q", "answer" := "x")))

  // ── ReAct ──────────────────────────────────────────────────────────────

  test("ReAct: inspect returns the two hoisted predictor views in stable order") {
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps    = predictorsOf(react)
    val views = ps.inspect(react)
    assertEquals(views.size, 2)
    assertEquals(views(0).moduleName, "react")
    assertEquals(views(1).moduleName, "react_extract")
    assertEquals(views(0).parameters, react.reactPredict.optimizableParameters)
    assertEquals(views(1).parameters, react.extractorPredict.optimizableParameters)
  }

  test("ReAct: replace(p, read(p)) round-trips to identity") {
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps    = predictorsOf(react)
    val back  = ps.replace(react, ps.read(react))
    // overrides untouched (both still None) -> the rebuilt program is value-equal to the original.
    assertEquals(back.reactPredictOverride, None)
    assertEquals(back.extractorPredictOverride, None)
    assertEquals(back, react)
  }

  test("ReAct: replace with demo-edited predicts is reflected via the override fields") {
    val react   = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps      = predictorsOf(react)
    val read    = ps.read(react)
    val edited0 = read(0).copy(demos = demo)
    val out     = ps.replace(react, Vector(edited0, read(1)))
    // the react predict carries the new demos; the extractor is untouched (still None override)
    assertEquals(out.reactPredict.demos, demo)
    assertEquals(out.extractorPredictOverride, None)
    assertEquals(out.extractorPredict.demos, Vector.empty[Example])
    // re-reading reflects the edit
    assertEquals(ps.read(out)(0).demos, demo)
  }

  test("ReAct: replace with a demo-edited extractor is reflected via the extractor override field") {
    val react   = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps      = predictorsOf(react)
    val read    = ps.read(react)
    val edited1 = read(1).copy(demos = demo)
    val out     = ps.replace(react, Vector(read(0), edited1))
    // the extractor carries the new demos; the react predict is untouched (still None override)
    assertEquals(out.extractorPredict.demos, demo)
    assertEquals(out.reactPredictOverride, None)
    assertEquals(out.reactPredict.demos, Vector.empty[Example])
    assertEquals(ps.read(out)(1).demos, demo)
  }

  // ── MultiChainComparison ─────────────────────────────────────────────────

  test("MultiChainComparison: inspect returns the single hoisted compare predictor view") {
    val mcc   = MultiChainComparison(baseSignature = qaSignature, m = AttemptCount(3))
    val ps    = predictorsOf(mcc)
    val views = ps.inspect(mcc)
    assertEquals(views.size, 1)
    assertEquals(views.head.parameters, ps.read(mcc).head)
  }

  test("MultiChainComparison: replace(p, read(p)) round-trips to identity") {
    val mcc  = MultiChainComparison(baseSignature = qaSignature, m = AttemptCount(3))
    val ps   = predictorsOf(mcc)
    val back = ps.replace(mcc, ps.read(mcc))
    assertEquals(back.comparePredictParametersOverride, None)
    assertEquals(back, mcc)
  }

  test("MultiChainComparison: replace with a demo-edited predict is reflected via the override field") {
    val mcc    = MultiChainComparison(baseSignature = qaSignature, m = AttemptCount(3))
    val ps     = predictorsOf(mcc)
    val edited = ps.read(mcc).head.copy(demos = demo)
    val out    = ps.replace(mcc, Vector(edited))
    assertEquals(out.comparePredictParametersOverride.map(_.demos), Some(demo))
    assertEquals(ps.read(out).head.demos, demo)
  }

  test("ReAct: replace rejects a wrong-sized update vector") {
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps    = predictorsOf(react)
    intercept[IllegalArgumentException](ps.replace(react, Vector.empty))
  }
