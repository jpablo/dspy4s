package dspy4s.harness.repl

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome
import zio.blocks.schema.json.Json

import scala.concurrent.duration.*

/** The files of a test: each read gives a fixed text, and the writes go to a list. */
final class FakeFiles extends Files:
  @volatile var writes: Vector[(String, String)]  = Vector.empty
  def read(path : String): String                 = s"the text of $path"
  def write(path: String, content: String): Unit  = writes = writes :+ (path -> content)

/** The `Turn` of a test, with fixed inputs and a fixed clock. */
final class FakeTurn(inputs: Map[String, Json] = Map.empty) extends Turn:
  def now(): Long                       = 1000L
  def input(name: String): Option[Json] = inputs.get(name)

/** The laws of the REPL: the givens of the modes, the preamble from a shape, submit, and the protections of Phase 0. */
class ScalaReplLaws extends munit.FunSuite:

  override val munitTimeout: Duration = 5.minutes

  private def repl(
      mode    : Mode,
      files   : FakeFiles         = FakeFiles(),
      preamble: String            = "",
      inputs  : Map[String, Json] = Map.empty
  ) =
    ScalaRepl(mode, Bindings(FakeTurn(inputs), files = Some(files)), preamble, cellTimeout = 10.seconds)

  private def compileError(run: CellRun): Boolean =
    run.outcome match
      case CellOutcome.CompileError(_) => true
      case _                           => false

  test("Mode givens: in read-only mode, a cell can read and cannot write") {
    val files = FakeFiles()
    val r     = repl(Mode.readOnly, files)
    try
      val read = r.run("""println(read("a.txt"))""")
      assertEquals(read.outcome, CellOutcome.Ok, read.output)
      assert(read.output.contains("the text of a.txt"), read.output)
      val write = r.run("""write("a.txt", "x")""")
      assert(compileError(write), write.toString)
      assertEquals(files.writes, Vector.empty)
    finally r.close()
  }

  test("Mode givens: in full mode, a cell can write") {
    val files = FakeFiles()
    val r     = repl(Mode.full, files)
    try
      val write = r.run("""write("a.txt", "x")""")
      assertEquals(write.outcome, CellOutcome.Ok, write.output)
      assertEquals(files.writes, Vector("a.txt" -> "x"))
    finally r.close()
  }

  test("Mode givens: a cell cannot name the host bindings") {
    val r = repl(Mode.full)
    try
      assert(compileError(r.run("""val f = host.files""")), "a cell named the bindings of the preamble")
      assert(compileError(r.run("""val b = dspy4s.harness.repl.HostBindings""")), "a cell named the host registry")
    finally r.close()
  }

  test("Shape: a type that a preamble defines from shape reads a value of the first type") {
    val shape = summon[Data[ReplPoint]].shape
    val text  = preamble(shape, Vector("p" -> shape)).fold(e => fail(e), identity)
    val value = ReplPoint(3, Some("n"))
    val r     = repl(Mode.readOnly, preamble = text, inputs = Map("p" -> summon[Data[ReplPoint]].encode(value)))
    try
      val run = r.run("""println(p.x + 1); println(p.note)""")
      assertEquals(run.outcome, CellOutcome.Ok, run.output)
      assertEquals(run.output.linesIterator.toVector, Vector("4", "Some(n)"))
    finally r.close()
  }

  test("Submit: a cell that calls submit ends with the first value, and defines no name") {
    val shape = summon[Data[ReplPoint]].shape
    val text  = preamble(shape).fold(e => fail(e), identity)
    val r     = repl(Mode.readOnly, preamble = text)
    try
      val run = r.run("""val before = 1; submit(5, None); submit(6, None)""")
      assertEquals(run.outcome, CellOutcome.Submitted(summon[Data[ReplPoint]].encode(ReplPoint(5, None))), run.output)
      assert(compileError(r.run("println(before)")), "a cell that ended by submit defined a name")
    finally r.close()
  }

  test("A definition stays for later cells, and a compile error changes nothing") {
    val r = repl(Mode.readOnly)
    try
      assertEquals(r.run("val xs = Vector(1, 2, 3)").outcome, CellOutcome.Ok)
      assert(compileError(r.run("""val broken: Int = "x"""")))
      val sum = r.run("println(xs.sum)")
      assertEquals((sum.outcome, sum.output), (CellOutcome.Ok, "6"))
    finally r.close()
  }

  test("A cell that fails at run time defines no name") {
    val r = repl(Mode.readOnly)
    try
      val failed = r.run("val boom: Int = 1 / 0")
      assert(failed.outcome.isInstanceOf[CellOutcome.Failed], failed.toString)
      assert(compileError(r.run("println(boom)")), "a failed cell defined a name")
    finally r.close()
  }

  test("The timeout stops a loop in guest code, and a later cell runs") {
    val r = ScalaRepl(Mode.readOnly, Bindings(FakeTurn()), cellTimeout = 1.second)
    try
      // Capture checking refuses a top-level `var`, so the loop is in a block.
      val loop = r.run("locally { var n = 0L; while true do n += 1 }")
      assertEquals(loop.outcome, CellOutcome.TimedOut(1000L))
      assert(!r.unclean, "the thread of the cell did not stop")
      assertEquals(r.run("println(1 + 1)").output, "2")
    finally r.close()
  }

  test("A recursive type and two types that hold each other need no extra given in a cell") {
    val r = repl(Mode.readOnly)
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
