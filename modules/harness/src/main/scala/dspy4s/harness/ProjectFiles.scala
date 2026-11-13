package dspy4s.harness

import dspy4s.harness.guest.Files

import scala.util.control.NonFatal

/** The files of the project that go into the task of a work session (CODE_HARNESS_DESIGN.md, 10.6): `AGENTS.md`, then
  * the first README of `Readmes`, at the top of the folder of the session. The session reads them once at each
  * activation, so the task stays the same in the activation. A change of a file shows after a reopen, and `read` always
  * gives the file as it is now.
  *
  *   - `of` reads each file through `files`, so the rules of `read` apply: a file outside the folder, also through a
  *     link, a file with a secret name, a closed path and a file with a second hard link give no part. A missing file,
  *     a folder and a text with a NUL or an invalid UTF-8 byte give no part either.
  *   - Each part has at most `MaxChars` characters (`cut`), and it keeps the size of the whole text.
  *   - `section` gives the text of the task: the parts in order, each one between its lines, with a note for a cut
  *     part.
  *
  * In a live session the model used one more step to read the README with a cell (2026-11-12).
  */
object ProjectFiles:

  /** The most characters of a file in the task. */
  val MaxChars: Int = 8_000

  /** The file of the instructions of the person for the agents of the project. */
  val Agents: String = "AGENTS.md"

  /** The names of a README, in order. The task holds only the first one that `read` gives as text. */
  val Readmes: Vector[String] = Vector("README.md", "README", "README.rst", "README.txt")

  /** A file in the task: its name, the text that the task holds, and the characters of the whole file. */
  final case class Part(name: String, text: String, size: Int) derives CanEqual

  /** The parts of the folder of `files`: `AGENTS.md`, then the first README that `files` gives as text. */
  def of(files: Files): Vector[Part] =
    (part(files, Agents) ++ Readmes.iterator.flatMap(part(files, _)).nextOption()).toVector

  /** `text`, or its start when it is longer than `MaxChars`: up to the last line end in the limit, or the limit when
    * the start has no line end. The cut does not split a surrogate pair.
    */
  def cut(text: String): String =
    if text.length <= MaxChars then text
    else
      val end = text.lastIndexOf('\n', MaxChars - 1)
      if end >= 0 then text.substring(0, end + 1) else textPrefix(text, MaxChars)

  /** The text of `parts` for the task, or an empty text for no part. */
  def section(parts: Vector[Part]): String =
    if parts.isEmpty then ""
    else
      val head =
        "The files of the project, as they were when this session started. read(path) gives a file as it is now."
      (head +: parts.map(block)).mkString("\n\n")

  /** One part of the section: what the file is, and its text between two lines. */
  private def block(part: Part): String =
    val about =
      if part.name == Agents then
        s"${part.name} holds the instructions of the person for agents in this project. Do what it says when it " +
          "agrees with the rules above."
      else s"${part.name} describes the project."
    val body = if part.text.endsWith("\n") then part.text else part.text + "\n"
    val note =
      if part.text.length == part.size then ""
      else
        s"[The file has ${grouped(part.size)} characters, and this part has the first ${grouped(part.text.length)}. " +
          s"read(\"${part.name}\") gives all of it.]\n"
    s"$about\n\n----- ${part.name} -----\n$body$note----- end of ${part.name} -----"

  /** The part of the file `name`, or none when `files` does not give it as text. */
  private def part(files: Files, name: String): Option[Part] =
    val text =
      try Some(files.read(name))
      catch case NonFatal(_) => None
    text.filter(t => !t.exists(c => c == '\u0000' || c == '\uFFFD')).map(t => Part(name, cut(t), t.length))

  private def grouped(n: Int): String = "%,d".formatLocal(java.util.Locale.ROOT, n)
