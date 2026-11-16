package dspy4s.harness.repl

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JavaFiles, Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The laws of the doc cells: each cell of a page of the site compiles and runs in the mode of its file. A page
  * includes its cells from the files of `src/test/resources/doc-cells`, so a change of the guest API that breaks a cell
  * of a page fails here. The README.md of that folder defines the format.
  */
class DocCellLaws extends munit.FunSuite:
  import DocCellLaws.*

  override val munitTimeout: Duration = 5.minutes

  test("Doc cells: the parser takes the regions in order, with the outcome of each, and refuses a broken file") {
    val text = """// mode: read
                 |// A comment outside the regions.
                 |
                 |// --8<-- [start:first-cell]
                 |val a = 1
                 |  println(a)
                 |// --8<-- [end:first-cell]
                 |// expect: compile-error
                 |// --8<-- [start:second]
                 |write("a.txt", "x")
                 |// --8<-- [end:second]""".stripMargin
    val cells = Vector(
      Cell("first-cell", "val a = 1\n  println(a)", Expected.Runs),
      Cell("second", """write("a.txt", "x")""", Expected.CompileError)
    )
    assertEquals(parse(text), Right(CellFile(Mode.readOnly, cells)))
    val start = "// --8<-- [start:a]"
    val end   = "// --8<-- [end:a]"
    for broken <- Vector(
                    s"$start\nval a = 1\n$end",
                    "// mode: none",
                    "// mode: read\nval a = 1",
                    s"// mode: read\n$start\nval a = 1",
                    s"// mode: read\n$start\n$end",
                    s"// mode: read\n// --8<-- [start:A]\nval a = 1\n// --8<-- [end:A]",
                    s"// mode: read\n$start\nval a = 1\n$end\n$start\nval b = 2\n$end",
                    s"// mode: read\n$start\n// --8<-- [start:b]\nval a = 1\n$end",
                    s"// mode: read\n// expect: compile-error\n\n$start\nval a = 1\n$end"
                  )
    do assert(parse(broken).isLeft, s"the parser took a broken file:\n$broken")
  }

  test("Doc cells: the folder has cell files") {
    assert(cellFiles.nonEmpty, "no *.scala file in the folder doc-cells of the test resources")
  }

  for file <- cellFiles do
    val name = file.getFileName.toString
    test(s"Doc cells: each cell of $name compiles and ends with its outcome, in the mode of the file") {
      val parsed   = parse(JavaFiles.readString(file, UTF_8)).fold(e => fail(s"$name: $e"), identity)
      val submitOf = preamble(summon[Data[String]].shape).fold(e => fail(e), identity)
      val r        = ScalaRepl(parsed.mode, bindings(), submitOf)
      try parsed.cells.foreach { cell =>
          val run      = r.run(cell.code)
          val expected = cell.expected match
            case Expected.CompileError                                     => "CompileError"
            case Expected.Runs if Submits.findFirstIn(cell.code).isDefined => "Submitted"
            case Expected.Runs                                             => "Ok"
          val obtained = run.outcome match
            case CellOutcome.Ok              => "Ok"
            case CellOutcome.CompileError(_) => "CompileError"
            case CellOutcome.Submitted(_)    => "Submitted"
            case CellOutcome.Failed(_)       => "Failed"
            case CellOutcome.TimedOut(_)     => "TimedOut"
          assertEquals(obtained, expected, s"the cell ${cell.name} of $name gave this text:\n${run.text}")
          assert(!r.needsRestart, s"after the cell ${cell.name} of $name, the REPL needs a restart:\n${run.text}")
        }
      finally r.close()
    }

