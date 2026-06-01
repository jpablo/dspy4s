package dspy4s.streaming

import dspy4s.core.contracts.DynamicValues
import zio.blocks.schema.DynamicValue

private[streaming] def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
  DynamicValues.recordFromEntries(entries)

private[streaming] def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
  DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)

private[streaming] def lookupString(rec: DynamicValue.Record, key: String): String =
  DynamicValues.recordGet(rec, key).map(DynamicValues.renderText).getOrElse("")
