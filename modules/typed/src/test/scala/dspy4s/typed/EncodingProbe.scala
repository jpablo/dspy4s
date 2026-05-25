package dspy4s.typed

import kyo.{Json, Schema}
import munit.FunSuite

enum ProbeMood derives Schema:
  case happy, sad, neutral

case class ProbeResult(mood: ProbeMood) derives Schema

/** Probe: what wire form does kyo-schema produce for a case class containing
  * a Scala enum? Printed so we can read it from the test log. Delete after
  * Phase 0 once the format is documented. */
class EncodingProbe extends FunSuite:

  test("probe: encode shape for case-class-with-enum") {
    val encoded = Json.encode(ProbeResult(ProbeMood.happy))
    println(s"[probe] ProbeResult(ProbeMood.happy) -> $encoded")
  }
