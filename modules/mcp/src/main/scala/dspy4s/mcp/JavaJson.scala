package dspy4s.mcp

import dspy4s.core.contracts.DynamicValues
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

import java.util.Objects
import scala.jdk.CollectionConverters.*

/** The conversion between the JSON values of the MCP Java SDK and [[DynamicValue]] (upgrade item I10).
  *
  * The SDK holds a JSON value as a Java object: a `java.util.Map` for an object, a `java.util.List` for an array, a
  * `String`, a `Boolean`, a `Number`, or `null`. The conversion keeps the key order of each object.
  */
private[mcp] object JavaJson:

  /** The [[DynamicValue]] of a JSON value of the SDK. An integral number becomes an `Int` when it fits, else a `Long`
    * (a `BigInt` when it does not fit a `Long`). An other number becomes a `Double`. A value that is not JSON becomes
    * its text.
    */
  def toDynamic(value: Any): DynamicValue =
    if Objects.isNull(value) then DynamicValue.Null
    else
      value match
        case text: String                   => DynamicValue.Primitive(PrimitiveValue.String(text))
        case flag: java.lang.Boolean        => DynamicValue.Primitive(PrimitiveValue.Boolean(flag.booleanValue))
        case number: java.lang.Number       => DynamicValue.Primitive(fromNumber(number))
        case character: java.lang.Character => DynamicValue.Primitive(PrimitiveValue.String(character.toString))
        case map: java.util.Map[?, ?]       => toRecord(map)
        case items: java.lang.Iterable[?]   => DynamicValue.Sequence(Chunk.from(items.asScala.map(toDynamic)))
        case items: Array[?]                => DynamicValue.Sequence(Chunk.from(items.map(toDynamic)))
        case other                          => DynamicValue.Primitive(PrimitiveValue.String(String.valueOf(other)))

  /** The record of a JSON object of the SDK, with the keys in the order of the map. */
  def toRecord(map: java.util.Map[?, ?]): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(map.entrySet.asScala.toVector.map { entry =>
      String.valueOf(entry.getKey) -> toDynamic(entry.getValue)
    }))

  /** The JSON object of a record, as the SDK takes it (for example the arguments of a tool call). */
  def toJavaMap(record: DynamicValue.Record): java.util.Map[String, AnyRef] =
    val map = new java.util.LinkedHashMap[String, AnyRef]()
    record.fields.foreach((key, value) => map.put(key, toJava(value)))
    map

  /** The JSON value of the SDK for a [[DynamicValue]]. A map key that is not a string becomes its text. A variant
    * becomes an object with one key, the name of the case. A primitive that has no JSON type (for example a date)
    * becomes its text.
    */
  def toJava(value: DynamicValue): AnyRef =
    value match
      case DynamicValue.Primitive(primitive) => fromPrimitive(primitive)
      case record: DynamicValue.Record       => toJavaMap(record)
      case DynamicValue.Sequence(elements)   => new java.util.ArrayList[AnyRef](elements.map(toJava).asJava)
      case DynamicValue.Map(entries)         =>
        val map = new java.util.LinkedHashMap[String, AnyRef]()
        entries.foreach((key, entry) => map.put(keyText(key), toJava(entry)))
        map
      case DynamicValue.Variant(caseName, inner) =>
        val map = new java.util.LinkedHashMap[String, AnyRef]()
        val _   = map.put(caseName, toJava(inner))
        map
      case _: DynamicValue.Null.type => null

  private def fromNumber(number: java.lang.Number): PrimitiveValue =
    number match
      case _: (java.lang.Byte | java.lang.Short | java.lang.Integer | java.lang.Long) => integral(number.longValue)
      case big: java.math.BigInteger                                                  =>
        if big.bitLength < 64 then integral(big.longValue) else PrimitiveValue.BigInt(BigInt(big))
      case _ => PrimitiveValue.Double(number.doubleValue)

  private def integral(value: Long): PrimitiveValue =
    if value.isValidInt then PrimitiveValue.Int(value.toInt) else PrimitiveValue.Long(value)

  private def fromPrimitive(primitive: PrimitiveValue): AnyRef =
    primitive match
      case PrimitiveValue.String(text)       => text
      case PrimitiveValue.Boolean(flag)      => java.lang.Boolean.valueOf(flag)
      case PrimitiveValue.Int(number)        => java.lang.Integer.valueOf(number)
      case PrimitiveValue.Long(number)       => java.lang.Long.valueOf(number)
      case PrimitiveValue.Short(number)      => java.lang.Integer.valueOf(number.toInt)
      case PrimitiveValue.Byte(number)       => java.lang.Integer.valueOf(number.toInt)
      case PrimitiveValue.Double(number)     => java.lang.Double.valueOf(number)
      case PrimitiveValue.Float(number)      => java.lang.Double.valueOf(number.toString.toDouble)
      case PrimitiveValue.BigInt(number)     => number.bigInteger
      case PrimitiveValue.BigDecimal(number) => number.bigDecimal
      case PrimitiveValue.Char(character)    => character.toString
      case _: PrimitiveValue.Unit.type       => new java.util.LinkedHashMap[String, AnyRef]()
      // A date, a time, a UUID, or a currency: each case class holds the one value.
      case other: Product if other.productArity == 1 => String.valueOf(other.productElement(0))
      case other                                     => String.valueOf(other)

  private def keyText(key: DynamicValue): String =
    DynamicValues.asString(key).getOrElse(DynamicValues.renderText(key))
