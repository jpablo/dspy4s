package dspy4s.core

import dspy4s.core.contracts.{FieldSpec, SignatureLayout, TypeRef}
import dspy4s.core.decisions.{DecisionEvidence, DecisionKind, DecisionPlan, DecisionSettings, Score}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.nio.charset.StandardCharsets

/** The rules of the decision outputs (experimental; upstream `DecisionState` in `dspy/adapters/decision_state.py`, with
  * the cases of `tests/predict/test_decision_parameters.py`).
  */
/** The levels of a test score. */
enum DpSeverity derives CanEqual:
  case Low, High

/** An enum that is not the level enum of the test score. */
enum DpPriority derives CanEqual:
  case First, Second

class DecisionPlanSuite extends FunSuite:

  private def json(text: String): DynamicValue =
    Schema.dynamic.jsonCodec.decode(text.getBytes(StandardCharsets.UTF_8)).fold(error => fail(error.toString), identity)

  private def record(text: String): DynamicValue.Record =
    json(text) match
      case value: DynamicValue.Record => value
      case other                      => fail(s"not an object: $other")

  private def num(value: Double): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Double(value))

  /** The signature of the upstream tests: `text -> flag: Noul, rating: Score[bad, fair, great], label: Choice[2,
    * other]`, with two native outputs.
    */
  private val layout: SignatureLayout = SignatureLayout
    .create(
      name = "Assess",
      inputFields = Vector(FieldSpec("text")),
      outputFields = Vector(
        FieldSpec("flag", TypeRef.noul, description = Some("Is it relevant?")),
        FieldSpec(
          "rating",
          TypeRef.score,
          description = Some("Rate usefulness."),
          enumValues = Vector("bad", "fair", "great")
        ),
        FieldSpec(
          "label",
          TypeRef.choice,
          description = Some("Classify the document."),
          enumValues = Vector("2", "other")
        ),
        FieldSpec("native_flag", TypeRef.bool, description = Some("Is it urgent?")),
        FieldSpec("native_label", enumValues = Vector("billing", "technical"), description = Some("Which team?")),
        FieldSpec("summary", description = Some("A summary."))
      ),
      instructions = Some("Assess the document.")
    )
    .fold(error => fail(error.message), identity)

  private def plan(settings: (String, DecisionSettings)*): DecisionPlan =
    DecisionPlan.of(layout, settings.toMap).fold(error => fail(error.message), identity)

  private def rejected(settings: (String, DecisionSettings)*): Boolean = DecisionPlan.of(layout, settings.toMap).isLeft

  private val noulAt   = (p: Double) => DecisionEvidence.Noul(p)
  private val rating   = DecisionEvidence.Score(Map(0 -> 0.1, 1 -> 0.3, 2 -> 0.6), confidence = 0.61)
  private val labelEv  = (two: Double, other: Double) => DecisionEvidence.Choice(Map("2" -> two, "other" -> other), 0.73)
  private val defaults = Map("flag" -> noulAt(0.8), "rating" -> rating, "label" -> labelEv(0.8, 0.2))

  private def decoded(plan: DecisionPlan, answers: Map[String, DecisionEvidence] = defaults): DynamicValue.Record =
    plan.decode(answers).fold(error => fail(error.message), identity)

  private def field(values: DynamicValue.Record, name: String, key: String): DynamicValue =
    dspy4s.core.contracts.DynamicValues.recordGet(values, name).collect { case r: DynamicValue.Record => r }
      .flatMap(dspy4s.core.contracts.DynamicValues.recordGet(_, key)).getOrElse(fail(s"no $name.$key in $values"))

  private def double(value: DynamicValue): Double =
    value match
      case DynamicValue.Primitive(PrimitiveValue.Double(v)) => v
      case DynamicValue.Primitive(PrimitiveValue.Int(v))    => v.toDouble
      case DynamicValue.Primitive(PrimitiveValue.Long(v))   => v.toDouble
      case other                                            => fail(s"not a number: $other")

  // ── The kinds ──────────────────────────────────────────────────────────────────────────────────────────────────

  test("the kind of a field comes from its type, and only noul, score, and choice fields are rich") {
    val kinds = layout.outputFields.map(f => f.name -> (DecisionKind.of(f), DecisionKind.isRich(f)))
    assertEquals(
      kinds,
      Vector(
        "flag"         -> (Some(DecisionKind.Noul), true),
        "rating"       -> (Some(DecisionKind.Score(Vector("bad", "fair", "great"))), true),
        "label"        -> (Some(DecisionKind.Choice(Vector("2", "other"))), true),
        "native_flag"  -> (Some(DecisionKind.Noul), false),
        "native_label" -> (Some(DecisionKind.Choice(Vector("billing", "technical"))), false),
        "summary"      -> (None, false)
      )
    )
  }

  test("a plan has each rich output, and a native output only with a settings entry") {
    assertEquals(plan().kinds.map(_._1), Vector("flag", "rating", "label"))
    assertEquals(
      plan("native_flag" -> DecisionSettings()).kinds.map(_._1),
      Vector("flag", "rating", "label", "native_flag")
    )
  }

  // ── The questions ──────────────────────────────────────────────────────────────────────────────────────────────

  test("each question has the instructions, the type, and the criteria, as upstream") {
    val p = plan()
    assertEquals(p.question("flag"), Right(record("""{"instructions": "Is it relevant?", "type": "noul"}""")))
    assertEquals(
      p.question("rating"),
      Right(record("""{"instructions": "Rate usefulness.", "type": "score", "criteria": ["bad", "fair", "great"]}"""))
    )
    assertEquals(
      p.question("label"),
      Right(record(
        """{"instructions": "Classify the document.", "type": "choice", "criteria": {"2": null, "other": null}}"""
      ))
    )
  }

  test("explicit instructions and criteria replace the defaults, and an explicit null criteria stays") {
    val p = plan(
      "flag" -> DecisionSettings(
        instructions = Some(json("""{"focus": "availability"}""")),
        criteria = Some(DynamicValue.Null)
      ),
      "rating" -> DecisionSettings(criteria =
        Some(json("""["Cosmetic", {"what": "Work interrupted"}, {"examples": ["Outage"]}]"""))
      )
    )
    assertEquals(
      p.question("flag"),
      Right(record("""{"instructions": {"focus": "availability"}, "type": "noul", "criteria": null}"""))
    )
    assertEquals(
      p.question("rating").map(q => dspy4s.core.contracts.DynamicValues.recordGet(q, "criteria")),
      Right(Some(json("""["Cosmetic", {"what": "Work interrupted"}, {"examples": ["Outage"]}]""")))
    )
    assertEquals(p.criteria("flag"), Some(DynamicValue.Null))
  }

  test("the default criteria: none for a Noul, the levels for a Score, a null for each Choice label") {
    val p = plan()
    assertEquals(p.criteria("flag"), None)
    assertEquals(p.criteria("rating"), Some(json("""["bad", "fair", "great"]""")))
    assertEquals(p.criteria("label"), Some(json("""{"2": null, "other": null}""")))
  }

  test("a decision output with no description and no instructions has no question") {
    val bare = SignatureLayout
      .create("S", Vector(FieldSpec("text")), Vector(FieldSpec("flag", TypeRef.noul)))
      .fold(error => fail(error.message), identity)
    val p = DecisionPlan.of(bare, Map.empty).fold(error => fail(error.message), identity)
    assert(p.question("flag").isLeft)
    val withInstructions =
      DecisionPlan.of(bare, Map("flag" -> DecisionSettings(instructions = Some(json("\"Is it?\"")))))
    assertEquals(
      withInstructions.flatMap(_.question("flag")),
      Right(record("""{"instructions": "Is it?", "type": "noul"}"""))
    )
  }

  // ── The decoding ───────────────────────────────────────────────────────────────────────────────────────────────

  test("a Noul value is p >= threshold, with the distance from the threshold as the confidence") {
    val p     = plan("flag" -> DecisionSettings(threshold = Some(0.75)))
    val cases = Vector((0.0, false, 1.0), (0.6, false, 0.2), (0.75, true, 0.0), (0.9, true, 0.2), (1.0, true, 1.0 / 3))
    cases.foreach { (probability, value, confidence) =>
      val out = decoded(p, defaults.updated("flag", noulAt(probability)))
      assertEquals(
        field(out, "flag", "value"),
        DynamicValue.Primitive(PrimitiveValue.Boolean(value)),
        s"p = $probability"
      )
      assertEqualsDouble(double(field(out, "flag", "confidence")), confidence, 1e-12, s"p = $probability")
      assertEqualsDouble(double(field(out, "flag", "probability")), probability, 1e-12)
    }
  }

  test("the threshold endpoints") {
    val cases = Vector((0.0, 0.0, true), (1.0, 0.99, false), (1.0, 1.0, true))
    cases.foreach { (threshold, probability, value) =>
      val out = decoded(
        plan("flag" -> DecisionSettings(threshold = Some(threshold))),
        defaults.updated("flag", noulAt(probability))
      )
      assertEquals(field(out, "flag", "value"), DynamicValue.Primitive(PrimitiveValue.Boolean(value)))
    }
  }

  test("a Score value is the mean level index, and the cuts select the level") {
    val cases = Vector(None -> 2, Some(Vector(0.5, 1.5)) -> 2, Some(Vector(0.5, 1.6)) -> 1, Some(Vector(0.5, 1.4)) -> 2)
    cases.foreach { (cuts, level) =>
      val out = decoded(plan("rating" -> DecisionSettings(cuts = cuts)))
      assertEqualsDouble(double(field(out, "rating", "value")), 1.5, 1e-12)
      assertEquals(field(out, "rating", "level"), DynamicValue.Primitive(PrimitiveValue.Int(level)), s"cuts $cuts")
      assertEqualsDouble(double(field(out, "rating", "confidence")), 0.61, 1e-12)
    }
    val scaled =
      decoded(plan(), defaults.updated("rating", DecisionEvidence.Score(Map(0 -> 0.099, 1 -> 0.297, 2 -> 0.594), 0.61)))
    assertEqualsDouble(double(field(scaled, "rating", "value")), 1.5, 1e-12)
    val probabilities = field(scaled, "rating", "probabilities")
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordKeys(probabilities match
        case r: DynamicValue.Record => r
        case other                  => fail(s"not an object: $other")),
      Vector("0", "1", "2")
    )
  }

  test("a Choice value maximizes probability times weight") {
    def pick(weights: Option[Map[String, Double]], two: Double = 0.8, other: Double = 0.2): DynamicValue =
      field(
        decoded(plan("label" -> DecisionSettings(weights = weights)), defaults.updated("label", labelEv(two, other))),
        "label",
        "value"
      )
    val label = (text: String) => DynamicValue.Primitive(PrimitiveValue.String(text))
    assertEquals(pick(None), label("2"))
    assertEquals(pick(Some(Map("2" -> 0.1))), label("other"))
    assertEquals(pick(Some(Map("2" -> 10.0, "other" -> 100.0))), label("other"))
    assertEquals(pick(Some(Map.empty)), label("2"))
    assertEquals(pick(Some(Map("2" -> 1.0))), label("2"))
    assertEquals(pick(Some(Map("2" -> 0.25))), label("2"), "a weighted tie goes to the raw winner")
    assertEquals(pick(Some(Map("2" -> 0.0))), label("other"))
    assertEquals(
      pick(Some(Map("other" -> 0.25)), two = 0.2, other = 0.8),
      label("other"),
      "a weighted tie goes to the raw winner"
    )
  }

  test("a Choice pick with no positive probability mass is a Left") {
    val p      = plan("label" -> DecisionSettings(weights = Some(Map("2" -> 0.0))))
    val result = p.decode(defaults.updated("label", labelEv(1.0, 0.0)))
    assert(result.left.exists(_.message.contains("no positive probability mass")), result.toString)
  }

  test("a native output gives the plain value") {
    val p   = plan("native_flag" -> DecisionSettings(threshold = Some(0.7)), "native_label" -> DecisionSettings())
    val out = decoded(
      p,
      defaults ++ Map(
        "native_flag"  -> noulAt(0.65),
        "native_label" -> DecisionEvidence.Choice(Map("billing" -> 0.3, "technical" -> 0.7), 0.9)
      )
    )
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordGet(out, "native_flag"),
      Some(DynamicValue.Primitive(PrimitiveValue.Boolean(false)))
    )
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordGet(out, "native_label"),
      Some(DynamicValue.Primitive(PrimitiveValue.String("technical")))
    )
  }

  test("a rich output gives its result record") {
    val out = decoded(plan())
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordGet(out, "flag").collect { case r: DynamicValue.Record =>
        dspy4s.core.contracts.DynamicValues.recordKeys(r)
      },
      Some(Vector("value", "confidence", "probability"))
    )
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordGet(out, "rating").collect { case r: DynamicValue.Record =>
        dspy4s.core.contracts.DynamicValues.recordKeys(r)
      },
      Some(Vector("value", "confidence", "probabilities", "level"))
    )
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordGet(out, "label").collect { case r: DynamicValue.Record =>
        dspy4s.core.contracts.DynamicValues.recordKeys(r)
      },
      Some(Vector("value", "confidence", "probabilities"))
    )
  }

  test("evidence that lacks a decision output is a Left") {
    assert(plan().decode(defaults - "rating").isLeft)
  }

  // ── The evidence of an answer ──────────────────────────────────────────────────────────────────────────────────

  test("the evidence of an answer, with the other keys of a Jev answer ignored") {
    val p = plan()
    assertEquals(p.evidence("flag", json("""{"type": "noul", "noul": 0.8}""")), Right(DecisionEvidence.Noul(0.8)))
    assertEquals(
      p.evidence(
        "rating",
        json(
          """{"type": "score", "score": 1.5, "confidence": 0.61, "probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}, "legend": {"0": "bad"}}"""
        )
      ),
      Right(rating)
    )
    assertEquals(
      p.evidence(
        "label",
        json("""{"type": "choice", "choice": "2", "confidence": 0.73, "probabilities": {"2": 0.8, "other": 0.2}}""")
      ),
      Right(labelEv(0.8, 0.2))
    )
    assertEquals(p.evidence("flag", json("""{"noul": 1}""")), Right(DecisionEvidence.Noul(1.0)))
  }

  test("malformed evidence is a Left") {
    val p   = plan()
    val bad = Vector(
      "flag"   -> """{"noul": 1.1}""",
      "flag"   -> """{"noul": -0.1}""",
      "flag"   -> """{"noul": true}""",
      "flag"   -> """{}""",
      "rating" -> """{"probabilities": {"0": 0.1, "1": 0.9}, "confidence": 0.6}""",
      "rating" -> """{"probabilities": {"0": -0.1, "1": 0.3, "2": 0.8}, "confidence": 0.6}""",
      "rating" -> """{"probabilities": {"0.9": 0.1, "1.9": 0.3, "2.9": 0.6}, "confidence": 0.6}""",
      "rating" -> """{"probabilities": {"00": 0.1, "1": 0.3, "2": 0.6}, "confidence": 0.6}""",
      "rating" -> """{"probabilities": {"0": 0.1, "1": 0.3, "2": 0.6, "3": 0.0}, "confidence": 0.6}""",
      "rating" -> """{"probabilities": {"0": 0.1, "1": 0.3, "2": 0.6}}""",
      "label"  -> """{"probabilities": {"unknown": 1}, "confidence": 0.7}""",
      "label"  -> """{"probabilities": {"2": 0.8, "other": 0.2}, "confidence": 1.2}"""
    )
    val accepted = bad.filter((name, text) => p.evidence(name, json(text)).isRight)
    assertEquals(accepted, Vector.empty)
    assert(p.decode(defaults.updated("rating", DecisionEvidence.Score(Map(0 -> 0.0, 1 -> 0.0, 2 -> 0.0), 0.6))).isLeft)
    assert(p.decode(defaults.updated("flag", DecisionEvidence.Noul(Double.NaN))).isLeft)
  }

  // ── The validation of the settings ─────────────────────────────────────────────────────────────────────────────

  test("each invalid setting is a Left") {
    val invalid: Vector[(String, DecisionSettings)] = Vector(
      "flag"    -> DecisionSettings(threshold = Some(-0.1)),
      "flag"    -> DecisionSettings(threshold = Some(Double.NaN)),
      "flag"    -> DecisionSettings(cuts = Some(Vector(0.5))),
      "missing" -> DecisionSettings(threshold = Some(0.3)),
      "summary" -> DecisionSettings(),
      "rating"  -> DecisionSettings(cuts = Some(Vector(0.5, 1.5)), weights = Some(Map("0" -> 1.0))),
      "label"   -> DecisionSettings(weights = Some(Map("unknown" -> 1.0))),
      "label"   -> DecisionSettings(weights = Some(Map("2" -> -1.0))),
      "label"   -> DecisionSettings(weights = Some(Map("2" -> Double.NaN))),
      "label"   -> DecisionSettings(weights = Some(Map("2" -> Double.PositiveInfinity))),
      "label"   -> DecisionSettings(weights = Some(Map("2" -> 0.0, "other" -> 0.0))),
      "rating"  -> DecisionSettings(cuts = Some(Vector(0.0, 1.5))),
      "rating"  -> DecisionSettings(cuts = Some(Vector(0.5, 2.0))),
      "rating"  -> DecisionSettings(cuts = Some(Vector(1.5, 0.5))),
      "rating"  -> DecisionSettings(cuts = Some(Vector(0.5, 0.5))),
      "rating"  -> DecisionSettings(cuts = Some(Vector(0.5))),
      "rating"  -> DecisionSettings(cuts = Some(Vector(0.5, Double.NaN))),
      "flag"    -> DecisionSettings(instructions = Some(num(42))),
      "flag"    -> DecisionSettings(instructions =
        Some(DynamicValue.Record(zio.blocks.chunk.Chunk.single("bad" -> num(Double.NaN))))
      ),
      "flag"   -> DecisionSettings(criteria = Some(json("""["yes", "no"]"""))),
      "flag"   -> DecisionSettings(criteria = Some(json("""{"maybe": "x"}"""))),
      "label"  -> DecisionSettings(criteria = Some(json("""{"2": "two"}"""))),
      "rating" -> DecisionSettings(criteria = Some(json("""["a", "b"]"""))),
      "rating" -> DecisionSettings(criteria = Some(json("""["a", null, "c"]"""))),
      "rating" -> DecisionSettings(criteria = Some(json("""["a", 3, "c"]""")))
    )
    val accepted = invalid.filterNot(rejected(_))
    assertEquals(accepted, Vector.empty)
  }

  test("a Score needs 2 to 10 levels, and a Choice needs distinct labels") {
    def layoutWith(output: FieldSpec): SignatureLayout =
      SignatureLayout.create(
        "S",
        Vector(FieldSpec("text")),
        Vector(output)
      ).fold(error => fail(error.message), identity)
    val levels = (n: Int) => (0 until n).map(i => s"l$i").toVector
    assert(DecisionPlan.of(
      layoutWith(FieldSpec("r", TypeRef.score, Some("R."), enumValues = levels(1))),
      Map.empty
    ).isLeft)
    assert(DecisionPlan.of(
      layoutWith(FieldSpec("r", TypeRef.score, Some("R."), enumValues = levels(11))),
      Map.empty
    ).isLeft)
    assert(DecisionPlan.of(
      layoutWith(FieldSpec("r", TypeRef.score, Some("R."), enumValues = levels(10))),
      Map.empty
    ).isRight)
    assert(DecisionPlan.of(
      layoutWith(FieldSpec("c", TypeRef.choice, Some("C."), enumValues = Vector.empty)),
      Map.empty
    ).isLeft)
    assert(DecisionPlan.of(
      layoutWith(FieldSpec("c", TypeRef.choice, Some("C."), enumValues = Vector("a", "a"))),
      Map.empty
    ).isLeft)
  }

  // ── The JSON form of the settings ──────────────────────────────────────────────────────────────────────────────

  test("the JSON form keeps only the explicit settings, in a fixed order, and reads back") {
    val settings = DecisionSettings(
      cuts = Some(Vector(0.4, 1.7)),
      instructions = Some(DynamicValue.Null),
      criteria = Some(json("""["Cosmetic", {"what": "Work interrupted"}, "Outage"]"""))
    )
    assertEquals(
      dspy4s.core.contracts.DynamicValues.recordKeys(settings.toDynamic),
      Vector("cuts", "instructions", "criteria")
    )
    assertEquals(DecisionSettings.fromDynamic(settings.toDynamic), Right(settings))
    assertEquals(DecisionSettings().toDynamic, DynamicValue.Record.empty)
    val all = DecisionSettings(threshold = Some(0.8), weights = Some(Map("2" -> 0.5)))
    assertEquals(DecisionSettings.fromDynamic(all.toDynamic), Right(all))
  }

  test("the JSON form reads integers, and rejects an unknown key or a wrong type") {
    assertEquals(
      DecisionSettings.fromDynamic(json("""{"threshold": 1}""")),
      Right(DecisionSettings(threshold = Some(1.0)))
    )
    assertEquals(
      DecisionSettings.fromDynamic(json("""{"cuts": [1, 1.5]}""")),
      Right(DecisionSettings(cuts = Some(Vector(1.0, 1.5))))
    )
    assert(DecisionSettings.fromDynamic(json("""{"threshold": 0.5, "unknown": "typo"}""")).isLeft)
    assert(DecisionSettings.fromDynamic(json("""{"threshold": "high"}""")).isLeft)
    assert(DecisionSettings.fromDynamic(json("""{"weights": [1, 2]}""")).isLeft)
    assert(DecisionSettings.fromDynamic(json("""{"threshold": true}""")).isLeft)
  }

  test("levelIn takes only the cases of the level enum") {
    val score = Score[DpSeverity](value = 1.0, confidence = 0.9, level = Some(1))
    assertEquals(score.levelIn(DpSeverity.values.toIndexedSeq), Some(DpSeverity.High))
    assert(
      compileErrors("score.levelIn(DpPriority.values.toIndexedSeq)").nonEmpty,
      "levelIn accepted the cases of an unrelated enum"
    )
  }
