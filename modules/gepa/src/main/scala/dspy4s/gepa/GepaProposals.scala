package dspy4s.gepa

/** The (parent, minibatch) tasks of one reflective iteration (gepa's `sampling_strategy`). Each task draws a parent
  * with the candidate selector and then a minibatch, in task order. Each task can give one proposal.
  *
  * A task after the first one in an iteration takes the next window of the minibatch sampler
  * ([[MinibatchSampler.sample]]). With `n × minibatchSize` larger than the trainset, the windows wrap, so some tasks
  * get the same minibatch.
  */
enum ProposalSampling derives CanEqual:
  /** One parent and one minibatch (gepa's `SingleMutationSampling`). The default, as upstream. */
  case Single

  /** One parent draw, and `n` minibatches for that parent (gepa's `SameParentSampling`). */
  case SameParent(n: ProposalCount)

  /** `n` parent draws, each with its own minibatch (gepa's `IndependentSampling`). */
  case Independent(n: ProposalCount)

  /** `parents` parent draws, and `mutations` minibatches for each of them (gepa's `PxNSampling`). */
  case PxN(parents: ProposalCount, mutations: ProposalCount)

  /** The number of parent draws of one iteration, and the number of tasks for each draw. Each task of one draw has the
    * parent of that draw.
    */
  private[gepa] def shape: (Int, Int) =
    this match
      case Single                  => (1, 1)
      case SameParent(n)           => (1, n)
      case Independent(n)          => (n, 1)
      case PxN(parents, mutations) => (parents, mutations)

  /** The number of tasks of one iteration. */
  def taskCount: Int =
    val (draws, tasksForEachDraw) = shape
    Math.multiplyExact(draws, tasksForEachDraw)

/** When an evaluated proposal passes (gepa's `acceptance_criterion`). The scores are those of the parent and of the
  * child on the minibatch of the task.
  */
enum ProposalAcceptance derives CanEqual:
  /** The sum of the child is larger than the sum of the parent (gepa's `StrictImprovementAcceptance`). The default. */
  case StrictImprovement

  /** The sum of the child is larger than or equal to the sum of the parent (gepa's `ImprovementOrEqualAcceptance`). */
  case ImprovementOrEqual

  def accepts(proposal: Proposal): Boolean =
    this match
      case StrictImprovement  => proposal.scoresAfter.sum > proposal.scoresBefore.sum
      case ImprovementOrEqual => proposal.scoresAfter.sum >= proposal.scoresBefore.sum

/** Which of the proposals of one iteration go to the validation set and to the pool (gepa's `selection_strategy`). Each
  * strategy takes only proposals that pass the acceptance criterion.
  */
enum ProposalSelection derives CanEqual:
  /** Each proposal that passes, in task order (gepa's `AllImprovements`). The default, as upstream. */
  case AllImprovements

  /** The one proposal with the largest gain. The first one wins a tie (gepa's `BestImprovement`). */
  case BestImprovement

  /** The `k` proposals with the largest gains, largest first. Proposals with equal gains keep their task order (gepa's
    * `TopKImprovements`).
    */
  case TopK(k: ProposalCount)

  /** The selected proposals of `proposals` (in task order), in the order in which the engine adds them to the pool. The
    * result can hold two proposals with equal candidates; the engine keeps the first of them.
    */
  def select(proposals: Vector[Proposal], acceptance: ProposalAcceptance): Vector[Proposal] =
    val passing = proposals.filter(acceptance.accepts)
    this match
      case AllImprovements => passing
      case BestImprovement =>
        // gepa starts with a best gain of minus infinity, and only a larger gain replaces the best proposal.
        passing.foldLeft((Option.empty[Proposal], Double.NegativeInfinity)) { case ((best, bestGain), proposal) =>
          if proposal.gain > bestGain then (Some(proposal), proposal.gain) else (best, bestGain)
        }._1.toVector
      // The sort is stable, as the sort of gepa is.
      case TopK(k) => passing.sortBy(_.gain)(using Ordering[Double].reverse).take(k)

  /** The largest number of proposals that [[select]] gives for `taskCount` proposals. */
  def limit(taskCount: Int): Int =
    this match
      case AllImprovements => taskCount
      case BestImprovement => math.min(1, taskCount)
      case TopK(k)         => math.min(k, taskCount)

/** One evaluated proposal of a reflective iteration (gepa's `CandidateProposal`): the child candidate, the pool index
  * of its parent, and the scores of the parent and of the child on the minibatch of the task.
  */
final case class Proposal(
    candidate   : Candidate,
    parent      : Int,
    scoresBefore: Vector[Double],
    scoresAfter : Vector[Double]
):
  /** The sum of the child minus the sum of the parent. */
  def gain: Double = scoresAfter.sum - scoresBefore.sum
