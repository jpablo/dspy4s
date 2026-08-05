package dspy4s.lm.contracts

import dspy4s.core.contracts.DspyError

trait RetryPolicy:
  def shouldRetry(attempt                 : Int, error: DspyError): Boolean
  def delayBeforeNextAttemptMillis(attempt: Int, error: DspyError): Long = 0L
