package dspy4s.core.primitives

import dspy4s.core.contracts.Parameter

final case class BasicParameter(name: String, state: Map[String, Any] = Map.empty) extends Parameter:
  override def reset(): Parameter = copy(state = Map.empty)

  def withState(key: String, value: Any): BasicParameter = copy(state = state.updated(key, value))
