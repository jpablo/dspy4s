package dspy4s.core.contracts

import zio.blocks.schema.DynamicValue

/** Renders [[HistoryEntry]] values into a human-readable, DSPy-`inspect_history`-style block.
  *
  * dspy4s has no global per-LM history buffer (unlike upstream `dspy.inspect_history`); history is the per-thread
  * accumulation on [[RuntimeContext.history]], appended by `RuntimeEnvironment.appendHistory`. This renderer is the
  * presentation half of `RuntimeEnvironment.inspectHistory(n)`: given the last `n` entries (already sliced by the
  * caller), it produces a string for printing or assertion.
  *
  * The entry [[HistoryEntry.payload]] is a caller-defined `DynamicValue.Record`, so rendering is generic over its
  * fields: each `(key, value)` is emitted on its own line via [[DynamicValues.renderText]]. A handful of well-known
  * keys are surfaced with friendlier labels to read like a chat transcript, but no field is required or dropped. */
object HistoryRenderer:

  private val Separator = "=" * 60

  /** Friendlier labels for well-known payload keys; everything else falls back to the raw key name. */
  private val FieldLabels: Map[String, String] = Map(
    "model"      -> "Model",
    "messages"   -> "Messages",
    "prompt"     -> "Prompt",
    "completion" -> "Completion",
    "outputs"    -> "Outputs",
    "mode"       -> "Mode"
  )

  /** Render `entries` (already limited to the last n by the caller) as a single string. Empty input yields a short
    * "no history" notice rather than an empty string, so callers can print the result unconditionally. */
  def render(entries: Vector[HistoryEntry]): String =
    if entries.isEmpty then "No LM history recorded."
    else entries.iterator.map(renderEntry).mkString("\n")

  private def renderEntry(entry: HistoryEntry): String =
    val header = s"$Separator\n[${entry.component}] @ ${entry.timestamp}"
    val fields = entry.payload.fields.iterator
      .map((key, value) => renderField(key, value))
      .mkString("\n")
    if fields.isEmpty then header else s"$header\n$fields"

  private def renderField(key: String, value: DynamicValue): String =
    val label = FieldLabels.getOrElse(key, key)
    s"$label: ${DynamicValues.renderText(value)}"
