package dspy4s.programs.optimization

import dspy4s.core.contracts.{DynamicValues, PredictorKey, SignatureLayout, TraceEntry, ValidationError, :=}
import munit.FunSuite
import zio.blocks.schema.DynamicValue

/** The rule that assigns trace entries to optimizable leaves (UPGRADE-3.2.1-to-3.4.0, decision D2). These tests use
  * hand-made entries; `PredictorTraceKeySuite` covers the entries that the real predictors record.
  */
class PredictorTracesSuite extends FunSuite:

  private val qa      = SignatureLayout.parse("question -> answer").toOption.get
  private val rewrite = SignatureLayout.parse("answer -> summary").toOption.get

  private def leaf(ordinal: Int, displayName: String, moduleName: String, layout: SignatureLayout) =
    IdentifiedOptimizable(
      OptimizableId.fromOrdinal(OptimizableOrdinal.assume(ordinal)),
      displayName,
      OptimizableView(OptimizableMetadata.from(layout, moduleName), OptimizableParameters())
    )

  private def record(entries: (String, DynamicValue)*): DynamicValue.Record = DynamicValues.recordFromEntries(entries)

  private def entry(
      component: String,
      key      : Option[PredictorKey],
      mark     : String,
      outputs  : DynamicValue.Record = record("answer" := "a"),
      nested   : Boolean             = false
  ): TraceEntry =
    TraceEntry(
      component = component,
      inputs = record("mark" := mark),
      outputs = outputs,
      predictor = key,
      withinPredictor = nested
    )

  private def marks(group: Vector[TraceEntry]): Vector[String] =
    group.map(e => DynamicValues.recordGet(e.inputs, "mark").map(DynamicValues.renderText).getOrElse(""))

  private val first  = leaf(0, "first", "predict", qa)
  private val second = leaf(1, "second", "predict", rewrite)

  test("leaves with different keys pass the check") {
    assertEquals(PredictorTraces.requireDistinctKeys(Vector(first, second)), Right(()))
  }

  test("a name makes two leaves over one signature different") {
    val named = leaf(1, "second", "second_pass", qa)
    assertEquals(PredictorTraces.requireDistinctKeys(Vector(first, named)), Right(()))
  }

  test("two leaves with the same module name and signature are rejected, and the message names them") {
    val twin = leaf(1, "second", "predict", qa)
    PredictorTraces.requireDistinctKeys(Vector(first, twin)) match
      case Left(error: ValidationError) =>
        assert(error.message.contains("'first'") && error.message.contains("'second'"), error.message)
        assert(error.message.contains("name"), error.message)
      case other => fail(s"expected a ValidationError, got $other")
  }

  test("instructions are not part of the key") {
    val withInstructions = qa.withInstructions(Some("Answer briefly."))
    assertEquals(PredictorKey.from("predict", withInstructions), first.metadata.key)
  }

  test("byLeaf assigns each entry to the leaf with the same key, in trace order") {
    val trace = Vector(
      entry("predict", Some(first.metadata.key), mark = "a"),
      entry("predict", Some(second.metadata.key), "b", record("summary" := "s"))
    )
    val groups = PredictorTraces.byLeaf(Vector(first, second), trace).toOption.get
    assertEquals(groups.map(marks), Vector(Vector("a"), Vector("b")))
  }

  test("byLeaf does not use the position: a leaf that runs in a loop gets all of its entries") {
    val trace = Vector(
      entry("predict", Some(first.metadata.key), mark = "step 1"),
      entry("predict", Some(first.metadata.key), mark = "step 2"),
      entry("predict", Some(first.metadata.key), mark = "step 3"),
      entry("predict", Some(second.metadata.key), "extract", record("summary" := "s")),
      entry("agent", None, "composite", record("summary" := "s"))
    )
    val groups = PredictorTraces.byLeaf(Vector(first, second), trace).toOption.get
    assertEquals(groups.map(marks), Vector(Vector("step 1", "step 2", "step 3"), Vector("extract")))
  }

  test("a leaf that did not run has an empty group") {
    val trace  = Vector(entry("predict", Some(first.metadata.key), mark = "a"))
    val groups = PredictorTraces.byLeaf(Vector(first, second), trace).toOption.get
    assertEquals(groups.map(marks), Vector(Vector("a"), Vector.empty))
  }

  test("an entry with a key that no leaf has is ignored") {
    val stranger = PredictorKey.from("other", qa)
    val trace    = Vector(entry("other", Some(stranger), mark = "x"), entry("predict", Some(first.metadata.key), mark = "a"))
    val groups   = PredictorTraces.byLeaf(Vector(first), trace).toOption.get
    assertEquals(groups.map(marks), Vector(Vector("a")))
  }

  test("a leaf module that ran but recorded no key is an error") {
    val custom = leaf(0, "custom", "scripted", qa)
    val trace  = Vector(entry("scripted", None, mark = "a"))
    PredictorTraces.byLeaf(Vector(custom), trace) match
      case Left(error: ValidationError) =>
        assert(error.message.contains("'custom'") && error.message.contains("ModuleLifecycle.predictor"), error.message)
      case other => fail(s"expected a ValidationError, got $other")
  }

  test("an entry that ran inside a predictor is not a missing key") {
    // The `Predict` inside ChainOfThought records an entry with no key. A separate `predict` leaf that did not run in
    // this example must not see that entry as its own.
    val trace  = Vector(entry("predict", None, "inner", nested = true))
    val groups = PredictorTraces.byLeaf(Vector(first), trace).toOption.get
    assertEquals(groups.map(marks), Vector(Vector.empty))
  }

  test("a composite module with the same name and other outputs is not a missing key") {
    val trace  = Vector(entry("predict", None, "composite", record("final" := "x")))
    val groups = PredictorTraces.byLeaf(Vector(first), trace).toOption.get
    assertEquals(groups.map(marks), Vector(Vector.empty))
  }
