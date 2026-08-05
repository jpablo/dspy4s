package dspy4s.adapters.contracts

import dspy4s.core.contracts.{DspyError, ParseError}

trait AdapterFallbackPolicy:
  def fallbackFor(error: DspyError, attemptedAdapter: String): Option[String]

object AdapterErrors:
  /** A parse failure for a missing output field. `raw` is the raw model response that couldn't be parsed — carry it so
    * failure-trace capture can show what the model actually produced.
    */
  def missingField(fieldName: String, raw: Option[String] = None): DspyError =
    ParseError(component = "adapter", message = s"Missing required output field: $fieldName", raw = raw)
