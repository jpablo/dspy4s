package dspy

import munit.FunSuite
import dspy.clients.cache.Cache

class CacheSuite extends FunSuite {
  test("Cache stores and expires values by TTL") {
    val c = new Cache[String](ttlMillis = 50)
    c.put("k", "v")
    assertEquals(c.get("k"), Some("v"))
    Thread.sleep(60)
    c.cleanup()
    assertEquals(c.get("k"), None)
  }
}
