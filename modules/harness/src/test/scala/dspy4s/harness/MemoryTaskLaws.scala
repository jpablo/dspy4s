package dspy4s.harness

import dspy4s.harness.kernel.{Entry, TurnOutcome, state}

/** The laws of the planted facts of the memory metric (CODE_HARNESS_DESIGN.md, 7.1), with no model. */
class MemoryTaskLaws extends munit.FunSuite:

  /** The texts of each turn of a journal: the messages, the cell outputs and the replies, by the number of the turn. */
  private def turns(task: MemoryTask): Vector[Vector[String]] =
    task.journal.map(_.entry).foldLeft(Vector(Vector.empty[String])) { (acc, entry) =>
      entry match
        case Entry.Input(_, text, _)                    => acc.init :+ (acc.last :+ text)
        case Entry.Observed(output, _, _)               => acc.init :+ (acc.last :+ output)
        case Entry.TurnEnded(TurnOutcome.Replied(text)) => (acc.init :+ (acc.last :+ text)) :+ Vector.empty
        case Entry.TurnEnded(_)                         => acc :+ Vector.empty
        case _                                          => acc
    }.filter(_.nonEmpty)

  /** Where the fact of `task` is: "cell", "message" or "reply". */
  private def place(task: MemoryTask): Set[String] =
    task.journal.map(_.entry).collect {
      case Entry.Input(_, text, _) if text.contains(task.expected)                    => "message"
      case Entry.Observed(output, _, _) if output.contains(task.expected)             => "cell"
      case Entry.TurnEnded(TurnOutcome.Replied(text)) if text.contains(task.expected) => "reply"
    }.toSet

  test("Memory tasks: the same seed gives the same tasks, and another seed gives other values") {
    assertEquals(MemoryTasks.generate(7L), MemoryTasks.generate(7L))
    assertNotEquals(MemoryTasks.generate(7L).map(_.expected), MemoryTasks.generate(8L).map(_.expected))
  }

  test(
    "Memory tasks: each fact is in one turn, far facts in the first quarter and near facts in the last three turns"
  ) {
    val tasks = MemoryTasks.generate(7L, turns = 60, facts = 6)
    assertEquals(tasks.size, 6)
    tasks.foreach { task =>
      val holding = turns(task).zipWithIndex.filter((texts, _) => texts.exists(_.contains(task.expected))).map(_._2)
      assertEquals(holding.size, 1, task.name)
      assertEquals(task.distance, 60 - holding.head, task.name)
      assert(holding.head < 60 / 4 || task.distance <= 3, s"${task.name}: the fact is in turn ${holding.head}")
      assert(!task.question.contains(task.expected), task.question)
      assert(state(task.journal).open.isEmpty, task.name)
    }
    assert(tasks.count(_.distance <= 3) >= 1 && tasks.count(_.distance > 3) >= 1)
    assertEquals(tasks.flatMap(place).toSet, Set("cell", "message", "reply"))
  }

  test("Memory tasks: correct takes the value in a longer answer, and refuses another value or a part of it") {
    val task = MemoryTasks.generate(7L).head
    assert(MemoryTasks.correct(task, s"The value is ${task.expected}."))
    assert(!MemoryTasks.correct(task, "I do not know."))
    assert(!MemoryTasks.correct(task, task.expected.dropRight(1)))
    assert(!MemoryTasks.correct(task, task.expected + "9"))
  }

  test("Memory tasks: the window keeps the facts of the near tasks, and leaves out the facts of the far tasks") {
    MemoryTasks.generate(7L).foreach { task =>
      val p    = Window.project(task.journal, (), HarnessLimits().budget)
      val text = p.status + p.observation + p.history.messages.mkString
      assertEquals(text.contains(task.expected), task.distance <= 3, task.name)
    }
  }

  test("Memory tasks: the score is the share of right answers, also for each distance") {
    val tasks = MemoryTasks.generate(7L, facts = 4)
    val got   = tasks.zipWithIndex.map((task, i) => (task, if i % 2 == 0 then task.expected else "no"))
    assertEqualsDouble(MemoryTasks.score(got), 0.5, 1e-9)
    assertEqualsDouble(MemoryTasks.score(Vector.empty), 0.0, 1e-9)
    val byDistance = MemoryTasks.byDistance(got)
    assertEquals(byDistance.map(_._1), tasks.map(_.distance).distinct.sorted)
    byDistance.foreach { (distance, score) =>
      val at = got.filter(_._1.distance == distance)
      assertEqualsDouble(score, at.count((t, a) => MemoryTasks.correct(t, a)).toDouble / at.size, 1e-9)
    }
  }
