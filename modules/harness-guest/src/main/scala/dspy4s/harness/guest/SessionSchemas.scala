package dspy4s.harness.guest

import zio.blocks.schema.Schema

/** The `Schema` of each session type of SessionTypes.scala. That file has capture checking, because `@assumeSafe` needs
  * it, and there the derivation of a `Schema` exceeds the recursion limit of the compiler. This file has no capture
  * checking, so the derivation is here, and the companion of each type gives its `Schema` as a given.
  */
private[guest] object SessionSchemas:
  lazy val strategyName: Schema[StrategyName] = Schema.derived
  lazy val scope: Schema[Scope]               = Schema.derived
  lazy val sessionRef: Schema[SessionRef]     = Schema.derived
  lazy val sessionCard: Schema[SessionCard]   = Schema.derived
