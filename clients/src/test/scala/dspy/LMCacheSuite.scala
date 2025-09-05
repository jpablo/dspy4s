package dspy

import munit.FunSuite
import dspy.clients._
import dspy.clients.cache.{Cache, LMWithCache}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class LMCacheSuite extends FunSuite {
  class CountingLM extends LM {
    @volatile var calls = 0
    def complete(prompt: Prompt, params: Map[String, String])(implicit ec: ExecutionContext): Future[Completion] = {
      calls += 1
      Future.successful(Completion("{\"x\":1}", ujson.Obj()))
    }
  }

  test("LMWithCache returns cached completion for same prompt/params") {
    val base  = new CountingLM
    val cache = new Cache[Completion](ttlMillis = 1000)
    val lm    = new LMWithCache(base, cache)
    val p     = Prompt("hi")
    val a     = lm.complete(p)
    val b     = lm.complete(p)
    for {
      _ <- a
      _ <- b
    } yield assertEquals(base.calls, 1)
  }
}
