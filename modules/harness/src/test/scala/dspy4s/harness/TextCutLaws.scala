package dspy4s.harness

import zio.blocks.schema.json.Json

import scala.util.Try

/** The laws of the cuts of a text for the journal, for a model and for a terminal (CODE_HARNESS_DESIGN.md, 4.1). The
  * JSON writer of the journal refuses a surrogate with no pair, so each cut must keep each character whole.
  */
class TextCutLaws extends munit.FunSuite:

  private val pair = "😀"

  /** True when each surrogate of `text` is in a pair. `codePoints` gives a surrogate with no pair as a code point of
    * its own, in the range of the surrogates.
    */
  private def whole(text: String): Boolean = text.codePoints().allMatch(cp => cp < 0xd800 || cp > 0xdfff)

  /** True when the JSON writer of the journal takes `text`. */
  private def writable(text: String): Boolean = Try(Json.String(text).print).isSuccess

  test("Cut: textPrefix, textSuffix and cutEnd never cut a surrogate pair") {
    val text = "a" * 10 + pair + "b" * 10
    (0 to text.length + 1).foreach { n =>
      Vector(textPrefix(text, n), textSuffix(text, n)).foreach { cut =>
        assert(cut.length <= n, s"$n: $cut")
        assert(whole(cut), s"$n: $cut")
      }
    }
    assertEquals(textPrefix(text, 11), "a" * 10)
    assertEquals(textPrefix(text, 12), "a" * 10 + pair)
    assertEquals(textSuffix(text, 11), "b" * 10)
    assertEquals(textSuffix(text, 12), pair + "b" * 10)
    assertEquals(cutEnd(text, 9, 2), 10)
    assertEquals(textPrefix(text, -1), "")
  }

  test("Cut: the JSON writer of the journal refuses a surrogate with no pair, so a cut must keep the pairs") {
    assert(!writable(("a" * 10 + pair).take(11)))
    assert(writable(textPrefix("a" * 10 + pair, 11)))
  }

  test("Cut: the text of a long value of a call keeps each character whole") {
    val text = Recorder.rendered(Json.String("a" * 1999 + pair + "b" * 100))
    assert(whole(text) && writable(text), text.takeRight(60))
    assert(text.startsWith("a" * 1999) && text.contains("more]"), text.takeRight(60))
  }

  test("Cut: Window.cut keeps each character whole") {
    val text = Window.cut("a" * 99 + pair + "b" * 100, 100)
    assert(whole(text) && writable(text), text)
    assert(text.startsWith("a" * 99) && text.contains("more characters were cut"), text)
  }

  test("Cut: the source of a compress call of the memory tree keeps each character whole") {
    val text = MemoryTree.compressSource("a" * (MemoryTree.MaxSource - 1) + pair + "b")
    assert(whole(text) && writable(text) && text.length <= MemoryTree.MaxSource, text.takeRight(10))
  }
