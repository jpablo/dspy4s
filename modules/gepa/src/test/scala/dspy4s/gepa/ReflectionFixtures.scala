package dspy4s.gepa

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import zio.blocks.schema.{DynamicValue, Schema}

import java.nio.charset.StandardCharsets

/** Helpers for the suites that script a reflection LM or read a reflective record. */
object ReflectionFixtures:

  /** The reply that the default proposer reads: one JSON object with the key `new_instruction`. */
  def reply(instruction: String): String = jsonObject("new_instruction", instruction)

  /** The reply to the call that shortens an instruction: one JSON object with the key `shortened_instruction`. */
  def shortenedReply(instruction: String): String = jsonObject("shortened_instruction", instruction)

  private def jsonObject(key: String, value: String): String =
    new String(Schema.dynamic.jsonCodec.encode(DynamicValues.record(key := value)), StandardCharsets.UTF_8)

  /** A string as a value of a reflective record. */
  def string(value: String): DynamicValue = DynamicValues.fromAny(value)

  /** The text of a value of a reflective record, for an assertion on its content. */
  def text(value: DynamicValue): String = DynamicValues.renderText(value)
