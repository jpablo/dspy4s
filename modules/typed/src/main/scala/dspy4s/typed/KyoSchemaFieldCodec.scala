package dspy4s.typed

import dspy4s.core.contracts.{DspyError, TypeRef, ValidationError}
import kyo.{Chunk, DecodeException, Result, Schema, Structure, UnknownVariantException}
import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

private[typed] final class KyoSchemaFieldCodec[A](
    schema: Schema[A],
    structure: Structure.Type,
    override val typeRef: TypeRef,
    override val metadata: Map[String, String]
) extends FieldCodec[A]:

  def decode(raw: Any): Either[DspyError, A] =
    val value = KyoSchemaFieldCodec.toStructure(raw, structure)
    KyoSchemaFieldCodec.toEither(Structure.decode[A](value)(using schema, summon))

  def encode(value: A): Any =
    KyoSchemaFieldCodec.fromStructure(Structure.encode[A](value)(using schema, summon))

private[typed] object KyoSchemaFieldCodec:

  inline def flatEnumSchema[A <: scala.reflect.Enum](using
      m: Mirror.SumOf[A]
  ): Schema[A] =
    val caseNames = summonEnumLabels[m.MirroredElemLabels]
    val cases = summonEnumCases[A, m.MirroredElemTypes]
    val byName = caseNames.zip(cases).toMap
    val byValue = cases.zip(caseNames).toMap
    Schema.init[A](
      writeFn = (value, writer) =>
        writer.string(byValue.getOrElse(value, value.toString)),
      readFn = reader =>
        val raw = reader.string()
        byName.getOrElse(
          raw,
          throw UnknownVariantException(caseNames, raw)(using reader.frame)
        )
    )

  def toStructure(value: Any): Structure.Value =
    value match
      case value: Structure.Value => value
      case null       => Structure.Value.Null
      case s: String  => Structure.Value.Str(s)
      case b: Boolean => Structure.Value.Bool(b)
      case i: Int     => Structure.Value.Integer(i.toLong)
      case l: Long    => Structure.Value.Integer(l)
      case s: Short   => Structure.Value.Integer(s.toLong)
      case b: Byte    => Structure.Value.Integer(b.toLong)
      case f: Float   => Structure.Value.Decimal(f.toDouble)
      case d: Double  => Structure.Value.Decimal(d)
      case bi: BigInt => Structure.Value.BigNum(BigDecimal(bi))
      case bd: BigDecimal => Structure.Value.BigNum(bd)
      case map: collection.Map[?, ?] =>
        val fields = map.iterator.collect {
          case (k: String, v) => k -> toStructure(v)
        }.toSeq
        Structure.Value.Record(Chunk.from(fields))
      case seq: Seq[?] =>
        Structure.Value.Sequence(Chunk.from(seq.map(toStructure)))
      case product: Product if product.productElementNames.nonEmpty =>
        val fields = product.productElementNames
          .zip(product.productIterator)
          .map { (name, field) => name -> toStructure(field) }
          .toSeq
        Structure.Value.Record(Chunk.from(fields))
      case other =>
        Structure.Value.Str(other.toString)

  def toStructure(value: Any, expected: Structure.Type): Structure.Value =
    value match
      case value: Structure.Value => value
      case null                   => Structure.Value.Null
      case _ =>
        expected match
          case Structure.Type.Primitive(kind, _) =>
            primitiveToStructure(value, kind)
          case Structure.Type.Product(_, _, _, fields) =>
            val rawFields: Map[String, Any] = value match
              case map: collection.Map[?, ?] =>
                map.iterator.collect { case (k: String, v) => k -> v }.toMap
              case product: Product if product.productElementNames.nonEmpty =>
                product.productElementNames.zip(product.productIterator).toMap[String, Any]
              case _ =>
                Map.empty[String, Any]
            Structure.Value.Record(Chunk.from(
              fields.flatMap(field =>
                rawFields.get(field.name).map(value =>
                  field.name -> toStructure(value, field.fieldType)
                )
              )
            ))
          case Structure.Type.Collection(_, _, elementType) =>
            value match
              case seq: Seq[?] =>
                Structure.Value.Sequence(Chunk.from(seq.map(toStructure(_, elementType))))
              case other =>
                toStructure(other)
          case Structure.Type.Mapping(_, _, keyType, valueType) =>
            value match
              case map: collection.Map[?, ?] =>
                Structure.Value.MapEntries(Chunk.from(
                  map.iterator.map { (k, v) =>
                    toStructure(k, keyType) -> toStructure(v, valueType)
                  }.toSeq
                ))
              case other =>
                toStructure(other)
          case Structure.Type.Optional(_, _, innerType) =>
            toStructure(value, innerType)
          case sum @ Structure.Type.Sum(_, _, _, variants, enumValues) =>
            value match
              case s: String if enumValues.contains(s) =>
                Structure.Value.Str(s)
              case map: collection.Map[?, ?] if map.size == 1 =>
                map.iterator.collectFirst { case (k: String, v) =>
                  val variantType = variants
                    .find(_.name == k)
                    .map(_.variantType)
                    .getOrElse(sum)
                  Structure.Value.VariantCase(k, toStructure(v, variantType))
                }.getOrElse(toStructure(value))
              case product: Product if product.productElementNames.nonEmpty =>
                toStructure(product)
              case other =>
                toStructure(other)

  def fromStructure(value: Structure.Value): Any =
    value match
      case Structure.Value.Record(fields) =>
        fields.map { (name, field) => name -> fromStructure(field) }.toMap
      case Structure.Value.VariantCase(name, value) =>
        Map(name -> fromStructure(value))
      case Structure.Value.Sequence(elements) =>
        elements.map(fromStructure).toList
      case Structure.Value.MapEntries(entries) =>
        entries.map { (k, v) => fromStructure(k) -> fromStructure(v) }.toMap
      case Structure.Value.Str(value)     => value
      case Structure.Value.Bool(value)    => value
      case Structure.Value.Integer(value) => value
      case Structure.Value.Decimal(value) => value
      case Structure.Value.BigNum(value)  => value
      case Structure.Value.Null           => null

  def fromStructure(value: Structure.Value, expected: Structure.Type): Any =
    (value, expected) match
      case (Structure.Value.Record(values), Structure.Type.Product(_, _, _, fields)) =>
        val byName = values.toMap
        fields.flatMap { field =>
          byName.get(field.name).map(field.name -> fromStructure(_, field.fieldType))
        }.toMap
      case (Structure.Value.Sequence(values), Structure.Type.Collection(_, _, elementType)) =>
        values.map(fromStructure(_, elementType)).toList
      case (Structure.Value.MapEntries(entries), Structure.Type.Mapping(_, _, keyType, valueType)) =>
        entries.map { (k, v) =>
          fromStructure(k, keyType) -> fromStructure(v, valueType)
        }.toMap
      case (Structure.Value.Null, Structure.Type.Optional(_, _, _)) =>
        null
      case (other, Structure.Type.Optional(_, _, innerType)) =>
        fromStructure(other, innerType)
      case (Structure.Value.VariantCase(name, value), Structure.Type.Sum(_, _, _, variants, _)) =>
        val variantType = variants
          .find(_.name == name)
          .map(_.variantType)
          .getOrElse(expected)
        Map(name -> fromStructure(value, variantType))
      case (other, Structure.Type.Primitive(kind, _)) =>
        primitiveFromStructure(other, kind)
      case (other, _) =>
        fromStructure(other)

  def toEither[A](result: Result[DecodeException, A]): Either[DspyError, A] =
    result.fold(
      onSuccess = value => Right(value),
      onFailure = err => Left(ValidationError(
        Option(err.getMessage).getOrElse(err.toString)
      )),
      onPanic = err => Left(ValidationError(
        s"panic during kyo-schema decode: ${err.getClass.getSimpleName}: ${err.getMessage}"
      ))
    )

  private def primitiveToStructure(
      value: Any,
      kind: Structure.PrimitiveKind
  ): Structure.Value =
    kind match
      case Structure.PrimitiveKind.String =>
        value match
          case s: String => Structure.Value.Str(s)
          case other     => Structure.Value.Str(other.toString)
      case Structure.PrimitiveKind.Char =>
        Structure.Value.Str(value.toString)
      case Structure.PrimitiveKind.Boolean =>
        value match
          case b: Boolean => Structure.Value.Bool(b)
          case s: String =>
            s.trim.toLowerCase match
              case "true"  => Structure.Value.Bool(true)
              case "false" => Structure.Value.Bool(false)
              case _       => Structure.Value.Str(s)
          case other => toStructure(other)
      case Structure.PrimitiveKind.Int | Structure.PrimitiveKind.Long |
          Structure.PrimitiveKind.Short | Structure.PrimitiveKind.Byte =>
        value match
          case i: Int   => Structure.Value.Integer(i.toLong)
          case l: Long  => Structure.Value.Integer(l)
          case s: Short => Structure.Value.Integer(s.toLong)
          case b: Byte  => Structure.Value.Integer(b.toLong)
          case bi: BigInt if bi.isValidLong => Structure.Value.Integer(bi.toLong)
          case s: String =>
            s.trim.toLongOption
              .map(Structure.Value.Integer(_))
              .getOrElse(Structure.Value.Str(s))
          case other => Structure.Value.Str(other.toString)
      case Structure.PrimitiveKind.BigInt =>
        value match
          case i: BigInt => Structure.Value.BigNum(BigDecimal(i))
          case i: Int    => Structure.Value.BigNum(BigDecimal(i))
          case l: Long   => Structure.Value.BigNum(BigDecimal(l))
          case s: String =>
            scala.util.Try(BigDecimal(s.trim)).toOption
              .map(Structure.Value.BigNum(_))
              .getOrElse(Structure.Value.Str(s))
          case other => toStructure(other)
      case Structure.PrimitiveKind.Float | Structure.PrimitiveKind.Double =>
        value match
          case d: Double => Structure.Value.Decimal(d)
          case f: Float  => Structure.Value.Decimal(f.toDouble)
          case i: Int    => Structure.Value.Decimal(i.toDouble)
          case l: Long   => Structure.Value.Decimal(l.toDouble)
          case s: String =>
            s.trim.toDoubleOption
              .map(Structure.Value.Decimal(_))
              .getOrElse(Structure.Value.Str(s))
          case other => toStructure(other)
      case Structure.PrimitiveKind.BigDecimal =>
        value match
          case bd: BigDecimal => Structure.Value.BigNum(bd)
          case d: Double      => Structure.Value.BigNum(BigDecimal(d))
          case f: Float       => Structure.Value.BigNum(BigDecimal.decimal(f.toDouble))
          case i: Int         => Structure.Value.BigNum(BigDecimal(i))
          case l: Long        => Structure.Value.BigNum(BigDecimal(l))
          case s: String =>
            scala.util.Try(BigDecimal(s.trim)).toOption
              .map(Structure.Value.BigNum(_))
              .getOrElse(Structure.Value.Str(s))
          case other => toStructure(other)
      case Structure.PrimitiveKind.Unit =>
        Structure.Value.Null

  private def primitiveFromStructure(
      value: Structure.Value,
      kind: Structure.PrimitiveKind
  ): Any =
    (value, kind) match
      case (Structure.Value.Integer(v), Structure.PrimitiveKind.Int)    => v.toInt
      case (Structure.Value.Integer(v), Structure.PrimitiveKind.Long)   => v
      case (Structure.Value.Integer(v), Structure.PrimitiveKind.Short)  => v.toShort
      case (Structure.Value.Integer(v), Structure.PrimitiveKind.Byte)   => v.toByte
      case (Structure.Value.BigNum(v), Structure.PrimitiveKind.BigInt)  => v.toBigInt
      case (Structure.Value.Decimal(v), Structure.PrimitiveKind.Float)  => v.toFloat
      case (Structure.Value.Decimal(v), Structure.PrimitiveKind.Double) => v
      case (Structure.Value.BigNum(v), Structure.PrimitiveKind.BigDecimal) => v
      case (Structure.Value.Str(v), Structure.PrimitiveKind.String)     => v
      case (Structure.Value.Str(v), Structure.PrimitiveKind.Char)       =>
        if v.nonEmpty then v.charAt(0) else null
      case (Structure.Value.Bool(v), Structure.PrimitiveKind.Boolean)   => v
      case (Structure.Value.Null, Structure.PrimitiveKind.Unit)         => ()
      case (other, _)                                                   => fromStructure(other)

  private inline def summonEnumLabels[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t)   => constValue[h & String] :: summonEnumLabels[t]

  private inline def summonEnumCases[A, T <: Tuple]: List[A] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (h *: t) =>
        val m = summonInline[Mirror.ProductOf[h & A]]
        val v = m.fromProduct(EmptyTuple).asInstanceOf[A]
        v :: summonEnumCases[A, t]
