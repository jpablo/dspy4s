package dspy4s.harness

import munit.FunSuite

import java.nio.file.Files

/** The laws of the task set (CODE_HARNESS_DESIGN.md, 9): the metric and the workspace, with no model. An optimizer
  * trusts the metric, so the metric must take each expected answer and refuse a wrong one.
  */
class TaskSetLaws extends FunSuite:

  test("Tasks: the metric takes each expected answer, also in another form, and refuses a wrong answer") {
    HarnessTasks.all.foreach { task =>
      assert(HarnessTasks.correct(task, task.expected), task.name)
      assert(HarnessTasks.correct(task, s" \"${task.expected}\". "), task.name)
      assert(!HarnessTasks.correct(task, task.expected + "9"), task.name)
    }
    val csv = HarnessTasks.all.find(_.name == "csv").getOrElse(fail("no csv task"))
    assert(HarnessTasks.correct(csv, "105.0"), "a number in another form")
  }

  test("Tasks: each task has a distinct name, and each folder that a question names is in the workspace") {
    assertEquals(HarnessTasks.all.map(_.name).distinct.size, HarnessTasks.all.size)
    val place = Place()
    try
      HarnessTasks.writeWorkspace(place.root)
      val folders = HarnessTasks.all.flatMap(t => "t\\d\\d".r.findAllIn(t.question).toVector).distinct
      assert(folders.nonEmpty)
      folders.foreach(f => assert(Files.isDirectory(place.root.resolve(f)), f))
    finally place.close()
  }

  test("Tasks: the examples of the task set hold the question as input and the expected answer as label") {
    val examples = HarnessTasks.examples
    assertEquals(examples.size, HarnessTasks.all.size)
    assert(examples.forall(_.inputKeys == Set("question")))
  }
