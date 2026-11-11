package dspy4s.harness

import dspy4s.core.runtime.RuntimeEnvironment
import munit.FunSuite

import scala.concurrent.duration.*

import LiveSupport.*

/** A live run of the memory metric with a real model (CODE_HARNESS_DESIGN.md, 7.1). It runs only with
  * `HARNESS_LIVE=true` (see `LiveSupport`). For each task of `MemoryTasks`, the next turn of the journal asks the
  * question, once with the window and once with the memory tree. The run writes the answers, the right ones and the
  * size of each view to the folder `memory` of the output folder. A wrong answer does not fail the test, because the
  * scores are the result.
  */
class MemoryLiveRun extends FunSuite:

  override val munitTimeout: Duration = 30.minutes

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  test("Memory live: the scores of the window and of the memory tree on the planted facts") {
    assume(live, "set HARNESS_LIVE=true for a live run")
    ???
  }
