package dspy4s.programs.para

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.DynamicPrediction
import dspy4s.core.contracts.IsEq
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.programs.contracts.Module
import dspy4s.programs.contracts.TypedCall
import dspy4s.typed.Prediction
import munit.FunSuite
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger

/** Executes the CD-category laws of [[CDCategory]] over the program instance ([[cdProgram]]) under
  * output-observational equality: tensor interchange + identity, swap involution, copy cocommutativity, and
  * discard-naturality (which, holding under this observation, is what makes the category Markov here). Then the
  * determinism classifier [[CDCategory.copyNaturality]] BOTH ways — it holds for a deterministic morphism and
  * FAILS for an effect-observing one (the non-degeneracy witness that the category is properly Markov, not
  * cartesian). Deferred (need unitors/associators, no consumer): comonoid counit / coassociativity, pentagon /
  * triangle coherence. */
class CDCategoryLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private given RuntimeContextProvider: RuntimeContext = RuntimeEnvironment.current

  private val C = summon[CDCategory[ModuleHom]]

  /** A pure (deterministic) program stub: maps the input via `f`. */
  private final case class Fn[I, O](f: I => O) extends Module[TypedCall[I], Prediction[O]]:
    override val moduleName: String = "fn"
    override protected def callInputs(call: TypedCall[I]): DynamicValue.Record       = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[I]): Boolean             = call.traceEnabled
    override protected def tracePayload(p: Prediction[O]): DynamicValue.Record       = p.raw.values
    override protected def forward(call: TypedCall[I])(using RuntimeContext): Either[DspyError, Prediction[O]] =
      Right(Prediction(f(call.input), DynamicPrediction.empty))

  /** An effect-observing (nondeterministic) stub: its output embeds a call counter, so two runs on the same
    * input differ — the stand-in for an LLM call. */
  private final class Counting extends Module[TypedCall[Int], Prediction[String]]:
    private val n: AtomicInteger = AtomicInteger(0)
    override val moduleName: String = "counting"
    override protected def callInputs(call: TypedCall[Int]): DynamicValue.Record       = DynamicValue.Record.empty
    override protected def callTraceEnabled(call: TypedCall[Int]): Boolean             = call.traceEnabled
    override protected def tracePayload(p: Prediction[String]): DynamicValue.Record    = p.raw.values
    override protected def forward(call: TypedCall[Int])(using RuntimeContext): Either[DspyError, Prediction[String]] =
      Right(Prediction(s"${call.input}-${n.incrementAndGet()}", DynamicPrediction.empty))

  /** Execute a stated law under output-observational equality: apply both sides to `input`, compare outputs. */
  private def obs[A, B](eq: IsEq[ModuleHom[A, B]], input: A): Unit =
    assertEquals(eq.lhs.apply(TypedCall(input)).map(_.output), eq.rhs.apply(TypedCall(input)).map(_.output))

  test("tensor is a bifunctor (interchange)") {
    val f1 = Fn[Int, Int](_ + 1)
    val g1 = Fn[String, String](_ + "!")
    val f2 = Fn[Int, String](_.toString)
    val g2 = Fn[String, Int](_.length)
    obs(C.tensorInterchange(f1, g1, f2, g2), (5, "ab"))
  }

  test("tensor preserves identities") {
    obs(C.tensorIdentity[Int, String], (5, "x"))
  }

  test("swap is involutive") {
    obs(C.swapInvolution[Int, String], (5, "x"))
  }

  test("copy is cocommutative: copy >>> swap = copy") {
    obs(C.cocommutativity[Int], 7)
  }

  test("discard is natural under output-observational equality: f >>> discard = discard") {
    obs(C.discardNatural(Fn[Int, String](_.toString)), 5)
  }

  test("copy is natural for a DETERMINISTIC morphism (classifier holds)") {
    obs(C.copyNaturality(Fn[Int, String](i => s"v$i")), 5)
  }

  test("copy is NOT natural for an effect-observing morphism (non-degeneracy witness)") {
    val eq  = C.copyNaturality[Int, String](new Counting)
    val lhs = eq.lhs.apply(TypedCall(5)).map(_.output).toOption.get // h once, duplicated: (x, x)
    val rhs = eq.rhs.apply(TypedCall(5)).map(_.output).toOption.get // h twice: (x, y), x != y
    assertEquals(lhs._1, lhs._2)      // run-once-then-copy: components identical
    assertNotEquals(rhs._1, rhs._2)   // copy-then-run-twice: components differ (h ran twice)
    assertNotEquals(lhs, rhs)         // so copy-naturality fails ⟹ the category is properly Markov, not cartesian
  }
