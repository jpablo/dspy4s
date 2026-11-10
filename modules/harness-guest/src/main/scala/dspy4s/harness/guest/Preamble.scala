package dspy4s.harness.guest

/** The Scala text of the type of a shape (5.1). The JSON of a value holds no type name, so `Box[Int]` can be a class
  * with an `Int` field, and a sealed trait can be an enum, with the same form.
  */
def typeText(shape: DataShape): String =
  shape match
    case DataShape.Plain(n)               => fullNames.getOrElse(n, n)
    case DataShape.Applied("Tuple", args) =>
      if args.isEmpty then "EmptyTuple" else args.map(typeText).mkString("(", ", ", ")")
    case DataShape.Applied(n, args) => s"$n[${args.map(typeText).mkString(", ")}]"
    case DataShape.Record(n, _)     => scalaName(n)
    case DataShape.Union(n, _)      => scalaName(n)

/** The preamble of a new REPL (5.1): the types, a companion object with one given for each type, and the object
  * `Preamble` with `submit` and one `val` for each input. An import of the members of `Preamble` comes after the
  * object, so a cell calls `submit` and reads each input by its name. A value comes from `input`, so the text holds no
  * value and no secret. A record gives `submit` one parameter for each field, and another type gives
  * `submit(value: T)`.
  *
  * The given of a type is in the companion object of the type, not at the top level. A top-level given that
  * `Data.derived` builds makes the compiler of the 3.10 nightly give each Array result of a later cell in safe mode a
  * capture set. Then `Array(1, 2, 3).map(_ + 1).sum` does not compile. The given stays, because the inline derivation
  * of `Data.auto` can stop on a deep type. The given has the full name of `Data`, so a case or a type with the name
  * `Data` does not hide the type class.
  *
  * `submit` and the input vals are members of an object for the same reason. A top-level `def` or `val` whose type has
  * a collection or a type of the same chunk has the same effect as a top-level given. Examples are
  * `def submit(name: String, kids: Vector[Tree])` and `val docs: Vector[String]`. A member of an object, with an
  * import, has no such effect. The JVM makes the object when a cell first uses one of its members, and then each val
  * reads its input. So an input that does not fit its type fails that cell, not the start of the REPL.
  *
  * Five things have no preamble: two types with one name and two forms, a union case that is not a record, an input
  * with the name of a guest API function, a type or an input with the name `Preamble`, and an input name that no Scala
  * name can hold. A name cannot be empty, and a name in backquotes cannot hold a backquote or a line break.
  */
def preamble(root: DataShape, inputs: Vector[(String, DataShape)] = Vector.empty): Either[String, String] =
  val problem = inputs.collectFirst {
    case (n, _) if apiNames(n)                                     => s"the input $n has the name of a guest API function"
    case (n, _) if n == objectName                                 => s"the input $n has the name of the preamble object"
    case (n, _) if n.isEmpty || n.exists(c => "`\n\r".contains(c)) => s"the input ${literal(n)} has no Scala name"
  }
  problem match
    case Some(error) => Left(error)
    case None        => definitions(DataShape.Applied("Tuple", root +: inputs.map(_._2))).flatMap { found =>
        if found.exists(_._1 == objectName) then Left(s"the type $objectName has the name of the preamble object")
        else
          val types   = found.map(_._2)
          val objects =
            found.map((n, _) => s"object ${scalaName(n)}:\n  given $dataName[${scalaName(n)}] = $dataName.derived")
          val vals   = inputs.map((n, s) => s"val ${scalaName(n)}: ${typeText(s)} = input[${typeText(s)}](${literal(n)})")
          val submit = root match
            case DataShape.Record(n, fields) =>
              val all = fields()
              s"def submit(${params(all)}): Nothing = submitValue(${scalaName(n)}(${all.map((f, _) => scalaName(f)).mkString(", ")}))"
            case other => s"def submit(value: ${typeText(other)}): Nothing = submitValue(value)"
          val members = (submit +: vals).map(m => s"  $m")
          Right((types ++ objects ++ (s"object $objectName:" +: members) :+ s"import $objectName.*").mkString("\n"))
      }

/** The name of the object that holds `submit` and the input vals. A type or an input with this name would clash with
  * the object, so it has no preamble.
  */
private val objectName = "Preamble"

/** The full name of the type class `Data`. A case of a type with the name `Data` hides the short name. */
private val dataName = "_root_.dspy4s.harness.guest.Data"

private val fullNames = Map("Instant" -> "java.time.Instant", "Duration" -> "java.time.Duration")

