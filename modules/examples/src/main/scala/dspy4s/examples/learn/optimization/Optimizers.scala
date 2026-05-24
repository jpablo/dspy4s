/**
 * DSPy Optimizers (formerly Teleprompters)
 *
 * Source:   docs/docs/learn/optimization/optimizers.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/optimization/optimizers.md
 * Status:   scaffold (3 python snippets — TODO translate)
 */
package dspy4s.examples.learn.optimization

object Optimizers {

  // ── Snippet 1 (lines 95–104) ────────────────────
  // | from dspy.teleprompt import BootstrapFewShotWithRandomSearch
  // |
  // | # Set up the optimizer: we want to "bootstrap" (i.e., self-generate) 8-shot examples of your program's steps.
  // | # The optimizer will repeat this 10 times (plus some initial attempts) before selecting its best attempt on the devset.
  // | config = dict(max_bootstrapped_demos=4, max_labeled_demos=4, num_candidate_programs=10, num_threads=4)
  // |
  // | teleprompter = BootstrapFewShotWithRandomSearch(metric=YOUR_METRIC_HERE, **config)
  // | optimized_program = teleprompter.compile(YOUR_PROGRAM_HERE, trainset=YOUR_TRAINSET_HERE)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 213–215) ────────────────────
  // | optimized_program.save(YOUR_SAVE_PATH)
  // TODO translate snippet 2

  // ── Snippet 3 (lines 222–225) ────────────────────
  // | loaded_program = YOUR_PROGRAM_CLASS()
  // | loaded_program.load(path=YOUR_SAVE_PATH)
  // TODO translate snippet 3
}
