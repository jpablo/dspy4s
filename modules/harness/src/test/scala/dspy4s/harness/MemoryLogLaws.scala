package dspy4s.harness

import dspy4s.harness.kernel.*

/** The laws of the memory log (CODE_HARNESS_DESIGN.md, 7.1): one message for each step, as a fold of the journal. */
class MemoryLogLaws extends munit.FunSuite:

  private val request = RequestId("FAKE-REQUEST")

  /** A chat turn: a message, a cell with its output, and a reply. */
  private def turn(n: Int): Vector[Entry] =
    Vector(
      Entry.Input(request, s"message $n", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", s"look $n", Some(s"println($n)")),
      Entry.Observed(s"$n", CellOutcome.Ok, Map.empty),
      Entry.StepStarted(1),
      Entry.Acted("", s"reply $n", None),
      Entry.TurnEnded(TurnOutcome.Replied(s"reply $n"))
    )

  private val chat: Vector[Logged] = Support.logOf(1000L, (0 until 3).flatMap(turn)*)

  test("Memory log: a message, a cell with its output, and the end of a turn give one message each") {
    val log = MemoryLog.messages(chat)
    assertEquals(
      log.map(_.kind),
      Vector.fill(3)(Vector(MessageKind.Heard(Source.Harness), MessageKind.Cell, MessageKind.Reply)).flatten
    )
    assert(log(0).text.contains("message 0"), log(0).text)
    assert(
      log(1).text.contains("look 0") && log(1).text.contains("println(0)") && log(1).text.contains("0"),
      log(1).text
    )
    assertEquals(log(2).text, "The model replied: reply 0")
  }

  test("Memory log: each message names its speaker, so a reply of the model never reads as words of the person") {
    // In session 70e1947a (2026-11-11), the model read its own earlier reply "Session instructions: ..." in the memory
    // as its real instructions.
    def at(seq: Long, source: Source, entry: Entry) = Logged(seq, Meta(1000L, source, Taint.Trusted, true), entry)
    val journal                                     = Vector(
      at(0, Source.User, Entry.Input(request, "FAKE-ASK", Map.empty)),
      at(1, Source.Harness, Entry.TurnStarted),
      at(2, Source.Harness, Entry.StepStarted(0)),
      at(3, Source.Policy, Entry.Acted("", "FAKE-LOOK", Some("println(1)"))),
      at(4, Source.Harness, Entry.Observed("1", CellOutcome.Ok, Map.empty)),
      at(5, Source.Harness, Entry.TurnEnded(TurnOutcome.Replied("FAKE-REPLY"))),
      at(6, Source.Harness, Entry.Input(request, "FAKE-JOB-END", Map.empty)),
      at(7, Source.Harness, Entry.TurnStarted),
      at(8, Source.Harness, Entry.TurnEnded(TurnOutcome.Aborted))
    )
    assertEquals(
      MemoryLog.messages(journal).map(_.text),
      Vector(
        "The person wrote: FAKE-ASK",
        "The model ran a cell: FAKE-LOOK\n```scala\nprintln(1)\n```\n1",
        "The model replied: FAKE-REPLY",
        "The harness wrote: FAKE-JOB-END",
        TurnOutcome.Aborted.report
      )
    )
  }

  test("Memory log: the control entries give no message, and a cell that did not end gives none") {
    val open = Support.logOf(
      1000L,
      Entry.Input(request, "start", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", "run", Some("println(1)")),
      Entry.EffectStarted(EffectId(0, 0), "now", zio.blocks.schema.json.Json.Null)
    )
    assertEquals(MemoryLog.messages(open).map(_.kind), Vector(MessageKind.Heard(Source.Harness)))
  }

  test("Memory log: another end of a turn gives a reply with the text of the report of the outcome") {
    val aborted = Support.logOf(
      1000L,
      Entry.Input(request, "start", Map.empty),
      Entry.TurnStarted,
      Entry.StopRequested("FAKE-STOP"),
      Entry.TurnEnded(TurnOutcome.Aborted)
    )
    val reply = MemoryLog.messages(aborted).last
    assertEquals(reply.kind, MessageKind.Reply)
    assertEquals(reply.text, TurnOutcome.Aborted.report)
  }

  /** A turn with the report of a job during its cell, and a turn whose cell a crash ended. */
  private def reportAndCrash: Vector[Logged] =
    Support.logOf(
      1000L,
      Entry.Input(request, "start", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", "run", Some("println(1)")),
      Entry.Input(RequestId("FAKE-REPORT"), "the job ended", Map.empty),
      Entry.Observed("1", CellOutcome.Ok, Map.empty),
      Entry.StepStarted(1),
      Entry.Acted("", "done", None),
      Entry.TurnEnded(TurnOutcome.Replied("done")),
      Entry.Input(request, "again", Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", "run", Some("println(2)")),
      Entry.TurnEnded(TurnOutcome.Crashed)
    )

  test("Memory log: a Cell message comes at its Observed, after a report of its cell, and a crashed cell gives none") {
    assertEquals(
      MemoryLog.messages(reportAndCrash).map(_.kind),
      Vector(
        MessageKind.Heard(Source.Harness),
        MessageKind.Heard(Source.Harness),
        MessageKind.Cell,
        MessageKind.Reply,
        MessageKind.Heard(Source.Harness),
        MessageKind.Reply
      )
    )
    assertEquals(MemoryLog.messages(reportAndCrash).last.text, TurnOutcome.Crashed.report)
  }

  test("Memory log: the messages of a journal are the start of the messages of each longer journal") {
    for journal <- Vector(chat, reportAndCrash); n <- 0 to journal.size do
      val shorter = MemoryLog.messages(journal.take(n))
      assert(MemoryLog.messages(journal).startsWith(shorter), s"$n entries: $shorter")
  }

  test("Memory log: beforeOpenTurn counts the messages up to the Reply of the last TurnEnded, and 0 with none") {
    assertEquals(MemoryLog.beforeOpenTurn(chat), 9L)
    val open = chat ++ Support.logOf(2000L, turn(3).take(5)*).map(l => l.copy(seq = l.seq + chat.size))
    assertEquals(MemoryLog.beforeOpenTurn(open), 9L)
    assertEquals(MemoryLog.messages(open).size, 11)
    // A message with no TurnStarted yet is in the next turn.
    assertEquals(MemoryLog.beforeOpenTurn(open.take(chat.size + 1)), 9L)
    assertEquals(MemoryLog.beforeOpenTurn(chat.take(5)), 0L)
    assertEquals(MemoryLog.beforeOpenTurn(reportAndCrash), 6L)
  }
