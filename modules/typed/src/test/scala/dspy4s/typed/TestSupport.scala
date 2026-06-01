package dspy4s.typed

import dspy4s.core.contracts.DynamicValues
import zio.blocks.schema.DynamicValue

private[typed] def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
  DynamicValues.recordFromEntries(entries)

private[typed] def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
  DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)

private[typed] def lookupString(rec: DynamicValue.Record, key: String): String =
  DynamicValues.recordGet(rec, key).map(DynamicValues.renderText).getOrElse("")
