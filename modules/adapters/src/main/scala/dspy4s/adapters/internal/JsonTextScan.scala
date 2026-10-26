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
