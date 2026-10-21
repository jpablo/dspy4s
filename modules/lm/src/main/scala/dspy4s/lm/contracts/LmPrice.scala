package dspy4s.lm.contracts

import dspy4s.core.contracts.LmUsage
import dspy4s.core.contracts.TokenCategory

/** The price of the tokens of one model, in US dollars for one million tokens (decision L10, PORT_GAPS G-27). The user
  * declares it: the port has no price catalog. Upstream reads the prices from the model catalog of the library
  * (`dspy/clients/costs.py`).
  *
  * @param inputPerMillion
  *   the price of an input token that is not cached
  * @param outputPerMillion
  *   the price of an output token that is not a reasoning token
  * @param cachedInputPerMillion
  *   the price of a cached input token. `None` gives the price of an input token, as upstream does when a model has no
  *   rate for cached tokens.
  * @param reasoningPerMillion
  *   the price of a reasoning token. `None` gives the price of an output token, as upstream.
  */
final case class LmPrice(
    inputPerMillion      : Double,
    outputPerMillion     : Double,
    cachedInputPerMillion: Option[Double] = None,
    reasoningPerMillion  : Option[Double] = None
) derives CanEqual:
  private def valid(price: Double): Boolean = price >= 0.0 && !price.isInfinite && !price.isNaN

  require(
    (Vector(inputPerMillion, outputPerMillion) ++ cachedInputPerMillion ++ reasoningPerMillion).forall(valid),
    s"A price must be a finite number that is not negative: $this"
  )

/** The cost estimate of one LM call. The estimate is advisory: an unknown price is not zero, and an estimate never
  * makes a call fail (upstream `estimate_cost`).
  */
enum LmCost derives CanEqual:

  /** The estimated cost in US dollars, from the declared price of `model`. */
  case Estimate(usd: Double, model: String)

  /** No estimate, for the stated reason. */
  case Unknown(reason: String)

object LmCost:

  /** The reason when the model has no declared price. */
  val NoPrice: String = "no price is declared for the model"

  /** The reason when the response has no usage. */
  val NoUsage: String = "input or output usage was not reported"

  /** The reason when a cached count is larger than the input count, or a reasoning count is larger than the output
    * count (upstream "usage dimensions cannot be separated safely").
    */
  val Inseparable: String = "usage dimensions cannot be separated safely"

  /** The cost of a call of `model` with `usage`, at `price`. The rule of upstream `_estimate_cost` for an OpenAI-style
    * usage, where the cached tokens are a part of the prompt tokens and the reasoning tokens are a part of the
    * completion tokens:
    *   - the input part is the prompt tokens minus the cached tokens, at the input price;
    *   - the cached part is the cached tokens, at the cached price;
    *   - the output part is the completion tokens minus the reasoning tokens, at the output price;
    *   - the reasoning part is the reasoning tokens, at the reasoning price.
    *
    * The cached tokens and the reasoning tokens are the [[dspy4s.core.contracts.TokenCategory.Cached]] and
    * [[dspy4s.core.contracts.TokenCategory.Reasoning]] extras of the usage. The result is [[Unknown]] with [[NoPrice]],
    * [[NoUsage]], or [[Inseparable]] when the estimate is not possible.
    */
  def of(model: String, price: Option[LmPrice], usage: Option[LmUsage]): LmCost =
    (price, usage) match
      case (None, _)                  => Unknown(NoPrice)
      case (_, None)                  => Unknown(NoUsage)
      case (Some(rates), Some(count)) =>
        val cached    = count.extras.getOrElse(TokenCategory.Cached, 0L)
        val reasoning = count.extras.getOrElse(TokenCategory.Reasoning, 0L)
        val inputs    = count.promptTokens - cached
        val outputs   = count.completionTokens - reasoning
        if Vector(cached, reasoning, inputs, outputs).exists(_ < 0L) then Unknown(Inseparable)
        else
          val perMillion = inputs * rates.inputPerMillion +
            cached * rates.cachedInputPerMillion.getOrElse(rates.inputPerMillion) +
            outputs * rates.outputPerMillion +
            reasoning * rates.reasoningPerMillion.getOrElse(rates.outputPerMillion)
          Estimate(perMillion / 1_000_000.0, model)
