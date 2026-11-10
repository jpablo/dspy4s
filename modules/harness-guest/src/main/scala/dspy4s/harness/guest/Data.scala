package dspy4s.harness.guest

// Capture checking: a cell runs with it, and `@assumeSafe`, which lets safe code call `Data`, needs it.
import language.experimental.captureChecking

import caps.assumeSafe
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import scala.compiletime.{constValue, constValueTuple, summonInline}
import scala.deriving.Mirror

/** A value that does not fit its type (5.1). A cell sees the message: `at a.y: expected a field of P, found {x}`. */
final case class DataError(path: String, expected: String, found: String)
    extends RuntimeException(s"at $path: expected $expected, found $found")

/** What a value looks like, for the preamble of a new REPL (5.1). A record and a union are lazy, because a type can
  * hold itself.
  */
enum DataShape:
  case Plain(name: String)
  case Applied(name: String, args: Vector[DataShape])
  case Record(name: String, fields: () => Vector[(String, DataShape)])
  case Union(name: String, cases: () => Vector[(String, DataShape)])

/** The type class of a value that a later cell, another session or the host reads back with a type (5.1). The guest API
  * owns it, because safe code cannot name the `Schema` of zio-blocks. The table of 5.1 gives the form of each kind of
  * value. Only `Data` reads that form.
  */
@annotation.implicitNotFound(
  "${A} cannot leave a cell. A value that leaves a cell is a number, a string, a Vector, List, Seq, IndexedSeq, Set, " +
    "Map, Option, Either or tuple of these, a case class or an enum. Use .toVector for an Array or a Range."
)
@assumeSafe trait Data[A]:
  def encode(value: A): Json
  def decode(value: Json, path: String): Either[DataError, A]
  def shape: DataShape

@assumeSafe private[guest] trait DataAuto:

  /** With this given, a case class or an enum needs no `derives Data`. A recursive type needs one line:
    * `given Data[T] = Data.derived`.
    */
  inline given auto[A](using m: Mirror.Of[A]): Data[A] = Data.derived[A]

@assumeSafe object Data extends DataAuto:
  import DataDerivation.*
  import DataSupport.*

  given Data[Int]   = whole[Int]("Int", BigDecimal(_), _.isValidInt, _.toInt)
  given Data[Long]  = whole[Long]("Long", BigDecimal(_), _.isValidLong, _.toLong)
  given Data[Short] = whole[Short]("Short", v => BigDecimal(v.toInt), _.isValidShort, _.toShort)
  given Data[Byte]  = whole[Byte]("Byte", v => BigDecimal(v.toInt), _.isValidByte, _.toByte)

  given Data[BigInt] =
    big[BigInt](
      "BigInt",
      v => new java.math.BigDecimal(v.bigInteger),
      n => scala.util.Try(BigInt(n.toBigIntegerExact)).toOption
    )

  given Data[BigDecimal] =
    big[BigDecimal]("BigDecimal", _.bigDecimal, n => Some(new BigDecimal(n, java.math.MathContext.UNLIMITED)))

  given Data[Double] =
    floating[Double](
      "Double",
      v => !v.isNaN && !v.isInfinite && !(v == 0.0 && 1.0 / v < 0.0),
      _.toString,
      _.toDouble
    )

  given Data[Float] =
    floating[Float](
      "Float",
      v => !v.isNaN && !v.isInfinite && !(v == 0.0f && 1.0f / v < 0.0f),
      _.toString,
      _.toFloat
    )

  given Data[String] = text[String]("String", identity, identity)
  given Data[Char]   =
    text[Char]("Char", _.toString, s => if s.length == 1 then s.head else throw IllegalArgumentException(s))
  given Data[Boolean] = scalar[Boolean]("Boolean", Json.Boolean(_)) { case Json.Boolean(v) => v }
  given Data[Unit]    = scalar[Unit]("Unit", _ => Json.Object(Chunk.empty)) { case Json.Object(_) => () }

  given Data[java.time.Instant]  = text[java.time.Instant]("Instant", _.toString, java.time.Instant.parse(_))
  given Data[java.time.Duration] = text[java.time.Duration]("Duration", _.toString, java.time.Duration.parse(_))

  given [A](using d: Data[A]): Data[Vector[A]]     = sequence("Vector", d, _.iterator, identity)
  given [A](using d: Data[A]): Data[List[A]]       = sequence("List", d, _.iterator, _.toList)
  given [A](using d: Data[A]): Data[Seq[A]]        = sequence("Seq", d, _.iterator, identity)
  given [A](using d: Data[A]): Data[IndexedSeq[A]] = sequence("IndexedSeq", d, _.iterator, identity)
  given [A](using d: Data[A]): Data[Set[A]]        = sequence("Set", d, _.iterator, _.toSet)

  /** A map with string keys is an object. */
  given stringMap[V](using v: Data[V]): Data[Map[String, V]] = StringMapData(v)

  /** Another map is an array of pairs. */
  given [K, V](using k: Data[K], v: Data[V]): Data[Map[K, V]] = PairMapData(k, v)

  given Data[EmptyTuple] = EmptyTupleData

  given [H, T <: Tuple](using h: Data[H], t: Data[T]): Data[H *: T] = TupleData(h, t)

  given [A](using d: Data[A]): Data[Option[A]] = OptionData(d)

  given [A](using d: Data[A]): Data[Some[A]] = SomeData(d)

  given Data[None.type] = NoneData

  given [A, B](using a: Data[A], b: Data[B]): Data[Either[A, B]] = EitherData(a, b)

  given [A, B](using b: Data[B]): Data[Right[A, B]] = RightData(b)

  given [A, B](using a: Data[A]): Data[Left[A, B]] = LeftData(a)

  /** The `Data` of a case class or an enum, from the names and the element types of its `Mirror`. */
  inline def derived[A](using m: Mirror.Of[A]): Data[A] =
    val name   = constValue[m.MirroredLabel]
    val labels = labelsOf(constValueTuple[m.MirroredElemLabels])
    inline m match
      case p: Mirror.ProductOf[A] =>
        RecordData[A, p.MirroredElemTypes](name, labels, p, summonInline[Fields[p.MirroredElemTypes]])
      case s: Mirror.SumOf[A] =>
        UnionData[A, s.MirroredElemTypes](name, labels.toVector, s, summonInline[Cases[A, s.MirroredElemTypes]])
