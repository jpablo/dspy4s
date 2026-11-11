package dspy4s.harness

import dspy4s.harness.kernel.*

import java.util.regex.Pattern
import scala.util.Random

/** A task of the memory metric (CODE_HARNESS_DESIGN.md, 7.1): a long chat journal with planted facts, and a question on
  * one fact for the next turn. `distance` is the number of turns from the turn of the fact to the question.
  */
final case class MemoryTask(name: String, journal: Vector[Logged], question: String, expected: String, distance: Int)

/** The planted facts of the memory metric (7.1). Each session is a chat of ordinary turns: a message, a cell that reads
  * or computes something, its output, and a reply. Some turns also hold a fact: a name and a value that occur in that
  * turn only, in the output of a cell, in a message of the person, or in a reply. Most facts are in the first quarter
  * of the turns (far tasks). A few are in a message or a reply of the last three turns (near tasks): a control, because
  * the window shows the messages and the replies of the newest turns. Each turn has enough text that the window of the
  * default budget leaves out the first quarter.
  */
object MemoryTasks:

  /** Where a fact is in its turn. */
  private enum Place derives CanEqual:
    case Cell, Message, Reply

  /** The names of the facts. A value is a token of letters and digits, so no word of the filler holds it. */
  private val Names = Vector(
    "code of the blue vault",
    "port of the staging database",
    "tag of the last release",
    "number of the night train",
    "key of the north lab",
    "id of the backup host",
    "pin of the side door",
    "seed of the first test run"
  )

  private val Words = Vector(
    "the",
    "file",
    "list",
    "reads",
    "lines",
    "from",
    "each",
    "folder",
    "and",
    "counts",
    "rows",
    "with",
    "a",
    "short",
    "check",
    "of",
    "names",
    "then",
    "writes",
    "notes",
    "about",
    "dates",
    "sizes",
    "errors",
    "in",
    "logs",
    "when",
    "build",
    "runs",
    "tests",
    "pass",
    "after",
    "change",
    "to",
    "parser",
    "keeps",
    "order",
    "values",
    "match",
    "table"
  )

  /** The tasks of `seed`: one session of `turns` turns with `facts` facts, and one task for each fact. The same seed
    * gives the same tasks. A third of the facts, at least one, are near.
    */
  def generate(seed: Long, turns: Int = 60, facts: Int = 6): Vector[MemoryTask] =
    val random = Random(seed)
    val near   = (facts / 3).max(1).min(facts)
    val far    = facts - near
    // The far facts are in distinct turns of the first quarter, and the near facts in distinct turns of the last three.
    val farTurns  = random.shuffle((0 until (turns / 4).max(1)).toVector).take(far)
    val nearTurns = random.shuffle((turns - 3).max(0).until(turns).toVector).take(near)
    val values    = Iterator.continually(token(random)).distinct.take(facts).toVector
    val names     = random.shuffle(Names).take(facts)
    // A near fact is in a message or a reply, which the window shows. A far fact can be in each place.
    val placed = (farTurns.map((_, false)) ++ nearTurns.map((_, true))).zipWithIndex.map { case ((turn, isNear), k) =>
      val place = if isNear then Vector(Place.Message, Place.Reply)(k % 2) else Place.values(k % 3)
      (turn, place, names(k), values(k))
    }
    val entries = Entry.Activated(Harness.versions, Support.config(TurnKind.Chat)) +: (0 until turns).toVector.flatMap {
      turn => chatTurn(random, turn, placed.filter(_._1 == turn).map(p => (p._2, s"Note: the ${p._3} is ${p._4}.")))
    }
    val journal = Support.logOf(1000L, entries*)
    placed.map { (turn, _, name, value) =>
      MemoryTask(
        s"the $name",
        journal,
        s"What is the $name? Answer with the value only.",
        value,
        turns - turn
      )
    }

  /** One chat turn with filler text, and the notes of its facts in their places. */
  private def chatTurn(random: Random, turn: Int, notes: Vector[(Place, String)]): Vector[Entry] =
    def at(place    : Place): String = notes.filter(_._1 == place).map(_._2).mkString(" ")
    def filler(words: Int): String   = Vector.fill(words)(Words(random.nextInt(Words.size))).mkString(" ")
    val message                      =
      Vector(s"Turn $turn: please look at this.", filler(80), at(Place.Message)).filter(_.nonEmpty).mkString(" ")
    val output = Vector(filler(40), at(Place.Cell)).filter(_.nonEmpty).mkString(" ")
    val reply  = Vector(s"Done with turn $turn.", filler(80), at(Place.Reply)).filter(_.nonEmpty).mkString(" ")
    Vector(
      Entry.Input(RequestId(s"turn-$turn"), message, Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(0),
      Entry.Acted("", s"I read the data of turn $turn.", Some(s"""println(read("data-$turn.txt"))""")),
      Entry.Observed(output, CellOutcome.Ok, Map.empty),
      Entry.StepStarted(1),
      Entry.Acted("", reply, None),
      Entry.TurnEnded(TurnOutcome.Replied(reply))
    )

  /** A value such as `K7Q2-9F`. */
  private def token(random: Random): String =
    def letter = ('A' + random.nextInt(26)).toChar
    def digit  = ('0' + random.nextInt(10)).toChar
    s"$letter$digit$letter$digit-$digit$letter"

  /** True when `got` holds the value of `task` as a whole word. */
  def correct(task: MemoryTask, got: String): Boolean =
    s"(?<![A-Za-z0-9-])${Pattern.quote(task.expected)}(?![A-Za-z0-9-])".r.findFirstIn(got).isDefined

  /** The share of right answers, from 0 to 1. No result gives 0. */
  def score(results: Vector[(MemoryTask, String)]): Double =
    if results.isEmpty then 0.0 else results.count((task, got) => correct(task, got)).toDouble / results.size

  /** The score of each distance, nearest first. */
  def byDistance(results: Vector[(MemoryTask, String)]): Vector[(Int, Double)] =
    results.groupBy(_._1.distance).toVector.sortBy(_._1).map((distance, at) => (distance, score(at)))
