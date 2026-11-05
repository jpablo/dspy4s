package dspy4s.signatures

import dspy4s.core.contracts.{DspyError, DynamicValues, PythonText, TypeRef}
import munit.FunSuite
import zio.blocks.maybe.Maybe
import zio.blocks.schema.{DynamicValue, Modifier, Schema}

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class AfPerson(
    @Modifier.rename("full_name") name: String,
    @Modifier.alias("years") age      : Int,
    note                              : Option[String]
)
object AfPerson:
  given Schema[AfPerson] = Schema.derived

final case class AfTeam(
    lead                       : AfPerson,
    members                    : List[AfPerson],
    backup                     : Option[AfPerson],
    @Modifier.rename("n") count: Int
)
object AfTeam:
  given Schema[AfTeam] = Schema.derived

@Modifier.fieldNaming("kebab-case")
final case class AfStreet(streetName: String, @Modifier.rename("zip") postCode: Int)
object AfStreet:
  given Schema[AfStreet] = Schema.derived

@Modifier.fieldNaming("snake_case")
final case class AfSnake(firstName: String, lastSeen: Option[String], homeAddress: AfStreet)
object AfSnake:
  given Schema[AfSnake] = Schema.derived

sealed trait AfShape
object AfShape:
  @Modifier.rename("circle")
  @Modifier.alias("ring")
  final case class Circle(radius: Double) extends AfShape
  final case class Square(side: Double)   extends AfShape
  case object Dot                         extends AfShape
  given Schema[AfShape] = Schema.derived

final case class AfShapes(shape: AfShape, shapes: List[AfShape], maybe: Option[AfShape])
object AfShapes:
  given Schema[AfShapes] = Schema.derived

@Modifier.caseNaming("snake_case")
sealed trait AfEvent
object AfEvent:
  final case class BigCircle(radiusSize: Double) extends AfEvent
  case object SmallDot                           extends AfEvent
  given Schema[AfEvent] = Schema.derived

final case class AfEvents(event: AfEvent, more: List[AfEvent])
object AfEvents:
  given Schema[AfEvents] = Schema.derived

@Modifier.caseNaming("snake_case")
enum AfLevel:
  case VeryHigh, VeryLow
object AfLevel:
  given Schema[AfLevel] = Schema.derived

sealed trait AfTone
object AfTone:
  @Modifier.rename("warm")
  case object Warm extends AfTone
  @Modifier.alias("icy")
  case object Cold extends AfTone
  given Schema[AfTone] = Schema.derived

final case class AfLabels(level: AfLevel, tone: AfTone, maybe: Option[AfLevel], many: List[AfTone])
object AfLabels:
  given Schema[AfLabels] = Schema.derived

@Modifier.discriminator("kind")
sealed trait AfKinded
object AfKinded:
  final case class Circle(radius: Double, note: Option[String]) extends AfKinded
  final case class Square(side: Double)                         extends AfKinded
  case object Dot                                               extends AfKinded
  sealed trait Animal                                           extends AfKinded
  final case class Cat(name: String)                            extends Animal
  given Schema[AfKinded] = Schema.derived

final case class AfKindeds(item: AfKinded, items: List[AfKinded], maybe: Option[AfKinded])
object AfKindeds:
  given Schema[AfKindeds] = Schema.derived

final case class AfTransient(
    name                              : String,
    @Modifier.transient() cached      : Int    = 7,
    @Modifier.encodeTransient() secret: String = "s"
)
object AfTransient:
  given Schema[AfTransient] = Schema.derived

final case class AfMaybe(hint: Maybe[String], count: Int)
object AfMaybe:
  given Schema[AfMaybe] = Schema.derived

/** The name or the form that an annotation gives is the name or the form of the schema, of the prompt, and of the
  * encode, and the decode reads it. The decode also reads an alias and the name in the code, as a lenient rule.
  */
