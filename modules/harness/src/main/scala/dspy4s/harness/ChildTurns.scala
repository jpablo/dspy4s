package dspy4s.harness

import dspy4s.core.contracts.RuntimeContext
import dspy4s.harness.guest.{Children, Data}

/** The start of a child of a program call (CODE_HARNESS_DESIGN.md, 8.1 and 8.2). The harness gives it to the handler of
  * `Children^`. `child` runs the turn of a child with the task and the inputs, and gives the value of its `submit`
  * through `Data[A]`. A child that gives no value makes it throw an exception with a plain message (5.1: "A child that
  * gives no value").
  */
trait ChildStart:
  def child[A: Data](task: String, inputs: Map[String, String])(using RuntimeContext): A

/** The handler of `Children^` (5.1, 8.2).
  *
  *   - `rlm` runs a child turn inside the cell, on the thread of the cell, with the context of the program call
  *     (`HostThreads.run`). The child gets the inputs as `String` values.
  *   - `rlmBatched` runs at most `width` children at the same time on host threads, each with its task and its inputs
  *     as `rlm` gets them, and keeps the order of the tasks. When one child fails, it throws, and the message has the
  *     text of that task.
  */
final class ChildTurns(start: ChildStart, host: HostThreads, width: Int) extends Children:

  def rlm[A: Data](task: String, inputs: Map[String, String]): A = host.run(start.child[A](task, inputs))

  def rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])]): Vector[A] =
    inBatch(tasks, width)((task, inputs) => rlm[A](task, inputs)) match
      case Right(values)      => values
      case Left((i, failure)) => throw IllegalStateException(
          s"rlmBatched: the child of the task $i, \"${tasks(i)._1}\", failed: ${plainMessage(failure)}"
        )
