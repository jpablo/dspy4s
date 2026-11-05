package dspy4s.signatures

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, PythonText, TypeRef}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.core.decisions.Noul
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

import java.time.*
import java.util.{Currency, UUID}

// Top-level fixtures (Mirror derivation needs them outside the suite).
sealed trait TvfShape
object TvfShape:
  final case class Circle(radius: Double)                     extends TvfShape
  final case class Square(side: Double, note: Option[String]) extends TvfShape
  case object Dot                                             extends TvfShape
  given Schema[TvfShape] = Schema.derived

final case class TvfShapes(
    shape : TvfShape,
    shapes: List[TvfShape],
    maybe : Option[TvfShape],
    byName: Map[String, TvfShape]
)
object TvfShapes:
  given Schema[TvfShapes] = Schema.derived

sealed trait TvfThing
object TvfThing:
  final case class Rock(weight: Int) extends TvfThing
  sealed trait Animal                extends TvfThing
  final case class Cat(name: String) extends Animal
  final case class Dog(name: String) extends Animal
  given Schema[TvfThing] = Schema.derived

final case class TvfThingBox(thing: TvfThing)
object TvfThingBox:
  given Schema[TvfThingBox] = Schema.derived

sealed trait TvfColor
object TvfColor:
  case object Blue   extends TvfColor
  sealed trait Warm  extends TvfColor
  case object Red    extends Warm
  case object Orange extends Warm
  given Schema[TvfColor] = Schema.derived

enum TvfKind:
  case alpha, beta
object TvfKind:
  given Schema[TvfKind] = Schema.derived

final case class TvfLabels(color: TvfColor, kind: TvfKind)
object TvfLabels:
  given Schema[TvfLabels] = Schema.derived

sealed trait TvfTree
object TvfTree:
  final case class Leaf(value: Int)              extends TvfTree
  final case class Node(children: List[TvfTree]) extends TvfTree
  given Schema[TvfTree] = Schema.derived

final case class TvfTreeBox(tree: TvfTree)
object TvfTreeBox:
  given Schema[TvfTreeBox] = Schema.derived

final case class TvfPairs(
    pair  : (Int, String),
    pairs : List[(Int, String)],
    maybe : Option[(Int, String)],
    nested: ((Int, Int), String)
)
object TvfPairs:
  given Schema[TvfPairs] = Schema.derived

final case class TvfTimes(
    instant  : Instant,
    uuid     : UUID,
    date     : LocalDate,
    dateTime : LocalDateTime,
    time     : LocalTime,
    offset   : OffsetDateTime,
    offsetT  : OffsetTime,
    zoned    : ZonedDateTime,
    duration : Duration,
    period   : Period,
    year     : Year,
    yearMonth: YearMonth,
    monthDay : MonthDay,
    day      : DayOfWeek,
    month    : Month,
    zone     : ZoneId,
    zoneOff  : ZoneOffset,
    currency : Currency,
    maybe    : Option[Instant],
    many     : List[Instant],
    byName   : Map[String, UUID]
)
object TvfTimes:
  given Schema[TvfTimes] = Schema.derived

final case class TvfNumbers(big: BigInt, exact: BigDecimal)
object TvfNumbers:
  given Schema[TvfNumbers] = Schema.derived

final case class TvfInner(a: Int, note: Option[String])
object TvfInner:
  given Schema[TvfInner] = Schema.derived

final case class TvfBoxedRecord(inner: TvfInner)
object TvfBoxedRecord:
  given Schema[TvfBoxedRecord] = Schema[TvfInner].transform(TvfBoxedRecord(_), _.inner)

final case class TvfUserId(value: String)
object TvfUserId:
  given Schema[TvfUserId] = Schema[String].transform(TvfUserId(_), _.value)

final case class TvfTags(values: List[String])
object TvfTags:
  given Schema[TvfTags] = Schema[List[String]].transform(TvfTags(_), _.values)

final case class TvfWrapped(item: TvfBoxedRecord, user: TvfUserId, tags: TvfTags)
object TvfWrapped:
  given Schema[TvfWrapped] = Schema.derived

final case class TvfEither(either: Either[Int, String])
object TvfEither:
  given Schema[TvfEither] = Schema.derived

sealed trait TvfMix
object TvfMix:
  final case class Stamped(at: Instant, pair: (Int, String), shape: TvfShape, maybe: Option[UUID], kind: TvfKind)
      extends TvfMix
  given Schema[TvfMix] = Schema.derived

final case class TvfDeep(
    inCase : TvfMix,
    inTuple: (Instant, TvfShape, (Int, Int), TvfKind),
    many   : List[(Instant, TvfShape)],
    byName : Map[String, (Int, String)],
    keyed  : Map[UUID, Instant],
    option : Option[List[TvfShape]],
    letter : Char
)
object TvfDeep:
  given Schema[TvfDeep] = Schema.derived

final case class TvfStamp(at: Instant)
object TvfStamp:
  given Schema[TvfStamp] = Schema[Instant].transform(TvfStamp(_), _.at)

final case class TvfPairBox(pair: (Int, String))
object TvfPairBox:
  given Schema[TvfPairBox] = Schema.derived[(Int, String)].transform(TvfPairBox(_), _.pair)

final case class TvfShapeBox(shape: TvfShape)
object TvfShapeBox:
  given Schema[TvfShapeBox] = Schema[TvfShape].transform(TvfShapeBox(_), _.shape)

final case class TvfKindBox(kind: TvfKind)
object TvfKindBox:
  given Schema[TvfKindBox] = Schema[TvfKind].transform(TvfKindBox(_), _.kind)

final case class TvfMaybeBox(maybe: Option[Int])
object TvfMaybeBox:
  given Schema[TvfMaybeBox] = Schema[Option[Int]].transform(TvfMaybeBox(_), _.maybe)