class AnnotatedFormsSuite extends FunSuite:

  private def shapeOf[A](using Schema[A]) = ZioSchemaCodec.derivedFromZioSchema[A]

  private def record(json: String): DynamicValue.Record =
    PythonText.parseJson(json) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"expected a JSON object, got $other")

  private def decoded[A](json: String)(using Schema[A]): Either[DspyError, A] = shapeOf[A].decode(record(json))

  private def textOf[A](value: A)(using Schema[A]): String = PythonText.json(shapeOf[A].encode(value))

  private def namesOf[A](using Schema[A]): Vector[String] = shapeOf[A].fieldSpecs.map(_.name)

  private def specOf[A](name: String)(using Schema[A]) =
    shapeOf[A].fieldSpecs.find(_.name == name).getOrElse(fail(s"no field $name"))

  private def at(value: DynamicValue, path: String*): DynamicValue =
    path.foldLeft(value) { (current, key) =>
      current match
        case found: DynamicValue.Record => DynamicValues.recordGet(found, key).getOrElse(fail(s"no key $key in $found"))
        case other                      => fail(s"expected a record to read $key, got $other")
    }

  private def schemaOf[A](using Schema[A]): DynamicValue.Record = shapeOf[A].jsonSchema.getOrElse(fail("no schema"))

  private def propertyNames[A](using Schema[A]): Vector[String] =
    at(schemaOf[A], "properties") match
      case found: DynamicValue.Record => DynamicValues.recordKeys(found)
      case other                      => fail(s"expected a record, got $other")

  private def assertTextRoundTrip[A](value: A)(using Schema[A]): Unit =
    val shape = shapeOf[A]
    assertEquals(shape.decode(shape.encode(value)), Right(value))
    assertEquals(shape.decode(record(PythonText.json(shape.encode(value)))), Right(value))

  private def person: AfPerson = AfPerson("a", 3, None)

  // ── A field ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("a renamed field has its new name in the field specs, in the schema, and in the encode") {
    assertEquals(namesOf[AfPerson], Vector("full_name", "age", "note"))
    assertEquals(propertyNames[AfPerson].toSet, Set("full_name", "age", "note"))
    assertEquals(textOf(person), """{"full_name": "a", "age": 3, "note": null}""")
  }

  test("a schema shows no alias: it is a lenient rule of the decode") {
    assert(!PythonText.json(schemaOf[AfPerson]).contains("years"), PythonText.json(schemaOf[AfPerson]))
    assert(!PythonText.json(schemaOf[AfTeam]).contains("years"), PythonText.json(schemaOf[AfTeam]))
  }

  test("a renamed field decodes from its new name, in each position") {
    assertEquals(
      decoded[AfTeam](
        """{"lead": {"full_name": "a", "age": 3}, "members": [{"full_name": "b", "age": 4, "note": "x"}],
          |"backup": {"full_name": "c", "age": 5}, "n": 1}""".stripMargin
      ),
      Right(AfTeam(person, List(AfPerson("b", 4, Some("x"))), Some(AfPerson("c", 5, None)), 1))
    )
  }

  test("a renamed field decodes from its alias, and from its name in the code") {
    assertEquals(decoded[AfPerson]("""{"full_name": "a", "years": 3}"""), Right(person))
    assertEquals(decoded[AfPerson]("""{"name": "a", "age": 3}"""), Right(person))
  }

  test("a renamed field takes the lenient rules of its type") {
    assertEquals(decoded[AfPerson]("""{"full_name": "a", "age": "3"}"""), Right(person))
    assertEquals(
      decoded[AfTeam]("""{"lead": {"full_name": "a", "age": "3"}, "members": [], "backup": null, "n": "1"}"""),
      Right(AfTeam(person, Nil, None, 1))
    )
  }

  test("a required field is required by its new name, and the error names it") {
    decoded[AfPerson]("""{"age": 3}""") match
      case Left(error)  => assert(error.message.contains("full_name"), error.message)
      case Right(value) => fail(s"the decode accepted $value")
  }

  test("a naming strategy changes each field name of its record, and a rename wins") {
    assertEquals(namesOf[AfSnake], Vector("first_name", "last_seen", "home_address"))
    assertEquals(propertyNames[AfSnake].toSet, Set("first_name", "last_seen", "home_address"))
    assertEquals(
      decoded[AfSnake]("""{"first_name": "a", "last_seen": null, "home_address": {"street-name": "s", "zip": "1"}}"""),
      Right(AfSnake("a", None, AfStreet("s", 1)))
    )
    assertEquals(
      textOf(AfSnake("a", Some("b"), AfStreet("s", 1))),
      """{"first_name": "a", "last_seen": "b", "home_address": {"street-name": "s", "zip": 1}}"""
    )
  }

  test("a field with a transient annotation is not in the field specs or the encode, and it decodes to its default") {
    assertEquals(namesOf[AfTransient], Vector("name", "secret"))
    assert(!propertyNames[AfTransient].contains("cached"))
    assertEquals(textOf(AfTransient("a")), """{"name": "a"}""")
    assertEquals(decoded[AfTransient]("""{"name": "a"}"""), Right(AfTransient("a")))
    assertEquals(decoded[AfTransient]("""{"name": "a", "secret": "t"}"""), Right(AfTransient("a", secret = "t")))
  }

  // ── A case ────────────────────────────────────────────────────────────────────────────────────────────────────

  test("a renamed case has its new name in the schema and in the encode") {
    val text = PythonText.json(schemaOf[AfShapes])
    assert(text.contains("\"circle\""), text)
    // The title of the record of the case stays the type name, `"title": "Circle"`, as the codec writes it.
    assert(!text.contains("\"Circle\": {"), text)
    assert(!text.contains("\"ring\""), text)
    assertEquals(
      textOf(AfShapes(AfShape.Circle(1.0), List(AfShape.Square(2.0)), Some(AfShape.Dot))),
      """{"shape": {"circle": {"radius": 1.0}}, "shapes": [{"Square": {"side": 2.0}}], "maybe": {"Dot": {}}}"""
    )
  }

  test("a renamed case decodes from its new name, from its alias, and from its name in the code") {
    def shape(form: String) = decoded[AfShapes](s"""{"shape": $form, "shapes": [$form], "maybe": $form}""")
    val expected            = AfShapes(AfShape.Circle(1.0), List(AfShape.Circle(1.0)), Some(AfShape.Circle(1.0)))
    assertEquals(shape("""{"circle": {"radius": 1}}"""), Right(expected))
    assertEquals(shape("""{"ring": {"radius": 1}}"""), Right(expected))
    assertEquals(shape("""{"Circle": {"radius": 1}}"""), Right(expected))
    assert(shape("""{"triangle": {"radius": 1}}""").isLeft)
  }

  test("a case naming strategy changes each case name, and not the field names of a case") {
    val text = PythonText.json(schemaOf[AfEvents])
    assert(text.contains("\"big_circle\""), text)
    assert(text.contains("\"radiusSize\""), text)
    assertEquals(
      decoded[AfEvents]("""{"event": {"big_circle": {"radiusSize": 1}}, "more": ["small_dot", {"small_dot": {}}]}"""),
      Right(AfEvents(AfEvent.BigCircle(1.0), List(AfEvent.SmallDot, AfEvent.SmallDot)))
    )
    assertEquals(
      textOf(AfEvents(AfEvent.BigCircle(1.0), List(AfEvent.SmallDot))),
      """{"event": {"big_circle": {"radiusSize": 1.0}}, "more": [{"small_dot": {}}]}"""
    )
  }

  // ── A label ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("an enum shows the names of its annotations as its allowed values, and in the encode") {
    assertEquals(specOf[AfLabels]("level").enumValues, Vector("very_high", "very_low"))
    assertEquals(specOf[AfLabels]("level").typeRef, TypeRef.string)
    assertEquals(specOf[AfLabels]("tone").enumValues, Vector("warm", "Cold"))
    assertEquals(
      textOf(AfLabels(AfLevel.VeryLow, AfTone.Warm, Some(AfLevel.VeryHigh), List(AfTone.Cold, AfTone.Warm))),
      """{"level": "very_low", "tone": "warm", "maybe": "very_high", "many": ["Cold", "warm"]}"""
    )
  }

  test("an enum decodes from its new name, from its alias, and from its name in the code") {
    assertEquals(
      decoded[AfLabels](
        """{"level": "very_low", "tone": "warm", "maybe": "very_high", "many": ["icy", "Cold", "Warm"]}"""
      ),
      Right(AfLabels(AfLevel.VeryLow, AfTone.Warm, Some(AfLevel.VeryHigh), List(AfTone.Cold, AfTone.Cold, AfTone.Warm)))
    )
    assertEquals(
      decoded[AfLabels]("""{"level": "VeryLow", "tone": "Cold", "maybe": null, "many": []}""").map(_.level),
      Right(AfLevel.VeryLow)
    )
    assert(decoded[AfLabels]("""{"level": "medium", "tone": "Cold", "maybe": null, "many": []}""").isLeft)
  }

  // ── A discriminator ───────────────────────────────────────────────────────────────────────────────────────────

  test("a discriminator is a field of the value in the schema and in the encode") {
    val text = PythonText.json(schemaOf[AfKindeds])
    assert(text.contains("\"kind\""), text)
    assertEquals(
      textOf(AfKindeds(AfKinded.Circle(1.0, None), List(AfKinded.Square(2.0), AfKinded.Dot), Some(AfKinded.Cat("c")))),
      """{"item": {"kind": "Circle", "radius": 1.0, "note": null}, "items": [{"kind": "Square", "side": 2.0}, """ +
        """{"kind": "Dot"}], "maybe": {"kind": "Cat", "name": "c"}}"""
    )
  }

  test("a discriminator selects the case, in each position, with the rules of its fields") {
    assertEquals(
      decoded[AfKindeds](
        """{"item": {"kind": "Circle", "radius": "1"},
          |"items": [{"kind": "Square", "side": 2}, {"kind": "Dot"}],
          |"maybe": {"kind": "Cat", "name": "c"}}""".stripMargin
      ),
      Right(AfKindeds(AfKinded.Circle(1.0, None), List(AfKinded.Square(2.0), AfKinded.Dot), Some(AfKinded.Cat("c"))))
    )
  }

  test("a value with a discriminator that names no case, or with no discriminator, is an error") {
    def rejected(item: String) =
      val result = decoded[AfKindeds](s"""{"item": $item, "items": [], "maybe": null}""")
      assert(result.isLeft, s"$item: $result")
    rejected("""{"kind": "Triangle", "side": 1}""")
    rejected("""{"radius": 1}""")
    rejected("""{"kind": 3, "radius": 1}""")
    rejected("""{"kind": "Circle"}""")
  }

  test("a sealed trait with a discriminator also reads the case name as a key and a bare case name") {
    assertEquals(
      decoded[AfKindeds]("""{"item": {"Circle": {"radius": 1}}, "items": ["Dot"], "maybe": null}"""),
      Right(AfKindeds(AfKinded.Circle(1.0, None), List(AfKinded.Dot), None))
    )
  }

  // ── A Maybe ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("a Maybe field is an optional field, and it decodes from a value, a null, and an absent field") {
    assert(specOf[AfMaybe]("hint").optional)
    assertEquals(specOf[AfMaybe]("hint").typeRef, TypeRef.string)
    assertEquals(decoded[AfMaybe]("""{"hint": "x", "count": 1}"""), Right(AfMaybe(Maybe.present("x"), 1)))
    assertEquals(decoded[AfMaybe]("""{"hint": null, "count": 1}"""), Right(AfMaybe(Maybe.absent, 1)))
    assertEquals(decoded[AfMaybe]("""{"count": 1}"""), Right(AfMaybe(Maybe.absent, 1)))
  }

  test("a Maybe field is its element or a null in the encode") {
    assertEquals(textOf(AfMaybe(Maybe.present("x"), 1)), """{"hint": "x", "count": 1}""")
    assertEquals(textOf(AfMaybe(Maybe.absent, 1)), """{"hint": null, "count": 1}""")
  }

  // ── The law for each annotation ───────────────────────────────────────────────────────────────────────────────

  test("the JSON text of a value with renamed fields decodes to the value") {
    assertTextRoundTrip(person)
    assertTextRoundTrip(AfTeam(person, List(AfPerson("b", 4, Some("x"))), Some(person), 2))
    assertTextRoundTrip(AfSnake("a", Some("b"), AfStreet("s", 1)))
    assertTextRoundTrip(AfTransient("a"))
  }

  test("the JSON text of a value with renamed cases decodes to the value") {
    assertTextRoundTrip(AfShapes(AfShape.Circle(1.0), List(AfShape.Square(2.0), AfShape.Dot), Some(AfShape.Dot)))
    assertTextRoundTrip(AfEvents(AfEvent.BigCircle(1.0), List(AfEvent.SmallDot)))
    assertTextRoundTrip(AfLabels(AfLevel.VeryLow, AfTone.Warm, Some(AfLevel.VeryHigh), List(AfTone.Cold)))
  }

  test("the JSON text of a value with a discriminator decodes to the value") {
    assertTextRoundTrip(
      AfKindeds(AfKinded.Circle(1.0, Some("n")), List(AfKinded.Square(2.0), AfKinded.Dot), Some(AfKinded.Cat("c")))
    )
  }

  test("the JSON text of a Maybe decodes to the value") {
    assertTextRoundTrip(AfMaybe(Maybe.present("x"), 1))
    assertTextRoundTrip(AfMaybe(Maybe.absent, 1))
  }
