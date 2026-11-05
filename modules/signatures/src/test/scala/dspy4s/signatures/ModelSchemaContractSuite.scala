package dspy4s.signatures

import dspy4s.core.contracts.{DynamicValues, PythonText}
import munit.FunSuite
import zio.blocks.schema.{DynamicValue, Schema}

// Top-level fixtures (Mirror derivation needs them outside the suite).
final case class MscInner(tags: List[String], label: String, note: Option[String])
object MscInner:
  given Schema[MscInner] = Schema.derived

final case class MscProbe(
    list       : List[String],
    vector     : Vector[MscInner],
    set        : Set[Int],
    map        : Map[String, Int],
    optList    : Option[List[String]],
    optMap     : Option[Map[String, Int]],
    opt        : Option[String],
    nested     : MscInner,
    nestedLists: List[List[String]],
    withDefault: List[String] = List("x"),
    text       : String
)
object MscProbe:
  given Schema[MscProbe] = Schema.derived

sealed trait MscShape
object MscShape:
  final case class Dots(points: List[Int])                   extends MscShape
  final case class Grid(rows: List[List[Int]], name: String) extends MscShape
  given Schema[MscShape] = Schema.derived

final case class MscHolder(shape: MscShape)
object MscHolder:
  given Schema[MscHolder] = Schema.derived

final case class MscTags(values: List[String])
object MscTags:
  given Schema[MscTags] = Schema[List[String]].transform(MscTags(_), _.values)

final case class MscWrapped(tags: MscTags)
object MscWrapped:
  given Schema[MscWrapped] = Schema.derived

final case class MscNode(label: String, children: List[MscNode])
object MscNode:
  given Schema[MscNode] = Schema.derived

/** The JSON schema that a prompt and a strict response format show, and the typed decode of the reply, must agree. The
  * model follows the schema: a field that the schema lets it leave out or set to null must decode. Upstream pydantic
  * has the same rules for these types: `list[T]` is a required array with no null, `dict` is a required object with no
  * null, and only `Optional[...]` takes a null.
  */
