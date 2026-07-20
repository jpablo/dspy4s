package dspy4s.programs

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
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import scala.collection.mutable.ArrayBuffer

class RecoveryCombinatorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit   = RuntimeEnvironment.resetForTests()

  private final case class Attempt(
      name: String,
      result: Either[DspyError, String],
      predict: DynamicPredict,
      runs: ArrayBuffer[String]
  ) extends Module[ProgramCall[Int], Prediction[String]]:
    override val moduleName: String                                              = name
    override protected def callInputs(call: ProgramCall[Int]): DynamicValue.Record  = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: ProgramCall[Int]): Boolean        = call.traceEnabled
    override protected def tracePayload(p: Prediction[String]): DynamicValue.Record = p.raw.values
    override protected def forward(call: ProgramCall[Int])(using
        RuntimeContext
    ): Either[DspyError, Prediction[String]] =
      runs += name
      result.map(value =>
        Prediction(value, DynamicPrediction(DynamicValues.record("source" -> DynamicValues.fromAny(name))))
      )

  private object Attempt:
    given attemptPredictor: Predictor[Attempt] with
      def get(program: Attempt): PredictorState = program.predict.predictorState
      def metadata(program: Attempt): PredictorMetadata = program.predict.predictorView.metadata
      def set(program: Attempt, updated: PredictorState): Attempt =
        program.copy(predict = program.predict.withPredictorState(updated))

  private def predictor(instruction: String): DynamicPredict =
    DynamicPredict(SignatureLayout.parse("i -> s").toOption.get.withInstructions(Some(instruction)))

  private def params[P](program: P)(using predictors: Predictors[P]): Vector[PredictorState] = predictors.read(program)

  private given RuntimeContext = RuntimeEnvironment.current

  test("recovery never runs the fallback after primary success") {
    val runs     = ArrayBuffer.empty[String]
    val primary  = Attempt("primary", Right("primary"), predictor("p"), runs)
    val fallback = Attempt("fallback", Right("fallback"), predictor("f"), runs)

    val result = primary.recoverWith(RecoveryPolicy.Always)(fallback)(ProgramCall(1))
    assertEquals(result.map(_.output), Right("primary"))
    assertEquals(runs.toVector, Vector("primary"))
  }

  test("Never preserves the original failure; Always runs the fallback") {
    val error    = ValidationError("bad input")
    val runs     = ArrayBuffer.empty[String]
    val primary  = Attempt("primary", Left(error), predictor("p"), runs)
    val fallback = Attempt("fallback", Right("fallback"), predictor("f"), runs)

    assertEquals(primary.recoverWith(RecoveryPolicy.Never)(fallback)(ProgramCall(1)), Left(error))
    assertEquals(runs.toVector, Vector("primary"))
    runs.clear()
    assertEquals(
      primary.recoverWith(RecoveryPolicy.Always)(fallback)(ProgramCall(1)).map(_.output),
      Right("fallback")
    )
    assertEquals(runs.toVector, Vector("primary", "fallback"))
    runs.clear()
    assertEquals(
      Compose.recover(primary, fallback, RecoveryPolicy.Always)(ProgramCall(1)).map(_.output),
      Right("fallback")
    )
    assertEquals(runs.toVector, Vector("primary", "fallback"))
  }

  test("ErrorCodes and When recover only explicitly selected failures") {
    val runs     = ArrayBuffer.empty[String]
    val error    = RuntimeError("rate_limit", "slow down")
    val primary  = Attempt("primary", Left(error), predictor("p"), runs)
    val fallback = Attempt("fallback", Right("fallback"), predictor("f"), runs)

    assertEquals(
      primary.recoverWith(RecoveryPolicy.onCodes("validation_error"))(fallback)(ProgramCall(1)),
      Left(error)
    )
    runs.clear()
    assertEquals(
      primary.recoverWith(RecoveryPolicy.when {
        case RuntimeError(component, _) => component == "rate_limit"
        case _                          => false
      })(fallback)(ProgramCall(1)).map(_.output),
      Right("fallback")
    )
    assertEquals(runs.toVector, Vector("primary", "fallback"))
  }

  test("fallback failure replaces an allowed primary failure") {
    val runs          = ArrayBuffer.empty[String]
    val primaryError  = ValidationError("primary")
    val fallbackError = RuntimeError("fallback", "failed")
    val primary       = Attempt("primary", Left(primaryError), predictor("p"), runs)
    val fallback      = Attempt("fallback", Left(fallbackError), predictor("f"), runs)

    assertEquals(primary.recoverWith(RecoveryPolicy.Always)(fallback)(ProgramCall(1)), Left(fallbackError))
  }

  test("a throwing policy is normalized into the typed error channel") {
    val runs     = ArrayBuffer.empty[String]
    val primary  = Attempt("primary", Left(ValidationError("primary")), predictor("p"), runs)
    val fallback = Attempt("fallback", Right("fallback"), predictor("f"), runs)
    val policy   = RecoveryPolicy.when(_ => throw IllegalStateException("policy boom"))

    assertEquals(
      primary.recoverWith(policy)(fallback)(ProgramCall(1)),
      Left(RuntimeError("program_recovery_policy", "policy boom"))
    )
    assertEquals(runs.toVector, Vector("primary"))
  }

  test("recovery retains both branches in stable optimizer order") {
    val runs     = ArrayBuffer.empty[String]
    val primary  = Attempt("primary", Left(ValidationError("primary")), predictor("p"), runs)
    val fallback = Attempt("fallback", Right("fallback"), predictor("f"), runs)
    val recovered = primary.recoverWith(RecoveryPolicy.Always)(fallback)
    val P         = summon[Predictors[RecoverWith[Int, String, Attempt, Attempt]]]

    assertEquals(params(recovered), Vector(primary.predict.predictorState, fallback.predict.predictorState))
    assertEquals(P.readNamed(recovered).map(_._1), Vector("primary", "fallback"))
    val replacements = Vector(predictor("p2").predictorState, predictor("f2").predictorState)
    assertEquals(P.read(P.replace(recovered, replacements)), replacements)
  }

  test("recovery is lifecycle-transparent while attempted branches remain observable") {
    val runs     = ArrayBuffer.empty[String]
    val starts   = ArrayBuffer.empty[String]
    val primary  = Attempt("primary", Left(ValidationError("primary")), predictor("p"), runs)
    val fallback = Attempt("fallback", Right("fallback"), predictor("f"), runs)
    val callback = new CallbackHandler:
      def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
        case start: ModuleStartEvent => starts += start.moduleName
        case _                       => ()

    RuntimeEnvironment.withCallbacks(Vector(callback)) {
      val _ = primary.recoverWith(RecoveryPolicy.Always)(fallback)(ProgramCall(1))
    }

    assertEquals(starts.toVector, Vector("primary", "fallback"))
  }
