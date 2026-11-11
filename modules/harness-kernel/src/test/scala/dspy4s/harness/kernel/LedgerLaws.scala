package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import zio.blocks.schema.json.Json

import Fixtures.*
import Schedules.*

/** The laws of the ledger (CODE_HARNESS.md, 7.4; CODE_HARNESS_DESIGN.md, 8.3 and 8.7): a pure fold of the effects of a
  * journal.
  */
class LedgerLaws extends ScalaCheckSuite:

  private def path(p: String): Json = Json.Object(("path", Json.String(p)))

  private def command(c: String): Json = Json.Object(("command", Json.String(c)))

  private val value = EffectResult.Value(Json.Null, "")

  /** The entries of one call: its intent and its outcome. */
  private def call(n: Int, op: String, args: Json, result: EffectResult): Vector[Entry] =
    Vector(Entry.EffectStarted(EffectId(1L, n), op, args), Entry.EffectDone(EffectId(1L, n), result))

  private def ledgerOf(entries: Vector[Entry]): Ledger = ledger(journal(entries*))

  test("Ledger: a read with a value adds its path once, in the order of the first read") {
    val got = ledgerOf(
      call(0, "read", path("/w/b.txt"), value) ++ call(1, "read", path("/w/a.txt"), value) ++
        call(2, "read", path("/w/b.txt"), value) ++ call(3, "read", path("/w/c.txt"), EffectResult.Error("no file"))
    )
    assertEquals(got.read, Vector("/w/b.txt", "/w/a.txt"))
    assertEquals(got.made, Vector.empty)
  }

  test(
    "Ledger: a write or an edit with a value or Unknown adds its path to made, and an error or a pending call does not"
  ) {
    val pending = EffectResult.NeedsApproval(GuardRule("write", ArgMatch.Exact(path("/w/d.txt"))))
    val got     = ledgerOf(
      call(0, "write", path("/w/a.txt"), value) ++ call(1, "edit", path("/w/b.txt"), EffectResult.Unknown) ++
        call(2, "write", path("/w/c.txt"), EffectResult.Error("denied")) ++
        call(3, "write", path("/w/d.txt"), pending) ++
        call(4, "edit", path("/w/a.txt"), value)
    )
    assertEquals(got.made, Vector("/w/a.txt", "/w/b.txt"))
  }

  test("Ledger: exec and spawn with a value or Unknown count as commands, and an error does not") {
    val got = ledgerOf(
      call(0, "exec", command("ls"), value) ++ call(1, "spawn", command("sbt ~test"), EffectResult.Unknown) ++
        call(2, "exec", command("rm -rf /"), EffectResult.Error("denied")) ++ call(3, "now", Json.Null, value)
    )
    assertEquals(got.commands, 2)
  }

  test("Ledger: ScopeRecorded sets the head and the clean flag, and Released adds its step") {
    val step = ReleaseStep(ReleaseItem.KeepRef("/r", "refs/archive/s1", "feature", "a1"), ReleaseResult.Done)
    val got  = ledgerOf(
      Vector(Entry.ScopeRecorded("a1", false), Entry.ScopeRecorded("b2", true), Entry.Released(step))
    )
    assertEquals((got.head, got.clean, got.released), (Some("b2"), Some(true), Vector(step)))
  }

  property("Ledger: the ledger of a prefix of a journal has its reads, its writes and its steps as prefixes") {
    forAll(schedules) { run =>
      val all = ledger(run.journal)
      run.marks.forall { at =>
        val part = ledger(run.journal.take(at))
        all.read.startsWith(part.read) && all.made.startsWith(part.made) && all.commands >= part.commands &&
        all.released.startsWith(part.released)
      }
    }
  }

  property("Ledger: an entry that is not an effect, a ScopeRecorded or a Released changes nothing") {
    forAll(schedules) { run =>
      val other = run.journal.filter {
        case Logged(_, _, Entry.EffectStarted(_, _, _) | Entry.EffectDone(_, _)) => false
        case Logged(_, _, Entry.ScopeRecorded(_, _) | Entry.Released(_))         => false
        case _                                                                   => true
      }
      ledger(other) == Ledger.empty
    }
  }
