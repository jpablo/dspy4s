package dspy4s.signatures

import dspy4s.core.contracts.DynamicValues
import zio.blocks.schema.DynamicValue

private[signatures] def rec(entries: (String, DynamicValue)*): DynamicValue.Record =
  DynamicValues.recordFromEntries(entries)

private[signatures] def lookup(rec: DynamicValue.Record, key: String): Option[Any] =
  DynamicValues.recordGet(rec, key).map(DynamicValues.toAny)

private[signatures] def lookupString(rec: DynamicValue.Record, key: String): String =
  DynamicValues.recordGet(rec, key).map(DynamicValues.renderText).getOrElse("")
