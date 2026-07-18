package dspy4s.programs.para

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Predictor
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Laws for the Para-shaped category prototype ([[ParaCat]] / [[Prog]]): the Para laws (parameter-free
  * identity, composition concatenates parameters, reparameterization round-trip and write-back), the Category
  * laws on the packaged carrier, and the construction gate (no `Predictors` evidence, no `Prog`). See the
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

  private val C = summon[ParaCat[Prog]]

  private def step[I, O](tag: String, sig: String)(f: I => O): Step[I, O] = Step(tag, f, predict(sig))

  // ── Para law: the identity is parameter-free ─────────────────────────────────────────────────────────────
  test("params(id) = empty") {
    assertEquals(C.id[Int].params, Vector.empty[DynamicPredict])
  }

  // ── Para law: composition concatenates parameters ────────────────────────────────────────────────────────
  test("params(f >>> g) = params(f) ++ params(g)") {
    val a  = Prog.of(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = Prog.of(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    assertEquals(ab.params, a.params ++ b.params)
  }

  // ── Para law: reparameterization round-trip and write-back ───────────────────────────────────────────────
  test("reparam(f, params(f)) preserves params and behavior") {
    val a  = Prog.of(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = Prog.of(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    val rt = ab.reparam(ab.params)
    assertEquals(rt.params, ab.params)
    assertEquals(rt(TypedCall(5)).map(_.output), ab(TypedCall(5)).map(_.output))
  }

  test("params(reparam(f, ps)) = ps (write-back, addressed by position)") {
    val a  = Prog.of(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = Prog.of(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    val fresh = Vector(predict("i -> s2"), predict("s -> n2"))
    assertEquals(ab.reparam(fresh).params, fresh)
    // The shape is untouched: the reparameterized composite still computes the same function.
    assertEquals(ab.reparam(fresh)(TypedCall(5)).map(_.output), Right(2))
  }

  // ── Category laws on the packaged carrier ────────────────────────────────────────────────────────────────
  test("id >>> f = f = f >>> id on the threaded output value and on params") {
    val f = Prog.of(step[Int, String]("f", "i -> s")(i => s"v$i"))
    val left  = C.id[Int] >>> f
    val right = f >>> C.id[String]
    assertEquals(left(TypedCall(7)).map(_.output), f(TypedCall(7)).map(_.output))
    assertEquals(right(TypedCall(7)).map(_.output), f(TypedCall(7)).map(_.output))
    assertEquals(left.params, f.params)
    assertEquals(right.params, f.params)
  }

  test("(f >>> g) >>> h = f >>> (g >>> h) on the output value and on params") {
    val f = Prog.of(step[Int, String]("f", "i -> s")(i => s"<$i>"))
    val g = Prog.of(step[String, String]("g", "s -> t")(s => s + s))
    val h = Prog.of(step[String, Int]("h", "t -> n")(s => s.length))
    val l = (f >>> g) >>> h
    val r = f >>> (g >>> h)
    assertEquals(l(TypedCall(3)).map(_.output), r(TypedCall(3)).map(_.output))
    assertEquals(l.params, r.params)
    assertEquals(l(TypedCall(3)).map(_.output), Right(6))
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
