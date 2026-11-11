package dspy4s.harness

/** The cuts of a text (CODE_HARNESS_DESIGN.md, 4.1). A cut counts UTF-16 units, and it never cuts a surrogate pair in
  * two. The JSON writer of the journal refuses a surrogate with no pair: a failed write ends the session, and a request
  * to a model with such a text fails. So each cut of a text for the journal, for a model or for a terminal uses these
  * functions.
  */

/** The end of a cut of at most `units` units of `text` from the index `from`. When the cut falls inside a surrogate
  * pair, it ends before the pair.
  */
def cutEnd(text: CharSequence, from: Int, units: Int): Int =
  val start = from.max(0).min(text.length)
  val end   = (start.toLong + units.max(0)).min(text.length.toLong).toInt
  if end > start && end < text.length && Character.isHighSurrogate(text.charAt(end - 1)) &&
    Character.isLowSurrogate(text.charAt(end))
  then end - 1
  else end

/** The start of the last `units` units of `text`. When that start falls inside a surrogate pair, it starts after the
  * pair.
  */
def suffixStart(text: CharSequence, units: Int): Int =
  val start = (text.length - units.max(0)).max(0)
  if start > 0 && start < text.length && Character.isLowSurrogate(text.charAt(start)) &&
    Character.isHighSurrogate(text.charAt(start - 1))
  then start + 1
  else start

/** The start of `text`, at most `units` units, with no surrogate pair cut in two. */
def textPrefix(text: String, units: Int): String = text.substring(0, cutEnd(text, 0, units))

/** The end of `text`, at most `units` units, with no surrogate pair cut in two. */
def textSuffix(text: String, units: Int): String = text.substring(suffixStart(text, units))
