package dspy4s.harness.guest

import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import scala.deriving.Mirror

/** The classes that `Data.derived` builds. This file has no capture checking: the `Data` of a field is a by-name value,
  * so that a type can hold itself, and capture checking counts that closure as a capability.
  */
object DataDerivation:
  import DataSupport.*

  /** The names of the fields or of the cases. `derived` calls this function and not `Tuple.toList`: the result type of
    * `toList` is a match type, and capture checking cannot reduce it for the tuple of labels in a file with capture
    * checking.
    */
  def labelsOf(names: Tuple): List[String] = names.productIterator.map(_.toString).toList

  /** The fields of a record, typed by the tuple of their types. The instance for `H *: T` applies the `Data` of `H` to
    * the value of `H`, so no field value is cast. The `Data` of a field is lazy, because a type can hold itself.
    */
  sealed trait Fields[T <: Tuple]:
    def encode(values: T): List[Json]
    def decode(found : Map[String, Json], labels: List[String], owner: String, path: String, whole: Json): Either[
      DataError,
      T
    ]
    def shapes: List[DataShape]

  object Fields:
    given none: Fields[EmptyTuple] = NoFields

    given more[H, T <: Tuple](using head: => Data[H], tail: Fields[T]): Fields[H *: T] = MoreFields(() => head, tail)

  object NoFields extends Fields[EmptyTuple]:
    def encode(values: EmptyTuple): List[Json] = Nil
    def decode(
        found : Map[String, Json],
        labels: List[String],
        owner : String,
        path  : String,
        whole : Json
    ): Either[DataError, EmptyTuple] = Right(EmptyTuple)
    def shapes: List[DataShape]      = Nil

  final class MoreFields[H, T <: Tuple](head: () => Data[H], tail: Fields[T]) extends Fields[H *: T]:
    private lazy val data = head()

    def encode(values: H *: T): List[Json] = data.encode(values.head) :: tail.encode(values.tail)

    def decode(
        found : Map[String, Json],
        labels: List[String],
        owner : String,
        path  : String,
        whole : Json
    ): Either[DataError, H *: T] =
      labels match
        case label :: rest =>
          for
            value  <- found.get(label).toRight(DataError(s"$path.$label", s"a field of $owner", describe(whole)))
            first  <- data.decode(value, s"$path.$label")
            others <- tail.decode(found, rest, owner, path, whole)
          yield first *: others
        case Nil => Left(DataError(path, s"a field of $owner", describe(whole)))

    def shapes: List[DataShape] = data.shape :: tail.shapes

  /** The cases of a union, typed by the tuple of their types. Each case type is a subtype of `A`, so a decoded case is
    * an `A` with no cast.
    */
  sealed trait Cases[A, T <: Tuple]:
    def encode(value: A, ordinal: Int): Json
    def decode(index: Int, inner: Json, path: String): Either[DataError, A]
    def shapes: List[DataShape]

  object Cases:
    given none[A]: Cases[A, EmptyTuple] = NoCases()

    given more[A, H <: A, T <: Tuple](using head: => Data[H], tail: Cases[A, T]): Cases[A, H *: T] =
      MoreCases(() => head, tail)

  final class NoCases[A] extends Cases[A, EmptyTuple]:
    def encode(value: A, ordinal: Int): Json                                = throw IllegalArgumentException(s"no case with the ordinal $ordinal")
    def decode(index: Int, inner: Json, path: String): Either[DataError, A] =
      Left(DataError(path, "a known case", describe(inner)))
    def shapes: List[DataShape] = Nil

  final class MoreCases[A, H <: A, T <: Tuple](head: () => Data[H], tail: Cases[A, T]) extends Cases[A, H *: T]:
    private lazy val data = head()

    def encode(value: A, ordinal: Int): Json =
      // `Mirror.SumOf` gives the ordinal of the case of a value, and the type of that case is not in the type system.
      // So the value of the case at ordinal 0 is cast to `H`, the type of that case.
      if ordinal == 0 then data.encode(value.asInstanceOf[H]) else tail.encode(value, ordinal - 1)

    def decode(index: Int, inner: Json, path: String): Either[DataError, A] =
      if index == 0 then data.decode(inner, path) else tail.decode(index - 1, inner, path)

    def shapes: List[DataShape] = data.shape :: tail.shapes

  /** A record: an object with one field for each parameter. */
  final class RecordData[A, T <: Tuple](
      name  : String,
      labels: List[String],
      m     : Mirror.ProductOf[A] { type MirroredElemTypes = T },
      fields: Fields[T]
  ) extends Data[A]:
    def encode(value: A): Json =
      value match
        case product: Product =>
          // `Mirror.ProductOf[A]` says that the fields of an `A` have the types `T`. `derived` serves records and
          // unions, so `A` has no `Product` bound here, and `Tuple.fromProductTyped` cannot give `T`.
          Json.Object(Chunk.from(labels.zip(fields.encode(Tuple.fromProduct(product).asInstanceOf[T]))))
        case other => throw IllegalArgumentException(s"$name is not a record: $other")

    def decode(value: Json, path: String): Either[DataError, A] =
      value match
        case Json.Object(found) => fields.decode(found.iterator.toMap, labels, name, path, value).map(m.fromTuple)
        case other              => Left(DataError(path, name, describe(other)))

    def shape: DataShape = DataShape.Record(name, () => labels.zip(fields.shapes).toVector)

  /** A union: an object with one field, the name of the case. */
  final class UnionData[A, T <: Tuple](name: String, labels: Vector[String], m: Mirror.SumOf[A], cases: Cases[A, T])
      extends Data[A]:
    def encode(value: A): Json =
      val i = m.ordinal(value)
      one(labels(i), cases.encode(value, i))

    def decode(value: Json, path: String): Either[DataError, A] =
      value match
        case Json.Object(found) if found.length == 1 =>
          val (label, inner) = found.head
          labels.indexOf(label) match
            case -1 => Left(DataError(path, s"a case of $name", s"case $label"))
            case i  => cases.decode(i, inner, s"$path.$label")
        case other => Left(DataError(path, s"a case of $name", describe(other)))

    def shape: DataShape = DataShape.Union(name, () => labels.zip(cases.shapes))

