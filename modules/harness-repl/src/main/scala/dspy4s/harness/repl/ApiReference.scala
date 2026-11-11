package dspy4s.harness.repl

import dspy4s.harness.guest.Mode

import java.nio.charset.StandardCharsets.UTF_8

/** The API reference (CODE_HARNESS_DESIGN.md, 5.1): the parts of the source text of the guest API that compile in a
  * cell of a mode. The harness and the MCP server both give it.
  *
  * The live sessions of 2026-11-10 gave this rule. The reference was the whole text, and a model in a work session told
  * the person that it could use the network, other sessions and `rlm`. Its mode gave none of them.
  *
  * The filter reads the source text and the table `Capability.all`, which the givens of the REPL also read. So a new
  * definition of the guest API needs no change here. A definition stays only when the mode gives each of these:
  *   - for a capability trait: the trait, as the bare `C` or as `C^`
  *   - for an `update def` of a capability trait: `C^`
  *   - for a `def`: each capability of its `using` clauses, `C^` for `C^`, and the bare `C` or `C^` for the bare `C`
  *
  * A definition that goes takes its comments with it. The filter reads the layout that the formatter gives. A
  * definition starts at the indent of its body, its next lines have a larger indent, and a head that ends with a colon
  * has a body.
  */
object ApiReference:

  /** The source files of the guest API, in the order of the reference. The build of harness-guest copies them. */
  val files: Vector[String] = Vector("Guest.scala", "SessionTypes.scala")

  /** The parts of the source files that compile in a cell of `mode`, with a blank line between two files. */
  def apply(mode: Mode): String = sources.map(keep(_, mode)).mkString("\n\n")

  /** The parts of the source text `source` that compile in a cell of `mode`. */
  def keep(source: String, mode: Mode): String = body(source.linesIterator.toVector, None, mode).mkString("\n")

  /** The source text of each file, or a comment for a file that is not in this build. */
  private lazy val sources: Vector[String] = files.map { name =>
    Option(getClass.getResourceAsStream(s"/dspy4s/harness/guest/$name.txt")).fold(s"// $name is not in this build")(
      stream =>
        try String(stream.readAllBytes(), UTF_8)
        finally stream.close()
    )
  }

  /** A part of a body: a definition with the comments before it, or the blank lines between two definitions. */
  private enum Piece:
    case Definition(comments: Vector[String], code: Vector[String])
    case Gap

  /** The lines of the body `lines` that compile in a cell of `mode`. `owner` is the capability of the trait of the
    * body. A blank line stays between two definitions that stay, when the text had one between them.
    */
  private def body(lines: Vector[String], owner: Option[Capability], mode: Mode): Vector[String] =
    val all     = pieces(lines)
    val leading = all.headOption.exists { case Piece.Gap => true; case _ => false }
    val kept    = all.flatMap {
      case Piece.Definition(comments, code) => definition(code, owner, mode).map(Piece.Definition(comments, _))
      case Piece.Gap                        => Some(Piece.Gap)
    }
    kept.foldLeft((Vector.empty[String], false)) {
      case ((out, _), Piece.Gap)                          => (out, true)
      case ((out, gap), Piece.Definition(comments, code)) =>
        val blank = if gap && (out.nonEmpty || leading) then Vector("") else Vector.empty
        (out ++ blank ++ comments ++ code, false)
    }._1

  /** The code of a definition with its body filtered, or `None` when the mode does not give what it needs. */
  private def definition(code: Vector[String], owner: Option[Capability], mode: Mode): Option[Vector[String]] =
    code.headOption.fold(Option(code)) { head =>
      val capability = traitName(head).flatMap(Capability.named)
      val needs      = capability.map(_ -> false).toVector ++ defNeeds(code, owner)
      Option.when(needs.forall((c, full) => c.givenIn(mode, full))) {
        if !head.trim.endsWith(":") then code
        else
          val (inner, after) = code.tail.span(line => line.trim.isEmpty || indentOf(line) > indentOf(head))
          head +: (body(inner, capability, mode) ++ after)
      }
    }

  /** The capabilities that a `def` needs, each with true for `C^`: those of its `using` clauses, and `C^` of `owner`
    * for an `update def`. Another definition needs none.
    */
  private def defNeeds(code: Vector[String], owner: Option[Capability]): Vector[(Capability, Boolean)] =
    code.headOption.filter(isDef).fold(Vector.empty) { head =>
      val text    = code.mkString(" ")
      val clauses = UsingClause.findAllMatchIn(text.take(bodyStart(text, 0, 0))).map(_.group(1)).toVector
      val usings  = clauses.flatMap(clause =>
        UsingType.findAllMatchIn(clause).flatMap(m => Capability.named(m.group(1)).map(_ -> (m.group(2) == "^")))
      )
      val update = owner.filter(_ => UpdateDef.findFirstIn(head).nonEmpty).map(_ -> true)
      usings ++ update
    }

  /** The pieces of the body `lines`. A definition starts at the indent of the first line. */
  private def pieces(lines: Vector[String]): Vector[Piece] =
    val indent   = lines.find(_.trim.nonEmpty).fold(0)(indentOf)
    val result   = Vector.newBuilder[Piece]
    var comments = Vector.empty[String]
    var code     = Vector.empty[String]
    var blanks   = 0

    def close(): Unit = if comments.nonEmpty || code.nonEmpty then result += Piece.Definition(comments, code): Unit

    lines.foreach { line =>
      val open = comments.nonEmpty || code.nonEmpty
      if line.trim.isEmpty then blanks += 1
      else if open && (indentOf(line) > indent || closes(line)) then
        // A next line of the open definition, or of the doc comment before it.
        val next = Vector.fill(blanks)("") :+ line
        if code.isEmpty then comments ++= next else code ++= next
        blanks = 0
      else if open && code.isEmpty && blanks == 0 then
        // The head of a definition just after its comments.
        if isComment(line) then comments :+= line else code :+= line
      else
        close()
        if blanks > 0 then result += Piece.Gap: Unit
        blanks = 0
        if isComment(line) then
          comments = Vector(line)
          code = Vector.empty
        else
          comments = Vector.empty
          code = Vector(line)
    }
    close()
    result.result()

  /** The index of the `=` before the body of the definition `text`, or the length of `text` when it has no body. A `=`
    * inside brackets, for example of a default value, does not count.
    */
  @annotation.tailrec
  private def bodyStart(text: String, i: Int, depth: Int): Int =
    if i >= text.length then text.length
    else
      text(i) match
        case '(' | '[' | '{'                                => bodyStart(text, i + 1, depth + 1)
        case ')' | ']' | '}'                                => bodyStart(text, i + 1, depth - 1)
        case '=' if depth == 0 && !text.startsWith("=>", i) => i
        case _                                              => bodyStart(text, i + 1, depth)

  private def indentOf(line: String): Int = line.indexWhere(_ != ' ').max(0)

  /** True for a line that closes the brackets or the body of the definition before it. */
  private def closes(line: String): Boolean =
    val trimmed = line.trim
    trimmed.startsWith(")") || trimmed.startsWith("]") || trimmed.startsWith("}") || trimmed.startsWith("end ")

  /** True for a comment line, or a line of annotations only, which belong to the next definition. */
  private def isComment(line: String): Boolean =
    val trimmed = line.trim
    trimmed.startsWith("/*") || trimmed.startsWith("//") || Annotations.matches(trimmed)

  private def isDef(head: String): Boolean = DefHead.findPrefixOf(head.trim).nonEmpty

  private def traitName(head: String): Option[String] = TraitHead.findFirstMatchIn(head).map(_.group(1))

  private val DefHead     = raw"(?:@\S+\s+|[\w\[\]]+\s+)*def\s".r
  private val TraitHead   = raw"\btrait\s+(\w+)".r
  private val UpdateDef   = raw"\bupdate\s+def\b".r
  private val UsingClause = raw"\(using\s+([^()]*)\)".r
  private val UsingType   = raw"(?:^|,)\s*(?:\w+\s*:\s*)?(\w+)(\^?)\s*(?=,|$$)".r
  private val Annotations = raw"(?:@[\w.]+\s*)+".r
