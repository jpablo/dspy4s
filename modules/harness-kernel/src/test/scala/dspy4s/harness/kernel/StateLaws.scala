package dspy4s.harness.kernel

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

import Fixtures.*
import Schedules.*

/** The laws Fold, Homomorphism, Store and Inputs of 4.7, and the law of the journal codec (4.1). */
class StateLaws extends ScalaCheckSuite:

  property("Fold: the live state of a run is the state of its journal") {
    forAll(schedules)(run => assertEquals(state(run.journal), run.live))
  }

  property("Model: a ModelChanged changes only the model of the configuration, at each place of a run") {
    // The effort of the next call to the policy comes from this model (CLI_SCREEN.md, 14.4).
    val model = ModelChoice("other", Map("reasoning_effort" -> Json.String("high")))
    forAll(states) { s =>
      val after = evolve(s, Logged(s.nextSeq, meta, Entry.ModelChanged(model)))
      assertEquals(after, s.copy(nextSeq = s.nextSeq + 1, config = s.config.copy(model = model)))
    }
  }

  property("Fold: state is the fold of evolve from the initial state") {
    forAll(schedules)(run => assertEquals(state(run.journal), run.journal.foldLeft(SessionState.initial)(evolve)))
  }

  property("Homomorphism: state(a ++ b) is the fold of b from state(a)") {
    forAll(schedules, Gen.choose(0.0, 1.0)) { (run, at) =>
      val (a, b) = run.journal.splitAt((run.journal.size * at).toInt)
      assertEquals(state(a ++ b), b.foldLeft(state(a))(evolve))
    }
  }

  property("Store: the store is the fold of the writes of the Observed entries") {
    forAll(schedules) { run =>
      val writes = run.journal.collect { case Logged(_, _, Entry.Observed(_, _, w)) => w }
      assertEquals(state(run.journal).store, writes.foldLeft(Map.empty[String, Json])(_ ++ _))
    }
  }

  property("Store: the Observed entry of a repair writes nothing") {
    forAll(states) { s =>
      val writes = repair(s).collect { case Entry.Observed(_, _, w) => w }
      assert(writes.forall(_.isEmpty), s"a repair wrote $writes")
    }
  }

  property("Inputs: inputs is the fold of the values of the Input entries") {
    forAll(schedules) { run =>
      val values = run.journal.collect { case Logged(_, _, Entry.Input(_, _, v)) => v }
      assertEquals(state(run.journal).inputs, values.foldLeft(Map.empty[String, Json])(_ ++ _))
    }
  }

  property("Inputs: a repeat of a Send adds nothing") {
    forAll(states) { s =>
      val send = Command.Send(RequestId("law-inputs"), "values", Map("x" -> Json.String("1")))
      decide(send, s) match
        case Left(_)        => ()
        case Right(entries) =>
          val once = commit(s, entries)
          assertEquals(decide(send, once), Right(Vector.empty))
    }
  }

  property("Inputs: a fork keeps the inputs") {
    forAll(schedules)(run => assertEquals(forked(run.journal).inputs, state(run.journal).inputs))
  }

  test("Journal codec: an Activated with no host facts decodes with none, and each fact of the host is in the text") {
    val codec = Schema[Logged].jsonCodec
    // A journal of an older host has no field `host`, so its text is the text of a config with no facts.
    val old = Logged(0L, Fixtures.meta, Fixtures.activated())
    assert(!codec.encodeToString(old).contains("\"host\""), codec.encodeToString(old))
    assertEquals(codec.decode(codec.encode(old)), Right(old))
    val facts = Fixtures.hostFacts
    val now   = Logged(0L, Fixtures.meta, Fixtures.activated(Fixtures.config().copy(host = Some(facts))))
    assertEquals(codec.decode(codec.encode(now)), Right(now))
    // A fact with the same value as a default would go out of the text, and a later default would then change it.
    val text = codec.encodeToString(now)
    Vector("cellMillis", "callsPerCell", "summaryCalls", "backend", "secretNames", "env")
      .foreach(field => assert(text.contains(s"\"$field\""), s"$field is not in $text"))
  }

  test("Settings: the mode, the limits and the sandbox in words") {
    import dspy4s.harness.guest.{Access, Mode}
    val work = Mode(Access.Full, Access.Full, Access.None, Access.Full, Access.None, Access.None, Access.Full)
    assertEquals(modeWords(work), "Files^, Shell^, Llm^ and Store^")
    assertEquals(modeWords(work.copy(files = Access.Read, shell = Access.None)), "Files, Llm^ and Store^")
    assertEquals(
      modeWords(work.meet(Mode(
        Access.None,
        Access.None,
        Access.None,
        Access.None,
        Access.None,
        Access.None,
        Access.None
      ))),
      "no capability"
    )
    val limits = limitLines(Fixtures.hostFacts.limits).toMap
    assertEquals(limits("time of a cell"), "30 s")
    assertEquals(limits("guest API calls of a cell"), "10,000")
    assertEquals(limits("summary calls of a turn"), "64")
    assertEquals(
      limitLines(Fixtures.hostFacts.limits.copy(summaryCalls = 0)).toMap.apply("summary calls of a turn"),
      "0 (no compress or merge call)"
    )
    val sandbox = sandboxLines(Fixtures.hostFacts.sandbox, "/home/u").toMap
    assertEquals(sandbox("backend"), "Seatbelt")
    assertEquals(sandbox("closed paths"), "~/.ssh, ~/.aws")
    assertEquals(sandbox("closed names"), ".env, and each .env.NAME")
    assertEquals(sandbox("variables of a command"), "HOME, PATH")
    assertEquals(
      sandboxLines(Fixtures.hostFacts.sandbox.copy(backend = None), "/home/u").toMap.apply("backend"),
      "none, so no command runs"
    )
  }

  property("Journal codec: each entry of a run comes back equal through the codec of Logged") {
    val codec = Schema[Logged].jsonCodec
    forAll(schedules) { run =>
      for logged <- run.journal do assertEquals(codec.decode(codec.encode(logged)), Right(logged))
    }
  }
