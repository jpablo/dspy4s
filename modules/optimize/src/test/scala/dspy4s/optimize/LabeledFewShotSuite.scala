package dspy4s.optimize

import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.programs.{ParameterId, Program}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZIO}

private final case class LfsQuestion(question: String)
private final case class LfsAnswer(answer: String)

final class LabeledFewShotSuite extends FunSuite:

  private val signature = Signature.derived[LfsQuestion, LfsAnswer]("Answer")
  private val student   = Program.predict(ParameterId("answer"), signature).fromRecords(signature.inputShape)

  private def example(index: Int): Example =
    Example(DynamicValues.record("question" := s"q$index", "answer" := s"a$index"))

  private def run[A](effect: ZIO[Any, ?, A]): A =
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  test("DemoCount accepts zero and rejects negative values") {
    assert(compileErrors("dspy4s.optimize.DemoCount(-1)").nonEmpty)
    assertEquals(DemoCount.either(0).map(_.toInt), Right(0))
    assertEquals(DemoCount.either(4).map(_.toInt), Right(4))
    assert(DemoCount.either(-1).isLeft)
  }

  test("LabeledFewShot samples deterministically") {
    val trainset = (1 to 20).map(example).toVector
    val config   = LabeledFewShotConfig(k = DemoCount(5), seed = 42L)

    val first  = run(LabeledFewShot(student, trainset, config)).bestProgram.program.parameters.all.head.value.demos
    val second = run(LabeledFewShot(student, trainset, config)).bestProgram.program.parameters.all.head.value.demos

    assertEquals(first.size, 5)
    assertEquals(first, second)
  }

  test("LabeledFewShot takes the first k examples when sampling is disabled") {
    val trainset = (1 to 10).map(example).toVector
    val report   = run(LabeledFewShot(
      student,
      trainset,
      LabeledFewShotConfig(k = DemoCount(3), sample = false)
    ))
    val demos = report.bestProgram.program.parameters.all.head.value.demos

    assertEquals(demos, trainset.take(3))
  }

  test("LabeledFewShot handles empty and short trainsets") {
    val empty = run(LabeledFewShot(student, Vector.empty)).bestProgram.program.parameters.all.head.value.demos
    val short = run(LabeledFewShot(
      student,
      Vector(example(1)),
      LabeledFewShotConfig(k = DemoCount(10))
    )).bestProgram.program.parameters.all.head.value.demos

    assertEquals(empty, Vector.empty)
    assertEquals(short, Vector(example(1)))
  }

  test("LabeledFewShot updates all stable parameter slots") {
    val first    = Program.predict(ParameterId("first"), signature)
    val second   = Program.predict(ParameterId("second"), signature)
    val composed = (first &&& second).fromRecords(signature.inputShape)
    val trainset = Vector(example(1))

    val compiled = run(LabeledFewShot(
      composed,
      trainset,
      LabeledFewShotConfig(k = DemoCount(1), sample = false)
    )).bestProgram

    assertEquals(compiled.program.parameters.all.map(_.id), Vector(ParameterId("first"), ParameterId("second")))
    assertEquals(compiled.program.parameters.all.map(_.value.demos), Vector(trainset, trainset))
  }
