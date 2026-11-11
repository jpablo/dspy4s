package dspy4s.harness.repl

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome
import zio.blocks.schema.json.Json

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

/** The files of a test whose read waits for `released`, at most 30 seconds. The read keeps its thread, `reached` counts
  * down when the read starts, and `interrupted` is true when an interrupt ended the wait.
  */
final class BlockingFiles extends Files:
  val reached: CountDownLatch          = CountDownLatch(1)
  val released: CountDownLatch         = CountDownLatch(1)
  @volatile var reader: Option[Thread] = None
  @volatile var interrupted: Boolean   = false

  def read(path: String): String =
    reader = Some(Thread.currentThread())
    reached.countDown()
    try released.await(30, TimeUnit.SECONDS): Unit
    catch
      case e: InterruptedException =>
        interrupted = true
        throw e
    s"the text of $path"

  def write(path: String, content: String): Unit                  = ()
  def edit(path : String, oldText: String, newText: String): Unit = ()

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
  def zoom(ref  : String): String       = s"the part $ref of the memory"

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

  test("Mode givens: zoom(ref) needs no capability, so it compiles in each mode, and it reads the turn") {
    for mode <- Vector(nothing, readFiles, writeFiles) do
      val r = ScalaRepl(mode, Bindings(FakeTurn(), files = Option.when(mode.files != Access.None)(FakeFiles())))
      try assertEquals(r.run("""println(zoom("3.5"))""").output, "the part 3.5 of the memory")
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
      val broken = r.run("""val broken: Int = "x"""")
      assert(compileError(broken), s"${broken.outcome}: ${broken.output}")
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

  // A live run found that a chain of Array operations did not compile in a cell: the result of `take` or `map` had
  // the type `Array[...]^{any.rd}`, and the next Array operation needs an Array with no capture set. A plain compile
  // with the same options compiles the same code.
  test("Arrays: a chain of Array operations compiles in a cell, at the top and in a def") {
    val r = repl()
    try
      val chain = r.run("println(Array(1, 2, 3).map(_ + 1).sum)")
      assertEquals((chain.outcome, chain.output.trim), (CellOutcome.Ok, "9"), chain.toString)
      val lines = r.run("val lines = \"a\\nb\\nc\".split(\"\\n\")\nprintln(lines.take(2).toList)")
      assertEquals(lines.outcome, CellOutcome.Ok, lines.toString)
      assert(lines.output.contains("List(a, b)"), lines.output)
      val total =
        r.run("def total(text: String): Int = text.split(\",\").map(_.trim.toInt).sum\nprintln(total(\"1, 2, 3\"))")
      assertEquals(total.outcome, CellOutcome.Ok, total.toString)
      assert(total.output.contains("6"), total.output)
    finally r.close()
  }

  // The cause was a top-level val that holds a value with capabilities. A cell of the model can make such a val too.
  test("Arrays: a chain compiles after a cell that keeps an Array or a capability in a typed val") {
    val r = repl(writeFiles)
    try
      val kept = r.run("val kept: Array[Int] = Array(1, 2, 3)")
      assertEquals(kept.outcome, CellOutcome.Ok, kept.toString)
      val handler = r.run("val handler: Files^ = summon[Files]")
      assertEquals(handler.outcome, CellOutcome.Ok, handler.toString)
      val chain = r.run("println(Array(4, 5).map(_ + 1).sum)")
      assertEquals((chain.outcome, chain.output.trim), (CellOutcome.Ok, "11"), chain.toString)
      val again = r.run("println(kept.map(_ * 2).sum)")
      assertEquals((again.outcome, again.output.trim), (CellOutcome.Ok, "12"), again.toString)
    finally r.close()
  }

  // A defect of the compiler of the 3.10 nightly, also in a bare REPL with no code of the harness: in safe mode, after a
  // failed top-level val with no type, such as `val inferred = summon[Files]`, a later Array chain does not compile.
  // The REPL hides the wrapper of the failed cell, so the compiler keeps the state. The harness starts a new REPL
  // (`needsRestart`). This test is an expected failure: when it passes, a compiler fix came, and the restart can go.
  test("Known compiler defect: after a failed val with no type, a later Array chain compiles".fail) {
    val r = repl(writeFiles)
    try
      assert(compileError(r.run("val inferred = summon[Files]")), "the val needs an explicit type in safe mode")
      val chain = r.run("println(Array(4, 5).map(_ + 1).sum)")
      assertEquals((chain.outcome, chain.output.trim), (CellOutcome.Ok, "11"), chain.toString)
    finally r.close()
  }

  test("Restart: a compile error that confuses the compiler sets needsRestart, and another compile error does not") {
    val r = repl(writeFiles)
    try
      assert(compileError(r.run("println(noSuchName)")))
      assert(!r.needsRestart, "a compile error of a name confuses nothing")
      assert(compileError(r.run("val xs = Array(1, 2, 3)")))
      assert(r.needsRestart, "a failed val with no type confuses the compiler")
    finally r.close()
  }

  // A cell of the model can break the Array chains of later cells with no error of its own: a top-level def whose
  // signature names a type of the same cell. The error of the next chain is the symptom, so it sets needsRestart.
  test("Restart: the Array symptom after an earlier cell sets needsRestart") {
    val r = repl()
    try
      val trigger = r.run("case class I(x: Int)\ndef f(i: I): Int = i.x")
      assertEquals(trigger.outcome, CellOutcome.Ok, trigger.toString)
      assert(!r.needsRestart, "the cell compiled, so nothing asks for a restart yet")
      assert(compileError(r.run("println(Array(4, 5).map(_ + 1).sum)")), "the chain shows the symptom")
      assert(r.needsRestart, "the symptom after an earlier cell asks for a new REPL")
    finally r.close()
  }

  // An explicit empty capture set on a new Array gives the text of the symptom in each REPL, so it is a real error. A
  // new REPL has no state of an earlier cell, so the same error in its first cell does not set needsRestart. The failed
  // cell also breaks the later Array chains, and the next chain then sets needsRestart, as after each earlier cell.
  test("Restart: the Array symptom in the first cell of a new REPL does not set needsRestart") {
    val r = repl()
    try
      val run = r.run("locally { val pure: Array[Int]^{} = Array(1, 2); println(pure.sum) }")
      assert(compileError(run) && showsArrayDefect(run.output), run.toString)
      assert(!r.needsRestart, "the first cell has no earlier cell, so the error is a real error")
    finally r.close()
  }

  // A preamble of each kind of type must leave a new REPL clean: a recursive type, and a record with a field of another
  // type of the preamble. A top-level `def` that names a type of the same chunk breaks later Array chains, so `submit`
  // is a member of an object.
  test("Arrays: a chain compiles after a preamble with a recursive type and a nested record") {
    Vector(summon[Data[ReplTree]].shape, summon[Data[ReplOuter]].shape).foreach { shape =>
      val r = repl(preamble = preamble(shape).fold(e => fail(e), identity))
      try
        val chain = r.run("println(Array(1, 2, 3).map(_ + 1).sum)")
        assertEquals((chain.outcome, chain.output.trim), (CellOutcome.Ok, "9"), s"$shape: $chain")
      finally r.close()
    }
  }

  // A top-level def or val whose type has a collection, such as `Vector[String]`, also breaks later Array chains. An
  // input of a task is often a collection.
  test("Arrays: a chain compiles after a preamble with an input val of a collection type") {
    val r = repl(
      preamble = pointPreamble(Vector("docs" -> summon[Data[Vector[String]]].shape)),
      inputs = Map("docs" -> Json.Array(Json.String("a"), Json.String("b")))
    )
    try
      val chain = r.run("println(Array(1, 2, 3).map(_ + 1).sum)")
      assertEquals((chain.outcome, chain.output.trim), (CellOutcome.Ok, "9"), chain.toString)
      val docs = r.run("println(docs.size)")
      assertEquals((docs.outcome, docs.output.trim), (CellOutcome.Ok, "2"), docs.toString)
    finally r.close()
  }

  test("Preamble: an enum with a case named Data compiles in a REPL") {
    val r = repl(preamble = preamble(summon[Data[ReplMessage]].shape).fold(e => fail(e), identity))
    try
      val run = r.run("println(ReplMessage.Ack)")
      assertEquals((run.outcome, run.output.trim), (CellOutcome.Ok, "Ack"), run.toString)
    finally r.close()
  }

  // Each harness REPL has a preamble from a shape. A top-level given that `Data.derived` builds gave each Array result
  // of a later cell a capture set, so the REPL of each harness call was broken before its first cell.
  test("Arrays: a chain compiles in a REPL with a preamble from a shape and an input field") {
    val r = repl(
      preamble = pointPreamble(Vector("label" -> summon[Data[String]].shape)),
      inputs = Map("label" -> Json.String("fake"))
    )
    try
      val chain = r.run("println(Array(1, 2, 3).map(_ + 1).sum)")
      assertEquals((chain.outcome, chain.output.trim), (CellOutcome.Ok, "9"), chain.toString)
      val typed = r.run("println(ReplPoint(1, None).x + label.length)")
      assertEquals((typed.outcome, typed.output.trim), (CellOutcome.Ok, "5"), typed.toString)
    finally r.close()
  }

  test("Arrays: a top-level val of a new Array with no type gets the hint of an explicit type") {
    val r = repl()
    try
      val run = r.run("val xs = Array(1, 2, 3)")
      run.outcome match
        case CellOutcome.CompileError(lines) =>
          val hints = lines.filter(_.startsWith("Hint:"))
          assert(hints.exists(_.contains("explicit type")), lines.mkString("\n"))
          assert(!hints.exists(_.contains("case Array(x, y)")), lines.mkString("\n"))
        case other => fail(s"the cell must not compile in safe mode: $other")
    finally r.close()
  }

  // The REPL shows the value of each val that a cell defines. A long value then fills the observation a second time.
  test("Echo: a long val gets a short echo, and println shows its whole text") {
    val r = repl()
    try
      val kept = r.run("val big = \"x\" * 5000")
      assertEquals(kept.outcome, CellOutcome.Ok, kept.toString)
      assert(kept.output.length < 1_000, s"${kept.output.length} characters of echo")
      val printed = r.run("println(\"y\" * 5000)")
      assertEquals(printed.output.count(_ == 'y'), 5_000)
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

  // The parallel executor interrupts the thread of a program call, and that thread waits in `run` (9).
  test(
    "Cell clock: an interrupt of the thread that runs a cell stops the cell, so the cell does not outlive the caller"
  ) {
    val files = BlockingFiles()
    val r     = ScalaRepl(readFiles, Bindings(FakeTurn(), files = Some(files)), cellTimeout = 30.seconds)
    try
      val thrown = AtomicBoolean(false)
      val caller = Thread(() =>
        try r.run("""println(read("a.txt"))"""): Unit
        catch case _: InterruptedException => thrown.set(true)
      )
      caller.start()
      assert(files.reached.await(30, TimeUnit.SECONDS), "the cell did not reach the read")
      caller.interrupt()
      caller.join(30_000)
      assert(thrown.get, "the interrupt did not go on to the caller of run")
      assert(files.reader.exists(!_.isAlive), "the thread of the cell still runs after the interrupt")
      assert(files.interrupted, "the read did not get the interrupt")
    finally
      files.released.countDown()
      r.close()
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

  test("Hints: sys.error does not compile in a safe cell, and its hint names a throw that fails the cell") {
    // In a live session of 2026-11-10, the model used sys.error to fail with a message, and the error had no hint.
    val r = repl()
    try
      r.run("""sys.error("FAKE-FAIL")""").outcome match
        case CellOutcome.CompileError(lines) =>
          val hints = lines.filter(_.startsWith("Hint:"))
          assert(hints.exists(_.contains("throw IllegalStateException(")), lines.mkString("\n"))
        case other => fail(s"sys.error compiled: $other")
      r.run("""throw IllegalStateException("FAKE-FAIL")""").outcome match
        case CellOutcome.Failed(error) => assert(error.contains("FAKE-FAIL"), error)
        case other                     => fail(s"the throw of the hint did not fail the cell: $other")
    finally r.close()
  }

/** A record for the preamble tests. It is top-level, so the host and the REPL have the same shape. */
final case class ReplPoint(x: Int, note: Option[String])

/** A recursive type for the preamble tests. */
final case class ReplTree(name: String, kids: Vector[ReplTree])

object ReplTree:
  given Data[ReplTree] = Data.derived

/** A record with a field of another type of the preamble. */
final case class ReplOuter(point: ReplPoint, label: String)

/** An enum with a case that has the name of the type class `Data`. */
enum ReplMessage:
  case Data(n: Int)
  case Ack
