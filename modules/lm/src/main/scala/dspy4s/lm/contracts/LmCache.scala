package dspy4s.lm.contracts

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** The key of one entry of an [[LmCache]]: the SHA-256 hash of a text, as lowercase hex text. The hex text is safe as a
  * file name. [[dspy4s.lm.runtime.RequestHash]] gives the key of a request.
  */
final case class CacheKey private (hex: String) derives CanEqual

object CacheKey:

  /** The key of `text`: the SHA-256 hash of its UTF-8 bytes. */
  def of(text: String): CacheKey =
    val digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
    CacheKey(HexFormat.of().formatHex(digest))

/** A store of LM responses, keyed by a [[CacheKey]]. `get` gives the response that `put` stored, with no change.
  * [[dspy4s.lm.runtime.ManagedLanguageModel]] computes the key, marks a hit with `cacheHit = true`, and removes the
  * usage of a hit after it computes the cost estimate of the hit from that usage (upstream keeps the cost of the cached
  * result). Thus a hit bills no tokens.
  */
trait LmCache:

  /** The response that `put` stored for `key`. */
  def get(key: CacheKey): Option[LmResponse]

  /** Store `response` for `key`. */
  def put(key: CacheKey, response: LmResponse): Unit

/** A cache that stores nothing: each `get` is a miss. */
object NoopLmCache extends LmCache:
  override def get(key: CacheKey): Option[LmResponse]         = None
  override def put(key: CacheKey, response: LmResponse): Unit = ()
