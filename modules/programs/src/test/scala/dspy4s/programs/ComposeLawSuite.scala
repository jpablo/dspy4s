package dspy4s.programs

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** Laws for the program-composition combinators `id` / `>>>` / `parallel` (Algebra 2's Category + Applicative;
  * `docs/refactor/algebra-2-program-composition.md`). Carrier note: `>>>` threads the plain output VALUE, so the
  * Category laws are stated on `.output` (the threaded denotation), not on the full `Prediction` envelope —
  * `p >>> id` keeps `p.output` but resets `.raw` (id's empty envelope), the documented value-vs-envelope split. */
class ComposeLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub: maps the input value via `f`, tags its raw record so `parallel`'s merge is
    * observable, and exposes `predict` as its single learnable leaf (for the addressability laws). */
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
      def get(program: Step[I, O]): DynamicPredict                       = program.predict
      def set(program: Step[I, O], updated: DynamicPredict): Step[I, O]  = program.copy(predict = updated)

  private def step[I, O](tag: String, sig: String)(f: I => O): Step[I, O] = Step(tag, f, predict(sig))

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  // ── Type inference smoke + value threading ───────────────────────────────────────────────────────────────
  test(">>> infers I/X/O and threads the output value into the next program") {
    val a  = step[Int, String]("a", "i -> s")(i => s"v$i")
    val b  = step[String, Int]("b", "s -> n")(s => s.length)
    val ab = a >>> b // expected AndThen[Int, String, Int, Step[Int,String], Step[String,Int]]
    assertEquals(ab.apply(TypedCall(5)).map(_.output), Right(2)) // "v5".length
  }

  // ── Category: identity ───────────────────────────────────────────────────────────────────────────────────
  test("id >>> p = p (left unit, full prediction)") {
    val p = step[Int, String]("p", "i -> s")(i => s"v$i")
    val viaId = (Compose.id[Int] >>> p).apply(TypedCall(7))
    val direct = p.apply(TypedCall(7))
    assertEquals(viaId.map(_.output), direct.map(_.output))
    // The left unit contributes nothing: even the raw envelope matches p's.
    assertEquals(viaId.map(_.raw.values), direct.map(_.raw.values))
  }

  test("p >>> id = p on the threaded output value (right unit; raw is id's empty envelope)") {
    val p = step[Int, String]("p", "i -> s")(i => s"v$i")
    val viaId = (p >>> Compose.id[String]).apply(TypedCall(7))
    assertEquals(viaId.map(_.output), p.apply(TypedCall(7)).map(_.output))
  }

  // ── Category: associativity ──────────────────────────────────────────────────────────────────────────────
  test("(a >>> b) >>> c = a >>> (b >>> c) on the output value") {
    val a = step[Int, String]("a", "i -> s")(i => s"<$i>")
    val b = step[String, String]("b", "s -> t")(s => s + s)
    val c = step[String, Int]("c", "t -> n")(s => s.length)
    val left  = ((a >>> b) >>> c).apply(TypedCall(3)).map(_.output)
    val right = (a >>> (b >>> c)).apply(TypedCall(3)).map(_.output)
    assertEquals(left, right)
    assertEquals(left, Right(6)) // "<3>" -> "<3><3>" -> length 6
  }

  // ── Applicative: parallel ────────────────────────────────────────────────────────────────────────────────
  test("parallel(a, b) runs both on the same input and tuples the outputs") {
    val a = step[Int, String]("a", "i -> s")(i => s"s$i")
    val b = step[Int, Int]("b", "i -> n")(i => i * 10)
    val result = Compose.parallel(a, b).apply(TypedCall(4))
    assertEquals(result.map(_.output), Right(("s4", 40)))
    // raw merges both sub-predictions' value records (second wins on key collision; here both write "tag").
    assertEquals(result.map(_.raw.values).map(DynamicValues.recordGet(_, "tag").map(DynamicValues.renderText)), Right(Some("b")))
  }

  test("parallel associates up to tuple reassociation") {
    val a = step[Int, String]("a", "i -> s")(i => s"a$i")
    val b = step[Int, String]("b", "i -> s")(i => s"b$i")
    val c = step[Int, String]("c", "i -> s")(i => s"c$i")
    val leftNested  = Compose.parallel(Compose.parallel(a, b), c).apply(TypedCall(1)).map(_.output)
    val rightNested = Compose.parallel(a, Compose.parallel(b, c)).apply(TypedCall(1)).map(_.output)
    // ((x, y), z)  reassociates to  (x, (y, z))
    val reassociated = leftNested.map { case ((x, y), z) => (x, (y, z)) }
    assertEquals(reassociated, rightNested)
    assertEquals(rightNested, Right(("a1", ("b1", "c1"))))
  }

  // ── Optimizer-addressability (fork 4): read distributes; replace round-trips ──────────────────────────────
  test(">>> read = read(a) ++ read(b); names are field-pathed; replace round-trips") {
    val a  = step[Int, String]("a", "i -> s")(i => s"v$i")
    val b  = step[String, Int]("b", "s -> n")(s => s.length)
    val ab = a >>> b
    val P  = summon[Predictors[AndThen[Int, String, Int, Step[Int, String], Step[String, Int]]]]

    assertEquals(P.read(ab), Vector(a.predict, b.predict))
    assertEquals(P.readNamed(ab).map(_._1), Vector("first", "second"))
    // replace(p, read(p)) == p  (checked on the read projection — the meaningful invariant)
    assertEquals(P.read(P.replace(ab, P.read(ab))), P.read(ab))
    // a genuine replace swaps the addressed predict
    val newSecond = predict("s -> m")
    assertEquals(P.read(P.replace(ab, Vector(a.predict, newSecond))), Vector(a.predict, newSecond))
  }

  test("parallel read = read(a) ++ read(b)") {
    val a   = step[Int, String]("a", "i -> s")(i => s"v$i")
    val b   = step[Int, Int]("b", "i -> n")(i => i)
    val par = Compose.parallel(a, b)
    val P   = summon[Predictors[Both[Int, String, Int, Step[Int, String], Step[Int, Int]]]]
    assertEquals(P.read(par), Vector(a.predict, b.predict))
    assertEquals(P.readNamed(par).map(_._1), Vector("first", "second"))
  }
