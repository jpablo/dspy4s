package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveType, Reflect, Schema}

import scala.annotation.tailrec

/** Helpers that read a zio-blocks `Reflect`. The [[TypeMarker]] of core and the codec of signatures use them. */
private[dspy4s] object Reflects:

  /** The reflect behind a deferred reflect. zio-blocks defers the reflect of a field with a default value, and of a
    * field whose type can be recursive.
    */
  def withoutDeferred[F[_, _], A](reflect: Reflect[F, A]): Reflect[F, A] =
    reflect match
      case deferred: Reflect.Deferred[F, A] => withoutDeferred(deferred.value)
      case other                            => other

  /** The case names of an enum with no parameters, in the order of the cases. A nested sealed trait gives the names of
    * its leaf cases, as the JSON schema of zio-blocks shows them. Empty for each other type.
    */
  def caseNames(reflect: Reflect[?, ?]): Vector[String] =
    withoutDeferred(reflect) match
      case variant: Reflect.Variant[?, ?] if variant.isEnumeration =>
        variant.cases.toVector.flatMap(term =>
          if term.value.isVariant then caseNames(term.value) else Vector(term.name)
        )
      case _ => Vector.empty

  /** The reflect of the element of an `Option`, also behind a wrapper: a variant with a `None` case (an empty record)
    * and a `Some` case, whose record has one field with the name `value`. `None` for each other reflect.
    */
  def optionElement[F[_, _]](reflect: Reflect[F, ?]): Option[Reflect[F, ?]] =
    val target: Reflect[F, ?] = withoutDeferred(reflect)
    target match
      case wrapper: Reflect.Wrapper[F, ?, ?]                         => optionElement(wrapper.wrapped)
      case variant: Reflect.Variant[F, ?] if variant.cases.size == 2 =>
        val hasNone = variant.caseByName("None").exists(_.value.asRecord.exists(_.fields.isEmpty))
        val element = variant.caseByName("Some").flatMap(_.value.asRecord).filter(_.fields.size == 1)
          .flatMap(_.fieldByName("value")).map(_.value)
        if hasNone then element else None
      case _ => None

  /** `true` for a primitive whose JSON schema is a text with a form of its own: a time value, a `UUID`, or a
    * `Currency`.
    */
  def hasTextForm(primitive: PrimitiveType[?]): Boolean =
    primitive match
      case _: PrimitiveType.String | _: PrimitiveType.Char | _: PrimitiveType.Boolean | _: PrimitiveType.Byte |
          _: PrimitiveType.Short | _: PrimitiveType.Int | _: PrimitiveType.Long | _: PrimitiveType.BigInt |
          _: PrimitiveType.Float | _: PrimitiveType.Double | _: PrimitiveType.BigDecimal |
          _: PrimitiveType.Unit.type => false
      case _ => true

  /** The wire form of the typed value `value` of `A`: the JSON shape that the schema of the model shows
    * ([[wireValue]]). Each boundary from a typed value to a `DynamicValue` uses this function, so a value has one form
    * after that boundary.
    */
  def wireOf[A](value: A)(using schema: Schema[A]): DynamicValue =
    wireValue(schema.toDynamicValue(value), schema.reflect)

  /** The wire form of `value`, a value that `Schema.toDynamicValue` gave for `reflect`:
    *   - A deferred reflect and a wrapper: the wire form of the value behind it.
    *   - A framework type with a [[TypeMarker]] stays as it is, because its readers use `Schema.fromDynamicValue`.
    *   - A tuple is a sequence of the wire form of each field, and each other record has each field in wire form.
    *   - A sequence has each element in wire form. A map has each key and each value in wire form. When each key is
    *     then a primitive, the map is a record with text keys, because a JSON object has text keys, and the decode
    *     coerces each key. A key that is a tuple or a record keeps the map, which has no JSON text that can be read.
    *   - An `Option`: `Some(x)` is the wire form of `x`, and `None` is a null. An `Option` directly in an `Option`
    *     keeps the case of the outer one, so `Some(None)` is not `None`.
    *   - An enum is the text of its leaf case name.
    *   - Each other variant is a record with one field: the case name, with the wire form of the value of the case. A
    *     case with no fields is `{"Dot": {}}`, and a nested sealed trait keeps its path: `{"Animal": {"Cat": {...}}}`.
    *   - A primitive with a text form ([[hasTextForm]]) is a text. Each other primitive stays.
    *
    * A value that does not agree with `reflect` stays as it is.
    */
  def wireValue(value: DynamicValue, reflect: Reflect[?, ?]): DynamicValue =
    val target: Reflect[?, ?] = withoutDeferred(reflect)
    if TypeMarker.of(target).isDefined then value
    else
      (value, target) match
        case (_, wrapper: Reflect.Wrapper[?, ?, ?])                   => wireValue(value, wrapper.wrapped)
        case (DynamicValue.Record(fields), rec: Reflect.Record[?, ?]) =>
          val wire = wireFields(fields, rec)
          if rec.typeId.isTuple then DynamicValue.Sequence(wire.map(_._2)) else DynamicValue.Record(wire)
        case (DynamicValue.Sequence(items), seq: Reflect.Sequence[?, ?, ?]) =>
          DynamicValue.Sequence(items.map(wireValue(_, seq.element)))
        case (DynamicValue.Map(entries), map: Reflect.Map[?, ?, ?, ?]) =>
          val wire = entries.map((key, item) => wireValue(key, map.key) -> wireValue(item, map.value))
          if wire.iterator.forall((key, _) => isPrimitive(key)) then
            DynamicValue.Record(wire.map((key, item) => DynamicValues.renderText(key) -> item))
          else DynamicValue.Map(wire)
        case (variant: DynamicValue.Variant, cases: Reflect.Variant[?, ?]) => wireVariant(variant, cases)
        case (_: DynamicValue.Primitive, primitive: Reflect.Primitive[?, ?])
            if hasTextForm(primitive.primitiveType) => DynamicValues.string(DynamicValues.renderText(value))
        case _ => value

  /** The record `value` of `reflect` with each field in wire form. It stays a record also for a tuple: this is the root
    * of a `Shape`, whose fields are the fields of a signature. A framework type at the root stays as it is.
    */
  def wireRecord(value: DynamicValue.Record, reflect: Reflect[?, ?]): DynamicValue.Record =
    val target: Reflect[?, ?] = withoutDeferred(reflect)
    if TypeMarker.of(target).isDefined then value
    else
      target match
        case wrapper: Reflect.Wrapper[?, ?, ?] => wireRecord(value, wrapper.wrapped)
        case rec: Reflect.Record[?, ?]         => DynamicValue.Record(wireFields(value.fields, rec))
        case _                                 => value

  private def isPrimitive(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Primitive => true
      case _                         => false

  private def wireFields(
      fields: Chunk[(String, DynamicValue)],
      target: Reflect.Record[?, ?]
  ): Chunk[(String, DynamicValue)] =
    fields.map((name, item) => name -> target.fieldByName(name).fold(item)(term => wireValue(item, term.value)))

  private def wireVariant(value: DynamicValue.Variant, target: Reflect.Variant[?, ?]): DynamicValue =
    optionElement(target) match
      case Some(element)                => wireOption(value, element)
      case None if target.isEnumeration => DynamicValues.string(leafName(value))
      case None                         =>
        val name = value.caseNameValue
        val item = target.caseByName(name).fold(value.value)(term => wireValue(value.value, term.value))
        DynamicValue.Record(Chunk.single(name -> item))

  /** The wire form of the `Option` value `value`, whose element has the reflect `element`. */
  private def wireOption(value: DynamicValue.Variant, element: Reflect[?, ?]): DynamicValue =
    val nested = optionElement(element).isDefined
    (value.caseNameValue, value.value) match
      case ("Some", DynamicValue.Record(fields)) =>
        val item = fields.headOption.fold[DynamicValue](value)((_, inner) => wireValue(inner, element))
        if nested then DynamicValue.Variant("Some", DynamicValue.Record(Chunk.single("value" -> item))) else item
      case _ => if nested then value else DynamicValue.Null

  /** The case name at the end of the path of the cases of `value`. */
  @tailrec
  private def leafName(value: DynamicValue.Variant): String =
    value.value match
      case inner: DynamicValue.Variant => leafName(inner)
      case _                           => value.caseNameValue
