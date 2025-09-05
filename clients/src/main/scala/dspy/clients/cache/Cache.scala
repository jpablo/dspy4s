package dspy.clients.cache

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

final class Cache[V](ttlMillis: Long) {
  private case class Entry(value: V, expiresAt: Long)
  private val store = new ConcurrentHashMap[String, Entry]()

  private def now(): Long = System.currentTimeMillis()

  def get(key: String): Option[V] = Option(store.get(key)).filter(_.expiresAt > now()).map(_.value)

  def put(key: String, value: V): Unit = {
    store.put(key, Entry(value, now() + ttlMillis))
  }

  def size(): Int = store.size()

  def cleanup(): Unit = {
    val t = now()
    store.entrySet().asScala.foreach { e =>
      if (e.getValue.expiresAt <= t) store.remove(e.getKey)
    }
  }
}
