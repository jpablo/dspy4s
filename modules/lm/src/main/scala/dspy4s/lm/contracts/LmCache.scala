package dspy4s.lm.contracts

trait LmCache:
  def get(request: LmRequest): Option[LmResponse]
  def put(request: LmRequest, response: LmResponse): Unit
