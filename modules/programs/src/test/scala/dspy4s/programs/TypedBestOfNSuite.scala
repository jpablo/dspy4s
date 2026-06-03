package dspy4s.programs

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer

class TypedBestOfNSuite extends FunSuite:

  private case class Q(q: String)
  private case class Cand(answer: String, score: Double)

  private def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
    DynamicValues.recordFromEntries(entries)

  /** A typed program stub returning scripted `Prediction[Cand]`s, tracking call count + the rolloutIds it saw. */
  private final class TypedStub(results: Vector[Either[DspyError, Prediction[Cand]]])
      extends Module[TypedCall[Q], Prediction[Cand]]:
    val rolloutIds: ArrayBuffer[Int] = ArrayBuffer.empty
    val calls: AtomicInteger         = AtomicInteger(0)
    private val counter              = AtomicInteger(0)
    override val moduleName: String  = "typed_stub"
    override protected def callInputs(call: TypedCall[Q]): DynamicValue.Record = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[Q]): Boolean       = call.traceEnabled
    override protected def tracePayload(p: Prediction[Cand]): DynamicValue.Record = p.raw.values
    override protected def forward(call: TypedCall[Q])(using RuntimeContext): Either[DspyError, Prediction[Cand]] =
      calls.incrementAndGet()
      rolloutIds += call.rolloutId.getOrElse(-1)
      results(Math.min(counter.getAndIncrement(), results.size - 1))

  private def candidate(answer: String, score: Double): Prediction[Cand] =
    Prediction(output = Cand(answer, score), raw = DynamicPrediction(values = rec("answer" := answer, "score" := score)))

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  test("typed BestOfN returns the highest-reward typed prediction and threads rolloutIds") {
    val stub = TypedStub(Vector(
      Right(candidate("A", 0.1)),
      Right(candidate("B", 0.9)),
      Right(candidate("C", 0.5))
    ))
    val bestOfN = BestOfN[Q, Cand](
      module    = stub,
      n         = 3,
      rewardFn  = (_, pred) => pred.output.score,
      threshold = 1.0
    )

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x"), rolloutId = Some(7)))

    assert(result.isRight, s"expected success, got: $result")
    assertEquals(result.toOption.get.output, Cand("B", 0.9))
    assertEquals(stub.calls.get(), 3)
    assertEquals(stub.rolloutIds.toVector, Vector(7, 8, 9))
    // Winner's (propagated) trace entry, then BestOfN's own wrapped entry.
    assertEquals(RuntimeEnvironment.current.trace.size, 2)
    assertEquals(
      DynamicValues.recordToMap(RuntimeEnvironment.current.trace.head.outputs).get("answer"),
      Some("B": Any)
    )
  }

  test("typed BestOfN short-circuits once the reward reaches the threshold") {
    val stub = TypedStub(Vector(
      Right(candidate("A", 0.95)),   // >= threshold -> stop after the first attempt
      Right(candidate("B", 0.10))
    ))
    val bestOfN = BestOfN[Q, Cand](stub, n = 3, rewardFn = (_, p) => p.output.score, threshold = 0.9)

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x")))
    assertEquals(result.toOption.map(_.output), Some(Cand("A", 0.95)))
    assertEquals(stub.calls.get(), 1)
  }

  test("typed BestOfN surfaces the last error after exhausting the default fail budget") {
    val stub = TypedStub(Vector(
      Left(RuntimeError("typed_stub", "f1")),
      Left(RuntimeError("typed_stub", "f2")),
      Left(RuntimeError("typed_stub", "f3"))
    ))
    val bestOfN = BestOfN[Q, Cand](stub, n = 3, rewardFn = (_, _) => 1.0, threshold = 0.0)

    given RuntimeContext = RuntimeEnvironment.current
    val result = bestOfN.apply(TypedCall(Q("x")))
    assert(result.isLeft)
    assertEquals(result.left.toOption.get.message, "f3")
  }

  test("typed Refine delegates to BestOfN and returns the best typed prediction") {
    val stub = TypedStub(Vector(
      Right(candidate("A", 0.2)),
      Right(candidate("B", 0.8))
    ))
    val refine = Refine[Q, Cand](stub, n = 2, rewardFn = (_, p) => p.output.score, threshold = 1.0)

    given RuntimeContext = RuntimeEnvironment.current
    val result = refine.apply(TypedCall(Q("x")))
    assertEquals(result.toOption.map(_.output), Some(Cand("B", 0.8)))
    assertEquals(stub.calls.get(), 2)
  }
