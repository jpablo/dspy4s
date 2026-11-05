package dspy4s.lm.runtime

import dspy4s.lm.contracts.{CacheKey, LmCache, LmResponse}

/** A cache with two levels. `get` reads `memory` first, then `disk`, and a hit of `disk` goes into `memory` too. `put`
  * writes the two levels.
  */
final case class CompositeLmCache(memory: LmCache, disk: LmCache) extends LmCache:
  override def get(key: CacheKey): Option[LmResponse] =
    memory.get(key).orElse {
      disk.get(key).map { response =>
        memory.put(key, response)
        response
      }
    }

  override def put(key: CacheKey, response: LmResponse): Unit =
    memory.put(key, response)
    disk.put(key, response)
