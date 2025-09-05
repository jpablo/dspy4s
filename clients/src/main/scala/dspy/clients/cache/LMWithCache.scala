package dspy.clients.cache

import dspy.clients.{Completion, LM, Prompt}

import java.security.MessageDigest
import scala.concurrent.{ExecutionContext, Future}

final class LMWithCache(underlying: LM, cache: Cache[Completion]) extends LM {
  override def complete(prompt: Prompt, params: Map[String, String])(implicit
      ec: ExecutionContext
  ): Future[Completion] = {
    val key = LMWithCache.keyFor(prompt, params)
    cache.get(key) match {
      case Some(c) => Future.successful(c)
      case None =>
        underlying.complete(prompt, params).map { c =>
          cache.put(key, c)
          c
        }
    }
  }
}

object LMWithCache {
  private def sha1(bytes: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(bytes)
    md.digest().map("%02x".format(_)).mkString
  }
  def keyFor(prompt: Prompt, params: Map[String, String]): String =
    sha1((prompt.content + "|" + params.toSeq.sortBy(_._1).mkString("&")).getBytes("UTF-8"))
}
