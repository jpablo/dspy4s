package dspy4s.harness.guest

import munit.ScalaCheckSuite
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.forAll
import zio.blocks.chunk.Chunk
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant}

/** The types of a cell: a record, an enum, a sealed trait, a recursive type and a generic type. */
final case class Point(x: Int, note: Option[String]) derives Schema

enum Figure derives Schema:
  case Circle(r: Double)
  case Box(w: Int, h: Int)
  case Empty

/** An enum with no fields in any case. Schema writes such a case as a string, and Data as an object. */
enum Kind derives Schema:
  case Fix, Feature

sealed trait Animal
final case class Cat(lives: Int) extends Animal
case object Fish                 extends Animal

/** A recursive type needs one line, because the inline given does not end on it (5.1). */
final case class Dir(name: String, kids: Vector[Dir])

object Dir:
  given Data[Dir] = Data.derived

final case class Tagged[A](value: A, tag: String)

/** The laws Data, Text and Form of 4.7, and the preamble of 5.1. */
class DataLaws extends ScalaCheckSuite:

  // ── Generators ──

  private val doubles: Gen[Double] = Gen.oneOf(
    arbitrary[Double],
    Gen.oneOf(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, -0.0, 0.0, Double.MinPositiveValue)
  )

  private val floats: Gen[Float] = Gen.oneOf(arbitrary[Float], Gen.oneOf(Float.NaN, Float.NegativeInfinity, -0.0f))

  private val strings: Gen[String] =
    Gen.oneOf(arbitrary[String], Gen.oneOf("", "a\u0000b", "é ☃ 😀", "\"quoted\"", "line\nbreak"))

  private val bigDecimals: Gen[BigDecimal] = Gen.oneOf(
    arbitrary[BigDecimal],
    Gen.oneOf(BigDecimal("2.50"), BigDecimal("1." + "1" * 40), BigDecimal("-0.000000000000000000000000000000000000001"))
  )

  private val bigInts: Gen[BigInt] = Gen.oneOf(arbitrary[BigInt], Gen.const(BigInt("1" * 50)))

  private val instants: Gen[Instant]   = Gen.choose(-1000000000000L, 1000000000000L).map(Instant.ofEpochMilli)
  private val durations: Gen[Duration] = Gen.choose(0L, 1000000000L).map(Duration.ofMillis)

  private val points: Gen[Point] = Gen.zip(arbitrary[Int], Gen.option(strings)).map(Point(_, _))

  private val figures: Gen[Figure] = Gen.oneOf(
    doubles.map(Figure.Circle(_)),
    Gen.zip(arbitrary[Int], arbitrary[Int]).map(Figure.Box(_, _)),
    Gen.const(Figure.Empty)
  )

  private val animals: Gen[Animal] = Gen.oneOf(arbitrary[Int].map(Cat(_)), Gen.const(Fish))

  private def dirs(depth: Int): Gen[Dir] =
    if depth == 0 then strings.map(Dir(_, Vector.empty))
    else
      Gen.zip(strings, Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, dirs(depth - 1)))).map((n, k) => Dir(n, k.toVector))

  // ── The observation ──

  /** `decode(encode(v)) == Right(v)`. */
  private def back[A](gen: Gen[A])(using d: Data[A]): Prop =
    forAll(gen)(v => assertEquals(d.decode(d.encode(v), "v"), Right(v)))

  /** A value with NaN is not equal to itself, so this law compares the forms of the value before and after. */
  private def sameForm[A](gen: Gen[A])(using d: Data[A]): Prop =
    forAll(gen) { v =>
      val json = d.encode(v)
      assertEquals(d.decode(json, "v").map(d.encode), Right(json))
    }

  /** `parse(print(encode(v))) == encode(v)`. */
  private def text[A](gen: Gen[A])(using d: Data[A]): Prop =
    forAll(gen) { v =>
      val json = d.encode(v)
      assertEquals(Json.parse(json.print), Right(json))
    }

  private def json(text: String): Json = Json.parse(text).fold(e => fail(s"$text does not parse: $e"), identity)

  // ── Data ──

  property("Data: whole numbers come back equal")(
    back(arbitrary[Int]) && back(arbitrary[Long]) && back(arbitrary[Short]) && back(arbitrary[Byte]) && back(bigInts)
  )

  property("Data: decimal numbers come back equal")(back(bigDecimals))

  property("Data: floating numbers, with NaN, the infinities and -0.0, come back with the same form")(
    sameForm(doubles) && sameForm(floats)
  )

  property("Data: -0.0 and the infinities come back as the same values") {
    forAll(Gen.oneOf(-0.0, Double.PositiveInfinity, Double.NegativeInfinity)) { v =>
      val d = summon[Data[Double]]
      assertEquals(
        d.decode(d.encode(v), "v").map(java.lang.Double.doubleToRawLongBits),
        Right(java.lang.Double.doubleToRawLongBits(v))
      )
    }
  }

  property("Data: text, booleans, instants and durations come back equal")(
    back(strings) && back(arbitrary[Char]) && back(arbitrary[Boolean]) && back(instants) && back(durations)
  )

  property("Data: collections come back equal")(
    back(Gen.listOf(arbitrary[Int]).map(_.toVector)) && back(Gen.listOf(strings)) &&
      back(Gen.listOf(arbitrary[Int]).map(_.toSet)) && back(Gen.listOf(arbitrary[Long]).map(_.toIndexedSeq))
  )

  property("Data: maps with text keys and with other keys come back equal")(
    back(Gen.mapOf(Gen.zip(strings, arbitrary[Int]))) && back(Gen.mapOf(Gen.zip(arbitrary[Int], strings)))
  )

  property("Data: tuples, options and eithers come back equal")(
    back(Gen.zip(arbitrary[Int], strings, arbitrary[Boolean])) &&
      back(Gen.option(Gen.option(arbitrary[Int]))) &&
      back(Gen.either(strings, arbitrary[Int]))
  )

  property("Data: case classes, enums, sealed traits, recursive and generic types come back with the same form")(
    back(points) && sameForm(figures) && back(animals) && back(dirs(3)) &&
      back(Gen.zip(points, strings).map(Tagged(_, _))) && back(Gen.listOf(points).map(all => Map("all" -> all)))
  )

  test("Data: a value that does not fit gives a DataError with its path") {
    val found = summon[Data[Point]].decode(json("""{"x": "one", "note": {"None": {}}}"""), "p")
    assertEquals(found, Left(DataError("p.x", "Int", "a string")))
    val missing = summon[Data[Point]].decode(json("""{"note": {"None": {}}}"""), "p")
    assertEquals(missing.left.map(_.path), Left("p.x"))
  }

  test("Data: decode ignores an extra field") {
    assertEquals(
      summon[Data[Point]].decode(json("""{"x": 1, "note": {"None": {}}, "more": true}"""), "p"),
      Right(Point(1, None))
    )
  }

  // ── Text ──

  property("Text: parse(print(encode(v))) == encode(v)")(
    text(doubles) && text(bigDecimals) && text(bigInts) && text(strings) && text(points) && text(figures) &&
      text(dirs(2)) && text(Gen.mapOf(Gen.zip(arbitrary[Int], strings)))
  )

  // ── Form ──

  test("Form: Data writes the forms of the table of 5.1") {
    assertEquals(summon[Data[Option[String]]].encode(Some("n")), json("""{"Some": "n"}"""))
    assertEquals(summon[Data[Option[String]]].encode(None), json("""{"None": {}}"""))
    assertEquals(summon[Data[Figure]].encode(Figure.Empty), json("""{"Empty": {}}"""))
    assertEquals(summon[Data[Map[Int, String]]].encode(Map(1 -> "a")), json("""[[1, "a"]]"""))
    assertEquals(summon[Data[Either[String, Int]]].encode(Right(1)), json("""{"Right": 1}"""))
    assertEquals(summon[Data[Double]].encode(Double.NaN), Json.String("NaN"))
    assertEquals(summon[Data[Point]].encode(Point(1, None)), json("""{"x": 1, "note": {"None": {}}}"""))
    assertEquals(summon[Data[Unit]].encode(()), Json.Object(Chunk.empty))
  }

  /** Each reader fails on the text of the other, for a value whose two forms differ. */
  private def otherForms[A](value: A)(using d: Data[A], s: Schema[A]): Unit =
    val codec      = s.jsonCodec
    val schemaText = codec.encodeToString(value)
    assertNotEquals(json(schemaText), d.encode(value), s"Data and Schema write one form for $value")
    assert(d.decode(json(schemaText), "v").isLeft, s"Data reads the Schema form $schemaText")
    assert(
      codec.decode(d.encode(value).print.getBytes(StandardCharsets.UTF_8)).isLeft,
      s"Schema reads the Data form of $value"
    )

  test("Form: only Data reads the form of Data: Some")(otherForms[Option[String]](Some("n")))
  // The row of the table of 5.1 is about an enum with no fields in any case. In an enum with cases that have fields,
  // Schema writes a case with no fields as {"Empty":{}}, the form of Data, so the two forms are the same there.
  test("Form: only Data reads the form of Data: an enum case with no fields")(otherForms[Kind](Kind.Fix))

  test("Form: an enum case with no fields, in an enum with fields in other cases, has one form in both") {
    val schemaForm = json(summon[Schema[Figure]].jsonCodec.encodeToString(Figure.Empty))
    assertEquals(schemaForm, summon[Data[Figure]].encode(Figure.Empty))
  }
  test("Form: only Data reads the form of Data: a map with other keys")(otherForms[Map[Int, String]](Map(1 -> "a")))
  test("Form: only Data reads the form of Data: Right")(otherForms[Either[String, Int]](Right(1)))
  test("Form: only Data reads the form of Data: a record with an option")(otherForms[Point](Point(1, Some("n"))))

  // ── The preamble ──

  test("Preamble: a record gives its type, a given, and submit with one parameter for each field") {
    assertEquals(
      preamble(summon[Data[Point]].shape, Vector("doc" -> summon[Data[String]].shape)),
      Right(
        """case class Point(x: Int, note: Option[String])
          |given Data[Point] = Data.derived
          |def submit(x: Int, note: Option[String]): Nothing = submitValue(Point(x, note))
          |val doc: String = input[String]("doc")""".stripMargin
      )
    )
  }

  test("Preamble: an enum, a recursive type and a plain type") {
    assertEquals(
      preamble(summon[Data[Figure]].shape),
      Right(
        """enum Figure:
          |  case Circle(r: Double)
          |  case Box(w: Int, h: Int)
          |  case Empty
          |given Data[Figure] = Data.derived
          |def submit(value: Figure): Nothing = submitValue(value)""".stripMargin
      )
    )
    assertEquals(
      preamble(summon[Data[Dir]].shape),
      Right(
        """case class Dir(name: String, kids: Vector[Dir])
          |given Data[Dir] = Data.derived
          |def submit(name: String, kids: Vector[Dir]): Nothing = submitValue(Dir(name, kids))""".stripMargin
      )
    )
    assertEquals(
      preamble(summon[Data[Vector[Int]]].shape),
      Right("def submit(value: Vector[Int]): Nothing = submitValue(value)")
    )
  }

  test("Preamble: an input with the name of a guest API function has no preamble") {
    assertEquals(
      preamble(summon[Data[String]].shape, Vector("read" -> summon[Data[String]].shape)),
      Left("the input read has the name of a guest API function")
    )
  }

  test("Preamble: two types with one name and two forms have no preamble") {
    val first  = DataShape.Record("P", () => Vector("x" -> DataShape.Plain("Int")))
    val second = DataShape.Record("P", () => Vector("y" -> DataShape.Plain("String")))
    assertEquals(preamble(DataShape.Applied("Tuple", Vector(first, second))), Left("two types have the name P"))
  }