class ModelSchemaContractSuite extends FunSuite:

  private def schemaOf[A](using schema: Schema[A]): DynamicValue.Record =
    ZioSchemaCodec.jsonSchemaForModel(schema).getOrElse(fail("no schema"))

  private def at(value: DynamicValue, path: String*): DynamicValue =
    path.foldLeft(value) { (current, key) =>
      current match
        case record: DynamicValue.Record =>
          DynamicValues.recordGet(record, key).getOrElse(fail(s"no key $key in $record"))
        case other => fail(s"expected a record to read $key, got $other")
    }

  private def typesOf(schema: DynamicValue): Set[String] =
    at(schema, "type") match
      case many: DynamicValue.Sequence => many.elements.flatMap(DynamicValues.asString).toSet
      case one                         => DynamicValues.asString(one).toSet

  private def requiredOf(schema: DynamicValue): Set[String] =
    at(schema, "required") match
      case many: DynamicValue.Sequence => many.elements.flatMap(DynamicValues.asString).toSet
      case other                       => fail(s"expected a list, got $other")

  private def propertiesOf(schema: DynamicValue): Vector[(String, DynamicValue)] =
    at(schema, "properties") match
      case record: DynamicValue.Record => record.fields.toVector
      case other                       => fail(s"expected a record, got $other")

  private def jsonRecord(text: String): DynamicValue.Record =
    PythonText.parseJson(text) match
      case Right(record: DynamicValue.Record) => record
      case other                              => fail(s"expected a JSON object, got $other")

  private def without(record: DynamicValue.Record, name: String): DynamicValue.Record =
    DynamicValue.Record(record.fields.filterNot(_._1 == name))

  private def withField(record: DynamicValue.Record, name: String, value: DynamicValue): DynamicValue.Record =
    DynamicValue.Record(without(record, name).fields :+ (name -> value))

  private def complete: DynamicValue.Record =
    jsonRecord(
      """{"list": ["a"], "vector": [{"tags": ["t"], "label": "l", "note": "n"}], "set": [1], "map": {"k": 1},
      |"optList": ["a"], "optMap": {"k": 1}, "opt": "o", "nested": {"tags": ["t"], "label": "l", "note": "n"},
      |"nestedLists": [["a"]], "withDefault": ["d"], "text": "x"}""".stripMargin
    )

  private def shape = ZioSchemaCodec.derivedFromZioSchema[MscProbe]

  test("a collection field that is not an Option has no null in its schema") {
    val schema = schemaOf[MscProbe]
    Vector("list", "vector", "set", "nestedLists", "withDefault").foreach { name =>
      assertEquals(typesOf(at(schema, "properties", name)), Set("array"), name)
    }
    assertEquals(typesOf(at(schema, "properties", "map")), Set("object"))
  }

  test("a collection field has no null at each level of the schema") {
    val schema = schemaOf[MscProbe]
    assertEquals(typesOf(at(schema, "properties", "nested", "properties", "tags")), Set("array"))
    assertEquals(typesOf(at(schema, "properties", "vector", "items", "properties", "tags")), Set("array"))
    assertEquals(typesOf(at(schema, "properties", "nestedLists", "items")), Set("array"))
  }

  test("an Option field keeps the null in its schema, also when it holds a collection") {
    val schema = schemaOf[MscProbe]
    assertEquals(typesOf(at(schema, "properties", "optList")), Set("array", "null"))
    assertEquals(typesOf(at(schema, "properties", "optMap")), Set("object", "null"))
    assertEquals(typesOf(at(schema, "properties", "opt")), Set("string", "null"))
    assertEquals(typesOf(at(schema, "properties", "nested", "properties", "note")), Set("string", "null"))
  }

  test("a collection field in a case of a sealed trait has no null in its schema") {
    val text = PythonText.json(schemaOf[MscHolder])
    assert(!text.contains("\"null\""), text)
  }

  test("a collection behind a transform has no null in its schema") {
    assertEquals(typesOf(at(schemaOf[MscWrapped], "properties", "tags")), Set("array"))
  }

  test("a collection field of a recursive type has no null in its definition, and it is required") {
    val node = at(schemaOf[MscNode], "$defs", "MscNode")
    assertEquals(typesOf(at(node, "properties", "children")), Set("array"))
    assertEquals(requiredOf(node), Set("label", "children"))
  }

  test("a collection field is required at each level, and an Option field or a field with a default is not") {
    val schema = schemaOf[MscProbe]
    assertEquals(
      requiredOf(schema),
      Set("list", "vector", "set", "map", "nested", "nestedLists", "text")
    )
    assertEquals(requiredOf(at(schema, "properties", "nested")), Set("tags", "label"))
    assertEquals(requiredOf(at(schema, "properties", "vector", "items")), Set("tags", "label"))
  }

  test("the complete record of these checks decodes") {
    assert(shape.decode(complete).isRight, shape.decode(complete).toString)
  }

  test("a null decodes for a field exactly when the schema permits it") {
    propertiesOf(schemaOf[MscProbe]).foreach { (name, property) =>
      val decoded = shape.decode(withField(complete, name, DynamicValue.Null))
      assertEquals(decoded.isRight, typesOf2(property).contains("null"), s"$name: $decoded")
    }
  }

  test("an absent field decodes exactly when the schema does not require it") {
    val schema = schemaOf[MscProbe]
    propertiesOf(schema).foreach { (name, _) =>
      val decoded = shape.decode(without(complete, name))
      assertEquals(decoded.isRight, !requiredOf(schema).contains(name), s"$name: $decoded")
    }
  }

  test("a null or an absent field in a nested record decodes exactly as its schema says") {
    val nested = at(schemaOf[MscProbe], "properties", "nested")
    val inner  = at(complete, "nested") match
      case record: DynamicValue.Record => record
      case other                       => fail(s"expected a record, got $other")
    propertiesOf(nested).foreach { (name, property) =>
      val withNull = shape.decode(withField(complete, "nested", withField(inner, name, DynamicValue.Null)))
      assertEquals(withNull.isRight, typesOf2(property).contains("null"), s"null $name: $withNull")
      val absent = shape.decode(withField(complete, "nested", without(inner, name)))
      assertEquals(absent.isRight, !requiredOf(nested).contains(name), s"absent $name: $absent")
    }
  }

  // A property with no `type` (an enum, for example) has no null.
  private def typesOf2(schema: DynamicValue): Set[String] =
    schema match
      case record: DynamicValue.Record if DynamicValues.recordGet(record, "type").isEmpty => Set.empty
      case _                                                                              => typesOf(schema)
