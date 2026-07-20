package dspy4s.programs

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

class TransformCombinatorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  private final case class Step[I, O](tag: String, run: I => Either[DspyError, O], predict: DynamicPredict)
      extends Module[TypedCall[I], Prediction[O]]:
    override val moduleName: String                                            = s"step_$tag"
    override protected def callInputs(call: TypedCall[I]): DynamicValue.Record = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[I]): Boolean       = call.traceEnabled
    override protected def tracePayload(p: Prediction[O]): DynamicValue.Record = p.raw.values
    override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      run(call.input).map(output => Prediction(output, DynamicPrediction(DynamicValues.record("tag" := tag))))

  private object Step:
    given stepPredictor[I, O]: Predictor[Step[I, O]] with
      def get(program: Step[I, O]): PredictorState = program.predict.predictorState
      def metadata(program: Step[I, O]): PredictorMetadata = program.predict.predictorView.metadata
      def set(program: Step[I, O], updated: PredictorState): Step[I, O] =
        program.copy(predict = program.predict.withPredictorState(updated))

  private def predictor(signature: String): DynamicPredict =
    DynamicPredict(SignatureLayout.parse(signature).toOption.get)

  private def step[I, O](tag: String, signature: String)(f: I => O): Step[I, O] =
    Step(tag, input => Right(f(input)), predictor(signature))

  private def params[P](program: P)(using predictors: Predictors[P]): Vector[PredictorState] =
    predictors.read(program)

  private given RuntimeContext = RuntimeEnvironment.current

  test("lift and liftEither embed local functions in the typed error carrier") {
    val lifted   = Compose.lift[Int, String](i => s"v$i")
    val fallible = Compose.liftEither[Int, String](i => if i >= 0 then Right(s"v$i") else Left(ValidationError("negative")))
    val throwing = Compose.lift[Int, Int](_ => throw IllegalStateException("boom"))

    assertEquals(lifted(TypedCall(3)).map(_.output), Right("v3"))
    assertEquals(fallible(TypedCall(-1)), Left(ValidationError("negative")))
    val failure = throwing(TypedCall(1)).left.toOption.get
    assertEquals(failure, RuntimeError("program_lift", "boom"))
    assertEquals(params(lifted), Vector.empty)
    assertEquals(params(fallible), Vector.empty)
  }

  test("mapOutput obeys identity/composition on output and preserves raw prediction evidence") {
    val base       = step[Int, String]("base", "i -> s")(i => s"v$i")
    val mappedIdentity = base.mapOutput(identity[String])
    val sequential = base.mapOutput(_.length).mapOutput(_ * 2)
    val composed   = base.mapOutput(s => s.length * 2)
    val direct     = base(TypedCall(12))

    assertEquals(mappedIdentity(TypedCall(12)), direct)
    assertEquals(sequential(TypedCall(12)), composed(TypedCall(12)))
    assertEquals(composed(TypedCall(12)).map(_.raw), direct.map(_.raw))
    assertEquals(params(composed), Vector(base.predict.predictorState))
  }

  test("contramapInput obeys identity/composition and forwards call controls") {
    val observed = ArrayBuffer.empty[TypedCall[Int]]
    val base = Step[Int, String](
      "base",
      i => { observed += TypedCall(i); Right(s"v$i") },
      predictor("i -> s")
    )
    val sequential = base.contramapInput[String](_.length).contramapInput[Vector[Int]](_.mkString)
    val composed   = base.contramapInput[Vector[Int]](items => items.mkString.length)

    assertEquals(base.contramapInput(identity[Int])(TypedCall(4)), base(TypedCall(4)))
    assertEquals(sequential(TypedCall(Vector(1, 20))), composed(TypedCall(Vector(1, 20))))

    val controls = TypedCall(Vector(1), DynamicValues.record("temperature" := 0.2), traceEnabled = false, rolloutId = Some(7))
    val controlAware = new Module[TypedCall[Int], Prediction[(Int, DynamicValue.Record, Boolean, Option[Int])]]:
      val moduleName: String = "control_aware"
      protected def callInputs(call: TypedCall[Int]): DynamicValue.Record = DynamicValue.Record.empty
      protected def callTraceEnabled(call: TypedCall[Int]): Boolean = call.traceEnabled
      protected def tracePayload(p: Prediction[(Int, DynamicValue.Record, Boolean, Option[Int])]): DynamicValue.Record =
        DynamicValue.Record.empty
      protected def forward(call: TypedCall[Int])(using RuntimeContext) =
        Right(Prediction((call.input, call.config, call.traceEnabled, call.rolloutId), DynamicPrediction.empty))
    val adapted = controlAware.contramapInput[Vector[Int]](_.sum)
    assertEquals(adapted(controls).map(_.output), Right((1, controls.config, false, Some(7))))
  }

  test("dimap is observationally equivalent to contramapInput followed by mapOutput") {
    val base = step[Int, String]("base", "i -> s")(i => s"v$i")
    val direct = base.dimap[String, Int](_.toInt)(_.length)
    val derived = base.contramapInput[String](_.toInt).mapOutput(_.length)

    assertEquals(direct(TypedCall("42")), derived(TypedCall("42")))
    assertEquals(params(direct), Vector(base.predict.predictorState))
  }

  test("transform wrappers are lifecycle-transparent") {
    val starts = ArrayBuffer.empty[String]
    val callback = new CallbackHandler:
      def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
        case start: ModuleStartEvent => starts += start.moduleName
        case _                       => ()
    val base = step[Int, String]("base", "i -> s")(_.toString)

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      val _ = base.dimap[String, Int](_.toInt)(_.length)(TypedCall("123"))
    }

    assertEquals(starts.toVector, Vector("step_base"))
  }

  test("fanout is the honest ordered name for shared-input pairing") {
    val order = ArrayBuffer.empty[String]
    val first  = step[Int, String]("first", "i -> s") { i => order += "first"; s"v$i" }
    val second = step[Int, Int]("second", "i -> n") { i => order += "second"; i + 1 }

    val result = Compose.fanout(first, second)(TypedCall(4))
    assertEquals(result.map(_.output), Right(("v4", 5)))
    assertEquals(order.toVector, Vector("first", "second"))
    assertEquals(result, Compose.parallel(first, second)(TypedCall(4)))
    assertEquals(result, first.fanout(second)(TypedCall(4)))
  }

  test("split is ordered independent-input pairing and fails before running the second leg") {
    val order = ArrayBuffer.empty[String]
    val first = Step[Int, String](
      "first",
      _ => { order += "first"; Left(ValidationError("first failed")) },
      predictor("i -> s")
    )
    val second = step[Boolean, Int]("second", "p -> n") { p => order += "second"; if p then 1 else 0 }

    assertEquals(Compose.split(first, second)(TypedCall((1, true))), Left(ValidationError("first failed")))
    assertEquals(order.toVector, Vector("first"))
    val expectedStates = Vector(first.predict.predictorState, second.predict.predictorState)
    assertEquals(params(Compose.split(first, second)), expectedStates)
    assertEquals(params(first.split(second)), expectedStates)
  }
