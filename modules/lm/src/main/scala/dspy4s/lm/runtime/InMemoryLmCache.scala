package dspy4s.lm.runtime

import dspy4s.lm.contracts.{CacheKey, LmCache, LmResponse}

import java.util.LinkedHashMap

/** An [[LmCache]] in memory, with the least recently used entry removed above `maxEntries`. The default is upstream
  * `memory_max_entries` (`dspy/clients/__init__.py`), so a long run such as a ReAnchor pass keeps its own entries.
  */
final class InMemoryLmCache(maxEntries: CacheCapacity = CacheCapacity(1000000)) extends LmCache:

  private val store = new LinkedHashMap[CacheKey, LmResponse](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[CacheKey, LmResponse]): Boolean =
      this.size() > maxEntries

  override def get(key: CacheKey): Option[LmResponse] =
    this.synchronized {
      Option(store.get(key))
    }

  override def put(key: CacheKey, response: LmResponse): Unit =
    this.synchronized {
      store.put(key, response)
      ()
    }

  def clear(): Unit =
    this.synchronized {
      store.clear()
    }

  def size: Int =
    this.synchronized {
      store.size()
    }
