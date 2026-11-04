package dspy4s.signatures

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, FieldSpec, History, PythonText, ToolCall, TypeRef}
import dspy4s.core.data.Example
import dspy4s.core.decisions.{Choice, Noul}
import munit.FunSuite
import zio.blocks.maybe.Maybe
import zio.blocks.schema.{DynamicValue, Modifier, Schema}

import java.time.Instant

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class ApItem(@Modifier.rename("the_name") name: String, @Modifier.alias("n") size: Int, note: Option[String])
object ApItem:
  given Schema[ApItem] = Schema.derived

final case class ApBox(item: ApItem)
object ApBox:
  given Schema[ApBox] = Schema[ApItem].transform(ApBox(_), _.item)

final case class ApPositions(
    one                          : ApItem,
    many                         : List[ApItem],
    maybe                        : Option[ApItem],
    byKey                        : Map[String, ApItem],
    pair                         : (ApItem, Int),
    box                          : ApBox,
    @Modifier.rename("opts") more: Vector[Option[ApItem]]
)
object ApPositions:
  given Schema[ApPositions] = Schema.derived

@Modifier.discriminator("kind")
sealed trait ApKinded
object ApKinded:
  @Modifier.rename("circle")
  @Modifier.alias("ring")
  final case class Circle(@Modifier.rename("r") radius: Double, note: Option[String]) extends ApKinded
  case object Dot                                                                     extends ApKinded
  sealed trait Animal                                                                 extends ApKinded
  @Modifier.alias("kitty")
  final case class Cat(name: String) extends Animal
  case object Dog                    extends Animal
  given Schema[ApKinded] = Schema.derived

final case class ApKindedBox(
    item : ApKinded,
    items: List[ApKinded],
    maybe: Option[ApKinded],
    byKey: Map[String, ApKinded],
    pair : (ApKinded, Int)
)
object ApKindedBox:
  given Schema[ApKindedBox] = Schema.derived

@Modifier.discriminator("outer")
sealed trait ApTwoLevels
object ApTwoLevels:
  final case class Top(x: Int) extends ApTwoLevels
  @Modifier.discriminator("inner")
  sealed trait Inner            extends ApTwoLevels
  final case class Deep(y: Int) extends Inner
  given Schema[ApTwoLevels] = Schema.derived

final case class ApTwoLevelsBox(through: ApTwoLevels, direct: ApTwoLevels.Inner)
object ApTwoLevelsBox:
  given Schema[ApTwoLevelsBox] = Schema.derived

@Modifier.discriminator("kind")
sealed trait ApClash
object ApClash:
  final case class Named(kind: String)                             extends ApClash
  final case class Labeled(@Modifier.rename("kind") label: String) extends ApClash
  final case class Other(size: Int)                                extends ApClash
  case object Empty                                                extends ApClash
  given Schema[ApClash] = Schema.derived

final case class ApClashBox(clash: ApClash)
object ApClashBox:
  given Schema[ApClashBox] = Schema.derived

@Modifier.discriminator("kind")
sealed trait ApMixed
object ApMixed:
  final case class Plain(x: Int)     extends ApMixed
  final case class Boxed(value: Int) extends ApMixed
  object Boxed:
    given Schema[Boxed] = Schema[Int].transform(Boxed(_), _.value)
  given Schema[ApMixed] = Schema.derived

final case class ApMixedBox(mixed: ApMixed)
object ApMixedBox:
  given Schema[ApMixedBox] = Schema.derived

@Modifier.discriminator("kind")
sealed trait ApLabel
object ApLabel:
  case object One extends ApLabel
  case object Two extends ApLabel
  given Schema[ApLabel] = Schema.derived

final case class ApLabelBox(label: ApLabel)
object ApLabelBox:
  given Schema[ApLabelBox] = Schema.derived

enum ApPlainEnum:
  @Modifier.rename("first")
  case A
  case B
object ApPlainEnum:
  given Schema[ApPlainEnum] = Schema.derived

final case class ApPlainEnumBox(value: ApPlainEnum)
object ApPlainEnumBox:
  given Schema[ApPlainEnumBox] = Schema.derived

@Modifier.discriminator("kind")
sealed trait ApTree
object ApTree:
  final case class Leaf(value: Int)                                      extends ApTree
  final case class Node(@Modifier.rename("kids") children: List[ApTree]) extends ApTree
  given Schema[ApTree] = Schema.derived

final case class ApTreeBox(tree: ApTree)
object ApTreeBox:
  given Schema[ApTreeBox] = Schema.derived

@Modifier.caseNaming("snake_case")
sealed trait ApOuter
object ApOuter:
  @Modifier.fieldNaming("snake_case")
  final case class BigBox(boxSize: Int) extends ApOuter
  @Modifier.caseNaming("kebab-case")
  sealed trait InnerKind                    extends ApOuter
  final case class DeepCat(catName: String) extends InnerKind
  given Schema[ApOuter] = Schema.derived

final case class ApOuterBox(outer: ApOuter, inner: ApOuter.InnerKind)
object ApOuterBox:
  given Schema[ApOuterBox] = Schema.derived

final case class ApTransientNoDefault(
    name                            : String,
    @Modifier.transient() note      : Option[String],
    @Modifier.transient() tags      : List[String],
    @Modifier.transient() scores    : Map[String, Int],
    @Modifier.encodeTransient() hint: Option[String]
)
object ApTransientNoDefault:
  given Schema[ApTransientNoDefault] = Schema.derived

@Modifier.noExtraFields()
final case class ApClosed(a: Int)
object ApClosed:
  given Schema[ApClosed] = Schema.derived

sealed trait ApFake
object ApFake:
  case object None                  extends ApFake
  final case class Some(value: Int) extends ApFake
  given Schema[ApFake] = Schema.derived

