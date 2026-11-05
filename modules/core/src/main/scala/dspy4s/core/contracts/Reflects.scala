package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Modifier, NameMapper, PrimitiveType, Reflect, Schema, Term}

import scala.annotation.tailrec

/** Helpers that read a zio-blocks `Reflect`: the name rules of an annotation, and the wire form of a typed value. The
  * [[TypeMarker]] of core and the codec of signatures use them.
  */
private[dspy4s] object Reflects:

  /** The reflect behind a deferred reflect. zio-blocks defers the reflect of a field with a default value, and of a
    * field whose type can be recursive.
    */
  def withoutDeferred[F[_, _], A](reflect: Reflect[F, A]): Reflect[F, A] =
    reflect match
      case deferred: Reflect.Deferred[F, A] => withoutDeferred(deferred.value)
      case other                            => other

  /** The wire names of the leaf cases of an enum with no parameters, in the order of the cases, as the JSON schema of
    * zio-blocks shows them ([[caseWireName]]). Empty for each other type.
    */
  def caseNames(reflect: Reflect[?, ?]): Vector[String] =
    withoutDeferred(reflect) match
      case variant: Reflect.Variant[?, ?] if variant.isEnumeration => leafCases(variant).map(caseWireName(variant, _))
      case _                                                       => Vector.empty

  // ── The names of the JSON codec of zio-blocks ─────────────────────────────────────────────────────────────────────
  // The schema of a model comes from that codec (`JsonCodecDeriver`), so these rules are its rules. This one place holds
  // the name rules for the field specs, the wire form, and the decode. The encode of a nested sealed trait with fields
  // keeps its path, and the schema shows only the leaf name, so the decode reads the two forms.

  /** The wire name of the field `term` of `record`: its first `Modifier.rename`, else the `Modifier.fieldNaming` of
    * `record` applied to the name in the code. An unknown naming strategy throws an `IllegalArgumentException`, as in
    * the codec.
    */
  def fieldWireName(record: Reflect.Record[?, ?], term: Term[?, ?, ?]): String =
    renameOf(term).getOrElse(
      record.modifiers.collectFirst { case Modifier.fieldNaming(strategy) =>
        NameMapper.fromString(strategy)(term.name)
      }
        .getOrElse(term.name)
    )

  /** The wire name of the case `term` of the variant `root`, or of a variant nested in `root`: its first
    * `Modifier.rename`, else the `Modifier.caseNaming` of `root` applied to the name in the code. Only the outer sealed
    * trait counts: the codec ignores the `caseNaming` of a nested sealed trait, because the outer one derives each leaf
    * case. An unknown naming strategy throws an `IllegalArgumentException`, as in the codec.
    */
  def caseWireName(root: Reflect.Variant[?, ?], term: Term[?, ?, ?]): String =
    renameOf(term).getOrElse(
      root.modifiers.collectFirst { case Modifier.caseNaming(strategy) => NameMapper.fromString(strategy)(term.name) }
        .getOrElse(term.name)
    )

  /** The other names that the decode reads for the field or the case `term` (`Modifier.alias`). */
  def aliasesOf(term: Term[?, ?, ?]): Vector[String] =
    term.modifiers.collect { case Modifier.alias(name) => name }.toVector

  /** The names that the decode reads for the field `term` of `record`, in the order in which they win: the wire name,
    * each alias, and the name in the code. A key belongs to one field ([[fieldOfKey]]), so a name of `term` that
    * belongs to another field is not in the result.
    */
  def fieldNames(record: Reflect.Record[?, ?], term: Term[?, ?, ?]): Vector[String] =
    (fieldWireName(record, term) +: aliasesOf(term) :+ term.name).distinct
      .filter(fieldOfKey(record, _).exists(_.name == term.name))

  /** The field of `record` that the key `key` belongs to: the first field with that wire name, else with that alias,
    * else with that name in the code. A transient field has no key.
    */
  private def fieldOfKey(record: Reflect.Record[?, ?], key: String): Option[Term[?, ?, ?]] =
    val fields = record.fields.filterNot(isTransient)
    fields.find(fieldWireName(record, _) == key)
      .orElse(fields.find(aliasesOf(_).contains(key)))
      .orElse(fields.find(_.name == key))

  /** The tests that find the case that the key `key` names, with the wire names of `root` ([[caseWireName]]), in the
    * order in which they win: the wire name, an alias, and the name in the code. The decode tries a test on each level
    * of `root` before the next test, so a key belongs to one case, also when the case is in a nested sealed trait.
    */
  def caseTests(root: Reflect.Variant[?, ?], key: String): Vector[Term[?, ?, ?] => Boolean] =
    Vector(caseWireName(root, _) == key, aliasesOf(_).contains(key), _.name == key)

  /** `true` for a field with `Modifier.transient`: it is not in the schema and not in the encode, and the decode gives
    * its default.
    */
  def isTransient(term: Term[?, ?, ?]): Boolean =
    term.modifiers.exists {
      case _: Modifier.transient => true
      case _                     => false
    }

  /** The discriminator of `variant`, when its wire form is a flat record: `{"kind": "Circle", "radius": 1}`. The codec
    * reads the `Modifier.discriminator` of the outer sealed trait only. It has no effect on an enum, which is a text,
    * or on a sealed trait with a case that is not a record, for example a transform, which keeps a case name as a key.
    * The port adds one rule: a case with a field with the wire name of the discriminator also keeps a case name as a
    * key, because a JSON object cannot hold the two values. The schema then shows that form too.
    */
  def discriminatorOf(variant: Reflect.Variant[?, ?]): Option[String] =
    variant.modifiers.collectFirst { case Modifier.discriminator(name) => name }.filter { name =>
      !variant.isEnumeration && leafCases(variant).forall { term =>
        withoutDeferred(term.value) match
          case record: Reflect.Record[?, ?] =>
            !record.fields.exists(field => !isTransient(field) && fieldWireName(record, field) == name)
          case _ => false
      }
    }

  private def renameOf(term: Term[?, ?, ?]): Option[String] =
    term.modifiers.collectFirst { case Modifier.rename(name) => name }

  /** The cases of `variant` that are not a nested variant, in the order of the cases, with the leaf cases of each
    * nested variant in its place.
    */
  private def leafCases(variant: Reflect.Variant[?, ?]): Vector[Term[?, ?, ?]] =
    variant.cases.toVector.flatMap { term =>
      withoutDeferred(term.value) match
        case nested: Reflect.Variant[?, ?] => leafCases(nested)
        case _                             => Vector(term)
    }

  /** The parts of an `Option` or a `zio.blocks.maybe.Maybe`: the name of the case with no value, the name of the case
    * with a value, the name of the field of that case, and the reflect of its element.
    */
  final case class OptionForm[F[_, _]](emptyCase: String, valueCase: String, valueField: String, element: Reflect[F, ?])

  /** The parts of the `Option` or the `Maybe` behind `reflect`, also behind a wrapper. The type id finds them, as in
    * the JSON codec, so a sealed trait with the cases `None` and `Some` is no `Option`. `None` for each other reflect.
    */
  def optionForm[F[_, _]](reflect: Reflect[F, ?]): Option[OptionForm[F]] =
    val target: Reflect[F, ?] = withoutDeferred(reflect)
    target match
      case wrapper: Reflect.Wrapper[F, ?, ?]                                                   => optionForm(wrapper.wrapped)
      case variant: Reflect.Variant[F, ?] if variant.typeId.isOption || variant.typeId.isMaybe =>
        for
          empty <- variant.cases.find(_.value.asRecord.exists(_.fields.isEmpty))
          full  <- variant.cases.find(_.value.asRecord.exists(_.fields.nonEmpty))
          field <- full.value.asRecord.flatMap(_.fields.headOption)
        yield OptionForm(empty.name, full.name, field.name, field.value)
      case _ => None

  /** The reflect of the element of an `Option` or a `Maybe` ([[optionForm]]). `None` for each other reflect. */
  def optionElement[F[_, _]](reflect: Reflect[F, ?]): Option[Reflect[F, ?]] = optionForm(reflect).map(_.element)

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
    *   - A tuple is a sequence of the wire form of each field. Each other record has its wire names ([[fieldWireName]])
    *     with each field in wire form, and it has no transient or encode-transient field.
    *   - A sequence has each element in wire form. A map has each key and each value in wire form. When each key is
    *     then a primitive, the map is a record with text keys, because a JSON object has text keys, and the decode
    *     coerces each key. A key that is a tuple or a record keeps the map, which has no JSON text that can be read.
    *   - An `Option` or a `Maybe`: a value is the wire form of the value, and no value is a null. A value of an
    *     `Option` or a `Maybe` directly in another one is a `Some` variant, so `Some(None)` is not `None`.
    *   - An enum is the text of the wire name of its leaf case ([[caseWireName]]).
    *   - A variant with a discriminator ([[discriminatorOf]]) is a flat record: the discriminator with the wire name of
    *     the leaf case, and then the fields of the case. A case with no fields is `{"kind": "Dot"}`, and a nested
    *     sealed trait has no path.
    *   - Each other variant is a record with one field: the wire name of the case, with the wire form of the value of
    *     the case. A case with no fields is `{"Dot": {}}`, and a nested sealed trait keeps its path:
    *     `{"Animal": {"Cat": {...}}}`.
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
    Chunk.from(fields.iterator.flatMap { (name, item) =>
      target.fieldByName(name) match
        case Some(term) if isEncoded(term) => Some(fieldWireName(target, term) -> wireValue(item, term.value))
        case Some(_)                       => None
        case None                          => Some(name -> item)
    })

  /** `true` for a field with no `Modifier.transient` and no `Modifier.encodeTransient`. */
  private def isEncoded(term: Term[?, ?, ?]): Boolean =
    !term.modifiers.exists {
      case _: Modifier.transient | _: Modifier.encodeTransient => true
      case _                                                   => false
    }

  private def wireVariant(value: DynamicValue.Variant, target: Reflect.Variant[?, ?]): DynamicValue =
    optionForm(target) match
      case Some(form)                   => wireOption(value, form)
      case None if target.isEnumeration => wireLabel(value, target, target)
      case None                         => discriminatorOf(target).fold(wireKeyed(value, target, target))(wireFlat(value, target, _))

  /** The text of the wire name of the leaf case of the enum value `value` of `target`. `root` is the outer sealed
    * trait.
    */
  private def wireLabel(
      value : DynamicValue.Variant,
      target: Reflect.Variant[?, ?],
      root  : Reflect.Variant[?, ?]
  ): DynamicValue =
    leafOf(value, target).fold[DynamicValue](value)((term, _) => DynamicValues.string(caseWireName(root, term)))

  /** The flat record of the value `value` of a variant with the discriminator `key`. */
  private def wireFlat(value: DynamicValue.Variant, target: Reflect.Variant[?, ?], key: String): DynamicValue =
    leafOf(value, target).fold[DynamicValue](value) { (term, item) =>
      wireValue(item, term.value) match
        case DynamicValue.Record(fields) =>
          DynamicValue.Record((key -> DynamicValues.string(caseWireName(target, term))) +: fields)
        case _ => value
    }

  /** The record with one field of the value `value` of `target`: the wire name of the case, with its wire form. `root`
    * is the outer sealed trait. A nested sealed trait keeps its path, and a nested enum is its text.
    */
  private def wireKeyed(
      value : DynamicValue.Variant,
      target: Reflect.Variant[?, ?],
      root  : Reflect.Variant[?, ?]
  ): DynamicValue =
    target.caseByName(value.caseNameValue).fold[DynamicValue](value) { term =>
      val item = (withoutDeferred(term.value), value.value) match
        case (nested: Reflect.Variant[?, ?], inner: DynamicValue.Variant) =>
          if nested.isEnumeration then wireLabel(inner, nested, root) else wireKeyed(inner, nested, root)
        case (other, inner) => wireValue(inner, other)
      DynamicValue.Record(Chunk.single(caseWireName(root, term) -> item))
    }

  /** The wire form of the `Option` or `Maybe` value `value` with the parts `form`. A value of an `Option` or a `Maybe`
    * in another one is a `Some` variant, for a `Maybe` too: the JSON text reads only that variant as its element
    * ([[PythonText.unwrap]]), because `Present` and `Absent` can be the cases of an enum of the user.
    */
  private def wireOption(value: DynamicValue.Variant, form: OptionForm[?]): DynamicValue =
    value match
      case DynamicValue.Variant(name, DynamicValue.Record(fields)) if name == form.valueCase =>
        val item = fields.headOption.fold[DynamicValue](value)((_, inner) => wireValue(inner, form.element))
        if optionElement(form.element).isEmpty then item
        else DynamicValue.Variant("Some", DynamicValue.Record(Chunk.single("value" -> item)))
      case _ => DynamicValue.Null

  /** The leaf case at the end of the path of the cases of `value`, with the value of that case. `None` when a name of
    * the path is no case of `target`.
    */
  @tailrec
  private def leafOf(
      value : DynamicValue.Variant,
      target: Reflect.Variant[?, ?]
  ): Option[(Term[?, ?, ?], DynamicValue)] =
    target.caseByName(value.caseNameValue) match
      case None       => None
      case Some(term) => (withoutDeferred(term.value), value.value) match
          case (nested: Reflect.Variant[?, ?], inner: DynamicValue.Variant) => leafOf(inner, nested)
          case (_, item)                                                    => Some(term -> item)
