package dspy4s.evaluate.metrics

import java.text.Normalizer

object NormalizeText:
  private val articles = Set("a", "an", "the")
  private val punctuation = """[!"#$%&'()*+,-./:;<=>?@\[\]^_`{|}~]""".r
  private val whitespacePattern = """\s+""".r
  private val combiningMarks = """\p{Mn}""".r

  def apply(s: String): String =
    val normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
    val asciiOnly = combiningMarks.replaceAllIn(normalized, "")
    val lowerCased = asciiOnly.toLowerCase
    val noPunctuation = punctuation.replaceAllIn(lowerCased, " ")
    val noArticles = noPunctuation.split("\\s+").filterNot(articles.contains).mkString(" ")
    whitespacePattern.replaceAllIn(noArticles.trim, " ").trim

  def dpr(s: String): String =
    val normalized = Normalizer.normalize(s, Normalizer.Form.NFD)
    val asciiOnly = combiningMarks.replaceAllIn(normalized, "")
    val lowerCased = asciiOnly.toLowerCase
    val noPunctuation = punctuation.replaceAllIn(lowerCased, " ")
    whitespacePattern.replaceAllIn(noPunctuation.trim, " ").trim
