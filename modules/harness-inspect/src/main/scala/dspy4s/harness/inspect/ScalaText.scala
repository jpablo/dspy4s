package dspy4s.harness.inspect

import zio.blocks.schema.json.Json

/** The text of a value as Scala (docs/code-harness/OBSERVABILITY.md, 5.3): the names of its cases and of its fields as
  * the code has them, with indents.
  *
  *   - A product prints as `Name(field = value, ...)`, from `productPrefix` and `productElementNames`. A case with no
  *     field prints as its name, a tuple as `(a, b)`, and `Some` as `Some(value)`.
  *   - A string prints as a Scala literal. A string with a line end prints as a `"""` block, when it holds no `"""`.
  *   - A `Long` has the suffix `L`, and a `Json` value prints as `Json(<its text>)`.
  *   - A value goes on one line when it fits in `Width` characters and holds no block. Else each part goes on a line of
  *     its own.
  */
object ScalaText:

  /** The most characters of a line. */
  val Width = 100

  def of(value: Any): String = render(value, 0, 0)

  /** `value` on one line when it holds no block, for a short place such as a field of a card. Else as `of` gives it. */
  def line(value: Any): String = flat(value).getOrElse(of(value))

  /** `value` at the indent `indent`, on a line with `used` characters before it. */
  private def render(value: Any, indent: Int, used: Int): String =
    flat(value).filter(_.length + used <= Width).getOrElse(broken(value, indent))

  /** `value` on one line, or none when a part of it is a block. */
  private def flat(value: Any): Option[String] =
    value match
      case text: String   => Option.unless(block(text))(literal(text))
      case c: Char        => Some(s"'${escaped(c, '\'')}'")
      case n: Long        => Some(s"${n}L")
      case json: Json     => Some(s"Json(${json.print})")
      case Some(inner)    => flat(inner).map(text => s"Some($text)")
      case _: None.type   => Some("None")
      case map: Map[?, ?] => all(map.toVector.map((k, v) => for a <- flat(k); b <- flat(v) yield s"$a -> $b"))
          .map(_.mkString(s"${nameOf(map)}(", ", ", ")"))
      case items: Iterable[?]                            => all(items.toVector.map(flat)).map(_.mkString(s"${nameOf(items)}(", ", ", ")"))
      case tuple: Tuple                                  => all(tuple.toList.toVector.map(flat)).map(_.mkString("(", ", ", ")"))
      case product: Product if product.productArity == 0 => Some(product.productPrefix)
      case product: Product                              => all(fields(product).map((name, v) => flat(v).map(text => s"$name = $text")))
          .map(_.mkString(s"${product.productPrefix}(", ", ", ")"))
      case other => Some(String.valueOf(other))

  /** `value` on more lines: each part on a line of its own, with the indent `indent + 2`. */
  private def broken(value: Any, indent: Int): String =
    val inner                                              = indent + 2
    val pad                                                = " " * inner
    def lines(open: String, parts: Vector[String]): String =
      if parts.isEmpty then s"$open)" else parts.mkString(s"$open\n$pad", s",\n$pad", s"\n${" " * indent})")
    value match
      case text: String if block(text) => "\"\"\"" + text + "\"\"\""
      case json: Json                  => s"Json(${json.print})"
      case Some(content)               => lines("Some(", Vector(render(content, inner, inner)))
      case map: Map[?, ?]              => lines(
          s"${nameOf(map)}(",
          map.toVector.map { (k, v) =>
            val key = render(k, inner, inner)
            s"$key -> ${render(v, inner, inner + key.length + 4)}"
          }
        )
      case items: Iterable[?]                           => lines(s"${nameOf(items)}(", items.toVector.map(render(_, inner, inner)))
      case tuple: Tuple                                 => lines("(", tuple.toList.toVector.map(render(_, inner, inner)))
      case product: Product if product.productArity > 0 =>
        lines(
          s"${product.productPrefix}(",
          fields(product).map((name, v) => s"$name = ${render(v, inner, inner + name.length + 3)}")
        )
      case other => flat(other).getOrElse(String.valueOf(other))

  private def all(parts: Vector[Option[String]]): Option[Vector[String]] =
    if parts.forall(_.isDefined) then Some(parts.flatten) else None

  private def fields(product: Product): Vector[(String, Any)] =
    product.productElementNames.toVector.zip(product.productIterator.toVector)

  private def nameOf(items: Iterable[?]): String =
    items match
      case _: Map[?, ?]                                      => "Map"
      case _: Vector[?]                                      => "Vector"
      case _: List[?]                                        => "List"
      case _: Set[?]                                         => "Set"
      case other if other.getClass.getName.contains("Chunk") => "Chunk"
      case _: Seq[?]                                         => "Seq"
      case other                                             => other.getClass.getSimpleName

  /** True for a text that prints as a `"""` block: it has a line end, and no `"""`. */
  private def block(text: String): Boolean = text.contains('\n') && !text.contains("\"\"\"")

  /** `text` as a Scala string literal on one line. */
  private def literal(text: String): String =
    val out = StringBuilder("\"")
    text.foreach(c => out ++= escaped(c, '"'))
    out += '"'
    out.result()

  /** `c` in a Scala literal whose quote is `quote`. */
  private def escaped(c: Char, quote: Char): String =
    c match
      case '\\'                                      => "\\\\"
      case '\n'                                      => "\\n"
      case '\r'                                      => "\\r"
      case '\t'                                      => "\\t"
      case q if q == quote                           => s"\\$q"
      case other if other < ' ' || other == '\u007f' => f"\\u${other.toInt}%04x"
      case other                                     => other.toString