final case class TvfStampBox(stamp: TvfStamp)
object TvfStampBox:
  given Schema[TvfStampBox] = Schema[TvfStamp].transform(TvfStampBox(_), _.stamp)

final case class TvfBehind(
    stamp: TvfStamp,
    pair : TvfPairBox,
    shape: TvfShapeBox,
    kind : TvfKindBox,
    maybe: TvfMaybeBox,
    twice: TvfStampBox,
    list : List[TvfStamp]
)
object TvfBehind:
  given Schema[TvfBehind] = Schema.derived

sealed trait TvfWins
object TvfWins:
  final case class Same(top: Int) extends TvfWins
  sealed trait Inner              extends TvfWins
  object Inner:
    final case class Same(deep: Int) extends Inner
  given Schema[TvfWins] = Schema.derived

final case class TvfWinsBox(wins: TvfWins)
object TvfWinsBox:
  given Schema[TvfWinsBox] = Schema.derived

final case class TvfDefaults(
    at   : Instant       = Instant.parse("2024-01-02T03:04:05Z"),
    pair : (Int, String) = (1, "a"),
    shape: TvfShape      = TvfShape.Dot,
    color: TvfColor      = TvfColor.Red
)
object TvfDefaults:
  given Schema[TvfDefaults] = Schema.derived

final case class TvfNouls(values: List[Noul])
object TvfNouls:
  given Schema[TvfNouls] = Schema[List[Noul]].transform(TvfNouls(_), _.values)

final case class TvfNoulsBox(nouls: TvfNouls)
object TvfNoulsBox:
  given Schema[TvfNoulsBox] = Schema.derived

final case class TvfForms(
    nested : Option[Option[Int]],
    inTuple: (Option[Int], String),
    shape  : (TvfShape, Int),
    either : Either[Int, String],
    mix    : TvfMix
)
object TvfForms:
  given Schema[TvfForms] = Schema.derived

final case class TvfDecided(noul: Noul, maybe: Option[Noul], text: Option[String])
object TvfDecided:
  given Schema[TvfDecided] = Schema.derived

enum TvfNamedNone:
  case None, Other
object TvfNamedNone:
  given Schema[TvfNamedNone] = Schema.derived

final case class TvfNullTexts(
    note  : Option[String],
    list  : List[Option[String]],
    byName: Map[String, Option[String]],
    pair  : (Option[String], Int),
    user  : Option[TvfUserId]
)
object TvfNullTexts:
  given Schema[TvfNullTexts] = Schema.derived

final case class TvfNamedNoneBox(named: Option[TvfNamedNone], other: List[Option[TvfNamedNone]])
object TvfNamedNoneBox:
  given Schema[TvfNamedNoneBox] = Schema.derived

final case class TvfLenient(n: Option[Int], at: Option[Instant], shape: Option[TvfShape], kind: Option[TvfKind])
object TvfLenient:
  given Schema[TvfLenient] = Schema.derived

final case class TvfKeys(
    kind  : Map[TvfKind, Int],
    color : Map[TvfColor, Int],
    number: Map[Int, String],
    uuid  : Map[UUID, Int],
    text  : Map[String, Int]
)
object TvfKeys:
  given Schema[TvfKeys] = Schema.derived

final case class TvfPairKeys(pairs: Map[(Int, Int), String])
object TvfPairKeys:
  given Schema[TvfPairKeys] = Schema.derived

final case class TvfNestedDefault(x: Option[Option[Int]] = Some(None))
object TvfNestedDefault:
  given Schema[TvfNestedDefault] = Schema.derived

final case class TvfTupleNames(_1: Int, _2: String)
object TvfTupleNames:
  given Schema[TvfTupleNames] = Schema.derived

final case class TvfTupleNamesBox(names: TvfTupleNames)
object TvfTupleNamesBox:
  given Schema[TvfTupleNamesBox] = Schema.derived

/** A typed value travels in one form: the JSON schema that the model sees, the JSON text that a prompt shows, and the
  * typed decode of a reply must agree. Upstream pydantic has one form for a union of models, a tuple, a date, and a
  * UUID. The port takes the form of the zio-blocks schema: a case name as a key, an array, and an ISO text.
  */
