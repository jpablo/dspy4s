package dspy4s.lm.runtime

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.:=
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.core.contracts.ToolCall
import dspy4s.lm.providers.DynamicJson
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
    val ordered = mutable.ArrayBuffer.empty[Accumulator]
    val active  = mutable.HashMap.empty[Int, Accumulator]
    deltas.foreach { delta =>
      val acc = active.get(delta.index) match
        case Some(current) if !startsNewCall(current, delta) => current
        case _ =>
          val fresh = new Accumulator(delta.index)
          ordered += fresh
          active(delta.index) = fresh
          fresh
      acc.merge(delta)
    }
    ordered.iterator.flatMap { acc =>
      acc.name.map { name =>
        ToolCall(name = name, args = parseArguments(acc.arguments.toString))
      }
    }.toVector

  /** Some OpenAI-compatible servers omit `tool_calls[].index`, so consecutive DISTINCT calls all arrive at the
    * fallback index 0. A delta carrying an `id` or `name` that CONFLICTS with the index's current accumulator
    * therefore begins a new call (spec-compliant streams never conflict within one index, so this is a no-op
    * for them). Fragments without id/name keep attaching to the index's most recent call. */
  private def startsNewCall(current: Accumulator, delta: LmToolCallDelta): Boolean =
    delta.id.exists(id => current.id.exists(_ != id)) ||
      delta.name.exists(name => current.name.exists(_ != name))

  /** JSON-decode a tool-call `arguments` string into a `DynamicValue.Record`; `{input: raw}` when it is not
    * valid JSON, `{value: json}` when it parses to a non-object. Shared with the non-streaming
    * `ProviderResponseParser.parseArgs` so both paths decode identically. */
  private[runtime] def parseArguments(raw: String): DynamicValue.Record =
    val trimmed = raw.trim
    if trimmed.isEmpty then DynamicValue.Record.empty
    else
      DynamicJson.decode(trimmed) match
        case Right(rec: DynamicValue.Record) => rec
        case Right(other)                    => DynamicValues.recordFromEntries(Seq("value" -> other))
        case Left(_)                         => DynamicValues.recordFromEntries(Seq("input" := trimmed))
