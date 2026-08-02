package dspy4s.programs.algebra

import dspy4s.core.contracts.DspyError
import dspy4s.core.data.RawPrediction
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.ValidationError
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.ModuleLifecycle
import dspy4s.programs.contracts.ProgramCall
import dspy4s.typed.Prediction
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

/** Executable semantics for [[OrderedTensorOps]] over unrestricted programs. In particular, this suite pins the
  * fail-fast counterexample that prevents [[ModuleHom]] from having the stronger [[CDCategory]] instance.
  */
class OrderedTensorOpsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach): Unit = RuntimeEnvironment.resetForTests()

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private val C = summon[OrderedTensorOps[ModuleHom]]

  test("unrestricted modules do not have a CDCategory instance") {
    val errors = compileErrors("summon[CDCategory[ModuleHom]]")
    assert(errors.nonEmpty)
  }

  private final case class Fn[I, O](f: I => O) extends Module[I, O]:
    override val moduleName: String = "fn"
    override protected val lifecycle: ModuleLifecycle[I, O] =
      ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), RawPrediction.empty))

  private final case class Fail[I, O](label: String) extends Module[I, O]:
    override val moduleName: String = s"fail_$label"
    override protected val lifecycle: ModuleLifecycle[I, O] =
      ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Left(ValidationError(label))

  private final class Counting extends Module[Int, String]:
    val calls: AtomicInteger = AtomicInteger(0)
    override val moduleName: String = "counting"
    override protected val lifecycle: ModuleLifecycle[Int, String] =
      ModuleLifecycle.typedWithoutInputs
    override protected def forward(call: ProgramCall[Int])(using
        RuntimeContext
    ): Either[DspyError, Prediction[String]] =
      Right(Prediction(s"${call.input}-${calls.incrementAndGet()}", RawPrediction.empty))

  test("ordered tensor is not bifunctorial for fail-fast programs") {
    val f1 = Fn[Int, Int](identity)
    val g1 = Fail[String, String]("g1")
    val f2 = Fail[Int, Int]("f2")
    val g2 = Fn[String, String](identity)

    val lhs = C.tensor(f1, g1) >>> C.tensor(f2, g2)
    val rhs = C.tensor(f1 >>> f2, g1 >>> g2)

    assertEquals(lhs(ProgramCall((1, "x"))).left.map(_.message), Left("g1"))
    assertEquals(rhs(ProgramCall((1, "x"))).left.map(_.message), Left("f2"))
  }

  test("tensor preserves values for identity programs") {
    val result = C.tensor(C.id[Int], C.id[String]).apply(ProgramCall((5, "x"))).map(_.output)
    assertEquals(result, Right((5, "x")))
  }

  test("swap is involutive and copy is cocommutative on values") {
    val swapped = (C.swap[Int, String] >>> C.swap[String, Int]).apply(ProgramCall((5, "x"))).map(_.output)
    val copied  = (C.copy[Int] >>> C.swap[Int, Int]).apply(ProgramCall(7)).map(_.output)

    assertEquals(swapped, Right((5, "x")))
    assertEquals(copied, Right((7, 7)))
  }

  test("discard equality on values hides an observable effect") {
    val f = new Counting
    val lhs            = (f >>> C.discard[String]).apply(ProgramCall(5)).map(_.output)
    val callsAfterLeft = f.calls.get()
    val rhs            = C.discard[Int].apply(ProgramCall(5)).map(_.output)

    assertEquals(lhs, rhs)
    assertEquals(callsAfterLeft, 1)
    assertEquals(f.calls.get(), 1)
  }

  test("copy commutes with deterministic programs but not effect-observing programs") {
    val deterministic = Fn[Int, String](i => s"v$i")
    val deterministicLeft = (deterministic >>> C.copy[String]).apply(ProgramCall(5)).map(_.output)
    val deterministicRight =
      (C.copy[Int] >>> C.tensor(deterministic, deterministic)).apply(ProgramCall(5)).map(_.output)
    assertEquals(deterministicLeft, deterministicRight)

    val effectful = new Counting
    val effectfulLeft = (effectful >>> C.copy[String]).apply(ProgramCall(5)).map(_.output).toOption.get
    val effectfulRight =
      (C.copy[Int] >>> C.tensor(effectful, effectful)).apply(ProgramCall(5)).map(_.output).toOption.get
    assertEquals(effectfulLeft._1, effectfulLeft._2)
    assertNotEquals(effectfulRight._1, effectfulRight._2)
  }
