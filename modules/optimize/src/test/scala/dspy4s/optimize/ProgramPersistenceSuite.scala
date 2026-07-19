package dspy4s.optimize

import dspy4s.programs.Predictors

import dspy4s.core.contracts.:=
import dspy4s.core.contracts.Example
import dspy4s.core.contracts.ValidationError
import dspy4s.programs.Predict
import dspy4s.programs.PredictorId
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

import ProgramPersistenceSuite.Pipe2

import java.nio.file.Files

class ProgramPersistenceSuite extends FunSuite:

  private val qaSignature = Signature.fromString("question -> answer")

  private val demo = Vector(
    Example(rec("question" := "q1", "answer" := "a1")).withInputs(Set("question")),
    Example(rec("question" := "q2", "answer" := "a2")).withInputs(Set("question"))
  )

  // ── Single typed Predict ──────────────────────────────────────────────────

  test("single Predict: dumpJson then loadJson into a fresh Predict restores demos") {
    val trained = Predict(qaSignature, demos = demo, name = Some("ask"))
    val fresh   = Predict(qaSignature, name = Some("ask"))
    assertEquals(fresh.demos, Vector.empty[Example])

    val json     = ProgramPersistence.dumpJson(trained)
    val restored = ProgramPersistence.loadJson(fresh, json)
    assert(restored.isRight, s"expected Right, got $restored")
    assertEquals(restored.toOption.get.demos, demo)
  }

  test("single Predict: save then load through a temp file restores demos") {
    val trained = Predict(qaSignature, demos = demo, name = Some("ask"))
    val fresh   = Predict(qaSignature, name = Some("ask"))

    val path = Files.createTempFile("dspy4s-program-state", ".json")
    try
      val saved = ProgramPersistence.save(trained, path.toString)
      assert(saved.isRight, s"expected Right, got $saved")
      val loaded = ProgramPersistence.load(fresh, path.toString)
      assert(loaded.isRight, s"expected Right, got $loaded")
      assertEquals(loaded.toOption.get.demos, demo)
    finally
      Files.deleteIfExists(path): Unit
  }

  // ── Composite (2 Predicts) ─────────────────────────────────────────────────

  test("composite: save then load restores both predictors' demos") {
    val trained = Pipe2(
      a = Predict(qaSignature, demos = demo, name = Some("ask")),
      b = Predict(qaSignature, demos = demo, name = Some("answer"))
    )
    val fresh = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = Predict(qaSignature, name = Some("answer"))
    )

    val path = Files.createTempFile("dspy4s-composite-state", ".json")
    try
      assert(ProgramPersistence.save(trained, path.toString).isRight)
      val loaded = ProgramPersistence.load(fresh, path.toString)
      assert(loaded.isRight, s"expected Right, got $loaded")
      val got = loaded.toOption.get
      assertEquals(got.a.demos, demo)
      assertEquals(got.b.demos, demo)
    finally
      Files.deleteIfExists(path): Unit
  }

  test("composite: loadState(p, dumpState(p)) restores both demos (round-trip equivalent)") {
    val trained = Pipe2(
      a = Predict(qaSignature, demos = demo, name = Some("ask")),
      b = Predict(qaSignature, demos = demo, name = Some("answer"))
    )
    val fresh = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = Predict(qaSignature, name = Some("answer"))
    )
    val restored = ProgramPersistence.loadState(fresh, ProgramPersistence.dumpState(trained))
    assert(restored.isRight, s"expected Right, got $restored")
    val got = restored.toOption.get
    assertEquals(got.a.demos, trained.a.demos)
    assertEquals(got.b.demos, trained.b.demos)
  }

  test("dumpState keys predictor state by stable predictor id") {
    val program = Pipe2(
      a = Predict(qaSignature, demos = demo, name = Some("ask")),
      b = Predict(qaSignature, name = Some("answer"))
    )

    val predictors = ProgramPersistence.dumpState(program).fields.toVector.collectFirst {
      case ("predictors", record: DynamicValue.Record) => record
    }.getOrElse(fail("expected a predictor-id record"))

    assertEquals(predictors.fields.toVector.map(_._1).toSet, Set(PredictorId(0).render, PredictorId(1).render))
  }

  test("keyed predictor state loads by id rather than JSON object order") {
    val firstDemo  = demo.take(1)
    val secondDemo = demo.drop(1)
    val trained = Pipe2(
      a = Predict(qaSignature, demos = firstDemo, name = Some("ask")),
      b = Predict(qaSignature, demos = secondDemo, name = Some("answer"))
    )
    val fresh = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = Predict(qaSignature, name = Some("answer"))
    )
    val dumped = ProgramPersistence.dumpState(trained)
    val keyed = dumped.fields.toVector.collectFirst {
      case ("predictors", record: DynamicValue.Record) => record
    }.getOrElse(fail("expected a predictor-id record"))
    val reversed = DynamicValue.Record(Chunk.from(keyed.fields.toVector.reverse))
    val state    = DynamicValue.Record(Chunk.from(Seq("predictors" -> reversed)))

    val restored = ProgramPersistence.loadState(fresh, state).toOption.get
    assertEquals(restored.a.demos, firstDemo)
    assertEquals(restored.b.demos, secondDemo)
  }

  test("loadState rejects missing and unknown predictor ids") {
    val program = Pipe2(
      a = Predict(qaSignature, name = Some("ask")),
      b = Predict(qaSignature, name = Some("answer"))
    )
    val oneState = summon[Predictors[Pipe2]].read(program).head.dumpState
    val malformed = DynamicValue.Record(Chunk.from(Seq(
      "predictors" -> DynamicValue.Record(Chunk.from(Seq(
        PredictorId(0).render -> oneState,
        PredictorId(2).render -> oneState
      )))
    )))

    val result = ProgramPersistence.loadState(program, malformed)
    assert(result.isLeft, s"expected Left, got $result")
    assert(result.left.toOption.get.message.contains("predictor-1"))
    assert(result.left.toOption.get.message.contains("predictor-2"))
  }

  test("loadState rejects positional predictor arrays") {
    val program = Predict(qaSignature, name = Some("ask"))
    val positional = DynamicValue.Record(Chunk.from(Seq(
      "predictors" -> DynamicValue.Sequence(Chunk.from(Seq(
        DynamicValue.Record.empty: DynamicValue
      )))
    )))
    val result = ProgramPersistence.loadState(program, positional)
    assert(result.isLeft, s"expected Left, got $result")
    assert(result.left.toOption.get.isInstanceOf[ValidationError])
    assert(result.left.toOption.get.message.contains("id-keyed record"))
  }

object ProgramPersistenceSuite:

  // A composite holding two typed Predicts (question -> answer).
  final case class Pipe2(
      a: Predict[(question: String), (answer: String)],
      b: Predict[(question: String), (answer: String)]
  )

  object Pipe2:
    given Predictors[Pipe2] = Predictors.derived
