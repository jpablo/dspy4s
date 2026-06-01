package dspy4s.lm.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.contracts.ToolCall
import dspy4s.lm.providers.JsonCodec
import zio.blocks.schema.DynamicValue

import scala.collection.mutable

/** Assembles streaming tool-call deltas into completed [[ToolCall]]s.
  *
  * OpenAI emits the function `name` and call `id` once (typically on the first
  * delta for a given `index`) and the `arguments` JSON string in fragments
  * across subsequent deltas. We accumulate by `index`, preserving the order in
  * which indices first appeared, and JSON-decode the concatenated arguments
  * into a `DynamicValue.Record`. Falls back to `{input: raw}` when the arguments
  * string is not valid JSON — matches the non-streaming `parseArgs`.
  */
object ToolCallAssembler:

  private final class Accumulator(val index: Int):
    var id: Option[String] = None
    var name: Option[String] = None
    val arguments: StringBuilder = new StringBuilder

    def merge(delta: LmToolCallDelta): Unit =
      if id.isEmpty then id = delta.id
      if name.isEmpty then name = delta.name
      delta.argumentsFragment.foreach(arguments.append)

  def assemble(deltas: Iterable[LmToolCallDelta]): Vector[ToolCall] =
    val byIndex = mutable.LinkedHashMap.empty[Int, Accumulator]
    deltas.foreach { delta =>
      val acc = byIndex.getOrElseUpdate(delta.index, new Accumulator(delta.index))
      acc.merge(delta)
    }
    byIndex.values.iterator.flatMap { acc =>
      acc.name.map { name =>
        ToolCall(name = name, args = parseArguments(acc.arguments.toString))
      }
    }.toVector

  private def parseArguments(raw: String): DynamicValue.Record =
    val trimmed = raw.trim
    if trimmed.isEmpty then DynamicValue.Record.empty
    else
      JsonCodec.decodeString(trimmed) match
        case Right(map) =>
          DynamicValues.recordFromEntries(map.toSeq.map((k, v) => k -> DynamicValues.fromAny(v)))
        case Left(_) =>
          DynamicValues.recordFromEntries(Seq("input" := trimmed))
