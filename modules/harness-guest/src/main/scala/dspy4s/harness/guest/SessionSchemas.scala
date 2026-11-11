package dspy4s.harness.guest

import zio.blocks.schema.Schema

/** The `Schema` of each session type of SessionTypes.scala. Host code that needs one imports `SessionSchemas.given`.
  *
  * That file has capture checking, because `@assumeSafe` needs it, and there the derivation of a `Schema` exceeds the
  * recursion limit of the compiler. This file has no capture checking, so the derivation is here.
  *
  * The givens are not in the companions of the types, because the API reference (5.1) shows the source text of
  * SessionTypes.scala, and a cell cannot use a `Schema`. In a live session of 2026-11-10, the givens were only noise in
  * the reference.
  */
private[harness] object SessionSchemas:
  given strategyName: Schema[StrategyName] = Schema.derived
  given scope: Schema[Scope]               = Schema.derived
  given sessionRef: Schema[SessionRef]     = Schema.derived
  given sessionCard: Schema[SessionCard]   = Schema.derived
