package dspy4s.optimize

import dspy4s.core.contracts.{:=, DynamicValues}
import dspy4s.core.decisions.{Choice, DecisionSettings, Noul}
import dspy4s.programs.optimization.{OptimizableStructure, optimizableParameters}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import DecisionPersistenceSuite.{Team, Ticket, Triage, TwoTriages}

/** The decision settings in the program state (experimental; upstream `Predict.dump_state` and `load_state` write and
  * read `fields`, and omit the key when there are no settings).
  */
class DecisionPersistenceSuite extends FunSuite:

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  private val signature = Signature.derived[Ticket, Triage]("Triage", "Triage the ticket.")

  private val trained: Predict[Ticket, Triage] = Vector(
    "urgent" -> DecisionSettings(
      threshold = Some(0.7),
      instructions = Some(text("Is immediate action needed?")),
      criteria = Some(DynamicValues.record("true" := "Service unavailable", "false" := "Workaround available"))
    ),
    "category" -> DecisionSettings(
      weights = Some(Map("billing" -> 0.5, "technical" -> 2.0)),
      instructions = Some(DynamicValues.record("focus" := "The team that owns the product"))
    ),
    "native_urgent" -> DecisionSettings(threshold = Some(0.25), instructions = Some(text("Is it urgent?")))
  ).foldLeft(Predict(signature, name = Some("triage"))) { case (predict, (name, settings)) =>
    predict.withDecision(name, settings).fold(error => fail(error.message), identity)
  }

  test("the settings go through the JSON of the program state, and a state with no settings clears them") {
    val json = ProgramPersistence.dumpJson(trained)
    assert(json.contains("\"decisions\""), json)
    val loaded = ProgramPersistence.loadJson(Predict(signature, name = Some("triage")), json)
    assertEquals(loaded.map(_.decisions), Right(trained.decisions))
    assertEquals(loaded.map(_.optimizableParameters), Right(trained.optimizableParameters))

    val plain = ProgramPersistence.dumpJson(Predict(signature, name = Some("triage")))
    assert(!plain.contains("decisions"), plain)
    assertEquals(
      ProgramPersistence.loadJson(trained, plain).map(_.decisions),
      Right(Map.empty[String, DecisionSettings])
    )
  }

  test("each predictor of a composite keeps its own settings through the program state") {
    val program = TwoTriages(first = trained, second = Predict(signature, name = Some("second")))
    val fresh   = TwoTriages(first = Predict(signature, name = Some("triage")), second = trained.copy(name = Some("x")))
    val loaded  = ProgramPersistence.loadState(fresh, ProgramPersistence.dumpState(program))
    assertEquals(loaded.map(_.first.decisions), Right(trained.decisions))
    assertEquals(loaded.map(_.second.decisions), Right(Map.empty[String, DecisionSettings]))
  }

  test("a state with settings of the wrong JSON type does not load") {
    val state = DynamicValues.record(
      "optimizableParameters" -> DynamicValues.record(
        "optimizable-0" -> DynamicValues.record(
          "instructions" -> DynamicValue.Null,
          "demos"        -> DynamicValue.Sequence(zio.blocks.chunk.Chunk.empty),
          "config"       -> DynamicValue.Record.empty,
          "decisions"    -> DynamicValues.record("urgent" -> DynamicValues.record("threshold" := "high"))
        )
      )
    )
    val loaded = ProgramPersistence.loadState(Predict(signature, name = Some("triage")), state)
    assert(loaded.left.exists(_.message.contains("threshold")), loaded.toString)
  }

object DecisionPersistenceSuite:

  enum Team derives Schema:
    case billing, technical

  final case class Ticket(ticket: String) derives Schema
  final case class Triage(urgent: Noul, category: Choice[Team], native_urgent: Boolean) derives Schema

  final case class TwoTriages(first: Predict[Ticket, Triage], second: Predict[Ticket, Triage])

  object TwoTriages:
    given OptimizableStructure[TwoTriages] = OptimizableStructure.derived