private val reserved = Set(
  "abstract",
  "case",
  "catch",
  "class",
  "def",
  "do",
  "else",
  "enum",
  "export",
  "extends",
  "false",
  "final",
  "finally",
  "for",
  "given",
  "if",
  "implicit",
  "import",
  "lazy",
  "match",
  "new",
  "null",
  "object",
  "override",
  "package",
  "private",
  "protected",
  "return",
  "sealed",
  "super",
  "then",
  "throw",
  "trait",
  "true",
  "try",
  "type",
  "val",
  "var",
  "while",
  "with",
  "yield"
)

/** The names of the guest API. An input with one of these names would hide the function. */
private val apiNames = Set(
  "read",
  "write",
  "edit",
  "exec",
  "spawn",
  "jobOutput",
  "waitJob",
  "stopJob",
  "keys",
  "load",
  "store",
  "now",
  "println",
  "httpGet",
  "llmQuery",
  "llmQueryBatched",
  "rlm",
  "rlmBatched",
  "start",
  "tell",
  "sessions",
  "observe",
  "zoom",
  "submit",
  "submitValue",
  "input"
)

/** A name as Scala source: in backquotes when it is a reserved word or not a plain name. The compiler reads the escapes
  * of a string in backquotes too, so the text in backquotes has the escapes of `literal`.
  */
private def scalaName(text: String): String =
  val plain = text.nonEmpty && text.head.isLetter && text.forall(c => c.isLetterOrDigit || c == '_')
  if plain && !reserved(text) then text else s"`${escaped(text)}`"

/** The Scala string literal of a text, in quotes. */
private def literal(text: String): String = s"\"${escaped(text)}\""

/** The characters of a text as Scala source in quotes or backquotes. A backslash, a quote and each control character
  * get an escape.
  */
private def escaped(text: String): String =
  text.flatMap {
    case '\\'             => "\\\\"
    case '"'              => "\\\""
    case '\n'             => "\\n"
    case '\r'             => "\\r"
    case '\t'             => "\\t"
    case c if c.isControl => f"\\u${c.toInt}%04x"
    case c                => c.toString
  }

private def params(fields: Vector[(String, DataShape)]): String =
  fields.map((n, s) => s"${scalaName(n)}: ${typeText(s)}").mkString(", ")

private def caseText(label: String, shape: DataShape): Either[String, String] =
  shape match
    case DataShape.Record(_, fields) if fields().isEmpty => Right(s"  case ${scalaName(label)}")
    case DataShape.Record(_, fields)                     => Right(s"  case ${scalaName(label)}(${params(fields())})")
    case other                                           => Left(s"the case $label of a union has the shape ${typeText(other)}")

/** The definition of each record and union that the shape reaches, in order. Two types with one name and two forms
  * fail.
  */
private def definitions(root: DataShape): Either[String, Vector[(String, String)]] =
  def add(found: Vector[(String, String)], n: String, text: String): Either[String, Option[Vector[(String, String)]]] =
    found.find(_._1 == n) match
      case Some((_, same)) if same == text => Right(None)
      case Some(_)                         => Left(s"two types have the name $n")
      case None                            => Right(Some(found :+ (n -> text)))
  def go(shape: DataShape, found: Vector[(String, String)]): Either[String, Vector[(String, String)]] =
    shape match
      case DataShape.Plain(_)         => Right(found)
      case DataShape.Applied(_, args) =>
        args.foldLeft[Either[String, Vector[(String, String)]]](Right(found))((f, a) => f.flatMap(go(a, _)))
      case DataShape.Record(n, fields) =>
        val all = fields()
        add(found, n, s"case class ${scalaName(n)}(${params(all)})").flatMap {
          case None        => Right(found)
          case Some(added) =>
            all.foldLeft[Either[String, Vector[(String, String)]]](Right(added))((f, a) => f.flatMap(go(a._2, _)))
        }
      case DataShape.Union(n, cases) =>
        val all   = cases()
        val lines = all.map(caseText(_, _))
        lines.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None    =>
            val text = s"enum ${scalaName(n)}:\n" + lines.collect { case Right(l) => l }.mkString("\n")
            add(found, n, text).flatMap {
              case None        => Right(found)
              case Some(added) =>
                val inner = all.flatMap {
                  case (_, DataShape.Record(_, fields)) => fields().map(_._2)
                  case _                                => Vector.empty
                }
                inner.foldLeft[Either[String, Vector[(String, String)]]](Right(added))((f, a) => f.flatMap(go(a, _)))
            }
  go(root, Vector.empty)
