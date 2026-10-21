package dspy4s.programs

import dspy4s.adapters.{DecisionAdapter, JSONAdapter}
import dspy4s.core.contracts.{:=, DspyError, DynamicValues, RuntimeContext}
import dspy4s.core.data.RawPrediction
import dspy4s.core.decisions.{DecisionKind, DecisionSettings, Noul}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.strategies.{ChainOfThought, MultiChainComparison, Predict, ReAct}
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

final case class ApQuestion(question: String) derives Schema
final case class ApAnswer(answer: String, confidence: Double) derives Schema
final case class ApTriage(urgent: Noul, answer: String) derives Schema
final case class ApNode(label: String, children: List[ApNode]) derives Schema
final case class ApTree(urgent: Noul, label: String, children: List[ApTree]) derives Schema

/** The strict `response_format` of each program that adds an output field to a typed output: the ReAct extractor and
  * MultiChainComparison add a field as ChainOfThought does. The schema has the added field first, as the layout does,
  * and `required` has each field. A decision output keeps its evidence schema, and a recursive output type keeps its
  * definitions. The decision adapter also puts evidence properties into the output schema: for a recursive output type,
  * the schema keeps each other property.
  */
class AugmentedSchemaPathsSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  /** A model that takes a response schema. It gives the replies in order, repeats the last one, and keeps each request.
    */
  private final class SchemaLm(replies: String*) extends LanguageModel:
    @volatile var requests: Vector[LmRequest]                                                  = Vector.empty
    override val id: String                                                                    = "schema-lm"
    override val mode: LmMode                                                                  = LmMode.Chat
    override def supportsResponseSchema: Boolean                                               = true
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      requests = requests :+ request
      val reply = replies(math.min(requests.size - 1, replies.size - 1))
      Right(LmResponse(outputs = Vector(LmOutput(text = reply))))

  private def run[A](lm: LanguageModel)(body: RuntimeContext ?=> A): A =
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(JSONAdapter()))) {
      body(using RuntimeEnvironment.current)
    }

  private def at(value: DynamicValue, path: String*): Option[DynamicValue] =
    path.foldLeft[Option[DynamicValue]](Some(value)) {
      case (Some(node: DynamicValue.Record), key) => DynamicValues.recordGet(node, key)
      case _                                      => None
    }

  /** The strict schema of the `response_format` of `request`. */
  private def strictSchema(request: LmRequest): DynamicValue =
    at(request.options, "response_format", "json_schema", "schema")
      .getOrElse(fail(s"no strict schema in ${request.options}"))

  private def keys(value: Option[DynamicValue]): Vector[String] =
    value match
      case Some(record: DynamicValue.Record) => DynamicValues.recordKeys(record)
      case other                             => fail(s"expected an object, got: $other")

  private def names(value: Option[DynamicValue]): Vector[String] =
    value match
      case Some(list: DynamicValue.Sequence) => list.elements.toVector.flatMap(DynamicValues.asString)
      case other                             => fail(s"expected a list, got: $other")

  private def text(value: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(value))

  private object Calculator extends ToolFunction:
    override val name: String                                                                             = "calculator"
    override val description: String                                                                      = "Adds numbers."
    override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
      Right(ToolFunction.result("4"))

  test("the ReAct extractor sends a response schema with the reasoning field first") {
    val lm = SchemaLm(
      """{"next_thought": "I know it.", "next_tool_name": "finish", "next_tool_args": {}}""",
      """{"reasoning": "It is 4.", "answer": "4", "confidence": 0.9}"""
    )
    val agent  = ReAct(Signature.derived[ApQuestion, ApAnswer]("QA"), Vector(Calculator))
    val result = run(lm)(agent(ApQuestion("2+2?")))
    assertEquals(result.map(_.output.answer), Right("4"))
    assertEquals(lm.requests.size, 2)
    val schema = strictSchema(lm.requests(1))
    assertEquals(keys(at(schema, "properties")), Vector("reasoning", "answer", "confidence"))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "answer", "confidence"))
    assertEquals(at(schema, "additionalProperties"), Some(DynamicValue.Primitive(PrimitiveValue.Boolean(false))))
  }

  test("MultiChainComparison sends a response schema with the rationale field first") {
    val lm         = SchemaLm("""{"rationale": "Both say 4.", "answer": "4", "confidence": 0.9}""")
    val comparison = MultiChainComparison(Signature.derived[ApQuestion, ApAnswer]("QA"), m = AttemptCount(2))
    val attempts   = Vector.fill(2)(RawPrediction(values = DynamicValues.record("rationale" := "add", "answer" := "4")))
    val result     = run(lm)(comparison.compare(ApQuestion("2+2?"), attempts))
    assertEquals(result.map(_.output.rationale), Right("Both say 4."))
    val schema = strictSchema(lm.requests.headOption.getOrElse(fail("no request")))
    assertEquals(keys(at(schema, "properties")), Vector("rationale", "answer", "confidence"))
    assertEquals(names(at(schema, "required")), Vector("rationale", "answer", "confidence"))
  }

  test("a ChainOfThought with a decision output sends the reasoning field and the evidence schema in layout order") {
    val program = ChainOfThought(Signature.derived[ApQuestion, ApTriage]("Triage", "Triage the question."))
      .withDecision("urgent", DecisionSettings(instructions = Some(text("Is it urgent?"))))
      .fold(error => fail(error.message), identity)
    val lm     = SchemaLm("""{"reasoning": "It is calm.", "urgent": {"noul": 0.2}, "answer": "later"}""")
    val output = run(lm)(program(ApQuestion("Is the sky blue?"))).fold(error => fail(error.message), _.output)
    assertEquals(output.reasoning, "It is calm.")
    assertEquals(output.urgent.value, false)
    val schema = strictSchema(lm.requests.headOption.getOrElse(fail("no request")))
    assertEquals(keys(at(schema, "properties")), Vector("reasoning", "urgent", "answer"))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "urgent", "answer"))
    assertEquals(at(schema, "properties", "urgent"), Some(DecisionAdapter.evidenceSchema(DecisionKind.Noul)))
  }

  test("a ChainOfThought over a recursive output type sends the reasoning field and keeps the definitions") {
    val lm     = SchemaLm("""{"reasoning": "One node.", "label": "root", "children": []}""")
    val result = run(lm)(ChainOfThought(Signature.derived[ApQuestion, ApNode]("Tree"))(ApQuestion("A tree?")))
    assertEquals(result.map(_.output.label), Right("root"))
    val schema = strictSchema(lm.requests.headOption.getOrElse(fail("no request")))
    assertEquals(at(schema, "$ref"), None)
    assertEquals(keys(at(schema, "properties")), Vector("reasoning", "label", "children"))
    assertEquals(names(at(schema, "required")), Vector("reasoning", "label", "children"))
    assertEquals(at(schema, "properties", "children", "items", "$ref"), Some(text("#/$defs/ApNode")))
    assertEquals(keys(at(schema, "$defs", "ApNode", "properties")), Vector("label", "children"))
  }

  test("a decision output of a recursive output type keeps each other property in the response schema") {
    val program = Predict(Signature.derived[ApQuestion, ApTree]("Tree", "Label the tree."))
      .withDecision("urgent", DecisionSettings(instructions = Some(text("Is it urgent?"))))
      .fold(error => fail(error.message), identity)
    val lm     = SchemaLm("""{"urgent": {"noul": 0.9}, "label": "root", "children": []}""")
    val output = Console.withErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream())) {
      run(lm)(program(ApQuestion("A tree?"))).fold(error => fail(error.message), _.output)
    }
    assertEquals(output.urgent.value, true)
    val schema = strictSchema(lm.requests.headOption.getOrElse(fail("no request")))
    assertEquals(keys(at(schema, "properties")), Vector("urgent", "label", "children"))
    assertEquals(names(at(schema, "required")), Vector("urgent", "label", "children"))
    assertEquals(at(schema, "properties", "urgent"), Some(DecisionAdapter.evidenceSchema(DecisionKind.Noul)))
  }
