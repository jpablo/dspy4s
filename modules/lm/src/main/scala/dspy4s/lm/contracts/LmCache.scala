package dspy4s.lm.contracts

/** A store of LM responses, keyed by the request. */
trait LmCache:

  /** The stored response of `request`, with `cacheHit = true` and the usage of the call that the store keeps. A hit
    * bills no tokens: [[dspy4s.lm.runtime.ManagedLanguageModel]] removes the usage of a hit after it computes the cost
    * estimate of the hit from that usage (upstream keeps the cost of the cached result).
    */
  def get(request: LmRequest): Option[LmResponse]
  def put(request: LmRequest, response: LmResponse): Unit
