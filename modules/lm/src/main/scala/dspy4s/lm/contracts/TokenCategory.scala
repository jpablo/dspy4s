package dspy4s.lm.contracts

/** A provider-specific token-usage category carried in [[LmUsage.extras]] — the counters beyond the universal
  * prompt/completion/total tokens (which are typed fields on `LmUsage`). Known categories are typed cases; `Other`
  * is the forward-compatible escape for a counter a provider reports that isn't modeled yet, so no usage data is
  * silently dropped.
  *
  * `wireName` is the provider/JSON key for the category; it is the serialization form used for cache persistence
  * and for the string-keyed `Prediction.lmUsage` surface, and round-trips through [[TokenCategory.fromWire]]. */
enum TokenCategory derives CanEqual:
  case Cached
  case Audio
  case Reasoning
  case AcceptedPrediction
  case RejectedPrediction
  case Other(name: String)

  def wireName: String = this match
    case Cached             => "cached_tokens"
    case Audio              => "audio_tokens"
    case Reasoning          => "reasoning_tokens"
    case AcceptedPrediction => "accepted_prediction_tokens"
    case RejectedPrediction => "rejected_prediction_tokens"
    case Other(name)        => name

object TokenCategory:
  private val known: Vector[TokenCategory] = Vector(Cached, Audio, Reasoning, AcceptedPrediction, RejectedPrediction)
  private val byWireName: Map[String, TokenCategory] = known.map(category => category.wireName -> category).toMap

  /** Recognize a provider/JSON key as a known category, or wrap it as `Other` (never dropped). */
  def fromWire(name: String): TokenCategory = byWireName.getOrElse(name, Other(name))
