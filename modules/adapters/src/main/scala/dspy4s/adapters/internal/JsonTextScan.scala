package dspy4s.adapters.internal

/** The string state of a scan of JSON text, one character at a time. The scan knows when a character is in a JSON
  * string, also after an escaped quote. The JSON parser and the JSON stream state find the end of an object or a list
  * with it.
  */
private[adapters] final class JsonTextScan:
  private var inString: Boolean = false
  private var escaped: Boolean  = false

  /** Read the next character `c`. `true` when `c` is outside each JSON string and is not a quote: such a character can
    * open or close an object or a list.
    */
  def structural(c: Char): Boolean =
    if inString then
      if escaped then escaped = false
      else if c == '\\' then escaped = true
      else if c == '"' then inString = false
      false
    else if c == '"' then
      inString = true
      false
    else true

  /** Start again outside each string. */
  def reset(): Unit =
    inString = false
    escaped = false

private[dspy4s] object JsonTextScan:

  /** The text of the first JSON object of `text`: from the first `{` to the `}` that closes it. A brace in a JSON
    * string does not count. `None` when `text` has no such object. The function does not parse the object.
    */
  def firstObject(text: String): Option[String] =
    val start = text.indexOf('{')
    if start < 0 then
      None
    else
      val scan  = new JsonTextScan
      var depth = 0
      var end   = -1
      var i     = start
      while i < text.length && end < 0 do
        val char = text.charAt(i)
        if scan.structural(char) then
          if char == '{' then depth += 1
          else if char == '}' then
            depth -= 1
            if depth == 0 then end = i
        i += 1
      Option.when(end >= start)(text.substring(start, end + 1))
