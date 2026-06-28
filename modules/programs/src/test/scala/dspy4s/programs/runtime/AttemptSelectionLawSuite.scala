package dspy4s.programs.runtime

import dspy4s.core.contracts.AdapterRef
import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.contracts.RuntimeError
import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import java.util.concurrent.atomic.AtomicInteger

/** Laws for the shared best-of-`n` reducer ([[AttemptSelection.bestOf]]), the `bestOf` operation of the
  * program-composition algebra (`docs/refactor/algebra-2-program-composition.md`). The end-to-end module
  * behavior is covered by TypedBestOfNSuite / RefinePerModuleAdviceSuite; this pins the primitive with pure
  * synthetic attempts (no LLM), the honest way to state the distributional laws. */
class AttemptSelectionLawSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  /** Run `n` attempts returning the scripted reward at each index; reward is the value itself. */
  private def scripted(rewards: Vector[Double], threshold: Double, failCount: Option[Int] = None) =
    AttemptSelection.bestOf[Double](rewards.size, threshold, failCount, "law")(
      runAttempt = idx => Right(rewards(idx)),
      reward     = d => Right(d)
    )

  // ── selectBest(p, 1, _, _) = p : a single attempt is returned verbatim, even below threshold ──────────────
  test("n = 1 is identity (returns the only attempt, regardless of threshold)") {
    val calls = AtomicInteger(0)
    val result = AttemptSelection.bestOf[Double](1, threshold = 0.9, None, "law")(
      runAttempt = _ => { calls.incrementAndGet(); Right(0.2) }, // below threshold
      reward     = d => Right(d)
    )
    assertEquals(result, Right(0.2))
    assertEquals(calls.get(), 1)
  }

  // ── monotonicity: the result reward is ≥ every successful attempt's reward (argmax) ───────────────────────
  test("returns the argmax-reward attempt (monotonicity)") {
    val rewards = Vector(0.1, 0.9, 0.5)
    val result  = scripted(rewards, threshold = 2.0) // unreachable threshold -> all attempts run
    assertEquals(result, Right(0.9))
    assert(rewards.forall(r => result.toOption.exists(_ >= r)))
  }

  // ── selectBest is permutation-invariant (independent samples, distinct rewards avoid tie-break) ───────────
  test("no-feedback selection is invariant under attempt permutation") {
    val base = Vector(0.1, 0.9, 0.5)
    val perms = Vector(
      Vector(0.1, 0.9, 0.5),
      Vector(0.9, 0.5, 0.1),
      Vector(0.5, 0.1, 0.9)
    )
    perms.foreach(p => assertEquals(scripted(p, threshold = 2.0), scripted(base, threshold = 2.0)))
  }

  // ── threshold short-circuit: the first attempt at/above threshold ends the loop ───────────────────────────
  test("short-circuits at the first attempt that reaches the threshold") {
    val calls = AtomicInteger(0)
    val result = AttemptSelection.bestOf[Double](3, threshold = 0.9, None, "law")(
      runAttempt = idx => { calls.incrementAndGet(); Right(Vector(0.95, 0.1, 0.99)(idx)) },
      reward     = d => Right(d)
    )
    assertEquals(result, Right(0.95))
    assertEquals(calls.get(), 1) // attempts 2 and 3 never ran
  }

  // ── failCount budget: tolerate exactly failCount failures, then surface the next error ────────────────────
  test("surfaces the last error after exhausting the default fail budget") {
    val result = AttemptSelection.bestOf[Double](3, threshold = 0.0, None, "law")(
      runAttempt = idx => Left(RuntimeError("law", s"f$idx")),
      reward     = d => Right(d)
    )
    assertEquals(result.left.toOption.map(_.message), Some("f2"))
  }

  test("a custom fail budget aborts earlier and keeps no best when all attempts failed") {
    val calls = AtomicInteger(0)
    val result = AttemptSelection.bestOf[Double](3, threshold = 0.0, failCount = Some(1), "law")(
      runAttempt = idx => { calls.incrementAndGet(); Left(RuntimeError("law", s"f$idx")) },
      reward     = d => Right(d)
    )
    assertEquals(result.left.toOption.map(_.message), Some("f1"))
    assertEquals(calls.get(), 2) // tolerated 1 failure, aborted on the 2nd
  }

  test("keeps a sub-threshold best when a later failure stays within budget") {
    val result = AttemptSelection.bestOf[Double](4, threshold = 1.0, failCount = Some(1), "law")(
      runAttempt = idx => idx match
        case 3 => Left(RuntimeError("law", "boom"))
        case other => Right(Vector(0.1, 0.7, 0.3)(other)),
      reward = d => Right(d)
    )
    assertEquals(result, Right(0.7)) // best survives the in-budget failure
  }

  // ── feedback makes the stream sequential: the carried adapter reaches the NEXT attempt and changes the
  //    outcome, so feedback is NOT permutation-invariant (the algebraic distinction from selectBest) ─────────
  test("feedback carries an adapter into the next attempt (order-dependent)") {
    val feedbackFired = AtomicInteger(0)

    // The attempt's value is whether the marker adapter is in scope; reward rewards its presence.
    def runWithFeedback(feedback: Option[(Boolean, Vector[dspy4s.core.contracts.TraceEntry], Double) => Either[DspyError, Option[AdapterRef]]]) =
      AttemptSelection.bestOf[Boolean](2, threshold = 1.0, None, "law")(
        runAttempt = _ => Right(summon[RuntimeContext].adapter.contains(AttemptSelectionLawSuite.MarkerAdapter)),
        reward     = sawMarker => Right(if sawMarker then 1.0 else 0.2),
        feedback   = feedback
      )

    // With feedback: attempt 1 is sub-threshold (no marker), the hook installs the marker, attempt 2 sees it.
    val withFeedback = runWithFeedback(Some { (_, _, _) =>
      feedbackFired.incrementAndGet()
      Right(Some(AttemptSelectionLawSuite.MarkerAdapter))
    })
    assertEquals(withFeedback, Right(true))
    assertEquals(feedbackFired.get(), 1)

    // Without feedback (independent samples): no attempt ever sees the marker -> different outcome.
    assertEquals(runWithFeedback(None), Right(false))
  }

object AttemptSelectionLawSuite:
  /** A bare marker adapter used only to observe that a feedback-carried adapter reaches the next attempt. */
  private object MarkerAdapter extends AdapterRef
