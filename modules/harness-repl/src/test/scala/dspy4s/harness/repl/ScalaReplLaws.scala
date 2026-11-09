package dspy4s.harness.repl

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome
import zio.blocks.schema.json.Json

import scala.concurrent.duration.*

/** The files of a test: each read gives a fixed text after `delayMillis`, and the writes go to a list. */
final class FakeFiles(delayMillis: Long = 0L) extends Files:
  @volatile var reads: Vector[String]            = Vector.empty
  @volatile var writes: Vector[(String, String)] = Vector.empty

  def read(path: String): String =
    if delayMillis > 0 then Thread.sleep(delayMillis)
    reads = reads :+ path
    s"the text of $path"

  def write(path: String, content: String): Unit                  = writes = writes :+ (path -> content)
  def edit(path : String, oldText: String, newText: String): Unit = writes = writes :+ (path -> s"$oldText>$newText")

/** The `Turn` of a test, with fixed inputs and a fixed clock. */
final class FakeTurn(inputs: Map[String, Json] = Map.empty) extends Turn:
  def now(): Long                       = 1000L
  def input(name: String): Option[Json] = inputs.get(name)

/** The laws of the REPL: the givens of the modes, the preamble from a shape, submit, and the protections of Phase 0. */
class ScalaReplLaws extends munit.FunSuite:

  override val munitTimeout: Duration = 5.minutes

  private val nothing    = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)
  private val readFiles  = nothing.copy(files = Access.Read)
  private val writeFiles = nothing.copy(files = Access.Full)

  private def repl(
      mode       : Mode              = readFiles,
      files      : FakeFiles         = FakeFiles(),
      preamble   : String            = "",
      inputs     : Map[String, Json] = Map.empty,
      cellTimeout: FiniteDuration    = 10.seconds,
      secondTry  : Boolean           = true
  ) =
    ScalaRepl(mode, Bindings(FakeTurn(inputs), files = Some(files)), preamble, cellTimeout, secondTry = secondTry)

  private def compileError(run: CellRun): Boolean =
    run.outcome match
      case CellOutcome.CompileError(_) => true
      case _                           => false

  private def pointPreamble(inputs: Vector[(String, DataShape)] = Vector.empty): String =
    preamble(summon[Data[ReplPoint]].shape, inputs).fold(e => fail(e), identity)

  // ── The givens of the modes (5.2) ──

  test("Mode givens: with the bare Files, a cell can read and cannot write") {
    val files = FakeFiles()
    val r     = repl(readFiles, files)
    try
      val read = r.run("""println(read("a.txt"))""")
      assertEquals(read.outcome, CellOutcome.Ok, read.output)
      assert(read.output.contains("the text of a.txt"), read.output)
      assert(compileError(r.run("""write("a.txt", "x")""")), "a cell wrote with the bare Files")
      assert(compileError(r.run("""edit("a.txt", "x", "y")""")), "a cell edited with the bare Files")
      assertEquals(files.writes, Vector.empty)
    finally r.close()
  }

  test("Mode givens: with Files^, a cell can write and edit") {
    val files = FakeFiles()
    val r     = repl(writeFiles, files)
    try
      assertEquals(r.run("""write("a.txt", "x")""").outcome, CellOutcome.Ok)
      assertEquals(r.run("""edit("a.txt", "x", "y")""").outcome, CellOutcome.Ok)
      assertEquals(files.writes, Vector("a.txt" -> "x", "a.txt" -> "x>y"))
    finally r.close()
  }

  test("Mode givens: a capability that the mode does not give does not compile") {
    val r = repl(readFiles)
    try for cell <- Vector(
                      """println(exec("ls"))""",
                      """println(httpGet("https://example.com"))""",
                      """store("k", 1)""",
                      """println(llmQuery("hi"))""",
                      """println(sessions())"""
                    )
      do assert(compileError(r.run(cell)), s"$cell compiled with only the bare Files")
    finally r.close()
  }

  test("Mode givens: a cell cannot name the host bindings") {
    val r = repl(writeFiles)
    try
      assert(compileError(r.run("""val f = host.files""")), "a cell named the bindings of the preamble")
      assert(compileError(r.run("""val b = dspy4s.harness.repl.HostBindings""")), "a cell named the host registry")
    finally r.close()
  }

  test("Mode givens: the bindings must have a handler for each capability that the mode gives") {
    val error =
      intercept[IllegalArgumentException](ScalaRepl(Mode.readOnly, Bindings(FakeTurn(), files = Some(FakeFiles()))))
    assert(error.getMessage.contains("llm, store, sessions"), error.getMessage)
  }

  // ── The preamble and submit (5.1) ──

  test("Shape: a type that a preamble defines from shape reads a value of the first type") {
    val shape = summon[Data[ReplPoint]].shape
    val value = ReplPoint(3, Some("n"))
    val r     =
      repl(preamble = pointPreamble(Vector("p" -> shape)), inputs = Map("p" -> summon[Data[ReplPoint]].encode(value)))
    try
      val run = r.run("""println(p.x + 1); println(p.note)""")
      assertEquals(run.outcome, CellOutcome.Ok, run.output)
      assertEquals(run.output.linesIterator.toVector, Vector("4", "Some(n)"))
    finally r.close()
  }

  test("Submit: a cell that calls submit ends with the first value, and defines no name") {
    val r = repl(preamble = pointPreamble())
    try
      val run = r.run("""val before = 1; submit(5, None); submit(6, None)""")
      assertEquals(run.outcome, CellOutcome.Submitted(summon[Data[ReplPoint]].encode(ReplPoint(5, None))), run.output)
      assert(compileError(r.run("println(before)")), "a cell that ended by submit defined a name")
    finally r.close()
  }

  test("Submit: after submit, the host refuses each later call of the cell and a second submit") {
    val files = FakeFiles()
    val r     = repl(files = files, preamble = pointPreamble())
    try
      val run = r.run(
        """try submit(5, None) catch case _: Throwable => ()
          |val afterRead = scala.util.Try(read("a.txt")).isFailure
          |val afterSubmit = scala.util.Try(submit(6, None)).isFailure
          |println(s"$afterRead $afterSubmit")""".stripMargin
      )
      assertEquals(run.outcome, CellOutcome.Submitted(summon[Data[ReplPoint]].encode(ReplPoint(5, None))), run.output)
      assert(run.output.contains("true true"), run.output)
      assertEquals(files.reads, Vector.empty)
      val next = r.run("""println(read("b.txt"))""")
      assertEquals(next.outcome, CellOutcome.Ok, "a later cell must run again")
    finally r.close()
  }

  // ── The protections of Phase 0 and the clock of a cell (6) ──

  test("A definition stays for later cells, and a compile error changes nothing") {
    val r = repl()
    try
      assertEquals(r.run("val xs = Vector(1, 2, 3)").outcome, CellOutcome.Ok)
      assert(compileError(r.run("""val broken: Int = "x"""")))
      val sum = r.run("println(xs.sum)")
      assertEquals((sum.outcome, sum.output), (CellOutcome.Ok, "6"))
    finally r.close()
  }

  test("A cell that fails at run time defines no name") {
    val r = repl()
    try
      val failed = r.run("val boom: Int = 1 / 0")
      assert(failed.outcome.isInstanceOf[CellOutcome.Failed], failed.toString)
      assert(compileError(r.run("println(boom)")), "a failed cell defined a name")
    finally r.close()
  }

  test("Cell clock: the timeout stops a loop in guest code, and a later cell runs") {
    val r = repl(cellTimeout = 1.second)
    try
      // Capture checking refuses a top-level `var`, so the loop is in a block.
      val loop = r.run("locally { var n = 0L; while true do n += 1 }")
      assertEquals(loop.outcome, CellOutcome.TimedOut(1000L))
      assert(!r.unclean, "the thread of the cell did not stop")
      assertEquals(r.run("println(1 + 1)").output, "2")
    finally r.close()
  }

  test("Cell clock: the clock stops during a guest API call") {
    val r = repl(files = FakeFiles(delayMillis = 2500L), cellTimeout = 2.seconds)
    try
      val slow = r.run("""println(read("a.txt").length)""")
      assertEquals((slow.outcome, slow.output), (CellOutcome.Ok, "17"), slow.toString)
    finally r.close()
  }

  test("Second try: a cell that the capture checker rejects for the enclosing object compiles in two parts") {
    val cell = """case class P(n: Int) derives Data
        |def size(path: String): Int = read(path).length""".stripMargin
    val once = repl(secondTry = false)
    try
      val whole = once.run(cell)
      assert(
        whole.outcome match
          case CellOutcome.CompileError(lines) => lines.exists(_.contains("of the enclosing object"))
          case _                               => false
        ,
        s"the cell must fail in one part: $whole"
      )
    finally once.close()
    val r = repl()
    try
      assertEquals(r.run(cell).outcome, CellOutcome.Ok)
      assertEquals(r.run("""println(size("a.txt") + P(1).n)""").output, "18")
    finally r.close()
  }

  test("Heap guard: under heap pressure, the guard stops the running cell, and its REPL closes") {
    val r      = repl(cellTimeout = 60.seconds)
    val before = HeapGuard.pressure
    try
      HeapGuard.pressure = () => true
      val stopped = r.run("locally { var n = 0L; while true do n += 1 }")
      HeapGuard.pressure = before
      assert(stopped.outcome.isInstanceOf[CellOutcome.Failed], stopped.toString)
      assert(r.outOfMemory, "the REPL must close after the heap guard stops its cell")
    finally
      HeapGuard.pressure = before
      r.close()
  }

  test("A recursive type and two types that hold each other need no extra given in a cell") {
    val r = repl()
    try
      val recursive = r.run(
        """case class Dir(name: String, kids: Vector[Dir])
          |val d = Dir("a", Vector(Dir("b", Vector.empty)))
          |println(summon[Data[Dir]].decode(summon[Data[Dir]].encode(d), "d") == Right(d))""".stripMargin
      )
      assertEquals(
        (recursive.outcome, recursive.output.linesIterator.next()),
        (CellOutcome.Ok, "true"),
        recursive.output
      )
      val mutual = r.run(
        """case class A(b: Option[B])
          |case class B(a: Option[A])
          |val a = A(Some(B(Some(A(None)))))
          |println(summon[Data[A]].decode(summon[Data[A]].encode(a), "a") == Right(a))""".stripMargin
      )
      assertEquals((mutual.outcome, mutual.output.linesIterator.next()), (CellOutcome.Ok, "true"), mutual.output)
    finally r.close()
  }

/** A record for the preamble tests. It is top-level, so the host and the REPL have the same shape. */
final case class ReplPoint(x: Int, note: Option[String])
