package dspy4s.optimize

import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.programs.{ParameterId, Program}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

import java.nio.file.Files

private final case class PersistenceQuestion(question: String)
private final case class PersistenceAnswer(answer: String)

final class ProgramPersistenceSuite extends FunSuite:

  private val signature = Signature.derived[PersistenceQuestion, PersistenceAnswer]("Answer")

  private def predict(id: String) = Program.predict(ParameterId(id), signature)

  private val demo = Example(DynamicValues.record("question" := "q", "answer" := "a"))

  test("JSON round-trip restores values by stable parameter ID") {
    val fresh   = predict("answer")
    val trained = fresh.modifyParameter(ParameterId("answer"))(_.copy(demos = Vector(demo))).toOption.get
    val json    = ProgramPersistence.dumpJson(trained)
    val loaded  = ProgramPersistence.loadJson(fresh, json).toOption.get

    assertEquals(loaded.parameters, trained.parameters)
  }

  test("file round-trip restores a record program") {
    val fresh   = predict("answer").fromRecords(signature.inputShape)
    val trained = fresh.modifyParameter(ParameterId("answer"))(_.copy(instructions = Some("Be brief"))).toOption.get
    val path    = Files.createTempFile("dspy4s-program-", ".json")

    try
      assertEquals(ProgramPersistence.save(trained, path.toString), Right(()))
      val loaded = ProgramPersistence.load(fresh, path.toString).toOption.get
      assertEquals(loaded.program.parameters, trained.program.parameters)
    finally
      Files.deleteIfExists(path)
      ()
  }

  test("state keys do not depend on composition position") {
    val program = predict("first") &&& predict("second")
    val state   = ProgramPersistence.dumpState(program)

    assertEquals(state.fields.map(_._1).toVector, Vector("first", "second"))
  }

  test("loading rejects missing and unknown parameter IDs") {
    val program = predict("first") &&& predict("second")
    val one     = program.parameters.get(ParameterId("first")).get.dumpState
    val state   = DynamicValue.Record(Chunk("first" -> one, "unknown" -> one))
    val result  = ProgramPersistence.loadState(program, state)

    assert(result.isLeft)
    assert(result.left.toOption.get.message.contains("missing=[second]"))
    assert(result.left.toOption.get.message.contains("extra=[unknown]"))
  }

  test("loading rejects malformed JSON") {
    val result = ProgramPersistence.loadJson(predict("answer"), "[]")

    assert(result.isLeft)
  }
