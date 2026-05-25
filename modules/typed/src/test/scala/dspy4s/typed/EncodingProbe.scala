package dspy4s.typed

import kyo.{Json, Schema}
import munit.FunSuite

enum ProbeMood derives Schema:
  case happy, sad, neutral

case class ProbeResult(mood: ProbeMood) derives Schema

/** Regression check for the Phase 0 finding that kyo-schema encodes Scala
  * enums as a discriminated wrapper object (`{"caseName":{}}`), not as a
  * flat string. This shape affects how adapters must serialize enum-typed
  * output fields if they ever round-trip through kyo-schema. */
class EncodingProbe extends FunSuite:

  test("kyo-schema encodes Scala enums as discriminated wrapper objects") {
    val encoded = Json.encode(ProbeResult(ProbeMood.happy))
    assertEquals(encoded, """{"mood":{"happy":{}}}""")
  }