sealed trait ApRenamedFake
object ApRenamedFake:
  @Modifier.rename("None")
  case object Empty extends ApRenamedFake
  @Modifier.rename("Some")
  final case class Full(value: Int) extends ApRenamedFake
  given Schema[ApRenamedFake] = Schema.derived

final case class ApFakes(fake: ApFake, maybe: Option[ApFake], renamed: ApRenamedFake, other: Option[ApRenamedFake])
object ApFakes:
  given Schema[ApFakes] = Schema.derived

final case class ApMaybes(
    int                              : Maybe[Int],
    long                             : Maybe[Long],
    float                            : Maybe[Float],
    double                           : Maybe[Double],
    bool                             : Maybe[Boolean],
    byte                             : Maybe[Byte],
    char                             : Maybe[Char],
    short                            : Maybe[Short],
    item                             : Maybe[ApItem],
    many                             : List[Maybe[String]],
    pair                             : (Maybe[Int], String),
    kept                             : Maybe[String] = Maybe.present("d"),
    @Modifier.rename("renamed") other: Maybe[Int]    = Maybe.absent
)
object ApMaybes:
  given Schema[ApMaybes] = Schema.derived

final case class ApPlainMaybes(int: Maybe[Int], double: Maybe[Double], at: Maybe[Instant], text: Maybe[String])

final case class ApNestedMaybes(inOption: Option[Maybe[String]], inMaybe: Maybe[Option[String]])
object ApNestedMaybes:
  given Schema[ApNestedMaybes] = Schema.derived

final case class ApSwap(@Modifier.rename("b") a: Int, @Modifier.rename("a") b: String)
object ApSwap:
  given Schema[ApSwap] = Schema.derived

final case class ApChain(@Modifier.rename("b") a: Int, @Modifier.rename("c") b: Int)
object ApChain:
  given Schema[ApChain] = Schema.derived

final case class ApChainDefault(@Modifier.rename("b") a: Int, @Modifier.rename("c") b: Int = 2)
object ApChainDefault:
  given Schema[ApChainDefault] = Schema.derived

final case class ApChainBox(inner: ApChainDefault, many: List[ApChainDefault])
object ApChainBox:
  given Schema[ApChainBox] = Schema.derived

final case class ApAliasKey(@Modifier.alias("b") a: Int = 0, b: Int = 0)
object ApAliasKey:
  given Schema[ApAliasKey] = Schema.derived

final case class ApAliasCode(@Modifier.alias("y") x: Int = 0, @Modifier.rename("z") y: Int = 0)
object ApAliasCode:
  given Schema[ApAliasCode] = Schema.derived

final case class ApSharedAlias(@Modifier.alias("k") a: Int = 0, @Modifier.alias("k") b: Int = 0)
object ApSharedAlias:
  given Schema[ApSharedAlias] = Schema.derived

final case class ApTransientKey(@Modifier.transient() a: Int = 0, @Modifier.rename("a") b: Int)
object ApTransientKey:
  given Schema[ApTransientKey] = Schema.derived

sealed trait ApCaseKeys
object ApCaseKeys:
  @Modifier.rename("first")
  final case class Cat(a: Int) extends ApCaseKeys
  sealed trait Inner           extends ApCaseKeys
  object Inner:
    final case class Cat(b: Int) extends Inner
  @Modifier.rename("Dog")
  final case class Hound(c: Int) extends ApCaseKeys
  @Modifier.rename("other")
  final case class Dog(d: Int) extends ApCaseKeys
  @Modifier.alias("Fox")
  final case class Wolf(e: Int) extends ApCaseKeys
  final case class Fox(f: Int)  extends ApCaseKeys
  given Schema[ApCaseKeys] = Schema.derived

final case class ApCaseKeysBox(value: ApCaseKeys)
object ApCaseKeysBox:
  given Schema[ApCaseKeysBox] = Schema.derived

@Modifier.discriminator("kind")
sealed trait ApFlatKeys
object ApFlatKeys:
  @Modifier.rename("first")
  final case class Cat(a: Int) extends ApFlatKeys
  sealed trait Inner           extends ApFlatKeys
  object Inner:
    final case class Cat(b: Int) extends Inner
  given Schema[ApFlatKeys] = Schema.derived

final case class ApFlatKeysBox(value: ApFlatKeys)
object ApFlatKeysBox:
  given Schema[ApFlatKeysBox] = Schema.derived

sealed trait ApLabelKeys
object ApLabelKeys:
  @Modifier.rename("first")
  case object Red   extends ApLabelKeys
  sealed trait Warm extends ApLabelKeys
  object Warm:
    case object Red extends Warm
  given Schema[ApLabelKeys] = Schema.derived

final case class ApLabelKeysBox(value: ApLabelKeys)
object ApLabelKeysBox:
  given Schema[ApLabelKeysBox] = Schema.derived

enum ApPresence:
  case Present, Absent
object ApPresence:
  given Schema[ApPresence] = Schema.derived

final case class ApFramework(
    @Modifier.rename("past") history: History,
    @Modifier.rename("tools") calls : List[ToolCall],
    @Modifier.rename("flag") noul   : Noul,
    @Modifier.rename("flags") nouls : List[Noul],
    @Modifier.alias("other") text   : String
)
object ApFramework:
  given Schema[ApFramework] = Schema.derived

final case class ApDefaults(
    @Modifier.rename("k") kinded: ApKinded = ApKinded.Dot,
    @Modifier.rename("i") item  : ApItem   = ApItem("d", 1, None),
    @Modifier.rename("l") tone  : ApLabel  = ApLabel.Two
)
object ApDefaults:
  given Schema[ApDefaults] = Schema.derived

@Modifier.fieldNaming("bogus")
final case class ApBogus(a: Int)
object ApBogus:
  given Schema[ApBogus] = Schema.derived

