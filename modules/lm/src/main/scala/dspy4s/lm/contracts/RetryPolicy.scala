package dspy4s.lm.contracts

import dspy4s.core.contracts.DspyError

/** Decides if a failed LM call gets a new attempt, and how long to wait before that attempt.
  *
  * `attempt` is the number of retries that ran before this failure: it is 0 for the failure of the first call. The
  * caller asks [[shouldRetry]] first, and asks [[delayBeforeNextAttemptMillis]] only when the answer is `true`.
  */
trait RetryPolicy:
  /** True when the call must run again after `error`. A policy answers `false` for an error that the next attempt
    * cannot correct (for example an authentication failure), and after the last permitted retry.
    */
  def shouldRetry(attempt: Int, error: DspyError): Boolean

  /** The time to wait before the next attempt, in milliseconds. The caller does not wait when the value is 0 or less.
    * `error` is the failure of the attempt that ended, so a policy can obey the delay that the provider asked for
    * ([[dspy4s.core.contracts.LmError.retryAfter]]).
    */
  def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long = 0L
