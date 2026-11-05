package dspy4s.signatures

import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  FieldSpec,
  LenientValues,
  PythonText,
  Reasoning,
  TypeMarker,
  TypeRef,
  ValidationError
}
import dspy4s.core.contracts.Reflects
import dspy4s.core.contracts.Reflects.{caseNames, withoutDeferred}
import zio.blocks.chunk.{Chunk, ChunkMap, NonEmptyChunk}
import zio.blocks.docs.Doc
import zio.blocks.schema.binding.{Binding, BindingType, HasBinding}
import zio.blocks.schema.derive.{Deriver, InstanceOverride}
import zio.blocks.schema.json.{
  Json,
  JsonCodec,
  JsonCodecDeriver,
  JsonReader,
  JsonSchema,
  JsonSchemaType,
  JsonWriter,
  SchemaType
}
import zio.blocks.schema.{
  DynamicOptic,
  DynamicValue,
  Lazy,
  Modifier,
  PrimitiveType,
  PrimitiveValue,
  Reflect,
  ReflectTransformer,
  Schema,
  Term
}
import zio.blocks.typeid.{Owner, TypeId}

/** The bridge between a zio-blocks `Schema[A]` and a dspy4s `Shape[A]`.
  *
  *   - [[derivedFromZioSchema]] makes the `Shape[A]` of a `Schema[A]`. The encode gives the wire form
  *     ([[Reflects.wireValue]]). The decode first coerces the record (for example, the LM text `"true"` or `"42"`
  *     becomes a primitive of the target type), and then uses `Schema.fromDynamicValue`.
  *   - [[fieldSpecsFromReflect]] gives the `FieldSpec` list of a `Reflect.Record`, with the wire names.
  *   - [[normalize]] coerces a `DynamicValue` to a target `Reflect`.
  *   - [[jsonSchemaForModel]] gives the JSON schema of a `Schema[A]` for a prompt, with no `id` in a `ToolCall`, with
  *     no alias, with `$defs` for a recursive type, and with the null and `required` rules of the decode.
  *
  * One place, [[Reflects]], holds the name rules of an annotation of zio-blocks. The encode of a nested sealed trait
  * with fields keeps its path, `{"inner_kind": {"deep_cat": ...}}`, and the schema shows only the leaf name. The decode
  * reads the two forms.
  *
  * The codec finds the framework types (`History`, `ToolCall`, and the decision types) by their marker ([[TypeMarker]])
  * in the modifiers of a reflect, not by their structure or their type id.
  *
  * The interchange format is `DynamicValue.Record` from end to end. The codec does not use a `Map[String, Any]`.
  */