class TypedValueFormsSuite extends FunSuite:

  private def shapeOf[A](using Schema[A]) = ZioSchemaCodec.derivedFromZioSchema[A]

  private def record(json: String): DynamicValue.Record =
    PythonText.parseJson(json) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"expected a JSON object, got $other")

  private def decoded[A](json: String)(using Schema[A]): Either[DspyError, A] = shapeOf[A].decode(record(json))

  private def specOf[A](name: String)(using Schema[A]) =
    shapeOf[A].fieldSpecs.find(_.name == name).getOrElse(fail(s"no field $name"))

  private def at(value: DynamicValue, path: String*): DynamicValue =
    path.foldLeft(value) { (current, key) =>
      current match
        case found: DynamicValue.Record => DynamicValues.recordGet(found, key).getOrElse(fail(s"no key $key in $found"))
        case other                      => fail(s"expected a record to read $key, got $other")
    }

  private def schemaOf[A](using Schema[A]): DynamicValue.Record =
    shapeOf[A].jsonSchema.getOrElse(fail("no schema"))

  /** The JSON text of the encode of `value`, as a prompt shows it, must decode to `value`. */
  private def assertTextRoundTrip[A](value: A)(using Schema[A]): Unit =
    val shape = shapeOf[A]
    assertEquals(shape.decode(shape.encode(value)), Right(value))
    assertEquals(shape.decode(record(PythonText.json(shape.encode(value)))), Right(value))

  private def shapes: TvfShapes =
    TvfShapes(
      shape = TvfShape.Circle(1.5),
      shapes = List(TvfShape.Square(2.0, None), TvfShape.Square(3.0, Some("n")), TvfShape.Dot),
      maybe = Some(TvfShape.Circle(1.0)),
      byName = Map("a" -> TvfShape.Circle(3.0))
    )

  private def pairs: TvfPairs =
    TvfPairs(
      pair = (1, "a"),
      pairs = List((2, "b"), (3, "c")),
      maybe = Some((4, "d")),
      nested = ((5, 6), "e")
    )

  private def times: TvfTimes =
    TvfTimes(
      instant = Instant.parse("2024-01-02T03:04:05Z"),
      uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
      date = LocalDate.parse("2024-01-02"),
      dateTime = LocalDateTime.parse("2024-01-02T03:04:05"),
      time = LocalTime.parse("03:04:05"),
      offset = OffsetDateTime.parse("2024-01-02T03:04:05+01:00"),
      offsetT = OffsetTime.parse("03:04:05+01:00"),
      zoned = ZonedDateTime.parse("2024-01-02T03:04:05+01:00[Europe/Paris]"),
      duration = Duration.parse("PT1H30M"),
      period = Period.parse("P1Y2M3D"),
      year = Year.of(2024),
      yearMonth = YearMonth.parse("2024-01"),
      monthDay = MonthDay.parse("--12-25"),
      day = DayOfWeek.MONDAY,
      month = Month.MARCH,
      zone = ZoneId.of("Europe/Paris"),
      zoneOff = ZoneOffset.of("+01:00"),
      currency = Currency.getInstance("USD"),
      maybe = Some(Instant.parse("2024-01-02T03:04:05Z")),
      many = List(Instant.parse("2024-02-03T04:05:06Z")),
      byName = Map("a" -> UUID.fromString("123e4567-e89b-12d3-a456-426614174000"))
    )

  // ── A sealed trait ────────────────────────────────────────────────────────────────────────────────────────────

  test("a sealed trait with fields has a wire type with a structure, and no allowed values") {
    assertEquals(specOf[TvfShapes]("shape").typeRef, TypeRef.union)
    assertEquals(specOf[TvfShapes]("shape").enumValues, Vector.empty)
    assertEquals(specOf[TvfShapes]("maybe").typeRef, TypeRef.union)
    assert(specOf[TvfShapes]("maybe").optional)
  }

  test("a sealed trait with fields shows each case name as a key in its schema") {
    val cases = at(schemaOf[TvfShapes], "properties", "shape", "oneOf") match
      case many: DynamicValue.Sequence => many.elements.toVector
      case other                       => fail(s"expected a list, got $other")
    val names = cases.flatMap { found =>
      at(found, "properties") match
        case properties: DynamicValue.Record => DynamicValues.recordKeys(properties)
        case other                           => fail(s"expected a record, got $other")
    }.toSet
    assertEquals(names, Set("Circle", "Square", "Dot"))
  }

  test("a sealed trait with fields decodes in the form of its schema, in each position") {
    assertEquals(
      decoded[TvfShapes](
        """{"shape": {"Circle": {"radius": 1.5}},
          |"shapes": [{"Square": {"side": 2, "note": null}}, {"Square": {"side": 3, "note": "n"}}, {"Dot": {}}],
          |"maybe": {"Circle": {"radius": 1}},
          |"byName": {"a": {"Circle": {"radius": 3}}}}""".stripMargin
      ),
      Right(shapes)
    )
  }

  test("a field of a case takes the lenient rules of its type, and an absent Option field is None") {
    assertEquals(
      decoded[TvfShapes](
        """{"shape": {"Circle": {"radius": "1.5"}},
          |"shapes": [{"Square": {"side": 2}}], "maybe": null, "byName": {}}""".stripMargin
      ),
      Right(TvfShapes(TvfShape.Circle(1.5), List(TvfShape.Square(2.0, None)), None, Map.empty))
    )
  }

  test("a case with no fields of a sealed trait with fields also decodes from its name") {
    assertEquals(
      decoded[TvfShapes]("""{"shape": "Dot", "shapes": ["Dot"], "maybe": "Dot", "byName": {}}"""),
      Right(TvfShapes(TvfShape.Dot, List(TvfShape.Dot), Some(TvfShape.Dot), Map.empty))
    )
  }

  test("a value that is not the form of the schema is an error") {
    def rejected(shape: String) =
      val result = decoded[TvfShapes](s"""{"shape": $shape, "shapes": [], "maybe": null, "byName": {}}""")
      assert(result.isLeft, s"$shape: $result")
    rejected("""{"Triangle": {"side": 1}}""")
    rejected("""{"Circle": {"radius": 1}, "Square": {"side": 1}}""")
    rejected("""{"radius": 1}""")
    rejected("""{"Circle": {"side": 1}}""")
    rejected("""{"Circle": 3}""")
    rejected("""[1]""")
    rejected("3")
    rejected("null")
  }

  test("a case of a nested sealed trait decodes from its own name, as the schema shows it") {
    val schemaText = PythonText.json(schemaOf[TvfThingBox])
    assert(schemaText.contains("\"Cat\""), schemaText)
    assert(schemaText.contains("\"Rock\""), schemaText)
    assertEquals(decoded[TvfThingBox]("""{"thing": {"Cat": {"name": "a"}}}"""), Right(TvfThingBox(TvfThing.Cat("a"))))
    assertEquals(decoded[TvfThingBox]("""{"thing": {"Rock": {"weight": 2}}}"""), Right(TvfThingBox(TvfThing.Rock(2))))
  }

  test("a sealed trait with only cases with no fields stays a label, also with a nested sealed trait") {
    assertEquals(specOf[TvfLabels]("kind").typeRef, TypeRef.string)
    assertEquals(specOf[TvfLabels]("kind").enumValues, Vector("alpha", "beta"))
    assertEquals(specOf[TvfLabels]("color").typeRef, TypeRef.string)
    assertEquals(specOf[TvfLabels]("color").enumValues.toSet, Set("Blue", "Red", "Orange"))
    assertEquals(
      decoded[TvfLabels]("""{"color": "Red", "kind": "beta"}"""),
      Right(TvfLabels(TvfColor.Red, TvfKind.beta))
    )
    assertEquals(
      decoded[TvfLabels]("""{"color": "Blue", "kind": "alpha"}"""),
      Right(TvfLabels(TvfColor.Blue, TvfKind.alpha))
    )
  }

  test("a recursive sealed trait decodes at each level") {
    assertEquals(
      decoded[TvfTreeBox]("""{"tree": {"Node": {"children": [{"Leaf": {"value": 1}}, {"Node": {"children": []}}]}}}"""),
      Right(TvfTreeBox(TvfTree.Node(List(TvfTree.Leaf(1), TvfTree.Node(Nil)))))
    )
  }

  // ── A tuple ───────────────────────────────────────────────────────────────────────────────────────────────────

  test("a tuple is an array in its schema, on the wire, and in the JSON text of a prompt") {
    assertEquals(specOf[TvfPairs]("pair").typeRef, TypeRef.list)
    assert(DynamicValues.recordGet(
      at(schemaOf[TvfPairs], "properties", "pair") match
        case found: DynamicValue.Record => found
        case other                      => fail(s"expected a record, got $other")
      ,
      "prefixItems"
    ).isDefined)
    assertEquals(
      PythonText.json(shapeOf[TvfPairs].encode(pairs)),
      """{"pair": [1, "a"], "pairs": [[2, "b"], [3, "c"]], "maybe": [4, "d"], "nested": [[5, 6], "e"]}"""
    )
  }

  test("a tuple decodes from an array, in each position") {
    assertEquals(
      decoded[TvfPairs](
        """{"pair": [1, "a"], "pairs": [[2, "b"], [3, "c"]], "maybe": [4, "d"], "nested": [[5, 6], "e"]}"""
      ),
      Right(pairs)
    )
  }

  test("an item of a tuple takes the lenient rules of its type") {
    assertEquals(
      decoded[TvfPairs]("""{"pair": ["1", "a"], "pairs": [], "maybe": null, "nested": [["5", 6], "e"]}""").map(_.pair),
      Right((1, "a"))
    )
  }

  test("an array with the wrong length or a wrong item is an error for a tuple") {
    def rejected(pair: String) =
      val result = decoded[TvfPairs](s"""{"pair": $pair, "pairs": [], "maybe": null, "nested": [[5, 6], "e"]}""")
      assert(result.isLeft, s"$pair: $result")
    rejected("[1]")
    rejected("""[1, "a", 3]""")
    rejected("""["x", "a"]""")
    rejected("3")
  }

  test("a record with the field names of a tuple still decodes, as a leniency of the decode") {
    assertEquals(
      decoded[TvfPairs](
        """{"pair": {"_1": 1, "_2": "a"}, "pairs": [], "maybe": null, "nested": {"_1": {"_1": 5, "_2": 6}, "_2": "e"}}"""
      ).map(_.pair),
      Right((1, "a"))
    )
  }

  test("a record from a model with the field names of a tuple stays an object in the JSON text") {
    def json(text: String) = PythonText.json(record(text))
    assertEquals(json("""{"_1": "x"}"""), """{"_1": "x"}""")
    assertEquals(json("""{"_1": 1, "_2": 2}"""), """{"_1": 1, "_2": 2}""")
    assertEquals(json("""{"_2": 2, "_1": 1}"""), """{"_2": 2, "_1": 1}""")
    assertEquals(json("{}"), "{}")
    assertEquals(PythonText.json(record("""{"_1": 1}"""), indent = Some(2)), "{\n  \"_1\": 1\n}")
    assertEquals(PythonText.jsonAscii(record("""{"_1": 1}""")), """{"_1": 1}""")
  }

  test("a case class with the field names of a tuple is an object in its encode, its schema, and its JSON text") {
    val value = TvfTupleNames(1, "a")
    assertEquals(PythonText.json(shapeOf[TvfTupleNames].encode(value)), """{"_1": 1, "_2": "a"}""")
    assertEquals(
      PythonText.json(shapeOf[TvfTupleNamesBox].encode(TvfTupleNamesBox(value))),
      """{"names": {"_1": 1, "_2": "a"}}"""
    )
    val schema = PythonText.json(schemaOf[TvfTupleNames])
    assert(schema.contains(""""properties": {"_1": {"""), schema)
    assertTextRoundTrip(value)
    assertTextRoundTrip(TvfTupleNamesBox(value))
  }

  // ── A value with a text form ──────────────────────────────────────────────────────────────────────────────────

  test("a time value and a UUID have a wire type with their name, so a bare text reaches the decode") {
    assertEquals(specOf[TvfTimes]("instant").typeRef, TypeRef("Instant"))
    assertEquals(specOf[TvfTimes]("uuid").typeRef, TypeRef("UUID"))
    assertEquals(specOf[TvfTimes]("date").typeRef, TypeRef("LocalDate"))
    assertEquals(specOf[TvfTimes]("maybe").typeRef, TypeRef("Instant"))
    assertEquals(specOf[TvfTimes]("many").typeRef, TypeRef.list)
    assertEquals(specOf[TvfTimes]("byName").typeRef, TypeRef.json)
  }

  test("a time value and a UUID decode from their text, in each position") {
    assertEquals(
      decoded[TvfTimes](
        """{"instant": "2024-01-02T03:04:05Z", "uuid": "123e4567-e89b-12d3-a456-426614174000", "date": "2024-01-02",
          |"dateTime": "2024-01-02T03:04:05", "time": "03:04:05", "offset": "2024-01-02T03:04:05+01:00",
          |"offsetT": "03:04:05+01:00", "zoned": "2024-01-02T03:04:05+01:00[Europe/Paris]", "duration": "PT1H30M",
          |"period": "P1Y2M3D", "year": "2024", "yearMonth": "2024-01", "monthDay": "--12-25", "day": "MONDAY",
          |"month": "MARCH", "zone": "Europe/Paris", "zoneOff": "+01:00", "currency": "USD",
          |"maybe": "2024-01-02T03:04:05Z", "many": ["2024-02-03T04:05:06Z"],
          |"byName": {"a": "123e4567-e89b-12d3-a456-426614174000"}}""".stripMargin
      ),
      Right(times)
    )
  }

  test("a text that is not a value of the type is an error that names the field, with no class name") {
    val json = PythonText.json(shapeOf[TvfTimes].encode(times))
    val bad  = json.replace("2024-01-02T03:04:05Z\", \"uuid", "soon\", \"uuid")
    decoded[TvfTimes](bad) match
      case Left(error) =>
        assert(error.message.contains(".instant"), error.message)
        assert(!error.message.contains("zio.blocks"), error.message)
      case Right(value) => fail(s"the decode accepted $value")
  }

  test("a whole number and an exact number have the wire types of a number") {
    assertEquals(specOf[TvfNumbers]("big").typeRef, TypeRef.int)
    assertEquals(specOf[TvfNumbers]("exact").typeRef, TypeRef("BigDecimal"))
    assertEquals(
      decoded[TvfNumbers]("""{"big": 123456789012345678901, "exact": 1.5}"""),
      Right(TvfNumbers(BigInt("123456789012345678901"), BigDecimal("1.5")))
    )
  }

  // ── A transform ───────────────────────────────────────────────────────────────────────────────────────────────

  test("a transform has the wire type of the value behind it") {
    assertEquals(specOf[TvfWrapped]("item").typeRef, TypeRef.json)
    assertEquals(specOf[TvfWrapped]("user").typeRef, TypeRef.string)
    assertEquals(specOf[TvfWrapped]("tags").typeRef, TypeRef.list)
  }

  test("a transform of a record decodes an Option field that is absent, null, or a value") {
    def wrapped(note: String) = decoded[TvfWrapped](s"""{"item": $note, "user": "u", "tags": ["t"]}""")
    val expected              = TvfWrapped(TvfBoxedRecord(TvfInner(1, None)), TvfUserId("u"), TvfTags(List("t")))
    assertEquals(wrapped("""{"a": 1}"""), Right(expected))
    assertEquals(wrapped("""{"a": 1, "note": null}"""), Right(expected))
    assertEquals(
      wrapped("""{"a": "1", "note": "n"}"""),
      Right(expected.copy(item = TvfBoxedRecord(TvfInner(1, Some("n")))))
    )
  }

  test("a transform of a record at the top has the fields of that record") {
    assertEquals(shapeOf[TvfBoxedRecord].fieldSpecs.map(_.name), Vector("a", "note"))
    assertEquals(decoded[TvfBoxedRecord]("""{"a": 1}"""), Right(TvfBoxedRecord(TvfInner(1, None))))
  }

  test("a transform of each form decodes in the form of the value behind it") {
    val json =
      """{"stamp": "2024-01-02T03:04:05Z", "pair": [1, "a"], "shape": {"Circle": {"radius": 1}}, "kind": "beta",
        |"maybe": "3", "twice": "2024-01-02T03:04:05Z", "list": ["2024-01-02T03:04:05Z"]}""".stripMargin
    val instant  = Instant.parse("2024-01-02T03:04:05Z")
    val expected = TvfBehind(
      TvfStamp(instant),
      TvfPairBox((1, "a")),
      TvfShapeBox(TvfShape.Circle(1.0)),
      TvfKindBox(TvfKind.beta),
      TvfMaybeBox(Some(3)),
      TvfStampBox(TvfStamp(instant)),
      List(TvfStamp(instant))
    )
    assertEquals(decoded[TvfBehind](json), Right(expected))
    assertTextRoundTrip(expected)
  }

  test("a transform has the wire type, the allowed values, and the optional flag of what it wraps") {
    assertEquals(specOf[TvfBehind]("stamp").typeRef, TypeRef("Instant"))
    assertEquals(specOf[TvfBehind]("pair").typeRef, TypeRef.list)
    assertEquals(specOf[TvfBehind]("shape").typeRef, TypeRef.union)
    assertEquals(specOf[TvfBehind]("kind").typeRef, TypeRef.string)
    assertEquals(specOf[TvfBehind]("kind").enumValues, Vector("alpha", "beta"))
    assertEquals(specOf[TvfBehind]("maybe").typeRef, TypeRef.int)
    assert(specOf[TvfBehind]("maybe").optional)
    assertEquals(specOf[TvfBehind]("twice").typeRef, TypeRef("Instant"))
  }

  test("a decision type behind a transform is a nested decision field, and a transform at the top has the law") {
    assertEquals(shapeOf[TvfNoulsBox].nestedDecisionFields, Vector("nouls"))
    assertTextRoundTrip(TvfBoxedRecord(TvfInner(1, Some("n"))))
    assertTextRoundTrip(TvfBoxedRecord(TvfInner(1, None)))
  }

  test("an Option behind a transform takes a null, a null text, and an absent field") {
    def maybe(field: String) =
      decoded[TvfBehind](
        s"""{"stamp": "2024-01-02T03:04:05Z", "pair": [1, "a"], "shape": "Dot", "kind": "beta", $field
         |"twice": "2024-01-02T03:04:05Z", "list": []}""".stripMargin
      ).map(_.maybe)
    assertEquals(maybe(""""maybe": null,"""), Right(TvfMaybeBox(None)))
    assertEquals(maybe(""""maybe": "n/a","""), Right(TvfMaybeBox(None)))
    assertEquals(maybe(""), Right(TvfMaybeBox(None)))
  }

  // ── Each form in each position ────────────────────────────────────────────────────────────────────────────────

  private def deep: TvfDeep =
    val instant = Instant.parse("2024-01-02T03:04:05Z")
    val id      = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    TvfDeep(
      inCase = TvfMix.Stamped(instant, (1, "a"), TvfShape.Dot, Some(id), TvfKind.beta),
      inTuple = (instant, TvfShape.Circle(1.0), (2, 3), TvfKind.alpha),
      many = List((instant, TvfShape.Square(1.0, None))),
      byName = Map("k" -> (4, "b")),
      keyed = Map(id -> instant),
      option = Some(List(TvfShape.Dot)),
      letter = 'x'
    )

  test("each form decodes in a case, in a tuple, in a list, as a key and a value of a map, and in an Option") {
    assertEquals(
      decoded[TvfDeep](
        """{"inCase": {"Stamped": {"at": "2024-01-02T03:04:05Z", "pair": [1, "a"], "shape": "Dot",
          |"maybe": "123e4567-e89b-12d3-a456-426614174000", "kind": "beta"}},
          |"inTuple": ["2024-01-02T03:04:05Z", {"Circle": {"radius": 1}}, [2, 3], "alpha"],
          |"many": [["2024-01-02T03:04:05Z", {"Square": {"side": 1}}]],
          |"byName": {"k": [4, "b"]},
          |"keyed": {"123e4567-e89b-12d3-a456-426614174000": "2024-01-02T03:04:05Z"},
          |"option": ["Dot"],
          |"letter": "x"}""".stripMargin
      ),
      Right(deep)
    )
    assertTextRoundTrip(deep)
  }

  test("a text that names no case gives the error of an unknown case") {
    decoded[TvfLabels]("""{"color": "Green", "kind": "beta"}""") match
      case Left(error)  => assert(error.message.contains("Green"), error.message)
      case Right(value) => fail(s"the decode accepted $value")
  }

  test("a case name of the sealed trait wins over a leaf name of a nested sealed trait") {
    assertEquals(decoded[TvfWinsBox]("""{"wins": {"Same": {"top": 1}}}"""), Right(TvfWinsBox(TvfWins.Same(1))))
    assertEquals(
      decoded[TvfWinsBox]("""{"wins": {"Inner": {"Same": {"deep": 2}}}}"""),
      Right(TvfWinsBox(TvfWins.Inner.Same(2)))
    )
  }

  test("a case of a sealed trait in an Option field stays the element of the Option") {
    val raw = DynamicValues.recordFromEntries(Vector(
      "shape"  -> summon[Schema[TvfShape]].toDynamicValue(TvfShape.Dot),
      "shapes" -> DynamicValues.sequence(Vector.empty),
      "maybe"  -> summon[Schema[TvfShape]].toDynamicValue(TvfShape.Circle(1.0)),
      "byName" -> DynamicValue.Record.empty
    ))
    assertEquals(shapeOf[TvfShapes].decode(raw).map(_.maybe), Right(Some(TvfShape.Circle(1.0))))
  }

  test("an absent field takes the default value of each form") {
    assertEquals(decoded[TvfDefaults]("{}"), Right(TvfDefaults()))
    assertEquals(specOf[TvfDefaults]("at").defaultValue.map(PythonText.str), Some("2024-01-02T03:04:05Z"))
    assertEquals(specOf[TvfDefaults]("pair").defaultValue.map(PythonText.str), Some("""[1, "a"]"""))
    assertEquals(specOf[TvfDefaults]("color").defaultValue.map(PythonText.str), Some("Red"))
  }

  test("a text with more than one character is no Char") {
    val json = PythonText.json(shapeOf[TvfDeep].encode(deep))
    assert(decoded[TvfDeep](json.replace(""""letter": "x"""", """"letter": "xy"""")).isLeft)
  }

  // ── The JSON text ─────────────────────────────────────────────────────────────────────────────────────────────

  test("the JSON text shows the path of a nested sealed trait with fields, and the leaf name of a nested enum") {
    assertEquals(
      PythonText.json(shapeOf[TvfThingBox].encode(TvfThingBox(TvfThing.Dog("d")))),
      """{"thing": {"Animal": {"Dog": {"name": "d"}}}}"""
    )
    assertEquals(
      PythonText.json(shapeOf[TvfLabels].encode(TvfLabels(TvfColor.Orange, TvfKind.alpha))),
      """{"color": "Orange", "kind": "alpha"}"""
    )
  }

  test("the text of a primitive with a text form and of a large number is its value") {
    val encoded            = shapeOf[TvfTimes].encode(times)
    def text(name: String) = PythonText.str(DynamicValues.recordGet(encoded, name).getOrElse(fail(s"no field $name")))
    assertEquals(text("instant"), "2024-01-02T03:04:05Z")
    assertEquals(text("uuid"), "123e4567-e89b-12d3-a456-426614174000")
    assertEquals(text("duration"), "PT1H30M")
    assertEquals(text("currency"), "USD")
    val numbers = shapeOf[TvfNumbers].encode(TvfNumbers(BigInt("123456789012345678901"), BigDecimal("1.5")))
    assertEquals(DynamicValues.renderText(numbers), """{"big": 123456789012345678901, "exact": 1.5}""")
  }

  // ── The wire form ─────────────────────────────────────────────────────────────────────────────────────────────

  private def field(value: DynamicValue.Record, name: String): DynamicValue =
    DynamicValues.recordGet(value, name).getOrElse(fail(s"no field $name"))

  private def text(value: String): DynamicValue = DynamicValues.string(value)

  private def parsed(json: String): DynamicValue =
    PythonText.parseJson(json).fold(error => fail(error.message), identity)

  test("the encode of a tuple is an array, in each position") {
    assertEquals(
      shapeOf[TvfPairs].encode(pairs),
      record("""{"pair": [1, "a"], "pairs": [[2, "b"], [3, "c"]], "maybe": [4, "d"], "nested": [[5, 6], "e"]}""")
    )
  }

  test("the encode of a sealed trait has the case name as a key, and a case with no fields is an empty object") {
    val encoded = shapeOf[TvfShapes].encode(TvfShapes(
      TvfShape.Dot,
      List(TvfShape.Square(2.0, None)),
      Some(TvfShape.Circle(1.5)),
      Map("a" -> TvfShape.Dot)
    ))
    assertEquals(field(encoded, "shape"), parsed("""{"Dot": {}}"""))
    assertEquals(field(encoded, "shapes"), parsed("""[{"Square": {"side": 2.0, "note": null}}]"""))
    assertEquals(field(encoded, "maybe"), parsed("""{"Circle": {"radius": 1.5}}"""))
    assertEquals(PythonText.json(field(encoded, "byName")), """{"a": {"Dot": {}}}""")
  }

  test("the encode of a nested sealed trait keeps the path of the cases, and the decode reads each form") {
    val value = TvfThingBox(TvfThing.Cat("a"))
    assertEquals(shapeOf[TvfThingBox].encode(value), record("""{"thing": {"Animal": {"Cat": {"name": "a"}}}}"""))
    assertEquals(decoded[TvfThingBox]("""{"thing": {"Animal": {"Cat": {"name": "a"}}}}"""), Right(value))
    assertEquals(decoded[TvfThingBox]("""{"thing": {"Cat": {"name": "a"}}}"""), Right(value))
  }

  test("the encode of an enum is the text of its leaf case") {
    assertEquals(
      shapeOf[TvfLabels].encode(TvfLabels(TvfColor.Red, TvfKind.beta)),
      record("""{"color": "Red", "kind": "beta"}""")
    )
  }

  test("the encode of a primitive with a text form is a text, and a number stays a number") {
    val encoded = shapeOf[TvfTimes].encode(times)
    assertEquals(field(encoded, "instant"), text("2024-01-02T03:04:05Z"))
    assertEquals(field(encoded, "uuid"), text("123e4567-e89b-12d3-a456-426614174000"))
    assertEquals(field(encoded, "date"), text("2024-01-02"))
    assertEquals(field(encoded, "day"), text("MONDAY"))
    assertEquals(field(encoded, "currency"), text("USD"))
    assertEquals(field(encoded, "maybe"), text("2024-01-02T03:04:05Z"))
    assertEquals(field(encoded, "many"), parsed("""["2024-02-03T04:05:06Z"]"""))
    assertEquals(PythonText.json(field(encoded, "byName")), """{"a": "123e4567-e89b-12d3-a456-426614174000"}""")
    val numbers = shapeOf[TvfNumbers].encode(TvfNumbers(BigInt(3), BigDecimal("1.5")))
    assertEquals(field(numbers, "big"), DynamicValue.Primitive(PrimitiveValue.BigInt(BigInt(3))))
    assertEquals(field(numbers, "exact"), DynamicValue.Primitive(PrimitiveValue.BigDecimal(BigDecimal("1.5"))))
  }

  test("the encode of an Option is its element or a null") {
    val encoded = shapeOf[TvfPairs].encode(pairs.copy(maybe = None))
    assertEquals(field(encoded, "maybe"), DynamicValue.Null)
    assertEquals(field(shapeOf[TvfPairs].encode(pairs), "maybe"), parsed("""[4, "d"]"""))
    assertEquals(field(shapeOf[TvfInner].encode(TvfInner(1, Some("n"))), "note"), text("n"))
  }

  test("the encode of a transform is the wire form of the value behind it") {
    assertEquals(
      shapeOf[TvfWrapped].encode(TvfWrapped(TvfBoxedRecord(TvfInner(1, None)), TvfUserId("u"), TvfTags(List("t")))),
      record("""{"item": {"a": 1, "note": null}, "user": "u", "tags": ["t"]}""")
    )
    assertEquals(
      shapeOf[TvfBoxedRecord].encode(TvfBoxedRecord(TvfInner(1, Some("n")))),
      record("""{"a": 1, "note": "n"}""")
    )
  }

  test("a tuple at the top stays the record of its fields, and each field is in wire form") {
    given Schema[(TvfKind, (Int, Int))] = Schema.derived
    val shape                           = shapeOf[(TvfKind, (Int, Int))]
    val encoded                         = shape.encode((TvfKind.beta, (1, 2)))
    assertEquals(encoded, record("""{"_1": "beta", "_2": [1, 2]}"""))
    assertEquals(shape.decode(encoded), Right((TvfKind.beta, (1, 2))))
  }

  test("a framework type stays in its typed form, in a field and in an Option") {
    val noul    = Noul(true, 0.5, None)
    val typed   = summon[Schema[Noul]].toDynamicValue(noul)
    val value   = TvfDecided(noul, Some(noul), Some("x"))
    val encoded = shapeOf[TvfDecided].encode(value)
    assertEquals(field(encoded, "noul"), typed)
    assertEquals(field(encoded, "maybe"), typed)
    assertEquals(field(encoded, "text"), text("x"))
    assertEquals(shapeOf[TvfDecided].decode(encoded), Right(value))
  }

  private def forms: TvfForms =
    TvfForms(
      nested = Some(Some(1)),
      inTuple = (None, "a"),
      shape = (TvfShape.Dot, 2),
      either = Left(3),
      mix = TvfMix.Stamped(Instant.parse("2024-01-02T03:04:05Z"), (4, "b"), TvfShape.Circle(1.5), None, TvfKind.alpha)
    )

  test("an Option in an Option keeps Some(None) through the encode") {
    val shape = shapeOf[TvfForms]
    Vector[Option[Option[Int]]](None, Some(None), Some(Some(1))).foreach { nested =>
      val value = forms.copy(nested = nested)
      assertEquals(shape.decode(shape.encode(value)), Right(value))
    }
  }

  test(
    "Some(None) survives only the direct path: its JSON text is a null, and an absent field with it as default is None"
  ) {
    val shape = shapeOf[TvfForms]
    val value = forms.copy(nested = Some(None))
    assertEquals(shape.decode(shape.encode(value)), Right(value))
    assertEquals(shape.decode(record(PythonText.json(shape.encode(value)))), Right(forms.copy(nested = None)))
    assertEquals(decoded[TvfNestedDefault]("{}"), Right(TvfNestedDefault(None)))
  }

  test("a text in an Option stays the text, also when it is a null text, in each position") {
    Vector("none", "None", "", "null", "N/A", "n/a").foreach { text =>
      assertTextRoundTrip(TvfNullTexts(
        Some(text),
        List(Some(text), None),
        Map("k" -> Some(text)),
        (Some(text), 1),
        Some(TvfUserId(text))
      ))
      assertTextRoundTrip(TvfInner(1, Some(text)))
    }
  }

  test("a case of an enum in an Option stays the case, when its name is a null text") {
    assertTextRoundTrip(TvfNamedNoneBox(Some(TvfNamedNone.None), List(Some(TvfNamedNone.None), None)))
    assertTextRoundTrip(TvfNamedNoneBox(None, List(Some(TvfNamedNone.Other))))
  }

  test("an Option of a type that does not take the null text keeps the lenient rule") {
    Vector("none", "None", "", "null", "N/A", "n/a").foreach { text =>
      assertEquals(
        decoded[TvfLenient](s"""{"n": "$text", "at": "$text", "shape": "$text", "kind": "$text"}"""),
        Right(TvfLenient(None, None, None, None))
      )
    }
  }

  test("a map key has its wire form, and the map is a record of text keys") {
    val id    = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    val value = TvfKeys(
      kind = Map(TvfKind.beta -> 1),
      color = Map(TvfColor.Red -> 2),
      number = Map(4 -> "a"),
      uuid = Map(id -> 5),
      text = Map("t" -> 6)
    )
    assertEquals(
      shapeOf[TvfKeys].encode(value),
      record(
        """{"kind": {"beta": 1}, "color": {"Red": 2}, "number": {"4": "a"},
          |"uuid": {"123e4567-e89b-12d3-a456-426614174000": 5}, "text": {"t": 6}}""".stripMargin
      )
    )
    assertTextRoundTrip(value)
    assertTextRoundTrip(value.copy(kind = Map.empty, color = Map(TvfColor.Blue -> 3, TvfColor.Orange -> 4)))
  }

  test("a tuple key stays a map, and the law holds") {
    val shape   = shapeOf[TvfPairKeys]
    val value   = TvfPairKeys(Map((1, 2) -> "p"))
    val encoded = shape.encode(value)
    assert(field(encoded, "pairs").isInstanceOf[DynamicValue.Map], encoded.toString)
    assertEquals(shape.decode(encoded), Right(value))
  }

  test("an Option in a tuple, a sealed trait in a tuple, a tuple in a case, and an Either have the wire form") {
    assertEquals(
      PythonText.json(shapeOf[TvfForms].encode(forms)),
      """{"nested": 1, "inTuple": [null, "a"], "shape": [{"Dot": {}}, 2], "either": {"Left": {"value": 3}}, """ +
        """"mix": {"Stamped": {"at": "2024-01-02T03:04:05Z", "pair": [4, "b"], "shape": {"Circle": {"radius": 1.5}}, """ +
        """"maybe": null, "kind": "alpha"}}}"""
    )
    assertTextRoundTrip(forms)
    assertTextRoundTrip(forms.copy(either = Right("r"), inTuple = (Some(5), "c"), nested = None))
  }

  test("each lift of a typed value gives the wire form") {
    given Schema[(Int, String)] = Schema.derived
    val color: TvfColor         = TvfColor.Red
    val shape: TvfShape         = TvfShape.Dot
    assertEquals(("c" := color)._2, text("Red"))
    assertEquals(("s" := shape)._2, parsed("""{"Dot": {}}"""))
    assertEquals(("p" := (1, "a"))._2, parsed("""[1, "a"]"""))
    assertEquals(("o" := Option("y"))._2, text("y"))
    assertEquals(("n" := Option.empty[String])._2, DynamicValue.Null)
    assertEquals(("t" := Instant.parse("2024-01-02T03:04:05Z"))._2, text("2024-01-02T03:04:05Z"))
    assertEquals(Example.empty.withRawValue("c", color).values, record("""{"c": "Red"}"""))
    assertEquals(RawPrediction(DynamicValue.Record.empty).withRawValue("c", color).values, record("""{"c": "Red"}"""))
  }

  test("the text of a lifted value is the text of its wire form") {
    val color: TvfColor = TvfColor.Red
    assertEquals(DynamicValues.renderText(("c" := color)._2), "Red")
    assertEquals(DynamicValues.renderText(("o" := Option("y"))._2), "y")
    assertEquals(RawPrediction(DynamicValues.record("c" := color, "o" := Option("y"))).asString("c"), Right("Red"))
    assertEquals(RawPrediction(DynamicValues.record("c" := color, "o" := Option("y"))).asString("o"), Right("y"))
  }

  // ── The law for each form ─────────────────────────────────────────────────────────────────────────────────────

  test("the JSON text of a sealed trait decodes to the value") {
    assertTextRoundTrip(shapes)
    assertTextRoundTrip(TvfThingBox(TvfThing.Dog("d")))
    assertTextRoundTrip(TvfLabels(TvfColor.Orange, TvfKind.alpha))
    assertTextRoundTrip(TvfTreeBox(TvfTree.Node(List(TvfTree.Leaf(1)))))
  }

  test("the JSON text of a tuple decodes to the value") {
    assertTextRoundTrip(pairs)
  }

  test("the JSON text of a time value and a UUID decodes to the value") {
    assertTextRoundTrip(times)
  }

  test("the JSON text of a transform and of an Either decodes to the value") {
    assertTextRoundTrip(TvfWrapped(TvfBoxedRecord(TvfInner(1, Some("n"))), TvfUserId("u"), TvfTags(List("t"))))
    assertTextRoundTrip(TvfEither(Left(1)))
    assertTextRoundTrip(TvfEither(Right("r")))
  }
