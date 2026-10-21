package dspy4s.lm.runtime

import dspy4s.lm.contracts.{LmCache, LmRequest, LmResponse}

import java.util.LinkedHashMap

/** An [[LmCache]] in memory, with the least recently used entry removed above `maxEntries`.
  *
  * The key is [[RequestHash.forRequestChecked]] of the request. A request with no key is a miss, and `put` stores
  * nothing for it and does not throw (upstream `Cache.get` and `Cache.put` go on with no cache,
  * `dspy/clients/cache.py`).
  */
final class InMemoryLmCache(maxEntries: CacheCapacity = CacheCapacity(1024)) extends LmCache:

  private val store = new LinkedHashMap[String, LmResponse](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, LmResponse]): Boolean =
      this.size() > maxEntries

  override def get(request: LmRequest): Option[LmResponse] =
    RequestHash.forRequestChecked(request).toOption.flatMap { key =>
      this.synchronized {
        Option(store.get(key)).map(_.copy(cacheHit = true))
      }
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    RequestHash.forRequestChecked(request).foreach { key =>
      this.synchronized {
        store.put(key, response.copy(cacheHit = false))
        ()
      }
    }

  def clear(): Unit =
    this.synchronized {
      store.clear()
    }

  def size: Int =
    this.synchronized {
      store.size()
    }
