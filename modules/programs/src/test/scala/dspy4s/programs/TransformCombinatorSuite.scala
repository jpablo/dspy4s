package dspy4s.programs

import dspy4s.programs.predictors.*
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.CallbackEvent
import dspy4s.core.contracts.CallbackHandler
import dspy4s.core.contracts.DspyError
import dspy4s.core.data.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.ModuleStartEvent
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

class TransformCombinatorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  private final case class Step[I, O](tag: String, run: I => Either[DspyError, O], predict: DynamicPredict)
      extends Module[I, Prediction[O]]:
    override val moduleName: String = s"step_$tag"
    override protected val lifecycle: ModuleLifecycle[I, Prediction[O]] =
      ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
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
    val fallible =
      Compose.liftEither[Int, String](i => if i >= 0 then Right(s"v$i") else Left(ValidationError("negative")))
    val throwing = Compose.lift[Int, Int](_ => throw IllegalStateException("boom"))

    assertEquals(lifted(ProgramCall(3)).map(_.output), Right("v3"))
    assertEquals(fallible(ProgramCall(-1)), Left(ValidationError("negative")))
    val failure = throwing(ProgramCall(1)).left.toOption.get
    assertEquals(failure, RuntimeError("program_lift", "boom"))
    assertEquals(params(lifted), Vector.empty)
    assertEquals(params(fallible), Vector.empty)
  }

  test("mapOutput obeys identity/composition on output and preserves raw prediction evidence") {
    val base       = step[Int, String]("base", "i -> s")(i => s"v$i")
    val mappedIdentity = base.mapOutput(identity[String])
    val sequential = base.mapOutput(_.length).mapOutput(_ * 2)
    val composed   = base.mapOutput(s => s.length * 2)
    val direct         = base(ProgramCall(12))

    assertEquals(mappedIdentity(ProgramCall(12)), direct)
    assertEquals(sequential(ProgramCall(12)), composed(ProgramCall(12)))
    assertEquals(composed(ProgramCall(12)).map(_.raw), direct.map(_.raw))
    assertEquals(params(composed), Vector(base.predict.predictorState))
  }

  test("contramapInput obeys identity/composition and forwards call controls") {
    val observed = ArrayBuffer.empty[ProgramCall[Int]]
    val base = Step[Int, String](
      "base",
      i => { observed += ProgramCall(i); Right(s"v$i") },
      predictor("i -> s")
    )
    val sequential = base.contramapInput[String](_.length).contramapInput[Vector[Int]](_.mkString)
    val composed   = base.contramapInput[Vector[Int]](items => items.mkString.length)

    assertEquals(base.contramapInput(identity[Int])(ProgramCall(4)), base(ProgramCall(4)))
    assertEquals(sequential(ProgramCall(Vector(1, 20))), composed(ProgramCall(Vector(1, 20))))

    val controls =
      ProgramCall(Vector(1), DynamicValues.record("temperature" := 0.2), traceEnabled = false, rolloutId = Some(7))
    val controlAware = new Module[Int, Prediction[(Int, DynamicValue.Record, Boolean, Option[Int])]]:
      val moduleName: String = "control_aware"
      protected val lifecycle
          : ModuleLifecycle[Int, Prediction[(Int, DynamicValue.Record, Boolean, Option[Int])]] =
        ModuleLifecycle.typedWithoutInputs
      protected def forward(call: ProgramCall[Int])(using RuntimeContext) =
        Right(Prediction((call.input, call.config, call.traceEnabled, call.rolloutId), DynamicPrediction.empty))
    val adapted = controlAware.contramapInput[Vector[Int]](_.sum)
    assertEquals(adapted(controls).map(_.output), Right((1, controls.config, false, Some(7))))
  }

  test("dimap is observationally equivalent to contramapInput followed by mapOutput") {
    val base = step[Int, String]("base", "i -> s")(i => s"v$i")
    val direct = base.dimap[String, Int](_.toInt)(_.length)
    val derived = base.contramapInput[String](_.toInt).mapOutput(_.length)

    assertEquals(direct(ProgramCall("42")), derived(ProgramCall("42")))
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
      val _ = base.dimap[String, Int](_.toInt)(_.length)(ProgramCall("123"))
    }

    assertEquals(starts.toVector, Vector("step_base"))
  }

  test("fanout is the honest ordered name for shared-input pairing") {
    val order = ArrayBuffer.empty[String]
    val first = step[Int, String]("first", "i -> s") { i =>
      order += "first"; s"v$i"
    }
    val second = step[Int, Int]("second", "i -> n") { i =>
      order += "second"; i + 1
    }

    val result = Compose.fanout(first, second)(ProgramCall(4))
    assertEquals(result.map(_.output), Right(("v4", 5)))
    assertEquals(order.toVector, Vector("first", "second"))
    assertEquals(result, Compose.parallel(first, second)(ProgramCall(4)))
    assertEquals(result, first.fanout(second)(ProgramCall(4)))
    assertEquals(result, first.parallel(second)(ProgramCall(4)))
    assertEquals(result, (first &&& second)(ProgramCall(4)))
  }

  test("split is ordered independent-input pairing and fails before running the second leg") {
    val order = ArrayBuffer.empty[String]
    val first = Step[Int, String](
      "first",
      _ => { order += "first"; Left(ValidationError("first failed")) },
      predictor("i -> s")
    )
    val second = step[Boolean, Int]("second", "p -> n") { p =>
      order += "second"; if p then 1 else 0
    }

    assertEquals(Compose.split(first, second)(ProgramCall((1, true))), Left(ValidationError("first failed")))
    assertEquals(order.toVector, Vector("first"))
    val expectedStates = Vector(first.predict.predictorState, second.predict.predictorState)
    assertEquals(params(Compose.split(first, second)), expectedStates)
    assertEquals(params(first.split(second)), expectedStates)
    assertEquals(params(first.tensor(second)), expectedStates)
    assertEquals(params(first *** second), expectedStates)
  }
