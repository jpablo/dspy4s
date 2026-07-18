package dspy4s.programs.para

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Predictor
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

// Top-level fixtures (Schema derivation requires top-level types): codec-equipped objects for the id laws.
final case class Boxed(n: Int) derives Schema
final case class Wrapped(s: String) derives Schema

/** Laws for the Para-shaped category prototype ([[ParaCat]] / [[Prog]]): the Para laws (parameter-free
  * identity, composition concatenates parameters, reparameterization round-trip and write-back), the Category
  * laws on the packaged carrier (id at CODEC-EQUIPPED objects, per the `RecordCodec` object constraint), and
  * the two construction gates (no `Predictors` evidence, no `Prog`; no `RecordCodec`, no `id`). See the
  * scaladoc on [[ParaCat]] for the correspondence this pins. */
class ParaCatLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub: maps the input via `f` and exposes `predict` as its single learnable leaf. */
  private final case class Step[I, O](tag: String, f: I => O, predict: DynamicPredict)
      extends Module[TypedCall[I], Prediction[O]]:
    override val moduleName: String = s"step_$tag"
    override protected def callInputs(call: TypedCall[I]): DynamicValue.Record       = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[I]): Boolean             = call.traceEnabled
    override protected def tracePayload(p: Prediction[O]): DynamicValue.Record       = p.raw.values
    override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), DynamicPrediction(values = DynamicValues.record("tag" := tag))))

  private object Step:
    given stepPredictor[I, O]: Predictor[Step[I, O]] with
      def get(program: Step[I, O]): DynamicPredict                      = program.predict
      def set(program: Step[I, O], updated: DynamicPredict): Step[I, O] = program.copy(predict = updated)

  /** A NON-product module: no `Predictor` leaf, no `Mirror`, hence no `Predictors` instance. Used to prove
    * the construction gate below. */
  private final class Opaque extends Module[TypedCall[Int], Prediction[Int]]:
    override val moduleName: String = "opaque"
    override protected def callInputs(call: TypedCall[Int]): DynamicValue.Record       = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[Int]): Boolean             = call.traceEnabled
    override protected def tracePayload(p: Prediction[Int]): DynamicValue.Record       = p.raw.values
    override protected def forward(call: TypedCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      Right(Prediction(call.input, DynamicPrediction.empty))

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private val C = summon[ParaCat[RecordCodec, Prog]]

  private def step[I, O](tag: String, sig: String)(f: I => O): Step[I, O] = Step(tag, f, predict(sig))

  /** Stub decoder for tests that do not exercise record-based evaluation. */
  private def noCodec[I]: DynamicValue.Record => Either[DspyError, I] =
    _ => Left(ValidationError("test stub: no input codec"))

  /** Package a Step with the stub decoder (Step has no signature, so no ProgInput instance applies). */
  private def pack[I, O](m: Step[I, O]): Prog[I, O] = Prog.of(m, noCodec[I])

  // ── Para law: the identity is parameter-free ─────────────────────────────────────────────────────────────
  test("params(id) = empty") {
    assertEquals(C.id[Boxed].params, Vector.empty[DynamicPredict])
  }

  // ── Para law: composition concatenates parameters ────────────────────────────────────────────────────────
  test("params(f >>> g) = params(f) ++ params(g)") {
    val a  = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = pack(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    assertEquals(ab.params, a.params ++ b.params)
  }

  // ── Para law: reparameterization round-trip and write-back ───────────────────────────────────────────────
  test("reparam(f, params(f)) preserves params and behavior") {
    val a  = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = pack(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    val rt = ab.reparam(ab.params)
    assertEquals(rt.params, ab.params)
    assertEquals(rt(TypedCall(5)).map(_.output), ab(TypedCall(5)).map(_.output))
  }

  test("params(reparam(f, ps)) = ps (write-back, addressed by position)") {
    val a  = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = pack(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    val fresh = Vector(predict("i -> s2"), predict("s -> n2"))
    assertEquals(ab.reparam(fresh).params, fresh)
    // The shape is untouched: the reparameterized composite still computes the same function.
    assertEquals(ab.reparam(fresh)(TypedCall(5)).map(_.output), Right(2))
  }

  // ── Category laws on the packaged carrier (id needs codec-equipped endpoints) ───────────────────────────
  test("id >>> f = f = f >>> id on the threaded output value and on params") {
    val f = pack(step[Boxed, Wrapped]("f", "b -> s")(b => Wrapped(s"v${b.n}")))
    val left  = C.id[Boxed] >>> f
    val right = f >>> C.id[Wrapped]
    assertEquals(left(TypedCall(Boxed(7))).map(_.output), f(TypedCall(Boxed(7))).map(_.output))
    assertEquals(right(TypedCall(Boxed(7))).map(_.output), f(TypedCall(Boxed(7))).map(_.output))
    assertEquals(left.params, f.params)
    assertEquals(right.params, f.params)
  }

  test("(f >>> g) >>> h = f >>> (g >>> h) on the output value and on params") {
    val f = pack(step[Int, String]("f", "i -> s")(i => s"<$i>"))
    val g = pack(step[String, String]("g", "s -> t")(s => s + s))
    val h = pack(step[String, Int]("h", "t -> n")(s => s.length))
    val l = (f >>> g) >>> h
    val r = f >>> (g >>> h)
    assertEquals(l(TypedCall(3)).map(_.output), r(TypedCall(3)).map(_.output))
    assertEquals(l.params, r.params)
    assertEquals(l(TypedCall(3)).map(_.output), Right(6))
  }

  // ── The packaged evaluation capability: decoder threading through composition ───────────────────────────
  test(">>> threads the FIRST leg's input decoder (the composite's input is the first leg's input)") {
    val dec7: DynamicValue.Record => Either[DspyError, Int] = _ => Right(7)
    val a = Prog.of(step[Int, String]("a", "i -> s")(i => s"v$i"), dec7)
    val b = pack(step[String, Int]("b", "s -> n")(s => s.length)) // b's decoder is the failing stub
    assertEquals((a >>> b).decodeInput(DynamicValue.Record.empty), Right(7))
    // reparam preserves the decoder too.
    assertEquals((a >>> b).reparam((a >>> b).params).decodeInput(DynamicValue.Record.empty), Right(7))
  }

  test("id's decoder IS the object codec; the left unit holds on evaluation under coherent packaging") {
    // Previously the pinned WRINKLE: id carried a failing decoder, so id >>> p degraded on the evaluation
    // observation. With codec-equipped objects (RecordCodec, the CategoryTC P[_] slot) id synthesizes its
    // decoder from the object's codec, and coherent packaging (p packaged via the same codec, through
    // ProgInput.fromRecordCodec) restores the left unit on this observation too.
    val boxedRecord = DynamicValues.record("n" := 5)
    val p = Prog.of(step[Boxed, Wrapped]("p", "b -> s")(b => Wrapped(s"v${b.n}"))) // packaged via the codec
    assertEquals(C.id[Boxed].decodeInput(boxedRecord), Right(Boxed(5)))
    assertEquals((C.id[Boxed] >>> p).decodeInput(boxedRecord), p.decodeInput(boxedRecord))
    assertEquals((C.id[Boxed] >>> p).decodeInput(boxedRecord), Right(Boxed(5)))
    assertEquals((p >>> C.id[Wrapped]).decodeInput(boxedRecord), Right(Boxed(5)))
  }

  test("id at a non-codec object does not compile (the structure is only a semicategory there)") {
    // Int is not a Product, so RecordCodec.fromSchema does not apply: id[Int] has no evaluation evidence to
    // synthesize and is rejected at compile time. Morphisms touching Int still compose (their packaged
    // decoders travel with them); only the unit is gated.
    val errors = compileErrors("C.id[Int]")
    assert(errors.nonEmpty, "expected id at a non-codec object to fail compilation")
    assert(errors.contains("RecordCodec"), s"expected a missing-RecordCodec error, got:\n$errors")
  }

  // ── The construction gate: no Predictors evidence, no Prog ───────────────────────────────────────────────
  test("packaging a program without Predictors evidence does not compile") {
    // Opaque is a plain (non-Product) Module: no Predictor leaf, no Mirror, so Predictors[Opaque] cannot be
    // summoned and Prog.of is a compile error. In the ambient Module world the same program runs fine but is
    // silently un-addressable; in the packaged category it cannot exist.
    val opaque = new Opaque
    assertEquals(opaque.apply(TypedCall(3)).map(_.output), Right(3)) // valid ambient program
    val errors = compileErrors("Prog.of(new Opaque)")
    assert(errors.nonEmpty, "expected Prog.of(new Opaque) to fail compilation")
    assert(errors.contains("Predictors"), s"expected a missing-Predictors error, got:\n$errors")
  }