@Modifier.fieldNaming("kebab-case")
final case class ApKebab(firstName: String)
object ApKebab:
  given Schema[ApKebab] = Schema.derived

final case class ApQuestion(question: String)
object ApQuestion:
  given Schema[ApQuestion] = Schema.derived

final case class ApNamed(@Modifier.rename("full_name") name: String, @Modifier.transient() cached: Int = 0)
object ApNamed:
  given Schema[ApNamed] = Schema.derived

@Modifier.fieldNaming("snake_case")
final case class ApSnake(firstName: String)
object ApSnake:
  given Schema[ApSnake] = Schema.derived

trait ApSpec extends Spec:
  def question: InputField[String]
  def named: OutputField[ApNamed]

object ApMethods:
  def sameCodeName(name      : String): ApNamed = ApNamed(name)
  def sameWireName(full_name : String): ApNamed = ApNamed(full_name)
  def sameStrategy(first_name: String): ApSnake = ApSnake(first_name)
  def transientName(cached   : String): ApNamed = ApNamed(cached)

/** The annotations of zio-blocks in each position, and the rules for a form that the codec does not make plain. */
class AnnotatedPositionsSuite extends FunSuite:

  private def shapeOf[A](using Schema[A]) = ZioSchemaCodec.derivedFromZioSchema[A]

  private def record(json: String): DynamicValue.Record =
    PythonText.parseJson(json) match
      case Right(value: DynamicValue.Record) => value
      case other                             => fail(s"expected a JSON object, got $other")

  private def decoded[A](json: String)(using Schema[A]): Either[DspyError, A] = shapeOf[A].decode(record(json))

  private def textOf[A](value: A)(using Schema[A]): String = PythonText.json(shapeOf[A].encode(value))

  private def schemaText[A](using Schema[A]): String =
    PythonText.json(shapeOf[A].jsonSchema.getOrElse(fail("no schema")))

  private def specOf[A](name: String)(using Schema[A]): FieldSpec =
    shapeOf[A].fieldSpecs.find(_.name == name).getOrElse(fail(s"no field $name"))

  private def assertTextRoundTrip[A](value: A)(using Schema[A]): Unit =
    val shape = shapeOf[A]
    assertEquals(shape.decode(shape.encode(value)), Right(value))
    assertEquals(shape.decode(record(PythonText.json(shape.encode(value)))), Right(value))

  // ── A renamed field in each position ──────────────────────────────────────────────────────────────────────────

  private def item(name: String, size: Int): ApItem = ApItem(name, size, None)

  private def positions: ApPositions =
    ApPositions(
      one = item("a", 1),
      many = List(item("b", 2)),
      maybe = Some(item("c", 3)),
      byKey = Map("k" -> item("d", 4)),
      pair = (item("e", 5), 6),
      box = ApBox(item("f", 7)),
      more = Vector(Some(item("g", 8)), None)
    )

  test(
    "a renamed field has its wire name in a nested record, a list, an Option, a map value, a tuple, and a transform"
  ) {
    def wire(name: String, size: Int) = s"""{"the_name": "$name", "size": $size, "note": null}"""
    assertEquals(
      textOf(positions),
      s"""{"one": ${wire("a", 1)}, "many": [${wire("b", 2)}], "maybe": ${wire("c", 3)}, """ +
        s""""byKey": {"k": ${wire("d", 4)}}, "pair": [${wire("e", 5)}, 6], "box": ${wire("f", 7)}, """ +
        s""""opts": [${wire("g", 8)}, null]}"""
    )
    assertTextRoundTrip(positions)
  }

  test("a renamed field decodes from each of its names, in each position") {
    def forms(field: (String, Int) => String) =
      decoded[ApPositions](
        s"""{"one": ${field("a", 1)}, "many": [${field("b", 2)}], "maybe": ${field("c", 3)},
           |"byKey": {"k": ${field("d", 4)}}, "pair": [${field("e", 5)}, 6], "box": ${field("f", 7)},
           |"opts": [${field("g", 8)}, null]}""".stripMargin
      )
    assertEquals(forms((name, size) => s"""{"the_name": "$name", "size": "$size"}"""), Right(positions))
    assertEquals(forms((name, size) => s"""{"the_name": "$name", "n": $size}"""), Right(positions))
    assertEquals(forms((name, size) => s"""{"name": "$name", "size": $size}"""), Right(positions))
  }

  test("when a record has two names of one field, the wire name wins, and then the alias") {
    assertEquals(decoded[ApItem]("""{"name": "b", "the_name": "a", "size": 1}"""), Right(ApItem("a", 1, None)))
    assertEquals(decoded[ApItem]("""{"the_name": "a", "n": 2, "size": 1}"""), Right(ApItem("a", 1, None)))
    assertEquals(decoded[ApItem]("""{"the_name": "a", "n": 2}"""), Right(ApItem("a", 2, None)))
  }

  test("a wire name wins over a name in the code of another field") {
    assertEquals(shapeOf[ApSwap].fieldSpecs.map(_.name), Vector("b", "a"))
    assertEquals(decoded[ApSwap]("""{"a": "x", "b": 1}"""), Right(ApSwap(1, "x")))
    assertEquals(textOf(ApSwap(1, "x")), """{"b": 1, "a": "x"}""")
    assertTextRoundTrip(ApSwap(1, "x"))
  }

  private def assertMissing[A](json: String, name: String)(using Schema[A]): Unit =
    decoded[A](json) match
      case Left(error)  => assert(error.message.contains(s"Missing required fields: $name"), error.message)
      case Right(value) => fail(s"the decode accepted $value")

  test("a name in the code does not read a key that is the wire name of another field") {
    assertMissing[ApSwap]("""{"b": 1}""", "a")
    assertMissing[ApSwap]("""{"a": "x"}""", "b")
    assertMissing[ApChain]("""{"b": 5}""", "c")
    assertEquals(decoded[ApChain]("""{"b": 5, "c": 6}"""), Right(ApChain(5, 6)))
    assertEquals(decoded[ApChainDefault]("""{"b": 5}"""), Right(ApChainDefault(5, 2)))
    assertEquals(decoded[ApChainDefault]("""{"a": 5}"""), Right(ApChainDefault(5, 2)))
  }

  test("an alias does not read a key that is the wire name of another field") {
    assertEquals(decoded[ApAliasKey]("""{"b": 5}"""), Right(ApAliasKey(0, 5)))
    assertEquals(decoded[ApAliasKey]("""{"a": 5}"""), Right(ApAliasKey(5, 0)))
  }

  test("a name in the code does not read a key that is an alias of another field") {
    assertEquals(decoded[ApAliasCode]("""{"y": 5}"""), Right(ApAliasCode(5, 0)))
    assertEquals(decoded[ApAliasCode]("""{"z": 5}"""), Right(ApAliasCode(0, 5)))
  }

  test("a key belongs to one field also when two fields have one alias, and a transient field owns no key") {
    assertEquals(decoded[ApSharedAlias]("""{"k": 5}"""), Right(ApSharedAlias(5, 0)))
    assertEquals(decoded[ApTransientKey]("""{"a": 5}"""), Right(ApTransientKey(0, 5)))
  }

  test("a key belongs to one field in a nested record, in a list, and in the canonical shape of an input") {
    assertEquals(
      decoded[ApChainBox]("""{"inner": {"b": 5}, "many": [{"b": 6}]}"""),
      Right(ApChainBox(ApChainDefault(5, 2), List(ApChainDefault(6, 2))))
    )
    assertEquals(Shape.canonicalDerived[ApChainDefault].decode(record("""{"b": 5}""")), Right(ApChainDefault(5, 2)))
  }

  test("a key belongs to one case, also over a nested sealed trait") {
    def value(json: String) = decoded[ApCaseKeysBox](s"""{"value": $json}""").map(_.value)
    assertEquals(value("""{"first": {"a": 1}}"""), Right(ApCaseKeys.Cat(1)))
    assertEquals(value("""{"Cat": {"b": 1}}"""), Right(ApCaseKeys.Inner.Cat(1)))
    assertEquals(value("""{"Inner": {"Cat": {"b": 1}}}"""), Right(ApCaseKeys.Inner.Cat(1)))
    assertEquals(value("""{"Dog": {"c": 1}}"""), Right(ApCaseKeys.Hound(1)))
    assertEquals(value("""{"other": {"d": 1}}"""), Right(ApCaseKeys.Dog(1)))
    assertEquals(value("""{"Fox": {"f": 1}}"""), Right(ApCaseKeys.Fox(1)))
    assertEquals(value("""{"Wolf": {"e": 1}}"""), Right(ApCaseKeys.Wolf(1)))
    assertTextRoundTrip(ApCaseKeysBox(ApCaseKeys.Inner.Cat(1)))
    assertTextRoundTrip(ApCaseKeysBox(ApCaseKeys.Cat(1)))
  }

  test("a key belongs to one case in the flat form of a discriminator, and in the text of an enum") {
    assertEquals(textOf(ApFlatKeysBox(ApFlatKeys.Inner.Cat(1))), """{"value": {"kind": "Cat", "b": 1}}""")
    assertTextRoundTrip(ApFlatKeysBox(ApFlatKeys.Inner.Cat(1)))
    assertTextRoundTrip(ApFlatKeysBox(ApFlatKeys.Cat(1)))
    assertEquals(textOf(ApLabelKeysBox(ApLabelKeys.Warm.Red)), """{"value": "Red"}""")
    assertTextRoundTrip(ApLabelKeysBox(ApLabelKeys.Warm.Red))
    assertTextRoundTrip(ApLabelKeysBox(ApLabelKeys.Red))
  }

  test("a user enum with the cases Present and Absent keeps each name in a framework type") {
    def shown(value: ApPresence) =
      PythonText.json(Example("team" := Choice(value, 0.7)).values)
    assert(shown(ApPresence.Absent).contains(""""value": "Absent""""), shown(ApPresence.Absent))
    assert(shown(ApPresence.Present).contains(""""value": "Present""""), shown(ApPresence.Present))
  }

  test("a Maybe with no value in a Maybe or an Option is a null in the JSON text") {
    assertEquals(
      textOf(ApNestedMaybes(None, Maybe.absent)),
      """{"inOption": null, "inMaybe": null}"""
    )
    assertEquals(
      textOf(ApNestedMaybes(Some(Maybe.absent), Maybe.present(None))),
      """{"inOption": null, "inMaybe": null}"""
    )
  }

  test("a default value has the wire form in its field spec, and an absent field decodes to it") {
    val kinded = specOf[ApDefaults]("k").defaultValue.getOrElse(fail("no default"))
    assertEquals(PythonText.json(kinded), """{"kind": "Dot"}""")
    val inner = specOf[ApDefaults]("i").defaultValue.getOrElse(fail("no default"))
    assertEquals(PythonText.json(inner), """{"the_name": "d", "size": 1, "note": null}""")
    assertEquals(decoded[ApDefaults]("{}"), Right(ApDefaults()))
  }

  // ── A discriminator in each position ──────────────────────────────────────────────────────────────────────────

  private def kindedBox: ApKindedBox =
    ApKindedBox(
      item = ApKinded.Circle(1.5, Some("n")),
      items = List(ApKinded.Dot, ApKinded.Cat("c"), ApKinded.Dog),
      maybe = Some(ApKinded.Cat("m")),
      byKey = Map("k" -> ApKinded.Circle(2.0, None)),
      pair = (ApKinded.Dot, 1)
    )

  test("a sealed trait with a discriminator has the wire type union, and no allowed values") {
    assertEquals(specOf[ApKindedBox]("item").typeRef, TypeRef.union)
    assertEquals(specOf[ApKindedBox]("item").enumValues, Vector.empty)
    assertEquals(specOf[ApKindedBox]("maybe").typeRef, TypeRef.union)
    assert(specOf[ApKindedBox]("maybe").optional)
    assertEquals(specOf[ApKindedBox]("items").typeRef, TypeRef.list)
  }

  test("a discriminator is flat in a list, an Option, a map value, and a tuple, with the flat name of a nested leaf") {
    assertEquals(
      textOf(kindedBox),
      """{"item": {"kind": "circle", "r": 1.5, "note": "n"}, """ +
        """"items": [{"kind": "Dot"}, {"kind": "Cat", "name": "c"}, {"kind": "Dog"}], """ +
        """"maybe": {"kind": "Cat", "name": "m"}, "byKey": {"k": {"kind": "circle", "r": 2.0, "note": null}}, """ +
        """"pair": [{"kind": "Dot"}, 1]}"""
    )
    assertTextRoundTrip(kindedBox)
  }

  test("a discriminator names a case by its wire name, its alias, or its name in the code") {
    def item(form: String) =
      decoded[ApKindedBox](
        s"""{"item": $form, "items": [], "maybe": null, "byKey": {}, "pair": [$form, 1]}"""
      ).map(_.item)
    val circle = Right(ApKinded.Circle(1.0, None))
    assertEquals(item("""{"kind": "circle", "r": 1}"""), circle)
    assertEquals(item("""{"kind": "ring", "r": "1"}"""), circle)
    assertEquals(item("""{"kind": "Circle", "radius": 1}"""), circle)
    assertEquals(item("""{"kind": "kitty", "name": "c"}"""), Right(ApKinded.Cat("c")))
    assertEquals(item("""{"Animal": {"Cat": {"name": "c"}}}"""), Right(ApKinded.Cat("c")))
    assertEquals(item("""{"Cat": {"name": "c"}}"""), Right(ApKinded.Cat("c")))
    assertEquals(item("""{"kitty": {"name": "c"}}"""), Right(ApKinded.Cat("c")))
    assertEquals(item(""""Dog""""), Right(ApKinded.Dog))
  }

  test("the schema of a discriminator has a const for each leaf case, and no alias") {
    val text = schemaText[ApKindedBox]
    assert(text.contains(""""kind": {"const": "circle"}"""), text)
    assert(text.contains(""""kind": {"const": "Dot"}"""), text)
    assert(text.contains(""""kind": {"const": "Cat"}"""), text)
    assert(!text.contains("\"ring\""), text)
    assert(!text.contains("\"kitty\""), text)
    assert(!text.contains("\"Animal\""), text)
  }

  test(
    "the discriminator of the outer sealed trait wins, and a nested sealed trait in a field of its own uses its own"
  ) {
    val box = ApTwoLevelsBox(ApTwoLevels.Deep(1), ApTwoLevels.Deep(2))
    assertEquals(textOf(box), """{"through": {"outer": "Deep", "y": 1}, "direct": {"inner": "Deep", "y": 2}}""")
    assertTextRoundTrip(box)
    val text = schemaText[ApTwoLevelsBox]
    assert(text.contains(""""outer": {"const": "Deep"}"""), text)
    assert(text.contains(""""inner": {"const": "Deep"}"""), text)
  }

  test("a case with a field with the name of the discriminator keeps a case name as a key, in each form") {
    val values = Vector[ApClash](ApClash.Named("x"), ApClash.Labeled("y"), ApClash.Other(1), ApClash.Empty)
    assertEquals(
      values.map(value => textOf(ApClashBox(value))),
      Vector(
        """{"clash": {"Named": {"kind": "x"}}}""",
        """{"clash": {"Labeled": {"kind": "y"}}}""",
        """{"clash": {"Other": {"size": 1}}}""",
        """{"clash": {"Empty": {}}}"""
      )
    )
    values.foreach(value => assertTextRoundTrip(ApClashBox(value)))
    val text = schemaText[ApClashBox]
    assert(!text.contains("const"), text)
    assert(text.contains(""""Named": {"""), text)
  }

  test("a sealed trait with a case that is not a record keeps a case name as a key, as the codec does") {
    assertEquals(textOf(ApMixedBox(ApMixed.Boxed(3))), """{"mixed": {"Boxed": 3}}""")
    assertEquals(textOf(ApMixedBox(ApMixed.Plain(1))), """{"mixed": {"Plain": {"x": 1}}}""")
    assertTextRoundTrip(ApMixedBox(ApMixed.Boxed(3)))
    assertTextRoundTrip(ApMixedBox(ApMixed.Plain(1)))
    val text = schemaText[ApMixedBox]
    assert(!text.contains("const"), text)
    assert(text.contains(""""Boxed": {"""), text)
  }

  test("an enum with a discriminator stays a text, as the codec writes it") {
    assertEquals(specOf[ApLabelBox]("label").typeRef, TypeRef.string)
    assertEquals(specOf[ApLabelBox]("label").enumValues, Vector("One", "Two"))
    assertEquals(textOf(ApLabelBox(ApLabel.Two)), """{"label": "Two"}""")
    assertTextRoundTrip(ApLabelBox(ApLabel.One))
    assert(!schemaText[ApLabelBox].contains("const"), schemaText[ApLabelBox])
  }

  test("the allowed values of a Scala enum agree with its schema, also with an annotation on a simple case") {
    val allowed = specOf[ApPlainEnumBox]("value").enumValues
    allowed.foreach(name => assert(schemaText[ApPlainEnumBox].contains(s"\"$name\""), schemaText[ApPlainEnumBox]))
    // The macro of zio-blocks reads the annotations of the type symbol of a case, and a simple case of an enum has the
    // symbol of the enum. So this rename has no effect, in the schema too.
    assertEquals(allowed, Vector("A", "B"))
    assertTextRoundTrip(ApPlainEnumBox(ApPlainEnum.A))
    assertTextRoundTrip(ApPlainEnumBox(ApPlainEnum.B))
  }

  test("a recursive sealed trait with a discriminator is flat at each level") {
    val tree = ApTreeBox(ApTree.Node(List(ApTree.Leaf(1), ApTree.Node(Nil))))
    assertEquals(
      textOf(tree),
      """{"tree": {"kind": "Node", "kids": [{"kind": "Leaf", "value": 1}, {"kind": "Node", "kids": []}]}}"""
    )
    assertTextRoundTrip(tree)
    val text = schemaText[ApTreeBox]
    assert(text.contains("$defs"), text)
    assert(text.contains(""""kind": {"const": "Node"}"""), text)
  }

  // ── A naming strategy on a case record and on a nested sealed trait ───────────────────────────────────────────

  test("fieldNaming on a case record names its fields, and caseNaming of a sealed trait does not") {
    assertEquals(
      textOf(ApOuterBox(ApOuter.BigBox(1), ApOuter.DeepCat("c"))).take(44),
      """{"outer": {"big_box": {"box_size": 1}}, "inn"""
    )
    assertTextRoundTrip(ApOuterBox(ApOuter.BigBox(1), ApOuter.DeepCat("c")))
    val text = schemaText[ApOuterBox]
    assert(text.contains(""""big_box": {"""), text)
    assert(text.contains(""""box_size": {"""), text)
    assert(text.contains(""""catName": {"""), text)
  }

  test("caseNaming of the outer sealed trait names a nested leaf, and a nested sealed trait in a field uses its own") {
    assertEquals(
      textOf(ApOuterBox(ApOuter.DeepCat("a"), ApOuter.DeepCat("b"))),
      """{"outer": {"inner_kind": {"deep_cat": {"catName": "a"}}}, "inner": {"deep-cat": {"catName": "b"}}}"""
    )
    val text = schemaText[ApOuterBox]
    assert(text.contains(""""deep_cat": {"""), text)
    assert(text.contains(""""deep-cat": {"""), text)
    assert(!text.contains("inner_kind"), text)
    assertEquals(
      decoded[ApOuterBox]("""{"outer": {"deep_cat": {"catName": "a"}}, "inner": {"deep-cat": {"catName": "b"}}}"""),
      Right(ApOuterBox(ApOuter.DeepCat("a"), ApOuter.DeepCat("b")))
    )
    assertEquals(
      decoded[ApOuterBox]("""{"outer": {"DeepCat": {"catName": "a"}}, "inner": {"DeepCat": {"catName": "b"}}}"""),
      Right(ApOuterBox(ApOuter.DeepCat("a"), ApOuter.DeepCat("b")))
    )
  }

  test("an unknown naming strategy fails when the shape is made, as in the codec") {
    val error = intercept[IllegalArgumentException](shapeOf[ApBogus])
    assert(error.getMessage.contains("bogus"), error.getMessage)
  }

  test("a wire name that is not an identifier is an error of a signature, and it is valid in a nested record") {
    val error = intercept[IllegalArgumentException] {
      Signature.fromShapes(
        "Kebab",
        "",
        ZioSchemaCodec.derivedFromZioSchema[ApQuestion],
        ZioSchemaCodec.derivedFromZioSchema[ApKebab]
      )
    }
    assert(error.getMessage.contains("valid identifiers"), error.getMessage)
    assertEquals(textOf(ApKebab("a")), """{"first-name": "a"}""")
  }

  // ── A transient field ─────────────────────────────────────────────────────────────────────────────────────────

  test("a transient Option or collection with no default decodes to no value, and a value of it goes away") {
    assertEquals(shapeOf[ApTransientNoDefault].fieldSpecs.map(_.name), Vector("name", "hint"))
    assertEquals(
      decoded[ApTransientNoDefault]("""{"name": "a", "note": "x", "tags": ["t"], "scores": {"s": 1}, "hint": "h"}"""),
      Right(ApTransientNoDefault("a", None, Nil, Map.empty, Some("h")))
    )
    val value = ApTransientNoDefault("a", Some("x"), List("t"), Map("s" -> 1), Some("h"))
    assertEquals(textOf(value), """{"name": "a"}""")
    assertEquals(
      shapeOf[ApTransientNoDefault].decode(shapeOf[ApTransientNoDefault].encode(value)),
      Right(ApTransientNoDefault("a", None, Nil, Map.empty, None))
    )
    val text = schemaText[ApTransientNoDefault]
    assert(!text.contains("tags"), text)
    assert(text.contains("hint"), text)
  }

  test("each other transient field with no default is a compile error of the macro of zio-blocks") {
    val errors = compileErrors(
      """
        final case class Bad(@zio.blocks.schema.Modifier.transient() x: Int)
        zio.blocks.schema.Schema.derived[Bad]
      """
    )
    assert(errors.contains("Missing default value"), errors)
  }

  // ── noExtraFields ─────────────────────────────────────────────────────────────────────────────────────────────

  test("noExtraFields closes the schema, and the decode ignores an extra field") {
    assert(schemaText[ApClosed].contains(""""additionalProperties": false"""), schemaText[ApClosed])
    assertEquals(decoded[ApClosed]("""{"a": 1, "b": 2}"""), Right(ApClosed(1)))
  }

  // ── A case with the name of a case of an Option ───────────────────────────────────────────────────────────────

  test("a sealed trait with the cases None and Some is no Option, also with a rename") {
    assert(!specOf[ApFakes]("fake").optional)
    assertEquals(specOf[ApFakes]("fake").typeRef, TypeRef.union)
    assert(!specOf[ApFakes]("renamed").optional)
    val value = ApFakes(ApFake.Some(1), Some(ApFake.None), ApRenamedFake.Full(2), Some(ApRenamedFake.Empty))
    assertEquals(
      textOf(value),
      """{"fake": {"Some": {"value": 1}}, "maybe": {"None": {}}, "renamed": {"Some": {"value": 2}}, "other": {"None": {}}}"""
    )
    assertTextRoundTrip(value)
    assertTextRoundTrip(ApFakes(ApFake.None, None, ApRenamedFake.Empty, None))
    assertEquals(
      decoded[ApFakes]("""{"fake": "None", "maybe": "None", "renamed": "None", "other": null}"""),
      Right(ApFakes(ApFake.None, Some(ApFake.None), ApRenamedFake.Empty, None))
    )
    assert(decoded[ApFakes]("""{"fake": null, "maybe": null, "renamed": "None", "other": null}""").isLeft)
  }

  // ── A Maybe ───────────────────────────────────────────────────────────────────────────────────────────────────

  private def maybes: ApMaybes =
    ApMaybes(
      int = Maybe.present(1),
      long = Maybe.present(2L),
      float = Maybe.present(1.5f),
      double = Maybe.present(2.5),
      bool = Maybe.present(true),
      byte = Maybe.present(3.toByte),
      char = Maybe.present('c'),
      short = Maybe.present(4.toShort),
      item = Maybe.present(ApItem("a", 1, None)),
      many = List(Maybe.present("x"), Maybe.absent),
      pair = (Maybe.present(5), "p"),
      kept = Maybe.absent,
      other = Maybe.present(6)
    )

  test("a Maybe of each element type is its element or a null, also in a list and a tuple") {
    assertEquals(
      textOf(maybes),
      """{"int": 1, "long": 2, "float": 1.5, "double": 2.5, "bool": true, "byte": 3, "char": "c", "short": 4, """ +
        """"item": {"the_name": "a", "size": 1, "note": null}, "many": ["x", null], "pair": [5, "p"], "kept": null, """ +
        """"renamed": 6}"""
    )
    assertTextRoundTrip(maybes)
    assertEquals(specOf[ApMaybes]("int").typeRef, TypeRef.int)
    assert(specOf[ApMaybes]("int").optional)
    assertEquals(specOf[ApMaybes]("item").typeRef, TypeRef.json)
  }

  test("a Maybe takes a null text and the lenient rules of its element, and an absent field gets its default") {
    assertEquals(
      decoded[ApMaybes](
        """{"int": "1", "bool": "yes", "double": "n/a", "many": [], "pair": [null, "p"], "renamed": "6"}"""
      ),
      Right(
        ApMaybes(
          int = Maybe.present(1),
          long = Maybe.absent,
          float = Maybe.absent,
          double = Maybe.absent,
          bool = Maybe.present(true),
          byte = Maybe.absent,
          char = Maybe.absent,
          short = Maybe.absent,
          item = Maybe.absent,
          many = Nil,
          pair = (Maybe.absent, "p"),
          kept = Maybe.present("d"),
          other = Maybe.present(6)
        )
      )
    )
  }

  test(
    "a Maybe in an Option and an Option in a Maybe keep each value on the direct path, and their text is the value"
  ) {
    val values = Vector(
      ApNestedMaybes(Some(Maybe.present("x")), Maybe.present(Some("y"))),
      ApNestedMaybes(Some(Maybe.absent), Maybe.present(None)),
      ApNestedMaybes(None, Maybe.absent)
    )
    values.foreach(value =>
      assertEquals(shapeOf[ApNestedMaybes].decode(shapeOf[ApNestedMaybes].encode(value)), Right(value))
    )
    assertEquals(textOf(values.head), """{"inOption": "x", "inMaybe": "y"}""")
    assertTextRoundTrip(values.head)
    assertTextRoundTrip(values.last)
  }

  test("an input and an output with one wire name are an error when the layout is made") {
    val error = intercept[IllegalArgumentException] {
      Signature.derived[ApRenamedInput, ApRenamedTo]("Clash")
    }
    assert(error.getMessage.contains("unique names"), error.getMessage)
  }

  test("the canonical derivation of a signature reads a Maybe field") {
    val shape = Shape.canonicalDerived[AfMaybe]
    assertEquals(shape.decode(record("""{"hint": "x", "count": 1}""")), Right(AfMaybe(Maybe.present("x"), 1)))
    assertEquals(shape.decode(record("""{"count": 1}""")), Right(AfMaybe(Maybe.absent, 1)))
    assertEquals(PythonText.json(shape.encode(AfMaybe(Maybe.absent, 1))), """{"hint": null, "count": 1}""")
    val canonical = Shape.canonicalDerived[ApPlainMaybes]
    val value     = ApPlainMaybes(Maybe.present(1), Maybe.present(1.5), Maybe.present(Instant.EPOCH), Maybe.absent)
    assertEquals(
      PythonText.json(canonical.encode(value)),
      """{"int": 1, "double": 1.5, "at": "1970-01-01T00:00:00Z", "text": null}"""
    )
    assertEquals(canonical.decode(canonical.encode(value)), Right(value))
  }

  test("the canonical derivation has no schema for a Maybe of a record, thus it is a compile error") {
    val errors = compileErrors("Shape.canonicalDerived[ApMaybes]")
    assert(errors.contains("Cannot derive schema"), errors)
  }

  // ── A framework type ──────────────────────────────────────────────────────────────────────────────────────────

  test("a History, a ToolCall, and a decision type stay in their typed form under a renamed field") {
    val noul  = Noul(true, 0.5, None)
    val value = ApFramework(History.empty, List(ToolCall("t", DynamicValue.Record.empty)), noul, List(noul), "x")
    assertEquals(shapeOf[ApFramework].fieldSpecs.map(_.name), Vector("past", "tools", "flag", "flags", "text"))
    assertEquals(specOf[ApFramework]("past").typeRef, TypeRef.history)
    assertEquals(specOf[ApFramework]("tools").typeRef, TypeRef.toolCalls)
    assertEquals(specOf[ApFramework]("flag").typeRef, TypeRef.noul)
    assertEquals(shapeOf[ApFramework].nestedDecisionFields, Vector("flags"))
    val encoded = shapeOf[ApFramework].encode(value)
    assertEquals(DynamicValues.recordGet(encoded, "flag"), Some(summon[Schema[Noul]].toDynamicValue(noul)))
    assertEquals(DynamicValues.recordGet(encoded, "past"), Some(summon[Schema[History]].toDynamicValue(History.empty)))
    assertEquals(shapeOf[ApFramework].decode(encoded), Right(value))
    assert(!schemaText[ApFramework].contains(""""id": {"""), schemaText[ApFramework])
    assert(!schemaText[ApFramework].contains(""""other""""), schemaText[ApFramework])
  }

  // ── The entry points of a signature ───────────────────────────────────────────────────────────────────────────

  test("Signature.derived has the wire names in its layout") {
    val signature = Signature.derived[ApQuestion, ApNamed]("Named")
    assertEquals(signature.layout.outputFields.map(_.name), Vector("full_name"))
    assertEquals(signature.outputShape.decode(record("""{"full_name": "a"}""")), Right(ApNamed("a")))
  }

  test("Signature.of keeps the name of its method, and a record in its field has its wire names") {
    val signature = Signature.of[ApSpec]
    assertEquals(signature.layout.outputFields.map(_.name), Vector("named"))
    val output = signature.outputShape.decode(record("""{"named": {"full_name": "a"}}"""))
    assertEquals(output.map(_.named), Right(ApNamed("a")))
  }

  test("Signature.from and fromType have the wire names in the layout, and the output has the names in the code") {
    val method = Signature.from(ApMethods.sameCodeName)
    assertEquals(method.layout.inputFields.map(_.name), Vector("name"))
    assertEquals(method.layout.outputFields.map(_.name), Vector("full_name"))
    assertEquals(method.outputShape.decode(record("""{"full_name": "a"}""")).map(_.name), Right("a"))
    val function = Signature.fromType[(name: String) => ApNamed]
    assertEquals(function.layout.outputFields.map(_.name), Vector("full_name"))
    val transientName = Signature.from(ApMethods.transientName)
    assertEquals(transientName.layout.inputFields.map(_.name), Vector("cached"))
  }

  test("the macro compares the wire names of an output with the inputs") {
    val rename = compileErrors("Signature.from(ApMethods.sameWireName)")
    assert(rename.contains("duplicate field names: full_name"), rename)
    val strategy = compileErrors("Signature.from(ApMethods.sameStrategy)")
    assert(strategy.contains("duplicate field names: first_name"), strategy)
  }

  test("Signature.prependOutput compares wire names in the layout, and the names in the code in the output type") {
    val field = FieldSpec("reasoning")
    val away  = Signature.derived[ApQuestion, ApRenamedAway]("Away").prependOutput("reasoning", field)
    assertEquals(away.layout.outputFields.map(_.name), Vector("reasoning", "why", "answer"))
    assertEquals(
      away.outputShape.decode(record("""{"reasoning": "r", "why": "w", "answer": "a"}""")).map(_.reasoning),
      Right("w")
    )
    val to = Signature.derived[ApQuestion, ApRenamedTo]("To").prependOutput("reasoning", field)
    assertEquals(to.layout.outputFields.map(_.name), Vector("reasoning", "answer"))
    val output = to.outputShape.decode(record("""{"reasoning": "r", "answer": "a"}"""))
    assertEquals(output.map(out => (out.reasoning, out.why, out.answer)), Right(("r", "r", "a")))
    val encoded = to.outputShape.encode((reasoning = "r", why = "r", answer = "a"))
    assertEquals(PythonText.json(encoded), """{"reasoning": "r", "answer": "a"}""")
  }

  test("Signature.prependOutput over a field renamed from the added name: encode and then decode is an error") {
    val away   = Signature.derived[ApQuestion, ApRenamedAway]("Away").prependOutput("reasoning", FieldSpec("reasoning"))
    val shape  = away.outputShape
    val result = shape.decode(shape.encode((reasoning = "w", answer = "a")))
    assert(result.left.exists(_.message.contains("reasoning")), result.toString)
  }

  test("Signature.prependOutput rejects an input with the wire name of the field") {
    val error = intercept[IllegalArgumentException] {
      Signature.derived[ApRenamedInput, ApNamed]("Input").prependOutput("reasoning", FieldSpec("reasoning"))
    }
    assert(error.getMessage.contains("it has an input with that name"), error.getMessage)
  }

final case class ApRenamedAway(@Modifier.rename("why") reasoning: String, answer: String)
object ApRenamedAway:
  given Schema[ApRenamedAway] = Schema.derived

final case class ApRenamedTo(@Modifier.rename("reasoning") why: String, answer: String)
object ApRenamedTo:
  given Schema[ApRenamedTo] = Schema.derived

final case class ApRenamedInput(@Modifier.rename("reasoning") question: String)
object ApRenamedInput:
  given Schema[ApRenamedInput] = Schema.derived