/** The instances and the helpers of `Data`: host code with only pure functions. */
private[guest] object DataSupport:

  def describe(value: Json): String =
    value match
      case Json.Object(fields) => fields.iterator.map(_._1).mkString("{", ", ", "}")
      case Json.Array(items)   => s"an array of ${items.length}"
      case Json.String(_)      => "a string"
      case Json.Number(n)      => s"the number $n"
      case Json.Boolean(_)     => "a boolean"
      case Json.Null           => "null"

  /** The decode of each item, or the first error. */
  def traverse[A, B](items: Iterator[A])(f: A => Either[DataError, B]): Either[DataError, Vector[B]] =
    items.foldLeft[Either[DataError, Vector[B]]](Right(Vector.empty)) { (done, item) =>
      done.flatMap(found => f(item).map(found :+ _))
    }

  def one(label: String, value: Json): Json = Json.Object(Chunk((label, value)))

  final class Scalar[A](name: String, to: A => Json, from: PartialFunction[Json, A]) extends Data[A]:
    def encode(value: A): Json                                  = to(value)
    def decode(value: Json, path: String): Either[DataError, A] =
      from.lift(value).toRight(DataError(path, name, describe(value)))
    def shape: DataShape = DataShape.Plain(name)

  def scalar[A](name: String, to: A => Json)(from: PartialFunction[Json, A]): Data[A] = Scalar(name, to, from)

  def whole[A](name: String, to: A => BigDecimal, valid: BigDecimal => Boolean, from: BigDecimal => A): Data[A] =
    scalar[A](name, v => Json.Number(to(v))) { case Json.Number(n) if valid(n) => from(n) }

  /** A number with no JSON form (NaN, the infinities, -0.0) is a string. So an encode never throws. */
  def floating[A](name: String, plain: A => Boolean, text: A => String, parse: String => A): Data[A] =
    scalar[A](name, v => if plain(v) then Json.Number(BigDecimal(text(v))) else Json.String(text(v))) {
      case Json.Number(n)                                                   => parse(n.toString)
      case Json.String(s) if Set("NaN", "Infinity", "-Infinity", "-0.0")(s) => parse(s)
    }

  /** The JSON reader keeps 34 digits. A number with more digits is a string, so no digit changes. */
  def big[A](name: String, exact: A => java.math.BigDecimal, back: java.math.BigDecimal => Option[A]): Data[A] =
    def read(n: java.math.BigDecimal): Option[A] = scala.util.Try(back(n)).toOption.flatten
    scalar[A](
      name,
      v =>
        val n = exact(v)
        if n.precision <= 34 then Json.Number(new BigDecimal(n, java.math.MathContext.UNLIMITED))
        else Json.String(n.toString)
    ) {
      case Json.Number(n) if read(n.bigDecimal).isDefined => read(n.bigDecimal).get
      case Json.String(s)
          if scala.util.Try(new java.math.BigDecimal(s)).isSuccess &&
            read(new java.math.BigDecimal(s)).isDefined => read(new java.math.BigDecimal(s)).get
    }

  def text[A](name: String, to: A => String, from: String => A): Data[A] =
    scalar[A](name, v => Json.String(to(v))) {
      case Json.String(s) if scala.util.Try(from(s)).isSuccess => from(s)
    }

  final class SequenceData[A, C](name: String, element: Data[A], items: C => Iterator[A], build: Vector[A] => C)
      extends Data[C]:
    def encode(value: C): Json                                  = Json.Array(Chunk.fromIterator(items(value).map(element.encode)))
    def decode(value: Json, path: String): Either[DataError, C] =
      value match
        case Json.Array(values) =>
          traverse(values.iterator.zipWithIndex)((v, i) => element.decode(v, s"$path[$i]")).map(build)
        case other => Left(DataError(path, name, describe(other)))
    def shape: DataShape = DataShape.Applied(name, Vector(element.shape))

  def sequence[A, C](name: String, element: Data[A], items: C => Iterator[A], build: Vector[A] => C): Data[C] =
    SequenceData(name, element, items, build)

  final class StringMapData[V](v: Data[V]) extends Data[Map[String, V]]:
    def encode(value: Map[String, V]): Json =
      Json.Object(Chunk.from(value.iterator.map((a, b) => (a, v.encode(b))).toList))
    def decode(value: Json, path: String): Either[DataError, Map[String, V]] =
      value match
        case Json.Object(fields) =>
          traverse(fields.iterator)((a, b) => v.decode(b, s"$path.$a").map((a, _))).map(_.toMap)
        case other => Left(DataError(path, "Map", describe(other)))
    def shape: DataShape = DataShape.Applied("Map", Vector(DataShape.Plain("String"), v.shape))

  final class PairMapData[K, V](k: Data[K], v: Data[V]) extends Data[Map[K, V]]:
    def encode(value: Map[K, V]): Json =
      Json.Array(Chunk.from(value.iterator.map((a, b) => Json.Array(Chunk(k.encode(a), v.encode(b)))).toList))
    def decode(value: Json, path: String): Either[DataError, Map[K, V]] =
      value match
        case Json.Array(items) => traverse(items.iterator) {
            case Json.Array(pair) if pair.length == 2 =>
              for
                key  <- k.decode(pair(0), s"$path.key")
                item <- v.decode(pair(1), s"$path.value")
              yield (key, item)
            case other => Left(DataError(path, "a pair", describe(other)))
          }.map(_.toMap)
        case other => Left(DataError(path, "Map", describe(other)))
    def shape: DataShape = DataShape.Applied("Map", Vector(k.shape, v.shape))

  object EmptyTupleData extends Data[EmptyTuple]:
    def encode(value: EmptyTuple): Json                                  = Json.Array(Chunk.empty)
    def decode(value: Json, path: String): Either[DataError, EmptyTuple] =
      value match
        case Json.Array(items) if items.isEmpty => Right(EmptyTuple)
        case other                              => Left(DataError(path, "an empty tuple", describe(other)))
    def shape: DataShape = DataShape.Applied("Tuple", Vector.empty)

  final class TupleData[H, T <: Tuple](h: Data[H], t: Data[T]) extends Data[H *: T]:
    def encode(value: H *: T): Json =
      t.encode(value.tail) match
        case Json.Array(items) => Json.Array(h.encode(value.head) +: items)
        case other             => Json.Array(Chunk(h.encode(value.head), other))
    def decode(value: Json, path: String): Either[DataError, H *: T] =
      value match
        case Json.Array(values) if values.nonEmpty =>
          for
            head <- h.decode(values.head, s"$path._1")
            tail <- t.decode(Json.Array(values.tail), path)
          yield head *: tail
        case other => Left(DataError(path, "a tuple", describe(other)))
    def shape: DataShape =
      t.shape match
        case DataShape.Applied("Tuple", rest) => DataShape.Applied("Tuple", h.shape +: rest)
        case other                            => DataShape.Applied("Tuple", Vector(h.shape, other))

  /** The value of an object with one field `label`, or `None`. */
  private def single(value: Json, label: String): Option[Json] =
    value match
      case Json.Object(found) if found.length == 1 && found.head._1 == label => Some(found.head._2)
      case _                                                                 => None

  final class OptionData[A](d: Data[A]) extends Data[Option[A]]:
    def encode(value: Option[A]): Json =
      value match
        case Some(v) => one("Some", d.encode(v))
        case None    => one("None", Json.Object(Chunk.empty))
    def decode(value: Json, path: String): Either[DataError, Option[A]] =
      single(value, "Some") match
        case Some(inner) => d.decode(inner, s"$path.Some").map(Some(_))
        case None        => single(value, "None").map(_ => None).toRight(DataError(path, "Option", describe(value)))
    def shape: DataShape = DataShape.Applied("Option", Vector(d.shape))

  final class SomeData[A](d: Data[A]) extends Data[Some[A]]:
    def encode(value: Some[A]): Json                                  = one("Some", d.encode(value.value))
    def decode(value: Json, path: String): Either[DataError, Some[A]] =
      single(value, "Some") match
        case Some(inner) => d.decode(inner, s"$path.Some").map(Some(_))
        case None        => Left(DataError(path, "Some", describe(value)))
    def shape: DataShape = DataShape.Applied("Some", Vector(d.shape))

  object NoneData extends Data[None.type]:
    def encode(value: None.type): Json                                  = one("None", Json.Object(Chunk.empty))
    def decode(value: Json, path: String): Either[DataError, None.type] =
      single(value, "None").map(_ => None).toRight(DataError(path, "None", describe(value)))
    def shape: DataShape = DataShape.Plain("None.type")

  final class EitherData[A, B](a: Data[A], b: Data[B]) extends Data[Either[A, B]]:
    def encode(value: Either[A, B]): Json =
      value match
        case Left(v)  => one("Left", a.encode(v))
        case Right(v) => one("Right", b.encode(v))
    def decode(value: Json, path: String): Either[DataError, Either[A, B]] =
      (single(value, "Left"), single(value, "Right")) match
        case (Some(inner), _) => a.decode(inner, s"$path.Left").map(Left(_))
        case (_, Some(inner)) => b.decode(inner, s"$path.Right").map(Right(_))
        case _                => Left(DataError(path, "Either", describe(value)))
    def shape: DataShape = DataShape.Applied("Either", Vector(a.shape, b.shape))

  final class RightData[A, B](b: Data[B]) extends Data[Right[A, B]]:
    def encode(value: Right[A, B]): Json                                  = one("Right", b.encode(value.value))
    def decode(value: Json, path: String): Either[DataError, Right[A, B]] =
      single(value, "Right") match
        case Some(inner) => b.decode(inner, s"$path.Right").map(Right(_))
        case None        => Left(DataError(path, "Right", describe(value)))
    def shape: DataShape = DataShape.Applied("Right", Vector(DataShape.Plain("Nothing"), b.shape))

  final class LeftData[A, B](a: Data[A]) extends Data[Left[A, B]]:
    def encode(value: Left[A, B]): Json                                  = one("Left", a.encode(value.value))
    def decode(value: Json, path: String): Either[DataError, Left[A, B]] =
      single(value, "Left") match
        case Some(inner) => a.decode(inner, s"$path.Left").map(Left(_))
        case None        => Left(DataError(path, "Left", describe(value)))
    def shape: DataShape = DataShape.Applied("Left", Vector(a.shape, DataShape.Plain("Nothing")))