object DocCellLaws:

  /** What a cell must give: `Runs` is `Ok`, or `Submitted` for a cell that calls `submit`. */
  enum Expected derives CanEqual:
    case Runs, CompileError

  final case class Cell(name: String, code: String, expected: Expected) derives CanEqual

  final case class CellFile(mode: Mode, cells: Vector[Cell]) derives CanEqual

  /** The modes of the mode comment. `work` and `controller` are the modes of the sessions of the terminal client:
    * `WorkRuntime.workMode` and `Controller.mode` of harness-host. This module does not see harness-host, so the two
    * modes are copies, and a change there needs the same change here.
    */
  val modes: Map[String, Mode] = Map(
    "work"       -> Mode.local.copy(sessions = Access.None, children = Access.None),
    "controller" -> Mode.controller.copy(children = Access.None),
    "full"       -> Mode.full,
    "local"      -> Mode.local,
    "read"       -> Mode.readOnly,
    "closed"     -> Mode.closed
  )

  /** The files of `MemoryFiles` at the start of each cell file. */
  val workspace: Map[String, String] = Map(
    "README.md"                 -> "# app\n\nA small Scala 3 app.\n\nRun it with `sbt run`.\n",
    "build.sbt"                 -> "scalaVersion := \"3.10.1\"\n\nname := \"app\"\n",
    "src/main/scala/Main.scala" -> "@main def hello(): Unit =\n  println(\"hello\")\n"
  )

  /** A handler for each capability. The mode decides which of them the REPL binds. */
  def bindings(): Bindings =
    Bindings(
      FakeTurn(),
      files = Some(MemoryFiles(workspace)),
      shell = Some(FakeShell()),
      network = Some(FakeNetwork()),
      llm = Some(FakeLlm()),
      store = Some(FakeStore()),
      sessions = Some(FakeSessions()),
      children = Some(FakeChildren())
    )

  /** The cell files, by name. The build copies the folder from `src/test/resources`. */
  lazy val cellFiles: Vector[Path] = Option(getClass.getResource("/doc-cells")).fold(Vector.empty[Path]) { url =>
    val stream = JavaFiles.list(Paths.get(url.toURI))
    try stream.iterator.asScala.filter(_.getFileName.toString.endsWith(".scala")).toVector.sortBy(_.toString)
    finally stream.close()
  }

  /** The mode and the cells of the text of a cell file, or the first error of its format. */
  def parse(text: String): Either[String, CellFile] =
    val lines = text.linesIterator.toList
    lines.headOption.map(_.trim) match
      case Some(ModeLine(word)) => modes.get(word) match
          case Some(mode) =>
            regions(lines.drop(1).zip(LazyList.from(2)), None, false, Vector.empty).map(CellFile(mode, _))
          case None => Left(s"line 1 names the unknown mode $word")
      case _ => Left("line 1 is not a mode comment, for example // mode: work")

  /** A region that started and has no end yet. */
  private final case class Open(name: String, line: Int, expected: Expected, code: Vector[String])

  @annotation.tailrec
  private def regions(
      lines : List[(String, Int)],
      open  : Option[Open],
      expect: Boolean,
      done  : Vector[Cell]
  ): Either[String, Vector[Cell]] =
    lines match
      case Nil               => open.fold(Right(done))(o => Left(s"the region ${o.name} of line ${o.line} has no end"))
      case (line, n) :: rest => (line.trim, open) match
          case (Start(name), None) =>
            if !RegionName.matches(name) then Left(s"line $n: the region name $name is not kebab-case")
            else if done.exists(_.name == name) then Left(s"line $n: a second region has the name $name")
            else
              val expected = if expect then Expected.CompileError else Expected.Runs
              regions(rest, Some(Open(name, n, expected, Vector.empty)), false, done)
          case (Start(name), Some(o))                 => Left(s"line $n: the region $name starts in the region ${o.name}")
          case (End(name), Some(o)) if name == o.name =>
            if o.code.forall(_.trim.isEmpty) then Left(s"line $n: the region $name is empty")
            else regions(rest, None, false, done :+ Cell(o.name, o.code.mkString("\n"), o.expected))
          case (End(name), _)                               => Left(s"line $n: the end of the region $name has no start")
          case (_, Some(o))                                 => regions(rest, Some(o.copy(code = o.code :+ line)), false, done)
          case (_, None) if expect                          => Left(s"line ${n - 1}: the expect line is not just before a start line")
          case (ExpectError, None)                          => regions(rest, None, true, done)
          case (t, None) if t.isEmpty || t.startsWith("//") => regions(rest, None, false, done)
          case (_, None)                                    => Left(s"line $n: the line is outside a region, and it is not a comment")

  private val ModeLine    = """// mode: (\S+)""".r
  private val Start       = """// --8<-- \[start:([^\]]*)\]""".r
  private val End         = """// --8<-- \[end:([^\]]*)\]""".r
  private val RegionName  = """[a-z0-9]+(-[a-z0-9]+)*""".r
  private val ExpectError = "// expect: compile-error"
  private val Submits     = """\bsubmit\(""".r
