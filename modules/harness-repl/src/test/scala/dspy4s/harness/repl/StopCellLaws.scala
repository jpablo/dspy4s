package dspy4s.harness.repl

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** The laws of `ScalaRepl.stopCell` (CODE_HARNESS_DESIGN.md, 10.3, phase 7): the stop of one cell keeps the REPL. */
class StopCellLaws extends munit.FunSuite:

  override val munitTimeout: Duration = 5.minutes

  private val readFiles =
    Mode(Access.Read, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)

  private def repl(): ScalaRepl = ScalaRepl(readFiles, Bindings(FakeTurn(), files = Some(FakeFiles())), "", 60.seconds)

  test(
    "Stop cell: stopCell ends the running cell with a failure, and the REPL keeps the definitions of earlier cells"
  ) {
    val r = repl()
    try
      assertEquals(r.run("val kept = 7").outcome, CellOutcome.Ok)
      val result = AtomicReference[CellRun]()
      val cell   = Thread.ofPlatform().start(() => result.set(r.run("locally { var n = 0L; while true do n += 1 }")))
      // The cell compiles first. A stop before the cell runs must also end it.
      Thread.sleep(3_000)
      r.stopCell()
      cell.join(20_000)
      assert(!cell.isAlive, "the cell did not end after stopCell")
      result.get.outcome match
        case CellOutcome.Failed(_) => ()
        case other                 => fail(s"not a failure: $other")
      assert(!r.unclean)
      assertEquals(r.run("println(kept)").output, "7")
    finally r.close()
  }

  test("Stop cell: stopCell with no running cell does nothing, and the next cell runs") {
    val r = repl()
    try
      r.stopCell()
      assertEquals(r.run("println(1 + 1)").output, "2")
    finally r.close()
  }
