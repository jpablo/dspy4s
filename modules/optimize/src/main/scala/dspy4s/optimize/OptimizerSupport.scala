package dspy4s.optimize

/** Deterministic helpers shared by functional optimizers. */
private[optimize] object OptimizerSupport:

  /** Map an optimizer seed to a stable base rollout ID in `[0, 1024)`. */
  def seedBase(seed: Long): Int = math.floorMod(seed.toInt, 1024)
