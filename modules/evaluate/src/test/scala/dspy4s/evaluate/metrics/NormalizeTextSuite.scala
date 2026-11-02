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

  // The expected values in the next tests come from upstream `normalize_text` and `DPR_normalize`, on CPython 3.12.

  test("keeps an accent as its NFD mark, as upstream") {
    assertEquals(NormalizeText("Café"), "café")
  }

  test("reads an article before a mark as a whole word, as Python \\b") {
    assertEquals(NormalizeText("á b"), "́ b")
  }

  test("a letter, a number, or a symbol at the end of an article follows the word rule of Python") {
    assertEquals(NormalizeText("a1 an2 the3"), "a1 an2 the3")
    assertEquals(NormalizeText("a٣"), "a٣")
    assertEquals(NormalizeText("a𝑎"), "a𝑎")
    assertEquals(NormalizeText("a«b"), "«b")
  }

  test("deletes punctuation before it removes articles") {
    assertEquals(NormalizeText("a-b"), "ab")
    assertEquals(NormalizeText("_a_"), "")
  }

  test("collapses whitespace with the rule of Python") {
    assertEquals(NormalizeText("the cat sat"), "cat sat")
    assertEquals(NormalizeText("x\u0085y"), "x y")
  }

  test("dprTokens keeps articles and gives each symbol as a token") {
    assertEquals(
      NormalizeText.dprTokens("The  quick,  brown   FOX!"),
      Vector("the", "quick", ",", "brown", "fox", "!")
    )
    assertEquals(NormalizeText.dprTokens("«cat» a1_b"), Vector("«", "cat", "»", "a1", "_", "b"))
    assertEquals(NormalizeText.dprTokens("Café"), Vector("café"))
    assertEquals(NormalizeText.dprTokens("x​y  z"), Vector("x", "y", "z"))
    assertEquals(NormalizeText.dprTokens("😀 ok"), Vector("😀", "ok"))
    assertEquals(NormalizeText.dprTokens(""), Vector.empty)
  }