private[signatures] object ZioSchemaCodec:

  /** Coerce a `DynamicValue` to the form that the target `Reflect` expects. A model writes the forms of the JSON schema
    * ([[jsonSchemaForModel]]), an encode gives the wire form ([[Reflects.wireValue]]), and `Schema.fromDynamicValue`
    * reads other forms. These are the coercions:
    *
    *   - A primitive: the lenient rules of [[LenientValues]] apply. For example, the LM text `"yes"` becomes
    *     `Primitive(Boolean(true))` when the target field is `Boolean`, and the texts `"$1,200"` and `"3.0"` become an
    *     `Int`. A text that only Java reads as a number (`"3d"`) stays a text, thus the decode fails.
    *   - A primitive with a text form, for example an `Instant` or a `UUID`: [[fromTextForm]] reads a text.
    *   - A variant: a text is a case with no fields, and a record with one field is the case with the name of that
    *     field ([[caseValue]]). `{"Circle": {"radius": 1}}` becomes `Variant("Circle", Record(radius))`. Thus
    *     `Schema.fromDynamicValue` can select the correct case. A variant with a discriminator
    *     ([[Reflects.discriminatorOf]]) first reads a record whose discriminator field is a text that names a case:
    *     `{"kind": "Circle", "radius": 1}`, with the other fields as the record of that case. A text or a record that
    *     names no case stays, and the decode gives the error.
    *   - A tuple: a sequence with one item for each field becomes a record with the fields `_1`, `_2`, and so on. A
    *     record with these fields also stays valid.
    *   - A transform (`Reflect.Wrapper`): the coercion goes to the value behind it.
    *
    * The decode reads three names for a field and for a case: the wire name, an alias, and the name in the code, in
    * this order ([[Reflects.fieldNames]] and [[Reflects.caseTests]]). A key belongs to one field or one case, so a name
    * does not read a key that names another field in a higher place of this order. The result has the name in the code,
    * because `Schema.fromDynamicValue` reads that. When a record has two names of one field, the first in this order
    * wins.
    *
    * The coercion goes into each field of a record that the target has. A key that names no field goes away, and the
    * value of a transient field too ([[mapRecordFields]]). The coercion also goes into each element of a sequence and
    * each entry of a map. Each other value, and a value that does not agree with its target, stays as it is.
    *
    * At each level, a record gets the default value of each field of the target that it does not have, `None` or
    * `Absent` for each `Option` or `Maybe` field with no default, and an empty collection for a transient collection
    * with no default, as the codec does. A required field that is absent stays absent, thus the decode fails.
    */
  def normalize(dv: DynamicValue, target: Reflect.Bound[?]): DynamicValue =
    // zio-blocks defers the reflect of some nested fields. The coercion needs the reflect behind it.
    (dv, withoutDeferred(target)) match
      // This rule comes before the rule of a null, because an `Option` behind a wrapper reads a null.
      case (value, wrapper: Reflect.Wrapper[Binding, ?, ?])                     => normalize(value, wrapper.wrapped)
      case (prim @ DynamicValue.Primitive(_), p: Reflect.Primitive[Binding, ?]) =>
        fromTextForm(LenientValues.coerce(prim, p.primitiveType), p)
      // An `Option` field: put a bare value, a null, or a text into its two cases before the general rules below.
      case (value, OptionFormOf(form))                                                        => normalizeOption(value, form)
      case (_: DynamicValue.Null.type, _)                                                     => DynamicValue.Null
      case (DynamicValue.Primitive(PrimitiveValue.String(s)), v: Reflect.Variant[Binding, ?]) =>
        variantFromText(s, v, v)
      case (rec: DynamicValue.Record, v: Reflect.Variant[Binding, ?])               => variantFromRecord(rec, v, v)
      case (rec: DynamicValue.Record, recTarget: Reflect.Record[Binding, ?])        => mapRecordFields(rec, recTarget)
      case (seq: DynamicValue.Sequence, recTarget: Reflect.Record[Binding, ?])      => tupleFromSequence(seq, recTarget)
      case (seq: DynamicValue.Sequence, seqTarget: Reflect.Sequence[Binding, ?, ?]) =>
        DynamicValue.Sequence(seq.elements.map(normalize(_, seqTarget.element)))
      case (m: DynamicValue.Map, mapTarget: Reflect.Map[Binding, ?, ?, ?]) =>
        DynamicValue.Map(m.entries.map((k, v) => normalize(k, mapTarget.key) -> normalize(v, mapTarget.value)))
      case (rec: DynamicValue.Record, mapTarget: Reflect.Map[Binding, ?, ?, ?]) =>
        // dspy4s keeps a map with text keys as a `DynamicValue.Record` (see `DynamicValues.fromAny`), but the zio-blocks
        // `Schema[Map[K, V]]` reads a `DynamicValue.Map`. Each field becomes an entry.
        DynamicValue.Map(rec.fields.map { (k, v) =>
          normalize(DynamicValue.Primitive(PrimitiveValue.String(k)), mapTarget.key) -> normalize(v, mapTarget.value)
        })
      case _ => dv

  /** The case `text` of `variant`, with no fields of its own. A text that names no case gets the error of an unknown
    * case in the decode. `root` is the outer sealed trait, whose names apply to its nested sealed traits.
    */
  private def variantFromText(
      text   : String,
      variant: Reflect.Variant[Binding, ?],
      root   : Reflect.Variant[Binding, ?]
  ): DynamicValue =
    caseValue(variant, root, text, DynamicValue.Record.empty)
      .getOrElse(DynamicValue.Variant(text, DynamicValue.Record.empty))

  /** The case of `rec`, in the forms of the JSON schema: a discriminator field that names a case, with the other fields
    * as the record of that case ([[discriminatedCase]]), or the case with the name of the one field of `rec`
    * (`{"Circle": {"radius": 1}}`). Each other record stays, and the decode gives the error.
    */
  private def variantFromRecord(
      rec    : DynamicValue.Record,
      variant: Reflect.Variant[Binding, ?],
      root   : Reflect.Variant[Binding, ?]
  ): DynamicValue =
    Reflects.discriminatorOf(root).flatMap(discriminatedCase(rec, _, variant, root))
      .orElse(
        rec.fields.headOption
          .filter(_ => rec.fields.length == 1)
          .flatMap((name, value) => caseValue(variant, root, name, value))
      )
      .getOrElse(rec)

  /** The case that the field `key` of `rec` names, with the other fields as its record. `None` when the field is not a
    * text that names a case.
    */
  private def discriminatedCase(
      rec    : DynamicValue.Record,
      key    : String,
      variant: Reflect.Variant[Binding, ?],
      root   : Reflect.Variant[Binding, ?]
  ): Option[DynamicValue] =
    rec.fields.collectFirst { case (`key`, DynamicValue.Primitive(PrimitiveValue.String(name))) => name }.flatMap {
      name =>
        caseValue(variant, root, name, DynamicValue.Record(rec.fields.filterNot(_._1 == key)))
    }

  /** The record of the tuple `target` from a sequence with one item for each field, as the JSON schema shows it:
    * `[1, "a"]`. Each other sequence stays, and the decode gives the error. A record that is not a tuple also takes no
    * sequence.
    */
  private def tupleFromSequence(seq: DynamicValue.Sequence, target: Reflect.Record[Binding, ?]): DynamicValue =
    if target.typeId.isTuple && seq.elements.length == target.fields.length then
      mapRecordFields(DynamicValue.Record(Chunk.from(target.fields.map(_.name).zip(seq.elements))), target)
    else seq

  /** The `Variant` value of the case of `variant` that `name` names ([[Reflects.caseTests]]), with `value` in the form
    * of that case. The wire name of a case on each level wins over an alias, and an alias wins over the name in the
    * code. For each of the three, a case of `variant` itself wins over a case of a nested variant. The JSON schema
    * shows the flat names of the leaf cases of a nested sealed trait, and `Schema.fromDynamicValue` reads the path of
    * the cases with the names in the code. Thus a leaf case gets its path: `Variant("Animal", Variant("Cat", ...))`.
    * When two leaf cases have one name, the first wins. `root` is the outer sealed trait, whose names apply to its
    * nested sealed traits, as in the codec. `None` when `variant` has no such case.
    */
  private def caseValue(
      variant: Reflect.Variant[Binding, ?],
      root   : Reflect.Variant[Binding, ?],
      name   : String,
      value  : DynamicValue
  ): Option[DynamicValue] =
    Reflects.caseTests(root, name).iterator.flatMap(caseValueBy(variant, root, _, value)).nextOption()

  /** The `Variant` value of the first case of `variant` for which `isCase` is true, or of a case of a nested variant.
    */
  private def caseValueBy(
      variant: Reflect.Variant[Binding, ?],
      root   : Reflect.Variant[Binding, ?],
      isCase : Term[?, ?, ?] => Boolean,
      value  : DynamicValue
  ): Option[DynamicValue] =
    variant.cases.find(isCase) match
      case Some(term) =>
        val inner = (value, unwrapped(term.value)) match
          case (DynamicValue.Primitive(PrimitiveValue.String(text)), nested: Reflect.Variant[Binding, ?]) =>
            variantFromText(text, nested, root)
          case (rec: DynamicValue.Record, nested: Reflect.Variant[Binding, ?]) => variantFromRecord(rec, nested, root)
          case _                                                               => normalize(value, term.value)
        Some(DynamicValue.Variant(term.name, inner))
      case None => variant.cases.iterator.flatMap { term =>
          unwrapped(term.value) match
            case nested: Reflect.Variant[Binding, ?] =>
              caseValueBy(nested, root, isCase, value).map(DynamicValue.Variant(term.name, _))
            case _ => None
        }.nextOption()

  /** `value` as a value of the primitive `target`, when `value` is still a text and `target` has a text form
    * ([[Reflects.hasTextForm]]). The JSON codec of the primitive reads the text, because the JSON schema of the
    * primitive comes from that codec. A model often writes a quoted text in the chat format, so one pair of double
    * quotes at the two ends goes first. A text that the codec does not read stays, and the decode gives the error.
    */
  private def fromTextForm[A](value: DynamicValue, target: Reflect.Primitive[Binding, A]): DynamicValue =
    value match
      case DynamicValue.Primitive(PrimitiveValue.String(text)) if Reflects.hasTextForm(target.primitiveType) =>
        val json = PythonText.json(DynamicValues.string(PythonText.quotedBody(text, '"').getOrElse(text)))
        Schema(target).jsonCodec.decode(json).fold(_ => value, target.toDynamicValue)
      case _ => value

  /** Coerce a value to the two cases that zio-blocks expects for an `Option` or a `Maybe` field: `Some` and `None`, or
    * `Present` and `Absent` (`form`). A value with no case goes through [[normalize]] for the element of `form` and
    * gets the usual coercion (for example, the text `"2399.0"` becomes a `Double`). A null is the case with no value,
    * and a null text of [[LenientValues.NullTexts]] too, when the element does not take that text ([[takesText]]). The
    * two typed cases also stay valid, and the value of the case gets the coercion. The wire form of an `Option` or a
    * `Maybe` in another one is a `Some` variant, also for a `Maybe` ([[Reflects.wireValue]]). A variant with another
    * case name is the element, for example a case of a sealed trait.
    */
  private def normalizeOption(value: DynamicValue, form: Reflects.OptionForm[Binding]): DynamicValue =
    def some(v: DynamicValue): DynamicValue =
      DynamicValue.Variant(
        form.valueCase,
        DynamicValue.Record(Chunk.single(form.valueField -> normalize(v, form.element)))
      )
    value match
      case _: DynamicValue.Null.type                                                                           => noValue(form)
      case DynamicValue.Variant(name, _) if name == form.emptyCase                                             => noValue(form)
      case DynamicValue.Variant(name, DynamicValue.Record(fields)) if name == form.valueCase || name == "Some" =>
        fields.headOption.fold(value)((_, inner) => some(inner))
      case DynamicValue.Primitive(PrimitiveValue.String(s))
          if LenientValues.isNullText(s) && !takesText(form.element, s) => noValue(form)
      case other => some(other)

  /** `true` when `reflect` reads the text `text` as a value: a text, or a variant with a case of that name. The wire
    * form of `Some("none")` is the text `none`, and upstream `parse_value` keeps each text for an `Optional[str]`.
    */
  private def takesText(reflect: Reflect.Bound[?], text: String): Boolean =
    reflect match
      case OptionOf(element) => takesText(element, text)
      case _                 => unwrapped(reflect) match
          case wrapper: Reflect.Wrapper[Binding, ?, ?] => takesText(wrapper.wrapped, text)
          case variant: Reflect.Variant[Binding, ?]    =>
            caseValue(variant, variant, text, DynamicValue.Record.empty).isDefined
          case prim: Reflect.Primitive[Binding, ?] => prim.primitiveType match
              case _: PrimitiveType.String => true
              case _                       => false
          case _ => false

  /** The case with no value of an `Option` or a `Maybe`, in the Variant shape that zio-blocks expects. */
  private def noValue(form: Reflects.OptionForm[Binding]): DynamicValue =
    DynamicValue.Variant(form.emptyCase, DynamicValue.Record.empty)

  /** The record of `target` with the names in the code, from `rec`. A field takes the value of the first of its names
    * that `rec` has ([[Reflects.fieldNames]]). A key that names no field goes away, because `Schema.fromDynamicValue`
    * ignores it, and so does the value of a transient field.
    *
    * The wire does not require a field with a default value or an `Option` field. A record with no such key gets the
    * default value. For an `Option` field with no default, it gets the case with no value, the same as an explicit null
    * or "none". A transient collection with no default is empty, as in the codec. The macro of zio-blocks requires a
    * default for each other transient field.
    */
  private def mapRecordFields(rec: DynamicValue.Record, target: Reflect.Record[Binding, ?]): DynamicValue.Record =
    DynamicValue.Record(Chunk.from(target.fields.flatMap { term =>
      val value =
        if Reflects.isTransient(term) then None
        else Reflects.fieldNames(target, term).iterator.flatMap(DynamicValues.recordGet(rec, _)).nextOption()
      value.map(normalize(_, term.value))
        .orElse(wireDefault(term.value).map(normalize(_, term.value)))
        .orElse(OptionFormOf.unapply(term.value).map(noValue))
        .orElse(Option.when(Reflects.isTransient(term))(emptyCollection(term.value)).flatten)
        .map(term.name -> _)
    }))

  /** The empty value of a sequence or a map. `None` for each other reflect. */
  private def emptyCollection(reflect: Reflect.Bound[?]): Option[DynamicValue] =
    unwrapped(reflect) match
      case _: Reflect.Sequence[Binding, ?, ?] => Some(DynamicValue.Sequence(Chunk.empty))
      case _: Reflect.Map[Binding, ?, ?, ?]   => Some(DynamicValue.Map(Chunk.empty))
      case _                                  => None

  /** The default value of a field in the wire form ([[Reflects.wireValue]]), from the reflect of the field: zio-blocks
    * keeps the default value of a case-class parameter there. The wire form of an `Option` is its element, thus a
    * default `Some(x)` is `x`, and a default `None` is no default (an absent `Option` field is `None`).
    */
  private def wireDefault(reflect: Reflect.Bound[?]): Option[DynamicValue] =
    storedDefault(reflect).map(Reflects.wireValue(_, reflect)).filterNot(default =>
      isOption(reflect) && DynamicValues.isNull(PythonText.unwrap(default))
    )

  private def storedDefault[A](reflect: Reflect.Bound[A]): Option[DynamicValue] =
    reflect.getDefaultValue.map(reflect.toDynamicValue)

  /** The field specs of the record behind `reflect` ([[recordOf]]), in the order of its fields, with no input or output
    * role. Each spec has the wire name of its field ([[Reflects.fieldWireName]]), and a transient field has no spec.
    * Empty for each other reflect.
    */
  def fieldSpecsFromReflect(reflect: Reflect.Bound[?]): Vector[FieldSpec] =
    recordOf(reflect).fold(Vector.empty)(record =>
      record.fields.toVector.filterNot(Reflects.isTransient).map(term =>
        fieldSpecOf(Reflects.fieldWireName(record, term), term.value)
      )
    )

  /** The record behind `reflect`: `reflect` itself, or the record behind a deferred reflect or a wrapper. */
  private def recordOf(reflect: Reflect.Bound[?]): Option[Reflect.Record[Binding, ?]] =
    unwrapped(reflect) match
      case rec: Reflect.Record[Binding, ?]         => Some(rec)
      case wrapper: Reflect.Wrapper[Binding, ?, ?] => recordOf(wrapper.wrapped)
      case _                                       => None

  /** The field spec of the field with the wire name `name` and the reflect `reflect`. An `Option` or a `Maybe` field is
    * an optional field, with the type and the enum values of its element. A field with a default value (a case-class
    * parameter with a default) has that value in the wire form as its `defaultValue` (see [[wireDefault]]).
    */
  def fieldSpecOf(name: String, reflect: Reflect.Bound[?]): FieldSpec =
    FieldSpec(
      name = name,
      typeRef = typeRefFor(reflect),
      defaultValue = wireDefault(reflect),
      enumValues = enumValuesOf(reflect),
      optional = isOption(reflect)
    )

  /** The case names that an enum field accepts: the cases of a `Reflect.Variant` with no fields ([[caseNames]]). Empty
    * for each other reflect. The wire `typeRef` of such a variant is `TypeRef.string`, because the LM sees a plain
    * label. The adapters use these values to tell the LM which labels are valid. An `Option` and a wrapper have the
    * enum values of what they hold. A `Score` field has its levels, and a `Choice` field has its option labels (see
    * [[decisionField]]).
    */
  private def enumValuesOf(reflect: Reflect.Bound[?]): Vector[String] =
    unwrapped(reflect) match
      case OptionOf(element)                       => enumValuesOf(element)
      case wrapper: Reflect.Wrapper[Binding, ?, ?] => enumValuesOf(wrapper.wrapped)
      case _                                       => decisionField(reflect).fold(caseNames(reflect))(_._2)

  /** Matches the reflect of an `Option` or a `Maybe`, also behind a wrapper ([[Reflects.optionForm]]). The result is
    * the reflect of its element.
    */
  private object OptionOf:
    def unapply(reflect: Reflect.Bound[?]): Option[Reflect.Bound[?]] = Reflects.optionElement(reflect)

  /** Matches the reflect of an `Option` or a `Maybe` ([[OptionOf]]). The result has its case names too. */
  private object OptionFormOf:
    def unapply(reflect: Reflect.Bound[?]): Option[Reflects.OptionForm[Binding]] = Reflects.optionForm(reflect)

  private def isOption(reflect: Reflect.Bound[?]): Boolean = OptionOf.unapply(reflect).isDefined

  /** The wire `TypeRef` of a value type, from its `Schema`. [[fieldSpecsFromReflect]] uses the same rules for each
    * field of a record. `SchemaInterop` gives it to the callers outside this module.
    */
  def typeRefForSchema[A](using schema: Schema[A]): TypeRef = typeRefFor(schema.reflect)

  /** The wire type of a reflect. An `Option` has the type of its element: an `Option[Int]` field is an optional int
    * field.
    */
  private def typeRefFor(reflect: Reflect.Bound[?]): TypeRef =
    reflect match
      case OptionOf(element) => typeRefFor(element)
      case _                 => decisionField(reflect).fold(typeRefOfValue(reflect))(_._1)

  /** The names of the fields of a `Reflect.Record` whose type holds a decision type inside an `Option`, a collection,
    * or a map (experimental, upstream `resolve_adapter` and `extract_custom_type_from_annotation`). A field of a
    * decision type is a decision output, thus it is not in the result. The walk does not go into the fields of a
    * record, as upstream does not go into the fields of a model.
    */
  def nestedDecisionFieldsOf(reflect: Reflect.Bound[?]): Vector[String] =
    recordOf(reflect).fold(Vector.empty)(record =>
      record.fields.toVector.collect {
        case term if !Reflects.isTransient(term) && decisionField(term.value).isEmpty && holdsDecision(term.value) =>
          Reflects.fieldWireName(record, term)
      }
    )

  /** `true` when `reflect` is a decision type, or an `Option`, a collection, or a map that holds one. */
  private def holdsDecision(reflect: Reflect.Bound[?]): Boolean =
    unwrapped(reflect) match
      case OptionOf(element)                       => holdsDecision(element)
      case found if decisionField(found).isDefined => true
      case seq: Reflect.Sequence[Binding, ?, ?]    => holdsDecision(seq.element)
      case map: Reflect.Map[Binding, ?, ?, ?]      => holdsDecision(map.key) || holdsDecision(map.value)
      case wrapper: Reflect.Wrapper[Binding, ?, ?] => holdsDecision(wrapper.wrapped)
      case _                                       => false

  /** The reflect behind a deferred reflect, with a wildcard value type. A match on the result of `withoutDeferred`
    * itself has the captured value type of `reflect`, and with it the compiler finds a sequence case or a map case
    * unreachable.
    */
  private def unwrapped(reflect: Reflect.Bound[?]): Reflect.Bound[?] = withoutDeferred(reflect)

  /** The type and the enum values of a decision field (experimental, upstream `dspy.experimental`), from the marker of
    * its type ([[TypeMarker]]): a `Noul` has no enum values, a `Score` has its levels (the cases of the element of its
    * `level` field), and a `Choice` has its option labels (the cases of its `value` field). `None` for each other
    * reflect.
    */
  private def decisionField(reflect: Reflect.Bound[?]): Option[(TypeRef, Vector[String])] =
    TypeMarker.of(reflect) match
      case Some(TypeMarker.noul)   => Some(TypeRef.noul -> Vector.empty)
      case Some(TypeMarker.score)  => Some(TypeRef.score -> casesOfField(reflect, "level"))
      case Some(TypeMarker.choice) => Some(TypeRef.choice -> casesOfField(reflect, "value"))
      case _                       => None

  /** The enum values of the field `name` of the record `reflect` (see [[enumValuesOf]]): a field of type `Option[E]`
    * has the cases of `E`. Empty when the record has no such field.
    */
  private def casesOfField(reflect: Reflect.Bound[?], name: String): Vector[String] =
    recordOf(reflect).flatMap(_.fieldByName(name)).fold(Vector.empty[String])(t => enumValuesOf(t.value))

  /** The wire type of a reflect that is not a decision type. A `History` record (by its marker) is [[TypeRef.history]],
    * and a sequence whose element is a `ToolCall` (by its marker) is [[TypeRef.toolCalls]]. A variant with only cases
    * with no fields is a label, so it is a [[TypeRef.string]], and each other variant is a [[TypeRef.union]]. A tuple
    * is a [[TypeRef.list]], because its schema is an array. A wrapper has the type of what it wraps.
    */
  private def typeRefOfValue(reflect: Reflect.Bound[?]): TypeRef =
    unwrapped(reflect) match
      case prim: Reflect.Primitive[Binding, ?]                                                => primitiveTypeRef(prim)
      case variant: Reflect.Variant[Binding, ?]                                               => if variant.isEnumeration then TypeRef.string else TypeRef.union
      case rec: Reflect.Record[Binding, ?] if isMarked(rec, TypeMarker.history)               => TypeRef.history
      case rec: Reflect.Record[Binding, ?] if rec.typeId.isTuple                              => TypeRef.list
      case seq: Reflect.Sequence[Binding, ?, ?] if isMarked(seq.element, TypeMarker.toolCall) => TypeRef.toolCalls
      case _: Reflect.Sequence[Binding, ?, ?]                                                 => TypeRef.list
      case wrapper: Reflect.Wrapper[Binding, ?, ?]                                            => typeRefFor(wrapper.wrapped)
      case _                                                                                  => TypeRef.json

  /** `true` when the type of `reflect` has the marker `marker`. */
  private def isMarked(reflect: Reflect[?, ?], marker: String): Boolean = TypeMarker.of(reflect).contains(marker)

  /** The reflect that the JSON schema of a model comes from. Each other part of the reflect stays as it is.
    *
    *   - A term has no `Modifier.alias`. An alias is a lenient rule of the decode. Upstream pydantic shows one name,
    *     and the schema of the codec (`allOf` with `oneOf` of `not` schemas, extra `properties`, or one more case in a
    *     `oneOf`) breaks the strict shape, which makes each property required.
    *   - The `id` field of each `ToolCall` record (by its marker) is transient, so the schema does not show that field.
    *   - A sealed trait with a discriminator that its wire form does not use ([[Reflects.discriminatorOf]]) has no
    *     `Modifier.discriminator`, so the schema shows a case name as a key.
    */
  private object ModelSchemaTerms extends ReflectTransformer.OnlyMetadata[Binding, Binding]:
    override def transformMetadata[K, A](f: Binding[K, A]): Lazy[Binding[K, A]] = Lazy(f)

    private def withoutAlias[S, T](term: Term[Binding, S, T]): Term[Binding, S, T] =
      term.copy(modifiers = term.modifiers.filter {
        case _: Modifier.alias => false
        case _                 => true
      })

    override def transformRecord[A](
        path              : DynamicOptic,
        fields            : IndexedSeq[Term[Binding, A, ?]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Record, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Record[Binding, A]] =
      val hideId = TypeMarker.in(modifiers).contains(TypeMarker.toolCall)
      val shown  = fields.map { term =>
        if hideId && term.name == "id" then term.copy(modifiers = term.modifiers :+ Modifier.transient())
        else withoutAlias(term)
      }
      super.transformRecord(path, shown, typeId, metadata, doc, modifiers, storedDefaultValue, storedExamples)

    override def transformVariant[A](
        path              : DynamicOptic,
        cases             : IndexedSeq[Term[Binding, A, ? <: A]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Variant, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Variant[Binding, A]] =
      super.transformVariant(
        path,
        cases.map(withoutAlias),
        typeId,
        metadata,
        doc,
        modifiers,
        storedDefaultValue,
        storedExamples
      )
        .map { variant =>
          if Reflects.discriminatorOf(variant).isDefined then variant
          else
            variant.copy(modifiers = modifiers.filter {
              case _: Modifier.discriminator => false
              case _                         => true
            })
        }

  /** The JSON schema of `schema` as a model sees it ([[ModelSchemaTerms]]). The schema does not ask for the `id` of a
    * `ToolCall` (upstream removes `id` from the JSON schema of `ToolCalls.ToolCall`): the id comes from the provider,
    * and a model that writes a tool call as text does not supply it.
    *
    * A recursive type has its schema in `$defs`, and each reference to it is a `$ref` (upstream pydantic does the
    * same). zio-blocks writes no `$ref`. It puts the schema of each field in the schema of its record, so for a
    * recursive type its `toJsonSchema` (a `lazy val`) reads itself and waits forever. Thus this function cuts each
    * cycle before zio-blocks makes the schema. When the root type is recursive, the root is a `$ref` to its definition.
    *
    * The decode reads each form that the schema shows, through [[normalize]]. The schema and the decode have one rule
    * for a null and for an absent field ([[ModelSchemaDeriver]]), for the sequences, maps, options, and records that
    * `ModelSchemaContractSuite` covers. The decode also reads the forms of a sealed trait (a case name as a key, and
    * flat leaf names), a tuple (an array), a primitive with a text form (a string with a `format`), and a transform
    * (the schema of the value behind it), which `TypedValueFormsSuite` covers, and each name and form of an annotation
    * of zio-blocks (the name rules of [[Reflects]]), which `AnnotatedFormsSuite` covers. The encode of a nested sealed
    * trait with fields keeps its path, which the schema does not show and the decode reads. When two leaf cases of a
    * nested sealed trait have one name, the schema has two equal names, and the decode takes the first case. An enum
    * encodes to the name of its leaf case, so the law `decode(encode(a)) == Right(a)` does not hold for the second case
    * of such a type.
    *
    * The result is the record of [[schemaRecord]], or `None` when the schema is not a JSON object.
    */
  def jsonSchemaForModel[A](schema: Schema[A]): Option[DynamicValue.Record] =
    val shown     = schema.reflect.transform(DynamicOptic.root, ModelSchemaTerms).force
    val recursive = recursiveTypes(shown)
    schemaRecord(
      if recursive.isEmpty then Schema(shown).deriving(ModelSchemaDeriver).derive.toJsonSchema.toJson
      else withDefinitions(shown, recursive).toJson
    )

  /** The JSON object `json` as a record, or `None` when `json` is not an object. The record has the value of the JSON
    * text of `json` with the rules of Python `json.loads` ([[PythonText.parseJson]]): a number with a decimal point or
    * an exponent is a float, and each other number is an integer. Thus the schema note of a prompt shows each number as
    * zio-blocks writes it.
    */
  def schemaRecord(json: Json): Option[DynamicValue.Record] =
    PythonText.parseJson(json.toString).toOption.collect { case record: DynamicValue.Record => record }

  /** The JSON schema of `root`, with a definition in `$defs` for each type in `recursive`. In each schema, each
    * reference to a type in `recursive` is a placeholder whose JSON schema is a `$ref` ([[SchemaOnlyCodec]]).
    */
  private def withDefinitions(root: Reflect.Bound[?], recursive: Vector[Reflect.Bound[?]]): JsonSchema =
    val names = definitionNames(recursive)
    val cut   = CutCycles(names)

    def jsonSchemaOf[B](reflect: Reflect.Bound[B]): JsonSchema =
      val builder = Schema(reflect.transform(DynamicOptic.root, cut).force).deriving(ModelSchemaDeriver)
      names.valuesIterator
        .foldLeft(builder)((acc, name) =>
          acc.instance(refTypeId[Any](name), SchemaOnlyCodec[Any](JsonSchema.refString(refOf(name))))
        )
        .derive
        .toJsonSchema

    val definitions = ChunkMap.from(recursive.map(reflect => names(reflect.typeId) -> jsonSchemaOf(reflect)))
    names.get(root.typeId).fold(jsonSchemaOf(root))(name => JsonSchema.refString(refOf(name))) match
      case obj: JsonSchema.Object => obj.copy($defs = Some(definitions))
      case other                  => JsonSchema.Object(allOf = Some(NonEmptyChunk.single(other)), $defs = Some(definitions))

  /** The types that occur again in themselves, in the order that the walk from `root` finds them. A type here is a
    * record, a variant, or a wrapper. A sequence, a map, and an `Option` are not types here: the walk goes into them.
    * Thus for `next: Option[Node]` the recursive type is `Node`, not `Option[Node]`.
    *
    * The type id identifies a type, as in zio-blocks. zio-blocks gives one type id to each instance of a generic type
    * (for example `Tree[Int]` and `Tree[String]`), so these instances share one definition. zio-blocks cannot encode a
    * value of such a type either.
    */
  private def recursiveTypes(root: Reflect.Bound[?]): Vector[Reflect.Bound[?]] =
    def visit(
        reflect: Reflect.Bound[?],
        path   : Set[TypeId[?]],
        found  : Vector[Reflect.Bound[?]]
    ): Vector[Reflect.Bound[?]] =
      def node(children: Iterable[Reflect.Bound[?]]): Vector[Reflect.Bound[?]] =
        if !path.contains(reflect.typeId) then
          children.foldLeft(found)((acc, child) => visit(child, path + reflect.typeId, acc))
        else if found.iterator.map(_.typeId).contains(reflect.typeId) then found
        else found :+ reflect
      reflect match
        case deferred: Reflect.Deferred[Binding, ?] => visit(deferred.value, path, found)
        case seq: Reflect.Sequence[Binding, ?, ?]   => visit(seq.element, path, found)
        case map: Reflect.Map[Binding, ?, ?, ?]     => visit(map.value, path, visit(map.key, path, found))
        // The `Some` and `None` records of an `Option` are not types here: the walk goes to the value in `Some`.
        case variant: Reflect.Variant[Binding, ?] if variant.typeId.isOption || variant.typeId.isMaybe =>
          variant.cases.foldLeft(found)((acc, term) =>
            term.value match
              case rec: Reflect.Record[Binding, ?] =>
                rec.fields.foldLeft(acc)((a, field) => visit(field.value, path, a))
              case other => visit(other, path, acc)
          )
        case rec: Reflect.Record[Binding, ?]         => node(rec.fields.map(_.value))
        case variant: Reflect.Variant[Binding, ?]    => node(variant.cases.map(_.value))
        case wrapper: Reflect.Wrapper[Binding, ?, ?] => node(Vector(wrapper.wrapped))
        case _                                       => found
    visit(root, Set.empty, Vector.empty)

  /** A name in `$defs` for each type in `types`: the simple name of the type, with a number when two types have the
    * same simple name.
    */
  private def definitionNames(types: Vector[Reflect.Bound[?]]): Map[TypeId[?], String] =
    types.foldLeft(Map.empty[TypeId[?], String]) { (names, reflect) =>
      val base  = reflect.typeId.name.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
      val taken = names.valuesIterator.toSet
      val name  = if !taken.contains(base) then base else Iterator.from(2).map(n => s"$base$n").filterNot(taken).next()
      names.updated(reflect.typeId, name)
    }

  private def refOf(name: String): String = s"#/$$defs/$name"

  /** The type id of a placeholder for the type with the definition `name`. */
  private def refTypeId[A](name: String): TypeId[A] = TypeId.nominal[A](refOf(name), Owner.Root)

  /** Gives each type in `names` the type id of its placeholder ([[refTypeId]]), except at the root. Thus the reflect of
    * a recursive type is the root with its fields, and each reference to a type in `names` is a placeholder.
    */
  private final class CutCycles(names: Map[TypeId[?], String])
      extends ReflectTransformer.OnlyMetadata[Binding, Binding]:
    override def transformMetadata[K, A](f: Binding[K, A]): Lazy[Binding[K, A]] = Lazy(f)

    private def shownTypeId[A](path: DynamicOptic, typeId: TypeId[A]): TypeId[A] =
      names.get(typeId) match
        case Some(name) if path.nodes.nonEmpty => refTypeId[A](name)
        case _                                 => typeId

    override def transformRecord[A](
        path              : DynamicOptic,
        fields            : IndexedSeq[Term[Binding, A, ?]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Record, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Record[Binding, A]] =
      val shown = shownTypeId(path, typeId)
      super.transformRecord(path, fields, shown, metadata, doc, modifiers, storedDefaultValue, storedExamples)

    override def transformVariant[A](
        path              : DynamicOptic,
        cases             : IndexedSeq[Term[Binding, A, ? <: A]],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Variant, A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Variant[Binding, A]] =
      val shown = shownTypeId(path, typeId)
      super.transformVariant(path, cases, shown, metadata, doc, modifiers, storedDefaultValue, storedExamples)

    override def transformWrapper[A, B](
        path              : DynamicOptic,
        wrapped           : Reflect[Binding, B],
        typeId            : TypeId[A],
        metadata          : Binding[BindingType.Wrapper[A, B], A],
        doc               : Doc,
        modifiers         : Seq[Modifier.Reflect],
        storedDefaultValue: Option[DynamicValue],
        storedExamples    : collection.immutable.Seq[DynamicValue]
    ): Lazy[Reflect.Wrapper[Binding, A, B]] =
      val shown = shownTypeId(path, typeId)
      super.transformWrapper(path, wrapped, shown, metadata, doc, modifiers, storedDefaultValue, storedExamples)

  /** The deriver of the JSON schema that a model sees. The decode ([[decodeValue]]) rejects a null and an absent field
    * for a sequence or a map. The JSON codec of zio-blocks reads both as an empty collection, and its JSON schema says
    * so. This deriver gives the rules of the decode, which are also the rules of upstream pydantic, and each other part
    * of the schema stays:
    *
    *   - `withRequireCollectionFields` puts a collection field in `required`, except when it has a default value.
    *   - A sequence has the type `array`, and a map has the type `object`, with no `null`.
    *
    * An `Option` adds the `null` to the schema of its element (`JsonSchema.withNullable`), so an `Option[List[A]]`
    * permits a null. The change is in the schema of the collection, so it applies at each level and needs no walk over
    * the schema. The codecs of this deriver are only for the JSON schema ([[SchemaOnlyCodec]]).
    */
  private object ModelSchemaDeriver extends Deriver[JsonCodec]:
    private val base = JsonCodecDeriver.withRequireCollectionFields(true)

    export base.{deriveDynamic, derivePrimitive, deriveRecord, deriveVariant, deriveWrapper}

    // Each derivation calls this function, and with this call the base deriver clears its cache of recursive records.
    override def instanceOverrides: IndexedSeq[InstanceOverride] = base.instanceOverrides

    override def deriveSequence[F[_, _], C[_], A](
        element     : Reflect[F, A],
        typeId      : TypeId[C[A]],
        binding     : Binding.Seq[C, A],
        doc         : Doc,
        modifiers   : Seq[Modifier.Reflect],
        defaultValue: Option[C[A]],
        examples    : Seq[C[A]]
    )(implicit F: HasBinding[F], D: HasInstance[F]): Lazy[JsonCodec[C[A]]] =
      base.deriveSequence(element, typeId, binding, doc, modifiers, defaultValue, examples)
        .map(withType(_, JsonSchemaType.Array))

    override def deriveMap[F[_, _], M[_, _], K, V](
        key         : Reflect[F, K],
        value       : Reflect[F, V],
        typeId      : TypeId[M[K, V]],
        binding     : Binding.Map[M, K, V],
        doc         : Doc,
        modifiers   : Seq[Modifier.Reflect],
        defaultValue: Option[M[K, V]],
        examples    : Seq[M[K, V]]
    )(implicit F: HasBinding[F], D: HasInstance[F]): Lazy[JsonCodec[M[K, V]]] =
      base.deriveMap(key, value, typeId, binding, doc, modifiers, defaultValue, examples)
        .map(withType(_, JsonSchemaType.Object))

    /** A codec with the JSON schema of `codec`, with `jsonType` as the type of the schema. */
    private def withType[A](codec: JsonCodec[A], jsonType: JsonSchemaType): JsonCodec[A] =
      SchemaOnlyCodec[A](
        codec.toJsonSchema match
          case schema: JsonSchema.Object => schema.copy(`type` = Some(SchemaType.Single(jsonType)))
          case other                     => other
      )

  /** A JSON codec that has the JSON schema `schema`. The codec is only for the JSON schema, so its encoder and its
    * decoder fail. A placeholder of a recursive type is such a codec, with a `$ref` as its schema.
    */
  private final class SchemaOnlyCodec[A](schema: => JsonSchema) extends JsonCodec[A]:
    override def decodeValue(in: JsonReader): A = error("A codec for a JSON schema has no decoder")

    override def encodeValue(x: A, out: JsonWriter): Unit = error("A codec for a JSON schema has no encoder")

    override lazy val toJsonSchema: JsonSchema = schema

  /** The wire type of a primitive. A `Reasoning` (a text with the type id of that type) is [[TypeRef.reasoning]]. A
    * primitive with a text form ([[Reflects.hasTextForm]]) and a `BigDecimal` have an opaque type with their type name,
    * for example `Instant`. A prompt shows that name and the JSON schema of the field, and the adapters keep a bare
    * text for such a type. Thus a `BigDecimal` keeps each digit of a text.
    */
  private def primitiveTypeRef(prim: Reflect.Primitive[Binding, ?]): TypeRef =
    prim.primitiveType match
      case _: PrimitiveType.String if Reasoning.hasTypeId(prim.typeId) => TypeRef.reasoning
      case _: PrimitiveType.String | _: PrimitiveType.Char             => TypeRef.string
      case _: PrimitiveType.Boolean                                    => TypeRef.bool
      case _: PrimitiveType.Int | _: PrimitiveType.Long | _: PrimitiveType.Short | _: PrimitiveType.Byte |
          _: PrimitiveType.BigInt => TypeRef.int
      case _: PrimitiveType.Float | _: PrimitiveType.Double => TypeRef.double
      case _: PrimitiveType.Unit.type                       => TypeRef.json
      case _                                                => TypeRef(prim.typeId.name)

  /** Decode `dv` as an `A`. [[normalize]] first coerces `dv` to the reflect of `schema`, then `Schema.fromDynamicValue`
    * decodes it. An error of zio-blocks becomes a `ValidationError` with the message of that error, which has the path
    * of the value.
    */
  def decodeValue[A](dv: DynamicValue)(using schema: Schema[A]): Either[DspyError, A] =
    schema.fromDynamicValue(normalize(dv, schema.reflect)).left.map(err => ValidationError(DspyError.messageOf(err)))

  /** The names that the decode reads for each field of the record behind `reflect`, by the wire name of the field
    * ([[Reflects.fieldNames]]).
    */
  private def otherNamesOf(reflect: Reflect.Bound[?]): Map[String, Vector[String]] =
    recordOf(reflect).fold(Map.empty)(record =>
      record.fields.iterator.filterNot(Reflects.isTransient).map(term =>
        Reflects.fieldWireName(record, term) -> Reflects.fieldNames(record, term)
      ).toMap
    )

  /** Make a `Shape[A]` from a zio-blocks `Schema[A]`. The encode gives each field in the wire form
    * ([[Reflects.wireRecord]]). The root stays a record, also for a tuple, because its fields are the fields of a
    * signature. The decode first checks that the record has each required field, and then uses [[decodeValue]].
    */
  def derivedFromZioSchema[A](using schema: Schema[A]): RoundTripShape[A] =
    val rootReflect   = schema.reflect
    val specs         = fieldSpecsFromReflect(rootReflect)
    val requireFields = Shape.requiredFieldsCheck(specs, otherNamesOf(rootReflect))

    new RoundTripShape[A]:
      override val fieldSpecs: Vector[FieldSpec] = specs

      // A tuple at the top is the record of the output fields. On the wire it is a JSON object, not the array of its schema.
      override lazy val jsonSchema: Option[DynamicValue.Record] =
        if recordOf(rootReflect).exists(_.typeId.isTuple) then None else jsonSchemaForModel(schema)

      override lazy val nestedDecisionFields: Vector[String] = nestedDecisionFieldsOf(rootReflect)

      override def encode(value: A): DynamicValue.Record =
        schema.toDynamicValue(value) match
          case rec: DynamicValue.Record => Reflects.wireRecord(rec, rootReflect)
          case other                    => throw new IllegalStateException(
              s"zio-blocks Schema did not encode to a Record: $other"
            )

      // `normalize` gives `None` to each absent optional field, at each level.
      override def decode(raw: DynamicValue.Record): Either[DspyError, A] =
        requireFields(raw).flatMap(_ => decodeValue[A](raw))
