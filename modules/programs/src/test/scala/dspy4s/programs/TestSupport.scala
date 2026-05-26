package dspy4s.programs

import dspy4s.core.contracts.DynamicValues
import zio.blocks.schema.DynamicValue

private[programs] def rec(entries: (String, Any)*): DynamicValue.Record =
  DynamicValues.recordFromEntries(entries)

private[programs] def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
  DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)

private[programs] def lookupString(rec: DynamicValue.Record, key: String): String =
  DynamicValues.recordGet(rec, key).map(DynamicValues.renderText).getOrElse("")
