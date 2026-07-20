package dspy4s.programs

import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.{Prediction, Signature}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

// Top-level fixtures (Schema derivation requires top-level types) for the RLM signature.
final case class RlmIn(question: String) derives Schema
final case class RlmOut(answer: String) derives Schema

/** Round-trip and distribution laws for the `Predictors` instances added for the remaining composites
  * ([[BestOfN]] pass-through, [[Refine]] `read = inner ++ [critic]`, [[RLM]] action+extract) — the gap-closing
  * counterpart of `ComposeLawSuite` / `ModeLawSuite`'s addressability sections. The invariant under test is
  * the spec's homomorphism contract: `read` distributes structurally, `replace(p, read(p)) == p`, and a
  * genuine replace writes back positionally. */
class CompositePredictorsSuite extends FunSuite:

  private object Interpreter extends CodeInterpreter:
    def execute(code: String): Either[DspyError, CodeResult] =
      Right(CodeResult(stdout = "", stderr = "", exitCode = 0))
    def close(): Unit = ()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub with one learnable leaf (same pattern as ComposeLawSuite's Step). */
  private final case class Leaf[I, O](f: I => O, predict: DynamicPredict)
      extends Module[TypedCall[I], Prediction[O]]:
    override val moduleName: String = "leaf"
    override protected def callInputs(call: TypedCall[I]): DynamicValue.Record       = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[I]): Boolean             = call.traceEnabled
    override protected def tracePayload(p: Prediction[O]): DynamicValue.Record       = p.raw.values
    override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), DynamicPrediction.empty))

  private object Leaf:
    given leafPredictor[I, O]: Predictor[Leaf[I, O]] with
      def get(program: Leaf[I, O]): PredictorState = program.predict.predictorState
      def metadata(program: Leaf[I, O]): PredictorMetadata = program.predict.predictorView.metadata
      def set(program: Leaf[I, O], updated: PredictorState): Leaf[I, O] =
        program.copy(predict = program.predict.withPredictorState(updated))

  test("hand-written composite traversals expose logical predictor names") {
    val signature  = Signature.derived[RlmIn, RlmOut]("CompositeNames")
    val react      = ReAct(baseSignature = signature, tools = Vector.empty)
    val codeAct    = CodeAct(baseSignature = signature, interpreter = Interpreter)
    val rlm        = RLM(baseSignature = signature)
    val comparison = MultiChainComparison(baseSignature = signature)

    assertEquals(
      summon[Predictors[ReAct[RlmIn, RlmOut]]].inspectNamed(react).map(_._1),
      Vector("react", "extractor")
    )
    assertEquals(
      summon[Predictors[CodeAct[RlmIn, RlmOut]]].inspectNamed(codeAct).map(_._1),
      Vector("codeact", "extractor")
    )
    assertEquals(
      summon[Predictors[RLM[RlmIn, RlmOut]]].inspectNamed(rlm).map(_._1),
      Vector("action", "extract")
    )
    assertEquals(
      summon[Predictors[MultiChainComparison[RlmIn, RlmOut]]].inspectNamed(comparison).map(_._1),
      Vector("compare")
    )
  }

  // ── BestOfN: pass-through ─────────────────────────────────────────────────────────────────────────────────

  test("BestOfN read/inspectNamed pass through to the inner program; replace round-trips") {
    val leaf = Leaf[Int, Int](identity, predict("a -> b"))
    val b    = BestOfN[Leaf[Int, Int], Int, Int](leaf, n = 2, rewardFn = (_, _) => 1.0, threshold = 1.0)
    val P    = summon[Predictors[BestOfN[Leaf[Int, Int], Int, Int]]]

    assertEquals(P.read(b), Vector(leaf.predict.predictorState))
    assertEquals(P.inspectNamed(b).map(_._1), Vector("self"))
    assertEquals(P.replace(b, P.read(b)), b) // round-trip: replace(p, read(p)) == p
    // A genuine replace writes back through to the inner leaf.
    val fresh = leaf.predict.predictorState.copy(instructions = Some("Use the tuned leaf."))
    assertEquals(P.read(P.replace(b, Vector(fresh))), Vector(fresh))
  }

  test("BestOfN pass-through distributes over a composed inner program (two leaves)") {
    val first    = Leaf[Int, String](i => s"v$i", predict("a -> b"))
    val second   = Leaf[String, Int](_.length, predict("b -> c"))
    val composed = AndThen[Int, String, Int, Leaf[Int, String], Leaf[String, Int]](first, second)
    val b = BestOfN[AndThen[Int, String, Int, Leaf[Int, String], Leaf[String, Int]], Int, Int](
      composed, n = 2, rewardFn = (_, _) => 1.0, threshold = 1.0
    )
    val P = summon[Predictors[BestOfN[AndThen[Int, String, Int, Leaf[Int, String], Leaf[String, Int]], Int, Int]]]
    assertEquals(P.read(b), Vector(first.predict.predictorState, second.predict.predictorState))
    assertEquals(P.inspectNamed(b).map(_._1), Vector("first", "second"))
  }

  // ── Refine: read = inner ++ [critic] ──────────────────────────────────────────────────────────────────────

  test("Refine read = read(inner) :+ critic; the default critic is the OfferFeedback predict") {
    val leaf = Leaf[Int, Int](identity, predict("a -> b"))
    val r    = Refine[Leaf[Int, Int], Int, Int](leaf, n = 2, rewardFn = (_, _) => 1.0, threshold = 1.0)
    val P    = summon[Predictors[Refine[Leaf[Int, Int], Int, Int]]]

    assertEquals(P.read(r), Vector(leaf.predict.predictorState, r.criticPredict.predictorState))
    assertEquals(P.inspectNamed(r).map(_._1), Vector("self", "critic"))
    assertEquals(r.criticPredict.layout.name, "OfferFeedback")
    assertEquals(r.criticPredict.name, Some("offer_feedback"))
  }

  test("Refine replace round-trips; a genuine critic replace writes back (and only the critic)") {
    val leaf = Leaf[Int, Int](identity, predict("a -> b"))
    val r    = Refine[Leaf[Int, Int], Int, Int](leaf, n = 2, rewardFn = (_, _) => 1.0, threshold = 1.0)
    val P    = summon[Predictors[Refine[Leaf[Int, Int], Int, Int]]]

    assertEquals(P.replace(r, P.read(r)), r) // exact no-op state round-trip
    // Swap only the critic: the inner leaf is untouched, the critic is written back.
    val tunedCritic = r.criticPredict.predictorState.copy(instructions = Some("Give concrete feedback."))
    val replaced    = P.replace(r, Vector(leaf.predict.predictorState, tunedCritic))
    assertEquals(P.read(replaced), Vector(leaf.predict.predictorState, tunedCritic))
    assertEquals(replaced.criticPredict.predictorState, tunedCritic)
    assertEquals(replaced.module, leaf)
    // Swap only the inner leaf: the critic stays the default.
    val tunedLeaf = leaf.predict.predictorState.copy(instructions = Some("Tune the answer."))
    val replaced2 = P.replace(r, Vector(tunedLeaf, r.criticPredict.predictorState))
    assertEquals(P.read(replaced2).head, tunedLeaf)
    assertEquals(replaced2.criticPredictOverride, None)
  }

  // ── RLM: action + extract, the ReAct override pattern ─────────────────────────────────────────────────────

  test("RLM read = [actionPredict, extractPredict]; replace round-trips and writes back") {
    val rlm = RLM(baseSignature = Signature.derived[RlmIn, RlmOut]("RlmQA"))
    val P   = summon[Predictors[RLM[RlmIn, RlmOut]]]

    assertEquals(P.read(rlm), Vector(rlm.actionPredict.predictorState, rlm.extractPredict.predictorState))
    assertEquals(P.replace(rlm, P.read(rlm)), rlm) // round-trip: overrides stay None
    // A genuine replace lands in the override fields and is visible through read.
    val tunedAction = rlm.actionPredict.predictorState.copy(instructions = Some("Use a tuned action."))
    val replaced    = P.replace(rlm, Vector(tunedAction, rlm.extractPredict.predictorState))
    assertEquals(P.read(replaced).head, tunedAction)
    assertEquals(replaced.actionPredictOverride.map(_.predictorState), Some(tunedAction))
    assertEquals(replaced.extractPredictOverride, None)
    // `extractPredict` is an instance val, so the new RLM re-derives an equivalent default; compare the
    // stable projection (layout/name), not the object (DynamicPredict's runtime field is reference-compared).
    assertEquals(P.inspect(replaced)(1).layout, rlm.extractPredict.layout)
    assertEquals(P.inspect(replaced)(1).moduleName, rlm.extractPredict.moduleName)
  }

  test("override-backed composites observe read-after-write and change-revert through state") {
    val rlm      = RLM(baseSignature = Signature.derived[RlmIn, RlmOut]("RlmQA"))
    val P        = summon[Predictors[RLM[RlmIn, RlmOut]]]
    val original = P.read(rlm)
    val metadata = P.inspect(rlm).map(_.metadata)
    val changed  = original.updated(0, original.head.copy(instructions = Some("Explore methodically.")))

    val updated  = P.replace(rlm, changed)
    val reverted = P.replace(updated, original)

    assertEquals(P.read(updated), changed)
    assertEquals(P.read(reverted), original)
    assertEquals(P.inspect(updated).map(_.metadata), metadata)
    assertEquals(P.inspect(reverted).map(_.metadata), metadata)
  }
