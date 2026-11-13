package dspy4s.harness.inspect

import zio.blocks.schema.json.Json

/** The layout of a JSON value for a view (docs/code-harness/OBSERVABILITY.md, 5.3). The printer of zio-blocks with
  * indents put a blank line in an empty object, so the views use this layout.
  */
object JsonText:

  /** `json` with indents of two spaces: each field and each item on a line of its own, and `{}` and `[]` for an empty
    * object and an empty array. Each key and each leaf has the text of the printer of zio-blocks.
    */
  def pretty(json: Json): String = layout(json, 0)

  private def layout(json: Json, indent: Int): String =
    val pad = " " * (indent + 2)
    val end = "\n" + " " * indent
    json match
      case o: Json.Object if o.value.nonEmpty =>
        o.value.toVector.map((k, v) => s"$pad${Json.String(k).print}: ${layout(v, indent + 2)}")
          .mkString("{\n", ",\n", s"$end}")
      case a: Json.Array if a.value.nonEmpty =>
        a.value.toVector.map(v => pad + layout(v, indent + 2)).mkString("[\n", ",\n", s"$end]")
      case _: Json.Object => "{}"
      case _: Json.Array  => "[]"
      case leaf           => leaf.print
