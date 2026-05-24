package dspy4s.evaluate.metrics

import munit.FunSuite

class NormalizeTextSuite extends FunSuite:

  test("normalizes case, punctuation, articles, and whitespace") {
    assertEquals(NormalizeText("The  quick,  brown   FOX!"), "quick brown fox")
  }

  test("strips leading and trailing articles") {
    assertEquals(NormalizeText("A cat on the mat"), "cat on mat")
  }

  test("keeps short words that are not articles") {
    assertEquals(NormalizeText("I a an the"), "i")
  }

  test("returns empty for empty input") {
    assertEquals(NormalizeText(""), "")
    assertEquals(NormalizeText("   "), "")
  }

  test("handles unicode accents via NFD") {
    assertEquals(NormalizeText("Café"), "cafe")
  }

  test("dpr keeps articles but strips punctuation and collapses whitespace") {
    assertEquals(NormalizeText.dpr("The  quick,  brown   FOX!"), "the quick brown fox")
  }
