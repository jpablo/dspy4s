package dspy4s.harness

import dspy4s.harness.kernel.Logged

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

  /** The tasks of `seed`: one session of `turns` turns with `facts` facts, and one task for each fact. The same seed
    * gives the same tasks. A third of the facts, at least one, are near.
    */
  def generate(seed: Long, turns: Int = 60, facts: Int = 6): Vector[MemoryTask] = ???

  /** True when `got` holds the value of `task` as a whole word. */
  def correct(task: MemoryTask, got: String): Boolean = ???

  /** The share of right answers, from 0 to 1. No result gives 0. */
  def score(results: Vector[(MemoryTask, String)]): Double = ???

  /** The score of each distance, nearest first. */
  def byDistance(results: Vector[(MemoryTask, String)]): Vector[(Int, Double)] = ???
