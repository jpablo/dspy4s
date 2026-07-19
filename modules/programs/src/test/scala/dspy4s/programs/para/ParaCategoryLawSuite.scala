package dspy4s.programs.para

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.CodeInterpreter
import dspy4s.core.contracts.CodeResult
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldRole
import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.Monoid
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.ChainOfThought
import dspy4s.programs.CodeAct
import dspy4s.programs.DynamicPredict
import dspy4s.programs.Predictor
import dspy4s.programs.ReAct
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import dspy4s.typed.Shape
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger

// Top-level fixtures (Schema derivation requires top-level types): codec-equipped objects for the id laws.
final case class Boxed(n: Int) derives Schema
final case class Wrapped(s: String) derives Schema

/** Executes the `@Law` statements of the Para prototype's structures ([[Category]] / [[ParaCategory]] over [[Program]],
  * the [[paramsDeloop]] delooping, [[ReadFunctor]]), each under the observation honest for it: structural `==` for
  * parameter vectors and delooping morphisms, observational equality (typed output / params / coherent decode /
  * lifecycle) for `Program` morphisms. Final `Prediction.raw` is deliberately outside that equality and has an explicit
  * counterexample below. Also pins the two construction gates (no `Predictors`, no `Program`; no `RecordCodec`, no
  * `id`), decoder threading, and the copy NON-law (`fanout` shares its input; copying is not natural for effectful
  * morphisms).
  */
class ParaCategoryLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A typed program stub: maps the input via `f` and exposes `predict` as its single learnable leaf. */
  private final case class Step[I, O](tag: String, f: I => O, predict: DynamicPredict)
      extends Module[TypedCall[I], Prediction[O]]:
    override val moduleName: String                                            = s"step_$tag"
    override protected def callInputs(call: TypedCall[I]): DynamicValue.Record = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[I]): Boolean       = call.traceEnabled
    override protected def tracePayload(p: Prediction[O]): DynamicValue.Record = p.raw.values
    override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), DynamicPrediction(values = DynamicValues.record("tag" := tag))))

  private object Step:
    given stepPredictor[I, O]: Predictor[Step[I, O]] with
      def get(program: Step[I, O]): DynamicPredict                      = program.predict
      def set(program: Step[I, O], updated: DynamicPredict): Step[I, O] = program.copy(predict = updated)

  private object UnusedInterpreter extends CodeInterpreter:
    def execute(code: String): Either[DspyError, CodeResult] =
      throw new AssertionError(s"decoder test unexpectedly executed code: $code")
    def close(): Unit = ()

  /** A NON-product module: no `Predictor` leaf, no `Mirror`, hence no `Predictors` instance. Used to prove the
    * construction gate below.
    */
  private final class Opaque extends Module[TypedCall[Int], Prediction[Int]]:
    override val moduleName: String                                              = "opaque"
    override protected def callInputs(call: TypedCall[Int]): DynamicValue.Record = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[Int]): Boolean       = call.traceEnabled
    override protected def tracePayload(p: Prediction[Int]): DynamicValue.Record = p.raw.values
    override protected def forward(call: TypedCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      Right(Prediction(call.input, DynamicPrediction.empty))

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private val C = summon[ParaCategory[RecordCodec, Program]]

  private def step[I, O](tag: String, sig: String)(f: I => O): Step[I, O] = Step(tag, f, predict(sig))

  /** Stub decoder for tests that do not exercise record-based evaluation. */
  private def noCodec[I]: DynamicValue.Record => Either[DspyError, I] =
    _ => Left(ValidationError("test stub: no input codec"))

  /** Package a Step with the stub decoder (Step has no signature, so no ProgramInput instance applies). */
  private def pack[I, O](m: Step[I, O]): Program[I, O] = Program.unsafeOf(m, noCodec[I])

  private final case class ProgramObservation[O](
      output: Either[DspyError, O],
      starts: Vector[String],
      trace: Vector[String],
      history: Vector[String]
  )

  /** Observe the executable semantics retained by Category equality. Structural nodes must not perturb lifecycle. */
  private def observe[I, O](program: Program[I, O], input: I): ProgramObservation[O] =
    RuntimeEnvironment.resetForTests()
    val starts = Vector.newBuilder[String]
    val callback = new CallbackHandler:
      def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
        case start: ModuleStartEvent => starts += start.moduleName
        case _                       => ()
    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given RuntimeContext = RuntimeEnvironment.current
      val output            = program(TypedCall(input)).map(_.output)
      ProgramObservation(
        output,
        starts.result(),
        RuntimeEnvironment.current.trace.map(_.component),
        RuntimeEnvironment.current.history.map(_.component)
      )
    }

  /** Execute an IsEq under the documented Program observation. `raw` is tested separately as an explicit non-law. */
  private def assertObsEq[I, O](
      eq: IsEq[Program[I, O]],
      input: I,
      record: DynamicValue.Record
  ): Unit =
    assertEquals(eq.lhs.params, eq.rhs.params)
    assertEquals(eq.lhs.decodeInput(record), eq.rhs.decodeInput(record))
    assertEquals(observe(eq.lhs, input), observe(eq.rhs, input))

  /** Execute an IsEq whose carrier supports plain structural equality (parameter vectors). */
  private def assertIsEq[A](eq: IsEq[A]): Unit =
    assertEquals(eq.lhs, eq.rhs)

  // ── Category laws over Program, executed from the trait's @Law statements ───────────────────────────────────
  test("Category laws (identity left/right, associativity) hold observationally on Program") {
    val f = Program.of(step[Boxed, Wrapped]("f", "b -> s")(b => Wrapped(s"v${b.n}")))
    val boxed7 = DynamicValues.record("n" := 7)
    assertObsEq(C.identityLeft(f), Boxed(7), boxed7)
    assertObsEq(C.identityRight(f), Boxed(7), boxed7)

    val a = pack(step[Int, String]("a", "i -> s")(i => s"<$i>"))
    val g = pack(step[String, String]("g", "s -> t")(s => s + s))
    val h = pack(step[String, Int]("h", "t -> n")(s => s.length))
    assertObsEq(C.associativity(a, g, h), 3, DynamicValue.Record.empty)
    assertEquals(((a >>> g) >>> h)(TypedCall(3)).map(_.output), Right(6)) // "<3>" -> "<3><3>" -> length 6
  }

  test("right identity preserves the Category observation but not the final raw envelope") {
    val f      = Program.of(step[Boxed, Wrapped]("f", "b -> s")(b => Wrapped(s"v${b.n}")))
    val direct = f(TypedCall(Boxed(7)))
    val viaId  = (f >>> C.id[Wrapped])(TypedCall(Boxed(7)))

    assertEquals(viaId.map(_.output), direct.map(_.output))
    assertEquals(viaId.map(_.raw), Right(DynamicPrediction.empty))
    assertNotEquals(viaId.map(_.raw), direct.map(_.raw))
  }

  // ── Para laws, executed from the @Law statements ─────────────────────────────────────────────────────────
  test("Para laws: paramsId, paramsCompose, reparam round-trip and write-back") {
    val a  = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = pack(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    assertIsEq(C.paramsId[Boxed])
    assertIsEq(C.paramsCompose(a, b))
    assertIsEq(C.reparamRoundTrip(ab))
    val fresh = Vector(predict("i -> s2"), predict("s -> n2"))
    assertIsEq(C.reparamWriteBack(ab, fresh))
    // Behavior riders: reparameterization changes parameters, never the shape's computation.
    assertEquals(ab.reparam(ab.params)(TypedCall(5)).map(_.output), ab(TypedCall(5)).map(_.output))
    assertEquals(ab.reparam(fresh)(TypedCall(5)).map(_.output), Right(2))
  }

  // ── fanout: behavior, its params law, and the copy NON-law ───────────────────────────────────────────────
  test("fanout runs both legs on the same input and satisfies paramsFanout") {
    val f = pack(step[Int, String]("f", "i -> s")(i => s"v$i"))
    val g = pack(step[Int, Int]("g", "i -> n")(i => i + 1))
    assertEquals(C.fanout(f, g)(TypedCall(4)).map(_.output), Right(("v4", 5)))
    assertIsEq(C.paramsFanout(f, g))
    assertEquals(C.parallel(f, g)(TypedCall(4)), C.fanout(f, g)(TypedCall(4)))
    assertIsEq(C.paramsParallel(f, g))
  }

  test("copy is NOT natural: h >>> fanout(f, g) shares h; fanout(h >>> f, h >>> g) re-runs it") {
    val runs = AtomicInteger(0)
    val h = pack(step[Int, Int]("h", "i -> j") { i =>
      val _ = runs.incrementAndGet(); i * 10
    })
    val f = pack(step[Int, String]("f", "i -> s")(i => s"v$i"))
    val g = pack(step[Int, Int]("g", "i -> n")(i => i + 1))

    val shared = h >>> C.fanout(f, g)
    val copied = C.fanout(h >>> f, h >>> g)

    runs.set(0)
    val sharedOut = shared(TypedCall(3)).map(_.output)
    assertEquals(runs.get(), 1) // h ran once (the whole point of sharing)
    runs.set(0)
    val copiedOut = copied(TypedCall(3)).map(_.output)
    assertEquals(runs.get(), 2) // h ran twice

    // With a DETERMINISTIC h the outputs coincide; with an effectful (LLM) h they need not — which is why
    // Fan-out naturality is a NON-law for ordered effectful execution, not an oversight.
    assertEquals(sharedOut, copiedOut)
    // And the optimizer sees the difference structurally: h's parameters appear once vs twice.
    assertEquals(shared.params.size, 3)
    assertEquals(copied.params.size, 4)

  }

  // ── The parameter monoid, and its delooping as a lawful Category instance (checked with real ==) ─────────
  test("the parameter monoid Monoid[Vector[DynamicPredict]] satisfies the monoid laws") {
    val M  = Monoid[Vector[DynamicPredict]]
    val v1 = Vector(predict("a -> b"))
    val v2 = Vector(predict("b -> c"))
    val v3 = Vector(predict("c -> d"))
    assertIsEq(M.associativity(v1, v2, v3))
    assertIsEq(M.identityLeft(v1))
    assertIsEq(M.identityRight(v1))
  }

  test("paramsDeloop is that monoid delooped: Category laws hold, and id delegates to the monoid's empty") {
    val M  = Monoid[Vector[DynamicPredict]]
    val v1 = Vector(predict("a -> b"))
    val v2 = Vector(predict("b -> c"))
    val v3 = Vector(predict("c -> d"))
    assertIsEq(paramsDeloop.identityLeft[Unit, Unit](v1))
    assertIsEq(paramsDeloop.identityRight[Unit, Unit](v1))
    assertIsEq(paramsDeloop.associativity[Unit, Unit, Unit, Unit](v1, v2, v3))
    // The delooping delegates to the monoid: the category's identity IS the monoid's empty element.
    assertEquals(paramsDeloop.id[Unit], M.empty)
  }

  // ── ReadFunctor: params as a functor value; its laws executed ────────────────────────────────────────────
  test("ReadFunctor preserves identities and composition (params is a functor into the delooping)") {
    val a = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b = pack(step[String, Int]("b", "s -> n")(s => s.length))
    assertIsEq(ReadFunctor.identities[Boxed])
    assertIsEq(ReadFunctor.composition(a, b))
  }

  // ── The packaged evaluation capability: decoder threading through composition ───────────────────────────
  test(">>> threads the FIRST leg's input decoder (the composite's input is the first leg's input)") {
    val dec7: DynamicValue.Record => Either[DspyError, Int] = _ => Right(7)
    val a = Program.unsafeOf(step[Int, String]("a", "i -> s")(i => s"v$i"), dec7)
    val b = pack(step[String, Int]("b", "s -> n")(s => s.length)) // b's decoder is the failing stub
    assertEquals((a >>> b).decodeInput(DynamicValue.Record.empty), Right(7))
    // reparam preserves the decoder too.
    assertEquals((a >>> b).reparam((a >>> b).params).decodeInput(DynamicValue.Record.empty), Right(7))
  }

  test("id's decoder IS the object codec; the left unit holds on evaluation under coherent packaging") {
    // With codec-equipped objects (RecordCodec, the CategoryTC P[_] slot) id synthesizes its decoder from
    // the object's codec, and coherent packaging (p packaged via the same codec, through
    // ProgramInput.fromRecordCodec) gives the left unit on the evaluation observation too.
    val boxedRecord = DynamicValues.record("n" := 5)
    val p = Program.of(step[Boxed, Wrapped]("p", "b -> s")(b => Wrapped(s"v${b.n}"))) // packaged via the codec
    assertEquals(C.id[Boxed].decodeInput(boxedRecord), Right(Boxed(5)))
    assertEquals((C.id[Boxed] >>> p).decodeInput(boxedRecord), p.decodeInput(boxedRecord))
    assertEquals((C.id[Boxed] >>> p).decodeInput(boxedRecord), Right(Boxed(5)))
    assertEquals((p >>> C.id[Wrapped]).decodeInput(boxedRecord), Right(Boxed(5)))
  }

  test("unsafeOf makes decoder incoherence explicit and keeps it outside the Category claim") {
    val boxedRecord = DynamicValues.record("n" := 5)
    val p = Program.unsafeOf(
      step[Boxed, Wrapped]("p", "b -> s")(b => Wrapped(s"v${b.n}")),
      _ => Right(Boxed(99))
    )

    assertEquals(p.decodeInput(boxedRecord), Right(Boxed(99)))
    assertEquals((C.id[Boxed] >>> p).decodeInput(boxedRecord), Right(Boxed(5)))
  }

  test("signature-backed composite modules supply ProgramInput without a RecordCodec fallback") {
    val layout = SignatureLayout.parse("question -> s").toOption.get
    val signature = Signature[DynamicValue.Record, Wrapped](
      name = "RecordInput",
      layout = layout,
      inputShape = Shape.MapShape(layout.inputFields),
      outputShape = Shape.derivedWithRole[Wrapped](FieldRole.Output)
    )
    val input = DynamicValues.record("question" := "hello")

    val chain = Program.of(ChainOfThought(signature))
    val react = Program.of(ReAct(signature, tools = Vector.empty))
    val code  = Program.of(CodeAct(signature, UnusedInterpreter))

    assertEquals(chain.decodeInput(input), Right(input))
    assertEquals(react.decodeInput(input), Right(input))
    assertEquals(code.decodeInput(input), Right(input))
    assertEquals(chain.params.size, 1)
    assertEquals(react.params.size, 2)
    assertEquals(code.params.size, 2)

    val errors = compileErrors("summon[RecordCodec[DynamicValue.Record]]")
    assert(errors.nonEmpty, "test requires an input type without the generic RecordCodec fallback")

    // A direct signature-backed instance also wins unambiguously when the generic RecordCodec fallback is available.
    val productChain = Program.of(ChainOfThought(Signature.derived[Boxed, Wrapped]("ProductInput")))
    assertEquals(productChain.decodeInput(DynamicValues.record("n" := 3)), Right(Boxed(3)))
  }

  test("id at a non-codec object does not compile (the structure is only a semicategory there)") {
    // Int is not a Product, so RecordCodec.fromSchema does not apply: id[Int] has no evaluation evidence to
    // synthesize and is rejected at compile time. Morphisms touching Int still compose (their packaged
    // decoders travel with them); only the unit is gated.
    val errors = compileErrors("C.id[Int]")
    assert(errors.nonEmpty, "expected id at a non-codec object to fail compilation")
    assert(errors.contains("RecordCodec"), s"expected a missing-RecordCodec error, got:\n$errors")
  }

  // ── The construction gate: no Predictors evidence, no Program ───────────────────────────────────────────────
  test("packaging a program without Predictors evidence does not compile") {
    // Opaque is a plain (non-Product) Module: no Predictor leaf, no Mirror, so Predictors[Opaque] cannot be
    // summoned and Program.of is a compile error. In the ambient Module world the same program runs fine but is
    // silently un-addressable; in the packaged category it cannot exist.
    val opaque = new Opaque
    assertEquals(opaque.apply(TypedCall(3)).map(_.output), Right(3)) // valid ambient program
    val errors = compileErrors("Program.of(new Opaque)")
    assert(errors.nonEmpty, "expected Program.of(new Opaque) to fail compilation")
    assert(errors.contains("Predictors"), s"expected a missing-Predictors error, got:\n$errors")
  }
