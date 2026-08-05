package dspy4s.lm.runtime

import dspy4s.lm.contracts.{LmCache, LmRequest, LmResponse}

import java.util.LinkedHashMap

final class InMemoryLmCache(maxEntries: CacheCapacity = CacheCapacity(1024)) extends LmCache:

  private val store = new LinkedHashMap[String, LmResponse](16, 0.75f, true):
    override def removeEldestEntry(eldest: java.util.Map.Entry[String, LmResponse]): Boolean =
      this.size() > maxEntries

  override def get(request: LmRequest): Option[LmResponse] =
    val key = RequestHash.forRequest(request)
    this.synchronized {
      Option(store.get(key)).map(_.copy(cacheHit = true, usage = None))
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    val key = RequestHash.forRequest(request)
    this.synchronized {
      store.put(key, response.copy(cacheHit = false))
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
