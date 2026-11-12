package dspy4s.harness.repl

import dspy4s.harness.guest.{Access, Mode}

import java.nio.charset.StandardCharsets.UTF_8

/** The laws of the filter of the API reference (CODE_HARNESS_DESIGN.md, 5.1). The laws of `ReferenceLaws` in the
  * harness compare the reference with a real REPL. These laws check the text that the filter keeps.
  */
class ApiReferenceLaws extends munit.FunSuite:

  private val levels = Access.values.toVector

  /** Each mode: seven capabilities with three levels. */
  private val modes: Vector[Mode] =
    for
      files    <- levels
      shell    <- levels
      network  <- levels
      llm      <- levels
      sessions <- levels
      children <- levels
      store    <- levels
    yield Mode(files, shell, network, llm, sessions, children, store)

  /** The names of the definitions of `text`. */
  private def names(text: String): Set[String] = raw"\bdef\s+(\w+)".r.findAllMatchIn(text).map(_.group(1)).toSet

  /** The modes with one capability of `mode` one level higher. */
  private def above(mode: Mode): Vector[Mode] =
    def up(a: Access): Option[Access] = levels.lift(a.ordinal + 1)
    Vector(
      up(mode.files).map(a => mode.copy(files = a)),
      up(mode.shell).map(a => mode.copy(shell = a)),
      up(mode.network).map(a => mode.copy(network = a)),
      up(mode.llm).map(a => mode.copy(llm = a)),
      up(mode.sessions).map(a => mode.copy(sessions = a)),
      up(mode.children).map(a => mode.copy(children = a)),
      up(mode.store).map(a => mode.copy(store = a))
    ).flatten

  test("ApiReference: the reference of the full mode is the whole source text of the guest API") {
    val whole = ApiReference.files.map { name =>
      val stream = getClass.getResourceAsStream(s"/dspy4s/harness/guest/$name.txt")
      try String(stream.readAllBytes(), UTF_8).linesIterator.mkString("\n")
      finally stream.close()
    }
    assertEquals(ApiReference(Mode.full), whole.mkString("\n\n"))
  }

  test("ApiReference: each call of the object api has a doc comment in the reference of the full mode") {
    // In a live session (70e1947a, 2026-11-11), the person asked why api.write had "just the name, no description".
    val lines = ApiReference(Mode.full).linesIterator.toVector
    val start = lines.indexWhere(_.contains("object api:"))
    val body  = lines.drop(start + 1).takeWhile(line => line.isEmpty || line.startsWith(" "))
    val bare  =
      body.indices.filter(i => body(i).trim.startsWith("def ") && !body.lift(i - 1).exists(_.trim.endsWith("*/")))
    assert(start >= 0, "the reference has no object api")
    assert(bare.isEmpty, s"calls with no description:\n${bare.map(i => body(i).trim).mkString("\n")}")
  }

  test("ApiReference: the reference has no note for the review and no number of a section of the design") {
    // The model reads the reference. A note for the developers, as "A proposal for the review: section 5.1 names
    // edit", or a section number, as "(5.1)", means nothing to it.
    val notes = raw"A proposal for the review|CODE_HARNESS|\(\d+(\.\d+)?(, \d+(\.\d+)?)*\)".r
    val found = ApiReference(Mode.full).linesIterator.filter(line => notes.findFirstIn(line).nonEmpty).toVector
    assert(found.isEmpty, s"lines with a note for the developers:\n${found.mkString("\n")}")
  }

  test("ApiReference: a definition that goes takes its doc comment, and a trait that goes takes its members") {
    val reference = ApiReference(Mode.readOnly.copy(llm = Access.None, sessions = Access.None))
    assert(reference.contains("def read(path: String): String"), reference)
    Vector("Replaces the one place", "def edit", "Commands in the workspace", "def exec", "Starts a job", "def spawn")
      .foreach(text => assert(!reference.contains(text), s"the reference holds $text"))
  }

  test("ApiReference: a higher mode keeps each name of a lower mode, and no reference has two blank lines together") {
    val references = modes.map(mode => mode -> ApiReference(mode)).toMap
    modes.foreach { mode =>
      val reference = references(mode)
      assert(!reference.contains("\n\n\n"), s"$mode:\n$reference")
      above(mode).foreach { higher =>
        val lost = names(reference) -- names(references(higher))
        assert(lost.isEmpty, s"$higher loses ${lost.mkString(", ")} of $mode")
      }
    }
  }
