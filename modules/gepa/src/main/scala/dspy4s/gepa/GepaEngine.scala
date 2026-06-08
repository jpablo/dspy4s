package dspy4s.gepa

import dspy4s.core.contracts.Example
import dspy4s.core.contracts.RuntimeContext
import dspy4s.lm.contracts.LanguageModel

import scala.util.Random

/** Configuration for a GEPA run. v0 covers the reflective-mutation loop; merge / multi-objective frontiers /
  * eval cache / resume are deferred (PORT_GAPS G-12). */
final case class GepaConfig(
    /** Budget: total metric (evaluation) calls before stopping. Reflection-LM calls do NOT count, matching gepa. */
    maxMetricCalls: Int,
    reflectionMinibatchSize: Int = 3,
    candidateSelector: CandidateSelector = CandidateSelector.Pareto,
    skipPerfectScore: Boolean = true,
    perfectScore: Double = 1.0,
    seed: Long = 0L
)

/** The outcome of a GEPA run: the best candidate (by mean validation score) and the program it yields. */
final case class GepaResult[P](
    bestCandidate: Candidate,
    bestProgram: P,
    bestScore: Double,
    numCandidates: Int,
    totalMetricCalls: Int
)

/** The GEPA engine (v0): genetic-Pareto reflective prompt evolution. Each iteration selects a parent candidate from
  * the Pareto frontier, reflects on its failures over a train minibatch to propose a better instruction, accepts
  * the mutation iff it improves the minibatch, then full-evaluates the accepted candidate on the validation set and
  * folds it into the Pareto frontier — until the metric-call budget is spent. A faithful port of the external
  * `gepa` engine's reflective-mutation path (no merge yet). See PORT_GAPS G-12. */
final class GepaEngine[P](
    adapter: GepaAdapter[P],
    reflectionLm: LanguageModel,
    config: GepaConfig
):

  def optimize(seedCandidate: Candidate, trainset: Vector[Example], valset: Vector[Example])(using
      RuntimeContext
  ): GepaResult[P] =
    val rng = new Random(config.seed)

    // Seed: full-evaluate the starting candidate on the validation set.
    var state = GepaState.seed(seedCandidate, fullEval(seedCandidate, valset), metricCalls = valset.size)

    while state.totalMetricCalls < config.maxMetricCalls do
      state = iterate(state, trainset, valset, rng)

    val best = state.bestIndex
    GepaResult(
      bestCandidate = state.candidates(best),
      bestProgram = adapter.applyCandidate(state.candidates(best)),
      bestScore = state.aggregateScore(best),
      numCandidates = state.candidates.size,
      totalMetricCalls = state.totalMetricCalls
    )

  /** One reflective-mutation iteration: returns the new state (with the iteration's metric calls accrued, and the
    * accepted candidate appended on success). */
  private def iterate(state: GepaState, trainset: Vector[Example], valset: Vector[Example], rng: Random)(using
      RuntimeContext
  ): GepaState =
    val parentIdx = config.candidateSelector.select(state, rng)
    val parent    = state.candidates(parentIdx)
    val minibatch = sampleMinibatch(trainset, rng)

    val parentEval = adapter.evaluate(minibatch, parent, captureTraces = true)
    var calls      = minibatch.size

    // Nothing to learn from a perfect minibatch.
    if config.skipPerfectScore && parentEval.scores.nonEmpty && parentEval.scores.forall(_ >= config.perfectScore) then
      return state.copy(totalMetricCalls = state.totalMetricCalls + calls)

    // v0: update ALL components (single-predictor → the one component; round-robin is a v1 refinement that needs P-c).
    val components  = parent.keys.toVector.sorted
    val reflective  = adapter.makeReflectiveDataset(parent, parentEval, components)
    val newCandidate = components.foldLeft(parent) { (cand, component) =>
      InstructionProposer.propose(parent.getOrElse(component, ""), reflective.getOrElse(component, Vector.empty), reflectionLm) match
        case Right(text) => cand.updated(component, text)
        case Left(_)     => cand // reflection failed for this component — keep its instruction
    }

    // Re-evaluate on the SAME minibatch; accept only on strict minibatch improvement.
    val newEval = adapter.evaluate(minibatch, newCandidate, captureTraces = false)
    calls += minibatch.size

    if newEval.scores.sum > parentEval.scores.sum then
      val newSubscores = fullEval(newCandidate, valset)
      calls += valset.size
      state.add(newCandidate, newSubscores, parent = Some(parentIdx), metricCalls = calls)
    else
      state.copy(totalMetricCalls = state.totalMetricCalls + calls)

  private def sampleMinibatch(trainset: Vector[Example], rng: Random): Vector[Example] =
    // v0: a random minibatch each iteration (gepa's epoch-shuffled-with-padding is a refinement).
    rng.shuffle(trainset.indices.toVector).take(config.reflectionMinibatchSize).map(trainset)

  private def fullEval(candidate: Candidate, valset: Vector[Example])(using RuntimeContext): Vector[Double] =
    adapter.evaluate(valset, candidate, captureTraces = false).scores
