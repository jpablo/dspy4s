package dspy4s.core.contracts

/** A provider-specific token-usage category carried in [[LmUsage.extras]]. Universal prompt/completion/total counts are
  * typed fields on [[LmUsage]]; this enum covers the provider-specific remainder without falling back to magic strings.
  * [[Other]] is the forward-compatible escape for counters not yet modeled by dspy4s.
  */
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
  private val known: Vector[TokenCategory] =
    Vector(Cached, Audio, Reasoning, AcceptedPrediction, RejectedPrediction)
  private val byWireName: Map[String, TokenCategory] = known.map(category => category.wireName -> category).toMap

  /** Recognize a provider/JSON key as a known category, or preserve it as [[Other]]. */
  def fromWire(name: String): TokenCategory = byWireName.getOrElse(name, Other(name))

/** Typed token accounting for one LM call. Usage is core execution metadata: both
  * [[dspy4s.core.data.DynamicPrediction]] and the LM
  * boundary carry this exact value, so no string-map conversion separates the two layers.
  *
  * Pointwise addition forms a commutative monoid: universal counters add and provider-specific counters combine by
  * category. [[empty]] is the all-zero usage value.
  */
final case class LmUsage(
    totalTokens: Long = 0L,
    promptTokens: Long = 0L,
    completionTokens: Long = 0L,
    extras: Map[TokenCategory, Long] = Map.empty
) derives CanEqual:
  def combine(that: LmUsage): LmUsage =
    val combinedExtras = that.extras.foldLeft(extras) { case (acc, (category, value)) =>
      acc.updated(category, acc.getOrElse(category, 0L) + value)
    }
    LmUsage(
      totalTokens = totalTokens + that.totalTokens,
      promptTokens = promptTokens + that.promptTokens,
      completionTokens = completionTokens + that.completionTokens,
      extras = combinedExtras
    )

object LmUsage:
  val empty: LmUsage = LmUsage()

  given monoid: Monoid[LmUsage] with
    def empty: LmUsage                                            = LmUsage.empty
    extension (a: LmUsage) infix def combine(b: LmUsage): LmUsage = a.combine(b)
