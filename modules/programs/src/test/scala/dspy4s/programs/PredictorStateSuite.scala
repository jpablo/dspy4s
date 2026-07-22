package dspy4s.programs

import dspy4s.programs.predictors.*
import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, IsEq, RuntimeContext, RuntimeError, ValidationError}
import dspy4s.core.data.Example
import dspy4s.core.signatures.SignatureDsl
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.programs.runtime.SettingsProgramRuntime
import dspy4s.typed.Signature
import munit.FunSuite
import zio.blocks.chunk.Chunk
import zio.blocks.schema.DynamicValue

class PredictorStateSuite extends FunSuite:

  private val layout =
    SignatureDsl.parse("question -> answer").toOption.get.withInstructions(Some("Be terse."))

  private val demos = Vector(
    Example("question" := "q1", "answer" := "a1").withInputs(Set("question")),
    Example("question" := "q2", "answer" := "a2").withInputs(Set("question")).withAugmented(true)
  )

  private val firstState = PredictorState(
    instructions = Some("First instruction."),
    demos = demos.take(1),
    config = DynamicValues.record("temperature" := 0.3)
  )

  private val secondState = PredictorState(
    instructions = Some("Second instruction."),
    demos = demos,
    config = DynamicValues.record("temperature" := 0.8, "top_p" := 0.9)
  )

  private object BoundLm extends LanguageModel:
    val id: String   = "bound-state-test"
    val mode: LmMode = LmMode.Chat
    def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      Left(RuntimeError("bound-state-test", s"unexpected call to ${request.model}"))

  /** Execute a stated `@Law` equation under structural equality (honest for these carriers: `set` short-circuits
    * or `copy`s, sharing every execution binding by reference). */
  private def holds[A](law: String, eq: IsEq[A]): Unit =
    assert(eq.lhs.equals(eq.rhs), s"$law: ${eq.lhs} != ${eq.rhs}")

  /** Runs the four `@Law` statements the [[Predictor]] lens carries (Get-Put / Put-Get / Put-Put inherited from
    * `Lens`, plus the metadata frame), then the view/extension-syntax invariants. */
  private def assertLeafLaws[P](program: P, first: PredictorState, second: PredictorState)(using
      leaf: Predictor[P]
  ): Unit =
    val original = leaf.get(program)
    val metadata = leaf.metadata(program)

    assertEquals(program.predictorState, original)
    assertEquals(program.predictorView, leaf.inspect(program))
    assertEquals(program.withPredictorState(first).predictorState, first)

    holds("get-put", leaf.getPut(program))
    holds("put-get", leaf.putGet(program, first))
    holds("put-put", leaf.putPut(program, first, second))
    holds("frame", leaf.frame(program, first))

    val view = leaf.inspect(program)
    assertEquals(view.state, original)
    assertEquals(view.metadata, metadata)
    assertEquals(view.metadata.structure.instructions, None)
    assertEquals(view.layout.instructions, original.instructions)

  test("PredictorState dumpState/fromState round-trips instructions, demos, and nested config") {
    val config = DynamicValues.record(
      "temperature" := 0.7,
      "provider"    -> DynamicValues.record("reasoning" := true, "budget" := 128)
    )
    val state   = PredictorState(Some("Be precise."), demos, config)
    val rebuilt = PredictorState.fromState(state.dumpState)

    assertEquals(rebuilt, Right(state))
  }

  test("PredictorState decoding requires every key and rejects invalid field types") {
    val missing = PredictorState.fromState(DynamicValue.Record.empty)
    val invalidInstructions = PredictorState.fromState(DynamicValues.record(
      "instructions" := 42,
      "demos"        -> DynamicValue.Sequence(Chunk.empty),
      "config"       -> DynamicValue.Record.empty
    ))
    val invalidDemos = PredictorState.fromState(DynamicValues.record(
      "instructions" -> DynamicValue.Null,
      "demos"        := "not-a-sequence",
      "config"       -> DynamicValue.Record.empty
    ))
    val invalidConfig = PredictorState.fromState(DynamicValues.record(
      "instructions" -> DynamicValue.Null,
      "demos"        -> DynamicValue.Sequence(Chunk.empty),
      "config"       := "not-a-record"
    ))

    Vector(missing, invalidInstructions, invalidDemos, invalidConfig).foreach { result =>
      assert(result.left.toOption.exists(_.isInstanceOf[ValidationError]))
    }
  }

  test("PredictorState rejects the former signature-based executable predictor format") {
    val oldFormat = DynamicValues.record(
      "signature" -> layout.dumpState,
      "demos"     -> DynamicValue.Sequence(Chunk.empty),
      "config"    -> DynamicValue.Record.empty
    )
    val result = PredictorState.fromState(oldFormat)

    assert(result.isLeft)
    assert(result.left.toOption.exists(_.message.contains("missing 'instructions'")))
  }

  test("Predictor[DynamicPredict] satisfies Get-Put, Put-Get, Put-Put, and the metadata frame") {
    val program = DynamicPredict(layout = layout, demos = demos, config = DynamicValues.record("seed" := 1))
    assertLeafLaws(program, firstState, secondState)
  }

  test("Predictor[Predict] satisfies Get-Put, Put-Get, Put-Put, and the metadata frame") {
    val signature = Signature.fromString("question -> answer").withInstructions(Some("Answer directly."))
    val program = Predict(
      signature,
      demos = demos,
      name = Some("typed_predict"),
      config = DynamicValues.record("seed" := 1)
    )
    assertLeafLaws(program, firstState, secondState)
  }

  test("Predictor[ChainOfThought] satisfies the lens laws and includes config in writable state") {
    val signature = Signature.fromString("question -> answer").withInstructions(Some("Reason carefully."))
    val program = ChainOfThought(
      signature,
      demos = demos,
      name = Some("typed_cot"),
      config = DynamicValues.record("seed" := 1)
    )
    val leaf = summon[Predictor[ChainOfThought[(question: String), (answer: String)]]]

    assertLeafLaws(program, firstState, secondState)
    assertEquals(leaf.get(program).config, DynamicValues.record("seed" := 1))
    assertEquals(leaf.inspect(program).layout.outputFields.head.name, "reasoning")
    assertEquals(leaf.inspect(program).metadata.structure.instructions, None)
  }

  test("DynamicPredict state replacement preserves every execution binding and signature structure") {
    val runtime = new SettingsProgramRuntime {}
    val tools   = Vector(ToolSpec("search", description = Some("Search the corpus")))
    val program = DynamicPredict(
      layout = layout,
      demos = demos,
      name = Some("bound_predict"),
      runtime = runtime,
      outputJsonSchema = Some("{\"type\":\"object\"}"),
      config = DynamicValues.record("seed" := 1),
      lm = Some(BoundLm),
      tools = tools
    )

    val updated = program.withPredictorState(secondState)

    assertEquals(updated.predictorState, secondState)
    assertEquals(updated.layout.name, program.layout.name)
    assertEquals(updated.layout.fields, program.layout.fields)
    assertEquals(updated.name, program.name)
    assert(updated.runtime eq runtime)
    assertEquals(updated.outputJsonSchema, program.outputJsonSchema)
    assert(updated.lm.exists(_ eq BoundLm))
    assertEquals(updated.tools, tools)
  }

  test("Predictors.empty rejects non-empty state vectors") {
    val empty = Predictors.empty[Int]
    assertEquals(empty.read(42), Vector.empty[PredictorState])
    assertEquals(empty.replace(42, Vector.empty), 42)

    val error = intercept[IllegalArgumentException] {
      empty.replace(42, Vector(PredictorState()))
    }
    assert(error.getMessage.contains("expects 0 updates"))
  }
