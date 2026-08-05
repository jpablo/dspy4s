package dspy4s.programs.algebra

import dspy4s.programs.optimization.{optimizableParameters, optimizableView, withOptimizableParameters}

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.algebra.{AnyGrade, AnyObject, IsEq, Lens, Monoid, NatGradedCategory, OrderedFanout}
import dspy4s.core.collections.SizedVector
import dspy4s.core.collections.SizedVector.*
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.strategies.ChainOfThought
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.programs.DynamicSignature
import dspy4s.programs.optimization.OptimizableLeaf
import dspy4s.programs.optimization.OptimizableMetadata
import dspy4s.programs.optimization.OptimizableParameters
import dspy4s.programs.ProgramRunner
import dspy4s.programs.RecordCodec
import dspy4s.programs.RecordObject
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.contracts.Prediction
import dspy4s.signatures.Shape
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.DynamicValue
import zio.blocks.schema.Schema

import java.util.concurrent.atomic.AtomicInteger

// Top-level fixtures (Schema derivation requires top-level types): codec-equipped inputs for runner observations.
final case class Boxed(n: Int) derives Schema
final case class Wrapped(s: String) derives Schema
final case class NestedValue(n: Int) derives Schema
final case class NestedBox(value: NestedValue) derives Schema
final case class ArrayBox(values: Array[Int]) derives Schema

/** Executes the `@Law` statements of the graded program structures ([[NatGradedCategory]], [[GradedFunctor]], and
  * [[Parameterization]] over [[Program]], the [[paramsDeloop]] delooping, [[ReadFunctor]]), each under the observation
  * honest for it: structural `==` for parameter vectors and delooping morphisms, observational equality (complete
  * prediction / params / lifecycle) for `Program` morphisms. Separate checks pin canonical boundary decoding, the
  * construction gate (no `OptimizableStructure`, no `Program`), the separation between program construction and
  * record-boundary decoding, and the copy NON-law (`fanout` shares its input; copying is not natural for effectful
  * morphisms).
  */
class ProgramAlgebraLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def predict(sig: String): DynamicPredict =
    DynamicPredict(layout = SignatureLayout.parse(sig).toOption.get)

  /** A program stub: maps the input via `f` and exposes `predict` as its single learnable leaf. */
  private final case class Step[I, O](tag: String, f: I => O, predict: DynamicPredict)
      extends Module[I, O]:

    override val moduleName: String = s"step_$tag"

    override protected val lifecycle: ModuleLifecycle[I, O] = ModuleLifecycle.typedWithoutInputs

    override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), RawPrediction(values = DynamicValues.record("tag" := tag))))

  private object Step:
    given stepOptimizable[I, O]: OptimizableLeaf[Step[I, O]] with
      def get(program     : Step[I, O]): OptimizableParameters                 = program.predict.optimizableParameters
      def metadata(program: Step[I, O]): OptimizableMetadata                   = program.predict.optimizableView.metadata
      def set(program: Step[I, O], updated: OptimizableParameters): Step[I, O] =
        program.copy(predict = program.predict.withOptimizableParameters(updated))

  /** A NON-product module: no `OptimizableLeaf` leaf, no `Mirror`, hence no `OptimizableStructure` instance. Used to
    * prove the construction gate below.
    */
  private final class Opaque extends Module[Int, Int]:
    override val moduleName: String                                                                                  = "opaque"
    override protected val lifecycle: ModuleLifecycle[Int, Int]                                                      = ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[Int])(using RuntimeContext): Either[DspyError, Prediction[Int]] =
      Right(Prediction(call.input, RawPrediction.empty))

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private val C = summon[NatGradedCategory[AnyObject, Program]]
  private val U = C.forgetGrade
  private val P = summon[Parameterization[AnyObject, Program]]
  private val F = summon[OrderedFanout[Program]]

  // ── Bundle-tagged dynamic objects: fresh types minted per parse (DynamicSignature) ─────────────────────────
  // Suite-level so the freshness compile gate can reference them from compileErrors snippets.
  private val qaBundle: DynamicSignature = DynamicSignature.parse("question -> answer").toOption.get
  // Referenced only inside the freshness compile gate's compileErrors snippet, which the unused checker
  // cannot see.
  @annotation.unused
  private val qaBundleAgain: DynamicSignature = DynamicSignature.parse("question -> answer").toOption.get

  private val shiftedBoxSchema = Schema.derived[Boxed].transform(box => Boxed(box.n + 1), box => Boxed(box.n - 1))
  private val shiftedBoxObject = RecordObject.fromSchema(shiftedBoxSchema)
  @annotation.unused
  private val shiftedBoxObjectAgain = RecordObject.fromSchema(shiftedBoxSchema)

  private def step[I, O](tag: String, sig: String)(f: I => O): Step[I, O] = Step(tag, f, predict(sig))

  /** Package a Step independently of record-boundary decoding. */
  private def pack[I, O](m: Step[I, O]): Program[I, O, 1] = Program.of(m)

  // Fixture for the Record-input compile gate below (suite-level so compileErrors snippets can reference
  // it; referenced only inside the snippet, which the unused checker cannot see).
  private val recordLayout = SignatureLayout.parse("question -> s").toOption.get
  @annotation.unused
  private val recordSignature = Signature[DynamicValue.Record, Wrapped](
    name = "RecordInput",
    layout = recordLayout,
    inputShape = Shape.MapShape(recordLayout.inputFields),
    outputShape = Shape.derived[Wrapped]
  )

  private final case class ProgramObservation[O](
      output : Either[DspyError, Prediction[O]],
      starts : Vector[String],
      trace  : Vector[String],
      history: Vector[String]
  )

  /** Observe the executable semantics retained by Category equality. Structural nodes must not perturb lifecycle. */
  private def observe[I, O](program: SomeProgram[I, O], input: I): ProgramObservation[O] =
    RuntimeEnvironment.resetForTests()
    val starts   = Vector.newBuilder[String]
    val callback = new CallbackHandler:
      def onEvent(event: CallbackEvent)(using RuntimeContext): Unit =
        event match
          case start: ModuleStartEvent => starts += start.moduleName
          case _                       => ()
    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      given RuntimeContext = RuntimeEnvironment.current
      val output           = program(ProgramCall(input))
      ProgramObservation(
        output,
        starts.result(),
        RuntimeEnvironment.current.trace.map(_.component),
        RuntimeEnvironment.current.history.map(_.component)
      )
    }

  /** Execute an IsEq under the documented Program observation (params + complete prediction + executable semantics;
    * record decoding is external to the morphism, so it cannot vary between the two sides).
    */
  private def assertObsEq[I, O](
      eq   : IsEq[AnyGrade[Program, I, O]],
      input: I
  ): Unit =
    assertEquals(eq.lhs.morphism.params, eq.rhs.morphism.params)
    assertEquals(observe(eq.lhs.morphism, input), observe(eq.rhs.morphism, input))

  /** Execute an IsEq whose carrier supports plain structural equality (parameter vectors). */
  private def assertIsEq[A](eq: IsEq[A]): Unit =
    assertEquals(eq.lhs, eq.rhs)

  // ── Graded-category laws over Program, executed from the trait's @Law statements ────────────────────────────
  test("graded Category laws hold observationally and grades compose as natural numbers") {
    val f                                  = Program.of(step[Boxed, Wrapped]("f", "b -> s")(b => Wrapped(s"v${b.n}")))
    val identity: Program[Boxed, Boxed, 0] = C.id[Boxed]
    assertObsEq(C.identityLeft(f), Boxed(7))
    assertObsEq(C.identityRight(f), Boxed(7))
    assertEquals(identity.params, Vector.empty)

    val a                              = pack(step[Int, String]("a", "i -> s")(i => s"<$i>"))
    val g                              = pack(step[String, String]("g", "s -> t")(s => s + s))
    val h                              = pack(step[String, Int]("h", "t -> n")(s => s.length))
    val composed: Program[Int, Int, 3] = (a >>> g) >>> h
    assertObsEq(C.associativity(a, g, h), 3)
    assertEquals(composed(ProgramCall(3)).map(_.output), Right(6)) // "<3>" -> "<3><3>" -> length 6
  }

  test("forgetGrade is a lawful functor into the underlying ordinary category") {
    val f = pack(step[Int, String]("f", "i -> s")(i => s"<$i>"))
    val g = pack(step[String, Int]("g", "s -> n")(_.length))

    assert(U.map(f).morphism eq f)
    assertObsEq(U.identities[Boxed], Boxed(7))
    assertObsEq(U.composition(f, g), 3)
  }

  test("identity preserves the complete prediction envelope through ProgramRunner") {
    val f      = Program.of(step[Boxed, Wrapped]("f", "b -> s")(b => Wrapped(s"v${b.n}")))
    val direct = f(ProgramCall(Boxed(7)))
    val viaId  = (f >>> C.id[Wrapped])(ProgramCall(Boxed(7)))

    assertEquals(viaId, direct)

    val record = DynamicValues.record("n" := 7)
    val runner = summon[ProgramRunner[SomeProgram[Boxed, Wrapped]]]
    assertEquals(runner.run(f >>> C.id[Wrapped], record), runner.run(f, record))
    assertEquals(runner.run(C.id[Boxed] >>> f, record), runner.run(f, record))
  }

  // ── parameterization laws, executed from the @Law statements ─────────────────────────────────────────────────────────
  test("parameterization laws: identity and composition preserve the ordered parameter vector") {
    val a  = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b  = pack(step[String, Int]("b", "s -> n")(s => s.length))
    val ab = a >>> b
    assertIsEq(P.paramsId[Boxed])
    assertIsEq(P.paramsCompose(a, b))
    assertIsEq(P.readFunctor.identities[Boxed])
    assertIsEq(P.readFunctor.composition(a, b))
    val staticallySizedRead: SizedParamsHom[Int, Int, 2] = P.readFunctor.map(ab)
    assertEquals(staticallySizedRead, ab.sizedParams)
    val fresh = Vector(
      predict("i -> s").optimizableParameters.copy(instructions = Some("first update")),
      predict("s -> n").optimizableParameters.copy(instructions = Some("second update"))
    )
    // Behavior riders: reparameterization changes parameters, never the shape's computation.
    assertEquals(ab.reparam(ab.params)(ProgramCall(5)).map(_.output), ab(ProgramCall(5)).map(_.output))
    assertEquals(ab.reparam(fresh)(ProgramCall(5)).map(_.output), Right(2))
  }

  test("a packaged fixed-shape program has a lawful statically sized parameter lens") {
    val inferred                            = Program.of(step[Boxed, Wrapped]("p", "b -> s")(b => Wrapped(s"v${b.n}")))
    val program: Program[Boxed, Wrapped, 1] = inferred
    val lens                                = summon[Lens[
      Program[Boxed, Wrapped, 1],
      SizedVector[OptimizableParameters, 1]
    ]]
    val current: SizedVector[OptimizableParameters, 1]            = lens.get(program)
    val updated                                                   = SizedVector.one(current.unsized.head.copy(instructions = Some("statically sized update")))
    val second                                                    = Program.of(step[Wrapped, Boxed]("q", "s -> b")(_ => Boxed(2)))
    val composed: Program[Boxed, Boxed, 2]                        = program >>> second
    val composedParameters: SizedVector[OptimizableParameters, 2] = composed.sizedParams
    val arityAgreement                                            = composed.optimizableParameters.arityAgreement(composed.program)

    val getPut = lens.getPut(program)
    assertEquals(getPut.lhs.params, getPut.rhs.params)
    assertEquals(observe(getPut.lhs, Boxed(1)), observe(getPut.rhs, Boxed(1)))
    assertIsEq(lens.putGet(program, updated))

    val putPut = lens.putPut(program, current, updated)
    assertEquals(putPut.lhs.params, putPut.rhs.params)
    assertEquals(observe(putPut.lhs, Boxed(1)), observe(putPut.rhs, Boxed(1)))
    assertEquals(composedParameters.unsized.size, 2)
    assertEquals(arityAgreement.lhs, arityAgreement.rhs)
  }

  // ── fanout: behavior, its params law, and the copy NON-law ───────────────────────────────────────────────
  test("fanout runs both legs on the same input and satisfies paramsFanout") {
    val f                                      = pack(step[Int, String]("f", "i -> s")(i => s"v$i"))
    val g                                      = pack(step[Int, Int]("g", "i -> n")(i => i + 1))
    val paired: Program[Int, (String, Int), 2] = F.fanout(f, g)
    assertEquals(paired(ProgramCall(4)).map(_.output), Right(("v4", 5)))
    assertIsEq(P.paramsFanout(f, g))
    assertEquals(F.parallel(f, g)(ProgramCall(4)), F.fanout(f, g)(ProgramCall(4)))
  }

  test("copy is NOT natural: h >>> fanout(f, g) shares h; fanout(h >>> f, h >>> g) re-runs it") {
    val runs = AtomicInteger(0)
    val h    = pack(step[Int, Int]("h", "i -> j") { i =>
      val _ = runs.incrementAndGet(); i * 10
    })
    val f = pack(step[Int, String]("f", "i -> s")(i => s"v$i"))
    val g = pack(step[Int, Int]("g", "i -> n")(i => i + 1))

    val shared = h >>> F.fanout(f, g)
    val copied = F.fanout(h >>> f, h >>> g)

    runs.set(0)
    val sharedOut = shared(ProgramCall(3)).map(_.output)
    assertEquals(runs.get(), 1) // h ran once (the whole point of sharing)
    runs.set(0)
    val copiedOut = copied(ProgramCall(3)).map(_.output)
    assertEquals(runs.get(), 2) // h ran twice

    // With a DETERMINISTIC h the outputs coincide; with an effectful (LLM) h they need not — which is why
    // Fan-out naturality is a NON-law for ordered effectful execution, not an oversight.
    assertEquals(sharedOut, copiedOut)
    // And the optimizer sees the difference structurally: h's parameters appear once vs twice.
    assertEquals(shared.params.size, 3)
    assertEquals(copied.params.size, 4)

  }

  // ── The parameter monoid, and its delooping as a lawful Category instance (checked with real ==) ─────────
  test("the parameter monoid Monoid[Vector[OptimizableParameters]] satisfies the monoid laws") {
    val M  = Monoid[Vector[OptimizableParameters]]
    val v1 = Vector(predict("a -> b").optimizableParameters)
    val v2 = Vector(predict("b -> c").optimizableParameters)
    val v3 = Vector(predict("c -> d").optimizableParameters)
    assertIsEq(M.associativity(v1, v2, v3))
    assertIsEq(M.identityLeft(v1))
    assertIsEq(M.identityRight(v1))
  }

  test("paramsDeloop is that monoid delooped: Category laws hold, and id delegates to the monoid's empty") {
    val M  = Monoid[Vector[OptimizableParameters]]
    val v1 = Vector(predict("a -> b").optimizableParameters)
    val v2 = Vector(predict("b -> c").optimizableParameters)
    val v3 = Vector(predict("c -> d").optimizableParameters)
    assertIsEq(paramsDeloop.identityLeft[Unit, Unit](v1))
    assertIsEq(paramsDeloop.identityRight[Unit, Unit](v1))
    assertIsEq(paramsDeloop.associativity[Unit, Unit, Unit, Unit](v1, v2, v3))
    // The delooping delegates to the monoid: the category's identity IS the monoid's empty element.
    assertEquals(paramsDeloop.id[Unit], M.empty)
  }

  // ── ReadFunctor: params as a functor value; its laws executed ────────────────────────────────────────────
  test("InspectFunctor and ReadFunctor preserve identities and composition") {
    val a      = pack(step[Int, String]("a", "i -> s")(i => s"v$i"))
    val b      = pack(step[String, Int]("b", "s -> n")(s => s.length))
    val aViews = InspectFunctor.map(a)
    val bViews = InspectFunctor.map(b)
    assertIsEq(InspectFunctor.identities[Boxed])
    assertIsEq(InspectFunctor.composition(a, b))
    assertIsEq(ForgetMetadataFunctor.identities[Boxed])
    assertIsEq(ForgetMetadataFunctor.composition(aViews, bViews))
    assertIsEq(ReadFunctor.identities[Boxed])
    assertIsEq(ReadFunctor.composition(a, b))
    assertEquals(ReadFunctor.map(a), ForgetMetadataFunctor.map(aViews))
  }

  // ── Boundary decoding: the coherence condition is gone because nothing per-program remains ──────────────
  test("record-boundary decoding uses one canonical codec per input type") {
    // There is no per-program decoder left to compare: RecordCodec[Boxed] is THE decode path for every packaged
    // program runner at Boxed. The former coherence law has nothing to range over.
    val boxedRecord = DynamicValues.record("n" := 5)
    assertEquals(summon[RecordCodec[Boxed]].decode(boxedRecord), Right(Boxed(5)))
    val p   = Program.of(step[Boxed, Wrapped]("p", "b -> s")(b => Wrapped(s"v${b.n}")))
    val ran = summon[ProgramRunner[SomeProgram[Boxed, Wrapped]]].run(p, boxedRecord)
    assert(ran.isRight, s"record-boundary run failed: ${ran.left.toOption}")
  }

  test("an incoherent per-program decoder is UNREPRESENTABLE (was: the ProgramInput coherence law)") {
    // The former counterexample supplied a decoder disagreeing with the object's codec and broke the left
    // unit. Both of its vehicles are gone: Program.of takes no decoder argument, and ProgramInput no longer
    // exists. The unlawful value cannot be written down; its law dissolved rather than being discharged.
    val viaArgument = compileErrors(
      """Program.of(step[Boxed, Wrapped]("p", "b -> s")(b => Wrapped("v")), (_: DynamicValue.Record) => Right(Boxed(99)))"""
    )
    assert(viaArgument.nonEmpty, "expected the decoder-argument constructor to be gone")
    val viaInstance = compileErrors("summon[ProgramInput[Step[Boxed, Wrapped], Boxed]]")
    assert(viaInstance.nonEmpty, "expected the ProgramInput capability to be gone")
    val rogueCodec = compileErrors(
      "new RecordCodec[Boxed] { def decode(record: DynamicValue.Record) = Right(Boxed(99)) }"
    )
    assert(rogueCodec.nonEmpty, "expected RecordCodec to reject application-defined competing instances")
    val schemaFactory = compileErrors("RecordCodec.fromSchema[Boxed](using summon[Schema[Boxed]])")
    assert(schemaFactory.nonEmpty, "expected the arbitrary-Schema RecordCodec factory to be gone")
    val arrayCodec = compileErrors("summon[RecordCodec[ArrayBox]]")
    assert(arrayCodec.nonEmpty, "array objects must not reopen canonical derivation through ambient ClassTag")
  }

  test("canonical object decoding ignores top-level and nested ambient schemas") {
    val boxedWire        = DynamicValues.record("n" := 5)
    val shiftedBoxSchema = Schema.derived[Boxed].transform(box => Boxed(box.n + 1), box => Boxed(box.n - 1))

    locally {
      given Schema[Boxed] = shiftedBoxSchema

      // The open Shape API intentionally honors an explicit custom schema.
      assertEquals(Shape.derived[Boxed].decode(boxedWire), Right(Boxed(6)))
      // Category-object derivation is closed and therefore remains a function of Boxed alone.
      assertEquals(summon[RecordCodec[Boxed]].decode(boxedWire), Right(Boxed(5)))
      assertEquals(
        Signature.derived[Boxed, Wrapped]("CanonicalTopLevel").inputShape.decode(boxedWire),
        Right(Boxed(5))
      )
    }

    val canonicalNested     = Signature.derived[NestedBox, Wrapped]("CanonicalNested")
    val nestedWire          = canonicalNested.inputShape.encode(NestedBox(NestedValue(7)))
    val shiftedNestedSchema =
      Schema.derived[NestedValue].transform(value => NestedValue(value.n + 1), value => NestedValue(value.n - 1))

    locally {
      given Schema[NestedValue] = shiftedNestedSchema

      val ambientOuterSchema = Schema.derived[NestedBox]
      assertEquals(
        Shape.derived[NestedBox](using ambientOuterSchema).decode(nestedWire),
        Right(NestedBox(NestedValue(8)))
      )
      assertEquals(
        summon[RecordCodec[NestedBox]].decode(nestedWire),
        Right(NestedBox(NestedValue(7)))
      )
      assertEquals(
        Signature.derived[NestedBox, Wrapped]("CanonicalNestedShadowed").inputShape.decode(nestedWire),
        Right(NestedBox(NestedValue(7)))
      )
    }
  }

  test("custom schemas mint fresh object types instead of competing codecs") {
    val decoded = shiftedBoxObject.decode(DynamicValues.record("n" := 5)).map(shiftedBoxObject.unwrap)
    assertEquals(decoded, Right(Boxed(6)))
    assertEquals(summon[RecordCodec[Boxed]].decode(DynamicValues.record("n" := 5)), Right(Boxed(5)))

    val same                            = shiftedBoxObject.stable
    val again                           = same
    val branded: shiftedBoxObject.Value = shiftedBoxObject.wrap(Boxed(1))
    val captured: same.Value            = branded
    val aliased: again.Value            = captured
    assertEquals(again.unwrap(aliased), Boxed(1))

    val crossing = compileErrors(
      "shiftedBoxObjectAgain.unwrap(shiftedBoxObject.wrap(Boxed(1)))"
    )
    assert(crossing.nonEmpty, "separate custom-schema bundles must mint separate object types")
  }

  test("a bundle-tagged dynamic input carries its canonical boundary codec") {
    // The bundle mints fresh In/Out types whose codec is born from the same parse as the signature. Packaging itself
    // is codec-independent; importing the bundle givens enables record-boundary execution.
    val p = qaBundle.packaged()
    assertEquals(p.params.size, 1)
    import qaBundle.given
    assert(summon[RecordCodec[qaBundle.In]].decode(DynamicValues.record("question" := "hi")).isRight)
    assertEquals(C.id[qaBundle.In].params, Vector.empty)
    // The validating entry rejects a record missing a declared field, at the boundary.
    assert(qaBundle.input(DynamicValue.Record.empty).isLeft)
  }

  test("freshness: re-parsing the SAME string mints a DISTINCT object (cross-bundle composition is a type error)") {
    // Two parses are two fibers that happen to agree; the compiler keeps them apart. Aliasing a bundle value
    // (val t = qaBundle) would share the type, which is exactly the right equivalence: same parse, same object.
    val errors = compileErrors("qaBundle.packaged() >>> qaBundleAgain.packaged()")
    assert(errors.nonEmpty, "expected cross-bundle composition to fail compilation")
  }

  test("stable preserves a bundle's fresh types across an ordinary inferred alias") {
    val same                   = qaBundle.stable
    val again                  = same
    val input: qaBundle.In     = qaBundle.input(DynamicValues.record("question" := "hi")).toOption.get
    val aliased: same.In       = input
    val aliasedAgain: again.In = aliased
    assertEquals(same.signature.inputShape.encode(aliasedAgain), qaBundle.signature.inputShape.encode(input))
  }

  test("program construction is independent of record-boundary decoding") {
    // The collapsed Record object deliberately has no canonical codec: different dynamic signatures validate
    // different fields. That no longer prevents packaging, because the algebra operates directly on I. It prevents
    // only record-boundary execution of the package. BARE-module running remains signature-backed.
    val gate = compileErrors("summon[RecordCodec[DynamicValue.Record]]")
    assert(gate.nonEmpty, "the collapsed Record object must stay codec-less")
    val packaged = Program.of(ChainOfThought(recordSignature))
    assertEquals(packaged.params.size, 1)
    val errors = compileErrors("summon[ProgramRunner[Program[DynamicValue.Record, Wrapped, 1]]]")
    assert(errors.nonEmpty, "expected record-boundary execution without a codec to fail compilation")
    assert(errors.contains("RecordCodec"), s"expected a missing-RecordCodec error, got:\n$errors")
    val _ = summon[ProgramRunner[ChainOfThought[DynamicValue.Record, Wrapped]]]
  }

  test("identity exists at every object while record-boundary execution remains codec-gated") {
    val identity = C.id[Opaque]
    assertEquals(identity.params, Vector.empty)
    val errors = compileErrors("summon[ProgramRunner[Program[Opaque, Opaque, 0]]]")
    assert(errors.nonEmpty, "expected record-boundary execution at a non-codec input to fail compilation")
    assert(errors.contains("RecordCodec"), s"expected a missing-RecordCodec error, got:\n$errors")
  }

  // ── The construction gate: no OptimizableStructure evidence, no Program ───────────────────────────────────────────────
  test("packaging a program without OptimizableStructure evidence does not compile") {
    // Opaque is a plain (non-Product) Module: no OptimizableLeaf leaf, no Mirror, so OptimizableStructure[Opaque] cannot be
    // summoned and Program.of is a compile error. In the ambient Module world the same program runs fine but is
    // silently un-addressable; in the packaged category it cannot exist.
    val opaque = new Opaque
    assertEquals(opaque(ProgramCall(3)).map(_.output), Right(3)) // valid ambient program
    val errors = compileErrors("Program.of(new Opaque)")
    assert(errors.nonEmpty, "expected Program.of(new Opaque) to fail compilation")
    assert(errors.contains("OptimizableStructure"), s"expected a missing-OptimizableStructure error, got:\n$errors")
  }
