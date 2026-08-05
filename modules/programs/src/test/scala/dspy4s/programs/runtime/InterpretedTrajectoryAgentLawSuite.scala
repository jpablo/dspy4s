package dspy4s.programs.runtime

import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.data.RawPrediction
import dspy4s.programs.IterationLimit
import dspy4s.programs.contracts.{ActionInterpreter, ActionOutcome, Module, ModuleLifecycle, ProgramCall}
import dspy4s.programs.runtime.InterpretedTrajectoryAgent.{ActionDecision, ActionPreparation, StepGeneration}
import dspy4s.typed.Prediction
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

/** Executes the branch laws documented on [[InterpretedTrajectoryAgent]] against a synthetic action language. ReAct and
  * CodeAct only supply the protected generation/lowering/recording operations; the tested transition is final.
  */
final class InterpretedTrajectoryAgentLawSuite extends FunSuite:

  private val unusedExtractor: Module[(String, String), String] = new Module[(String, String), String]:
    override val moduleName: String                                             = "law_extractor"
    override protected val lifecycle: ModuleLifecycle[(String, String), String] = ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[(String, String)])(using
        RuntimeContext
    ): Either[DspyError, Prediction[String]] =
      Right(Prediction(call.input._2, RawPrediction.empty))

  private def interpreter(
      executeAction: String => Either[DspyError, ActionOutcome[String]]
  ): ActionInterpreter[String, String] =
    new ActionInterpreter[String, String]:
      override def execute(action: String)(using RuntimeContext): Either[DspyError, ActionOutcome[String]] =
        executeAction(action)

  private final class TestAgent(
      generation       : Vector[String] => Either[DspyError, StepGeneration[String, String]],
      preparation      : String => ActionPreparation[String, String],
      interpreterValue : ActionInterpreter[String, String],
      rejectionRecorder: (Int, String, String) => String,
      outcomeRecorder  : (Int, String, String, ActionOutcome[String]) => String,
      decision         : (String, String, ActionOutcome[String]) => ActionDecision
  ) extends InterpretedTrajectoryAgent[String, String, String]:
    type ModelStep   = String
    type Action      = String
    type Observation = String

    override val moduleName: String                                             = "interpreted_trajectory_law"
    override protected val lifecycle: ModuleLifecycle[String, String]           = ModuleLifecycle.typedWithoutInputs
    override val maxIterations: IterationLimit                                  = IterationLimit(1)
    override protected val extractorPredict: Module[(String, String), String]   = unusedExtractor
    override protected val trajectoryKey: String                                = "trajectory"
    override protected val actionInterpreter: ActionInterpreter[String, String] = interpreterValue

    override protected def renderTrajectory(trajectory: Vector[String]): String = trajectory.mkString(" -> ")

    override protected def generateStep(
        call      : ProgramCall[String],
        trajectory: Vector[String]
    )(using RuntimeContext): Either[DspyError, StepGeneration[String, String]] =
      generation(trajectory)

    override protected def prepareAction(step: String): ActionPreparation[String, String] = preparation(step)

    override protected def decide(
        step   : String,
        action : String,
        outcome: ActionOutcome[String]
    ): ActionDecision = decision(step, action, outcome)

    override protected def recordRejection(
        iteration  : Int,
        step       : String,
        observation: String
    ): String = rejectionRecorder(iteration, step, observation)

    override protected def recordOutcome(
        iteration: Int,
        step     : String,
        action   : String,
        outcome  : ActionOutcome[String]
    ): String = outcomeRecorder(iteration, step, action, outcome)

    def advance(trajectory: Vector[String], iteration: Int)(using
        RuntimeContext
    ): Either[DspyError, TrajectoryAgent.Step[String]] =
      trajectoryStep(ProgramCall("input"))(trajectory, iteration)

  private given RuntimeContext = RuntimeContext()

  test("Halted returns the generation view without interpreting or recording") {
    val interpreterCalls = AtomicInteger(0)
    val recordCalls      = AtomicInteger(0)
    val decisionCalls    = AtomicInteger(0)
    val agent            = TestAgent(
      generation = _ => Right(StepGeneration.Halted(Vector("used"))),
      preparation = _ => fail("Halted must not prepare an action"),
      interpreterValue = interpreter { _ =>
        interpreterCalls.incrementAndGet()
        Right(ActionOutcome.Succeeded("unreachable"))
      },
      rejectionRecorder = (_, _, _) =>
        recordCalls.incrementAndGet()
        "unreachable"
      ,
      outcomeRecorder = (_, _, _, _) =>
        recordCalls.incrementAndGet()
        "unreachable"
      ,
      decision = (_, _, _) =>
        decisionCalls.incrementAndGet()
        ActionDecision.Continue
    )

    assertEquals(agent.advance(Vector("original"), 4), Right(AgentLoop.Step.Done(Vector("used"))))
    assertEquals(interpreterCalls.get(), 0)
    assertEquals(recordCalls.get(), 0)
    assertEquals(decisionCalls.get(), 0)
  }

  test("Rejected records one failed outcome and does not invoke the interpreter") {
    val interpreterCalls = AtomicInteger(0)
    val decisionCalls    = AtomicInteger(0)
    val recorded         = ArrayBuffer.empty[(Int, String, String)]
    val agent            = TestAgent(
      generation = _ => Right(StepGeneration.Generated("model-step", Vector("used"))),
      preparation = _ => ActionPreparation.Rejected("invalid action"),
      interpreterValue = interpreter { _ =>
        interpreterCalls.incrementAndGet()
        Right(ActionOutcome.Succeeded("unreachable"))
      },
      rejectionRecorder = (iteration, step, observation) =>
        recorded += ((iteration, step, observation))
        "rejected-entry"
      ,
      outcomeRecorder = (_, _, _, _) => fail("Rejected must not record an interpreted outcome"),
      decision = (_, _, _) =>
        decisionCalls.incrementAndGet()
        ActionDecision.Continue
    )

    assertEquals(
      agent.advance(Vector("original"), 2),
      Right(AgentLoop.Step.Continue(Vector("used", "rejected-entry")))
    )
    assertEquals(interpreterCalls.get(), 0)
    assertEquals(decisionCalls.get(), 0)
    assertEquals(
      recorded.toVector,
      Vector((2, "model-step", "invalid action"))
    )
  }

  test("Ready interprets, records, and decides exactly once") {
    val interpreterCalls = AtomicInteger(0)
    val recordCalls      = AtomicInteger(0)
    val decisionCalls    = AtomicInteger(0)
    val agent            = TestAgent(
      generation = _ => Right(StepGeneration.Generated("model-step", Vector("used"))),
      preparation = _ => ActionPreparation.Ready("action"),
      interpreterValue = interpreter { action =>
        interpreterCalls.incrementAndGet()
        assertEquals(action, "action")
        Right(ActionOutcome.Succeeded("observation"))
      },
      rejectionRecorder = (_, _, _) => fail("Ready must not record a rejection"),
      outcomeRecorder = (iteration, step, action, outcome) =>
        recordCalls.incrementAndGet()
        assertEquals((iteration, step, action), (3, "model-step", "action"))
        assertEquals(outcome, ActionOutcome.Succeeded("observation"))
        "ready-entry"
      ,
      decision = (step, action, outcome) =>
        decisionCalls.incrementAndGet()
        assertEquals((step, action), ("model-step", "action"))
        assertEquals(outcome, ActionOutcome.Succeeded("observation"))
        ActionDecision.Continue
    )

    assertEquals(
      agent.advance(Vector("original"), 3),
      Right(AgentLoop.Step.Continue(Vector("used", "ready-entry")))
    )
    assertEquals(interpreterCalls.get(), 1)
    assertEquals(recordCalls.get(), 1)
    assertEquals(decisionCalls.get(), 1)
  }

  test("Stop is decided after the interpreted outcome has been recorded") {
    val order = ArrayBuffer.empty[String]
    val agent = TestAgent(
      generation = _ => Right(StepGeneration.Generated("model-step", Vector.empty)),
      preparation = _ => ActionPreparation.Ready("action"),
      interpreterValue = interpreter { _ =>
        order += "interpret"
        Right(ActionOutcome.Succeeded("observation"))
      },
      rejectionRecorder = (_, _, _) => fail("Ready must not record a rejection"),
      outcomeRecorder = (_, _, _, _) =>
        order += "record"
        "final-entry"
      ,
      decision = (_, _, _) =>
        order += "decide"
        ActionDecision.Stop
    )

    assertEquals(agent.advance(Vector.empty, 0), Right(AgentLoop.Step.Done(Vector("final-entry"))))
    assertEquals(order.toVector, Vector("interpret", "record", "decide"))
  }

  test("the decision can continue after a recoverable interpreted failure") {
    val seenOutcomes = ArrayBuffer.empty[ActionOutcome[String]]
    val agent        = TestAgent(
      generation = _ => Right(StepGeneration.Generated("model-step", Vector.empty)),
      preparation = _ => ActionPreparation.Ready("action"),
      interpreterValue = interpreter(_ => Right(ActionOutcome.Failed("retryable"))),
      rejectionRecorder = (_, _, _) => fail("Ready must not record a rejection"),
      outcomeRecorder = (_, _, _, outcome) =>
        assertEquals(outcome, ActionOutcome.Failed("retryable"))
        "failed-entry"
      ,
      decision = (_, _, outcome) =>
        seenOutcomes += outcome
        ActionDecision.Continue
    )

    assertEquals(
      agent.advance(Vector.empty, 0),
      Right(AgentLoop.Step.Continue(Vector("failed-entry")))
    )
    assertEquals(seenOutcomes.toVector, Vector(ActionOutcome.Failed("retryable")))
  }

  test("a fatal interpreter error propagates without recording an entry") {
    val recordCalls   = AtomicInteger(0)
    val decisionCalls = AtomicInteger(0)
    val failure       = RuntimeError("interpreted-law", "fatal")
    val agent         = TestAgent(
      generation = _ => Right(StepGeneration.Generated("model-step", Vector("used"))),
      preparation = _ => ActionPreparation.Ready("action"),
      interpreterValue = interpreter(_ => Left(failure)),
      rejectionRecorder = (_, _, _) =>
        recordCalls.incrementAndGet()
        "unreachable"
      ,
      outcomeRecorder = (_, _, _, _) =>
        recordCalls.incrementAndGet()
        "unreachable"
      ,
      decision = (_, _, _) =>
        decisionCalls.incrementAndGet()
        ActionDecision.Continue
    )

    assertEquals(agent.advance(Vector("original"), 0), Left(failure))
    assertEquals(recordCalls.get(), 0)
    assertEquals(decisionCalls.get(), 0)
  }

  test("a fatal generation error propagates without entering a later state") {
    val preparationCalls = AtomicInteger(0)
    val interpreterCalls = AtomicInteger(0)
    val recordCalls      = AtomicInteger(0)
    val decisionCalls    = AtomicInteger(0)
    val failure          = RuntimeError("interpreted-law", "generation failed")
    val agent            = TestAgent(
      generation = _ => Left(failure),
      preparation = _ =>
        preparationCalls.incrementAndGet()
        ActionPreparation.Ready("unreachable")
      ,
      interpreterValue = interpreter { _ =>
        interpreterCalls.incrementAndGet()
        Right(ActionOutcome.Succeeded("unreachable"))
      },
      rejectionRecorder = (_, _, _) =>
        recordCalls.incrementAndGet()
        "unreachable"
      ,
      outcomeRecorder = (_, _, _, _) =>
        recordCalls.incrementAndGet()
        "unreachable"
      ,
      decision = (_, _, _) =>
        decisionCalls.incrementAndGet()
        ActionDecision.Continue
    )

    assertEquals(agent.advance(Vector("original"), 0), Left(failure))
    assertEquals(preparationCalls.get(), 0)
    assertEquals(interpreterCalls.get(), 0)
    assertEquals(recordCalls.get(), 0)
    assertEquals(decisionCalls.get(), 0)
  }
