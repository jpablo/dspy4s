package dspy4s.optimize

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.Example
import dspy4s.programs.CodeAct
import dspy4s.programs.MultiChainComparison
import dspy4s.programs.ReAct
import dspy4s.core.contracts.RuntimeContext
import dspy4s.programs.contracts.ToolFunction
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import ProgramPredictors.given

class ProgramPredictorsSuite extends FunSuite:

  /** Resolves the right [[Predictors]] instance from the program's *static* type, so the `[I, O]` of the given
    * are inferred at the call site (accessing the `given ... with` object directly would pin them to `Nothing`). */
  private def predictorsOf[P](@annotation.unused program: P)(using ps: Predictors[P]): Predictors[P] = ps

  private val qaSignature = Signature.fromString("question -> answer")

  private val search: ToolFunction = new ToolFunction:
    override val name: String        = "search"
    override val description: String = "look something up"
    override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
      Right(ToolFunction.result("ok"))

  private object NoopInterpreter extends CodeInterpreter:
    def execute(code: String): Either[DspyError, CodeResult] = Right(CodeResult("", "", 0))
    override def close(): Unit                                 = ()

  private val demo = Vector(Example(rec("question" := "q", "answer" := "x")))

  // ── ReAct ──────────────────────────────────────────────────────────────

  test("ReAct: read returns the two hoisted predicts (react, extractor) in stable order") {
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps    = predictorsOf(react)
    val read  = ps.read(react)
    assertEquals(read.size, 2)
    assertEquals(read(0).name, Some("react"))
    assertEquals(read(1).name, Some("react_extract"))
    // the read predicts ARE the program's hoisted members (reference identity)
    assert(read(0) eq react.reactPredict)
    assert(read(1) eq react.extractorPredict)
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

  // ── CodeAct ────────────────────────────────────────────────────────────

  test("CodeAct: read returns the two hoisted predicts (codeact, extractor) in stable order") {
    val codeAct = CodeAct(baseSignature = qaSignature, interpreter = NoopInterpreter)
    val ps      = predictorsOf(codeAct)
    val read    = ps.read(codeAct)
    assertEquals(read.size, 2)
    assertEquals(read(0).name, Some("codeact"))
    assertEquals(read(1).name, Some("codeact_extract"))
    assert(read(0) eq codeAct.codeActPredict)
    assert(read(1) eq codeAct.extractorPredict)
  }

  test("CodeAct: replace(p, read(p)) round-trips to identity") {
    val codeAct = CodeAct(baseSignature = qaSignature, interpreter = NoopInterpreter)
    val ps      = predictorsOf(codeAct)
    val back    = ps.replace(codeAct, ps.read(codeAct))
    assertEquals(back.codeActPredictOverride, None)
    assertEquals(back.extractorPredictOverride, None)
    assertEquals(back, codeAct)
  }

  test("CodeAct: replace with demo-edited predicts is reflected via the override fields") {
    val codeAct = CodeAct(baseSignature = qaSignature, interpreter = NoopInterpreter)
    val ps      = predictorsOf(codeAct)
    val read    = ps.read(codeAct)
    val edited1 = read(1).copy(demos = demo)
    val out     = ps.replace(codeAct, Vector(read(0), edited1))
    assertEquals(out.extractorPredict.demos, demo)
    assertEquals(out.codeActPredictOverride, None)
    assertEquals(out.codeActPredict.demos, Vector.empty[Example])
    assertEquals(ps.read(out)(1).demos, demo)
  }

  // ── MultiChainComparison ─────────────────────────────────────────────────

  test("MultiChainComparison: read returns the single hoisted compare predict") {
    val mcc  = MultiChainComparison(baseSignature = qaSignature, m = 3)
    val ps   = predictorsOf(mcc)
    val read = ps.read(mcc)
    assertEquals(read.size, 1)
    assert(read(0) eq mcc.comparePredict)
  }

  test("MultiChainComparison: replace(p, read(p)) round-trips to identity") {
    val mcc  = MultiChainComparison(baseSignature = qaSignature, m = 3)
    val ps   = predictorsOf(mcc)
    val back = ps.replace(mcc, ps.read(mcc))
    assertEquals(back.comparePredictOverride, None)
    assertEquals(back, mcc)
  }

  test("MultiChainComparison: replace with a demo-edited predict is reflected via the override field") {
    val mcc    = MultiChainComparison(baseSignature = qaSignature, m = 3)
    val ps     = predictorsOf(mcc)
    val edited = ps.read(mcc).head.copy(demos = demo)
    val out    = ps.replace(mcc, Vector(edited))
    assertEquals(out.comparePredict.demos, demo)
    assertEquals(ps.read(out).head.demos, demo)
  }

  test("ReAct: replace rejects a wrong-sized update vector") {
    val react = ReAct(baseSignature = qaSignature, tools = Vector(search))
    val ps    = predictorsOf(react)
    intercept[IllegalArgumentException](ps.replace(react, Vector.empty))
  }
