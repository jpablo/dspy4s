package dspy4s.harness

import dspy4s.core.contracts.DynamicValues
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.programs.ProgramRunner
import dspy4s.programs.contracts.ProgramCall
import dspy4s.programs.optimization.OptimizableStructure
import munit.FunSuite

import java.nio.file.Path

import Models.*

/** The laws of the optimizable structure of a harness, and what the optimizers need (CODE_HARNESS_DESIGN.md, 9). Each
  * optimizer of dspy4s takes a program `P: {OptimizableStructure, ProgramRunner}`.
  */
class StructureLaws extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private def harness: Harness[Question, Answer] =
    Harness.forTask(signature, HarnessEnv(None, Path.of("/tmp"), HostList.empty, None, Secrets.none))

  private def structure: OptimizableStructure[Harness[Question, Answer]] =
    summon[OptimizableStructure[Harness[Question, Answer]]]

  test("Structure: the leaves are the policy and the finisher, with distinct keys") {
    assertEquals(structure.inspectNamed(harness).map(_._1), Vector("policy", "finisher"))
    assertEquals(structure.inspect(harness).map(_.metadata).distinct.size, 2)
  }

  test("Structure: a replace with the parameters that read gives changes no parameter") {
    assertEquals(structure.read(structure.replace(harness, structure.read(harness))), structure.read(harness))
  }

  test("Structure: a replace changes the instructions of each leaf, and nothing else") {
    val changed = structure.read(harness).zip(Vector("P", "F")).map((p, text) => p.copy(instructions = Some(text)))
    val updated = structure.replace(harness, changed)
    assertEquals(structure.read(updated).map(_.instructions), Vector(Some("P"), Some("F")))
    assertEquals(updated.limits, harness.limits)
    assertEquals(updated.strategy.name, harness.strategy.name)
  }

  test("Structure: the arity agrees with the leaves") {
    val law = structure.arityAgreement(harness)
    assertEquals(law.lhs, law.rhs)
    assertEquals(structure.arity(harness), 2)
  }

  test("Optimizers: a harness has what each optimizer needs") {
    def leaves[P: {OptimizableStructure, ProgramRunner}](program: P): Option[Int] =
      Option(summon[ProgramRunner[P]]).map(_ => summon[OptimizableStructure[P]].arity(program))
    assertEquals(leaves(harness), Some(2))
  }

  test("Optimizers: the runner reads a record of the inputs, and gives the output in RawPrediction.values") {
    val model = ScriptLm(policy("compute", Some("submit(\"42\")")))
    val place = Place()
    try
      val env    = HarnessEnv(Some(place.root), place.home, HostList.empty, Backends.detect(), Secrets.none)
      val record = signature.inputShape.encode(Question("What is 6 times 7?"))
      val result = withModel(model) {
        summon[ProgramRunner[Harness[Question, Answer]]].run(Harness.forTask(signature, env), ProgramCall(record))
      }
      assertEquals(
        result.map(raw => DynamicValues.recordGet(raw.values, "answer")),
        Right(Some(DynamicValues.string("42")))
      )
    finally place.close()
  }
