package dspy4s.evaluate.metrics

import dspy4s.core.contracts.PythonText

import java.text.Normalizer
import java.util.Locale
import scala.util.matching.Regex

/** The text forms that the metrics compare: upstream `normalize_text` (`dspy/evaluate/metrics.py`) and the tokens of
  * upstream `DPR_normalize` (`dspy/dsp/utils/dpr.py`).
  */
object NormalizeText:

  /** Python `string.punctuation`. */
  private val Punctuation: Set[Char] = """!"#$%&'()*+,-./:;<=>?@[\]^_`{|}~""".toSet

  /** Python `\b(a|an|the)\b`. A Python word character is a letter, a number, or `_`. Java `\b` reads a mark in a
    * different way, so this pattern uses lookarounds.
    */
  private val Articles: Regex = """(?<![\p{L}\p{N}_])(?:a|an|the)(?![\p{L}\p{N}_])""".r

  /** The pattern of upstream `SimpleTokenizer`: a run of letters, numbers, and marks, or one other character that is
    * not a separator and not a control character.
    */
  private val Token: Regex = """[\p{L}\p{N}\p{M}]+|[^\p{Z}\p{C}]""".r

  /** Upstream `normalize_text`: NFD, lower case, no punctuation, no articles, and one space between the words. The NFD
    * marks stay, so an accent stays.
    */
  def apply(text: String): String =
    val noPunctuation = nfd(text).toLowerCase(Locale.ROOT).filterNot(Punctuation)
    PythonText.split(Articles.replaceAllIn(noPunctuation, " ")).mkString(" ")

  /** Upstream `DPR_normalize`: the lower-case tokens of the NFD form of `text`. */
  def dprTokens(text: String): Vector[String] =
    Token.findAllIn(nfd(text)).map(_.toLowerCase(Locale.ROOT)).toVector

  private def nfd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
