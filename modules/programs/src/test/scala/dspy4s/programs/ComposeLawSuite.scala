package dspy4s.programs

import dspy4s.programs.predictors.*
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

/** Laws and operational semantics for `id` / `>>>` / ordered fan-out. `>>>` threads the typed value and accumulates the
  * raw execution envelope with its lawful sequential operation, so Category identity and associativity hold on the full
  * `Prediction`. Structural combinators are lifecycle-transparent, making equality stable under association even when a
  * leaf observes the live trace.
  */
class ComposeLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub: maps the input value via `f`, tags its raw record so `parallel`'s merge is observable, and
    * exposes `predict` as its single learnable leaf (for the addressability laws).
    */
  private final case class Step[I, O](tag: String, f: I => O, predict: DynamicPredict)
      extends Module[I, O]:
    override val moduleName: String = s"step_$tag"
    override protected val lifecycle: ModuleLifecycle[I, O] =
      ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), RawPrediction(values = DynamicValues.record("tag" := tag))))

  /** A leaf that makes lifecycle structure semantically observable by returning the trace size at its forward boundary.
    * Associativity requires both syntax trees to have run the same leaves before reaching it.
    */
  private final case class TraceSize(predict: DynamicPredict) extends Module[String, Int]:
    override val moduleName: String = "trace_size"
    override protected val lifecycle: ModuleLifecycle[String, Int] =
      ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[String])(using
        RuntimeContext
    ): Either[DspyError, Prediction[Int]] =
      Right(Prediction(RuntimeEnvironment.current.trace.size, RawPrediction.empty))

  private object Step:
    given stepPredictor[I, O]: Predictor[Step[I, O]] with
      def get(program: Step[I, O]): PredictorState = program.predict.predictorState
      def metadata(program: Step[I, O]): PredictorMetadata = program.predict.predictorView.metadata
      def set(program: Step[I, O], updated: PredictorState): Step[I, O] =
        program.copy(predict = program.predict.withPredictorState(updated))

  private object TraceSize:
    given traceSizePredictor: Predictor[TraceSize] with
      def get(program: TraceSize): PredictorState = program.predict.predictorState
      def metadata(program: TraceSize): PredictorMetadata = program.predict.predictorView.metadata
      def set(program: TraceSize, updated: PredictorState): TraceSize =
        program.copy(predict = program.predict.withPredictorState(updated))

  private def step[I, O](tag: String, sig: String)(f: I => O): Step[I, O] = Step(tag, f, predict(sig))

  private def identified[P](program: P)(using predictors: Predictors[P]): Vector[IdentifiedPredictor] =
    predictors.readIdentified(program)

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  // ── Type inference smoke + value threading ───────────────────────────────────────────────────────────────
  test(">>> infers I/X/O and threads the output value into the next program") {
    val a  = step[Int, String]("a", "i -> s")(i => s"v$i")
    val b  = step[String, Int]("b", "s -> n")(s => s.length)
    val ab = a >>> b // expected AndThen[Int, String, Int, Step[Int,String], Step[String,Int]]
    assertEquals(ab.apply(ProgramCall(5)).map(_.output), Right(2)) // "v5".length
    assertEquals(a.andThen(b).apply(ProgramCall(5)), ab.apply(ProgramCall(5)))
  }

  // ── Category: identity ───────────────────────────────────────────────────────────────────────────────────
  test("id >>> p = p (left unit, full prediction)") {
    val p      = step[Int, String]("p", "i -> s")(i => s"v$i")
    val viaId  = (Compose.id[Int] >>> p).apply(ProgramCall(7))
    val direct = p.apply(ProgramCall(7))
    assertEquals(viaId.map(_.output), direct.map(_.output))
    // The left unit contributes nothing: even the raw envelope matches p's.
    assertEquals(viaId.map(_.raw.values), direct.map(_.raw.values))
  }

  test("p >>> id = p on the complete prediction and lifecycle") {
    val p = step[Int, String]("p", "i -> s")(i => s"v$i")

    def run(program: Module[Int, String]) =
      RuntimeEnvironment.resetForTests()
      val result = program.apply(ProgramCall(7))
      (result, RuntimeEnvironment.current.trace.map(_.component))

    val viaId  = run(p >>> Compose.id[String])
    val direct = run(p)
    assertEquals(viaId._1, direct._1)
    assertEquals(viaId._2, direct._2)
  }

  // ── Category: associativity ──────────────────────────────────────────────────────────────────────────────
  test("(a >>> b) >>> c = a >>> (b >>> c) on the complete prediction") {
    val a     = step[Int, String]("a", "i -> s")(i => s"<$i>")
    val b     = step[String, String]("b", "s -> t")(s => s + s)
    val c     = step[String, Int]("c", "t -> n")(s => s.length)
    val left  = ((a >>> b) >>> c).apply(ProgramCall(3))
    val right = (a >>> (b >>> c)).apply(ProgramCall(3))
    assertEquals(left, right)
    assertEquals(left.map(_.output), Right(6)) // "<3>" -> "<3><3>" -> length 6
  }

  test("associativity remains observable when the final leaf reads the live trace") {
    val a = step[Int, String]("a", "i -> s")(_.toString)
    val b = step[String, String]("b", "s -> t")(identity)
    val c = TraceSize(predict("t -> n"))

    def run(program: Module[Int, Int]): (Either[DspyError, Int], Vector[String]) =
      RuntimeEnvironment.resetForTests()
      val output = program.apply(ProgramCall(1)).map(_.output)
      output -> RuntimeEnvironment.current.trace.map(_.component)

    val left  = run((a >>> b) >>> c)
    val right = run(a >>> (b >>> c))

    assertEquals(left, right)
    assertEquals(left, Right(2) -> Vector("step_a", "step_b", "trace_size"))
  }

  test("predictor identity is invariant under composition reassociation; structural names are only labels") {
    val a = step[Int, String]("a", "i -> s")(_.toString)
    val b = step[String, String]("b", "s -> t")(identity)
    val c = step[String, Int]("c", "t -> n")(_.length)

    val left  = (a >>> b) >>> c
    val right = a >>> (b >>> c)

    val leftEntries  = identified(left)
    val rightEntries = identified(right)

    assertEquals(leftEntries.map(_.id), Vector(PredictorId(0), PredictorId(1), PredictorId(2)))
    assertEquals(rightEntries.map(_.id), leftEntries.map(_.id))
    assertEquals(leftEntries.map(_.view), rightEntries.map(_.view))
    assertNotEquals(leftEntries.map(_.displayName), rightEntries.map(_.displayName))
  }

  test("structural composition emits callbacks only for semantic leaves") {
    val starts = ArrayBuffer.empty[String]
    val handler = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
        case start: ModuleStartEvent => starts += start.moduleName
        case _                       => ()

    val a = step[Int, String]("a", "i -> s")(_.toString)
    val b = step[String, String]("b", "s -> t")(identity)
    val c = step[String, Int]("c", "t -> n")(_.length)

    RuntimeEnvironment.withCallbacks(Vector(handler)) {
      val _ = ((a >>> b) >>> c).apply(ProgramCall(1))
    }

    assertEquals(starts.toVector, Vector("step_a", "step_b", "step_c"))
  }

  // ── Ordered fan-out ─────────────────────────────────────────────────────────────────────────────────────
  test("parallel(a, b) runs both on the same input and tuples the outputs") {
    val a      = step[Int, String]("a", "i -> s")(i => s"s$i")
    val b      = step[Int, Int]("b", "i -> n")(i => i * 10)
    val result = Compose.parallel(a, b).apply(ProgramCall(4))
    assertEquals(result.map(_.output), Right(("s4", 40)))
    // raw merges both sub-predictions' value records (second wins on key collision; here both write "tag").
    assertEquals(
      result.map(_.raw.values).map(DynamicValues.recordGet(_, "tag").map(DynamicValues.renderText)),
      Right(Some("b"))
    )
  }

  test("parallel associates up to tuple reassociation") {
    val a           = step[Int, String]("a", "i -> s")(i => s"a$i")
    val b           = step[Int, String]("b", "i -> s")(i => s"b$i")
    val c           = step[Int, String]("c", "i -> s")(i => s"c$i")
    val leftNested  = Compose.parallel(Compose.parallel(a, b), c).apply(ProgramCall(1)).map(_.output)
    val rightNested = Compose.parallel(a, Compose.parallel(b, c)).apply(ProgramCall(1)).map(_.output)
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

    assertEquals(P.read(ab), Vector(a.predict.predictorState, b.predict.predictorState))
    assertEquals(P.readNamed(ab).map(_._1), Vector("first", "second"))
    // replace(p, read(p)) == p  (checked on the read projection — the meaningful invariant)
    assertEquals(P.read(P.replace(ab, P.read(ab))), P.read(ab))
    // a genuine replace swaps the addressed predict
    val newSecond = b.predict.predictorState.copy(instructions = Some("Use the updated second step."))
    val updates   = Vector(a.predict.predictorState, newSecond)
    assertEquals(P.read(P.replace(ab, updates)), updates)
  }

  test("parallel read = read(a) ++ read(b)") {
    val a   = step[Int, String]("a", "i -> s")(i => s"v$i")
    val b   = step[Int, Int]("b", "i -> n")(i => i)
    val par = Compose.parallel(a, b)
    val P   = summon[Predictors[Both[Int, String, Int, Step[Int, String], Step[Int, Int]]]]
    assertEquals(P.read(par), Vector(a.predict.predictorState, b.predict.predictorState))
    assertEquals(P.readNamed(par).map(_._1), Vector("first", "second"))
  }

  // ── Ordered tensor and structural copy ──────────────────────────────────────────────────────────────────
  test("tensor(a, b) runs INDEPENDENT programs on independent inputs and pairs them") {
    val a      = step[Int, String]("a", "i -> s")(i => s"s$i")
    val b      = step[Boolean, Int]("b", "p -> n")(p => if p then 1 else 0)
    val result = Compose.tensor(a, b).apply(ProgramCall((4, true)))
    assertEquals(result.map(_.output), Right(("s4", 1)))
  }

  test("copy(Δ) duplicates its input") {
    assertEquals(Compose.copy[Int].apply(ProgramCall(7)).map(_.output), Right((7, 7)))
  }

  test("parallel(a, b) = copy >>> tensor(a, b)  (fan-out is copy-then-tensor)") {
    val a             = step[Int, String]("a", "i -> s")(i => s"s$i")
    val b             = step[Int, Int]("b", "i -> n")(i => i * 10)
    val viaFanout     = Compose.parallel(a, b).apply(ProgramCall(4)).map(_.output)
    val viaCopyTensor = (Compose.copy[Int] >>> Compose.tensor(a, b)).apply(ProgramCall(4)).map(_.output)
    assertEquals(viaFanout, viaCopyTensor)
    assertEquals(viaFanout, Right(("s4", 40)))
  }

  test("tensor read = read(a) ++ read(b) (structural, same as parallel)") {
    val a  = step[Int, String]("a", "i -> s")(i => s"v$i")
    val b  = step[Boolean, Int]("b", "p -> n")(_ => 0)
    val tn = Compose.tensor(a, b)
    val P  = summon[Predictors[Tensor[Int, Boolean, String, Int, Step[Int, String], Step[Boolean, Int]]]]
    assertEquals(P.read(tn), Vector(a.predict.predictorState, b.predict.predictorState))
    assertEquals(P.readNamed(tn).map(_._1), Vector("first", "second"))
  }

  // ── Determinism classifier: copy commutes with a deterministic morphism ─────────────────────────────────
  // h >>> copy  =  copy >>> tensor(h, h)   holds because our Step is deterministic (a pure function). For an
  // effect-observing h the two sides run h once vs twice and diverge — the copy NON-naturality is already pinned
  // in ParaCategoryLawSuite. This is a useful classifier, not a law of unrestricted executable programs.
  test("copy is natural for a deterministic morphism: h >>> copy = copy >>> tensor(h, h)") {
    val h   = step[Int, String]("h", "i -> s")(i => s"v$i")
    val lhs = (h >>> Compose.copy[String]).apply(ProgramCall(5)).map(_.output)
    val rhs = (Compose.copy[Int] >>> Compose.tensor(h, h)).apply(ProgramCall(5)).map(_.output)
    assertEquals(lhs, rhs)
    assertEquals(lhs, Right(("v5", "v5")))
  }
