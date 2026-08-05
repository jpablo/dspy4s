package dspy4s.lm.runtime

import dspy4s.lm.contracts.{LmCache, LmRequest, LmResponse}

final case class CompositeLmCache(memory: Option[LmCache], disk: Option[LmCache]) extends LmCache:
  override def get(request: LmRequest): Option[LmResponse] =
    memory.flatMap(_.get(request)).orElse {
      disk.flatMap(_.get(request)).map { response =>
        memory.foreach(_.put(request, response))
        response
      }
    }

  override def put(request: LmRequest, response: LmResponse): Unit =
    memory.foreach(_.put(request, response))
    disk.foreach(_.put(request, response))

object NoopLmCache extends LmCache:
  override def get(request: LmRequest): Option[LmResponse]         = None
  override def put(request: LmRequest, response: LmResponse): Unit = ()
