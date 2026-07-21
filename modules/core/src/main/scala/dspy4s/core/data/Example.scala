package dspy4s.core.data

import dspy4s.core.contracts.{DspyError, DynamicValues, ValidationError}
import dspy4s.core.contracts.updated
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, PrimitiveValue, Schema}

/** A single labeled data point: a `DynamicValue.Record` of field values plus an [[inputKeys]] partition that names
  * which fields are inputs (everything else is a label / expected output). Used as training data for optimizers
  * (`BootstrapFewShot`, `LabeledFewShot`), evaluation datasets (`Evaluate`), and few-shot demos attached to a
  * `Predict`.
  *
  *   - [[inputKeys]] partitions [[values]] into [[inputs]] and [[labels]]. The partition is content-derived, not stored
  *     separately, so updates to `values` flow through automatically.
  *   - [[augmented]] is set to `true` by `BootstrapFewShot` on traces it harvested from the teacher; raw user trainset
  *     examples stay `false`. The optimizer surface reads this flag to count bootstrapped vs labeled demos against the
  *     configured caps.
  *
  * Immutable; all mutators return a copy. `withInputs` intersects the requested key set with the current field names,
  * so passing keys that aren't in `values` silently drops them rather than declaring phantom inputs.
  */
final case class Example(
    values: DynamicValue.Record,
    inputKeys: Set[String] = Set.empty,
    augmented: Boolean = false
):
  /** Field-value accessor by name. */
  def get(key: String): Option[DynamicValue] = DynamicValues.recordGet(values, key)

  def inputs: DynamicValue.Record = DynamicValues.recordFilterKeys(values, inputKeys.contains)
  def labels: DynamicValue.Record = DynamicValues.recordFilterKeys(values, name => !inputKeys.contains(name))

  def withInputs(keys: Set[String]): Example =
    copy(inputKeys = keys.intersect(DynamicValues.recordKeys(values).toSet))

  def withValue(key: String, value: DynamicValue): Example =
    copy(values = values.updated(key, value))

  /** Convenience overload for callers passing a plain typed Scala value; lifts it via its `Schema`. A value type
    * without a `Schema` is a compile error.
    */
  def withRawValue[A](key: String, value: A)(using schema: Schema[A]): Example =
    withValue(key, schema.toDynamicValue(value))

  def without(keys: Set[String]): Example =
    copy(
      values = DynamicValues.recordFilterKeys(values, name => !keys.contains(name)),
      inputKeys = inputKeys -- keys
    )

  def withAugmented(flag: Boolean): Example = copy(augmented = flag)

  /** Serialize to a [[zio.blocks.schema.DynamicValue.Record]] -- the codec spine carried everywhere else in dspy4s.
    * Round-trips with [[Example.fromState]] and serializes to clean JSON via the `DynamicValue` JSON codec. The record
    * has three fields: `values` (the field-value record verbatim), `inputKeys` (a sequence of the input field-name
    * strings), and `augmented` (a boolean). Mirrors `SignatureLayout.dumpState`.
    */
  def dumpState: DynamicValue.Record =
    val keyValues: Seq[DynamicValue] =
      inputKeys.toVector.sorted.map(k => DynamicValue.Primitive(PrimitiveValue.String(k)))
    DynamicValue.Record(Chunk.from(Seq(
      "values"    -> (values: DynamicValue),
      "inputKeys" -> DynamicValue.Sequence(Chunk.from(keyValues)),
      "augmented" -> DynamicValue.Primitive(PrimitiveValue.Boolean(augmented))
    )))

object Example:
  /** Convenience constructor: `Example("q" -> "...", "a" -> "...")`. Produces an example with no declared input keys;
    * call `withInputs(...)` to mark a subset as inputs. Values are lifted into the spine via [[DynamicValues.fromAny]].
    */
  def apply(entries: (String, DynamicValue)*): Example =
    Example(values = DynamicValues.recordFromEntries(entries))

  /** An example with no fields. */
  def empty: Example = Example(values = DynamicValue.Record.empty)

  /** Re-hydrate an [[Example]] from the `DynamicValue.Record` produced by [[Example.dumpState]]. The inverse of the
    * serialization primitive: reads `values` (must be a record), `inputKeys` (a sequence of strings), and `augmented`
    * (a boolean).
    */
  def fromState(state: DynamicValue.Record): Either[DspyError, Example] =
    def readValues: Either[DspyError, DynamicValue.Record] =
      DynamicValues.recordGet(state, "values") match
        case Some(rec: DynamicValue.Record) => Right(rec)
        case _                              => Left(ValidationError("Example state is missing a record 'values'"))

    def readInputKeys: Either[DspyError, Set[String]] =
      DynamicValues.recordGet(state, "inputKeys") match
        case None | Some(_: DynamicValue.Null.type) => Right(Set.empty)
        case Some(seq: DynamicValue.Sequence) =>
          seq.elements.iterator.foldLeft[Either[DspyError, Set[String]]](Right(Set.empty)) { (acc, raw) =>
            acc.flatMap { keys =>
              raw match
                case DynamicValue.Primitive(PrimitiveValue.String(s)) => Right(keys + s)
                case _ => Left(ValidationError("Example state 'inputKeys' must be a sequence of strings"))
            }
          }
        case Some(_) => Left(ValidationError("Example state 'inputKeys' must be a sequence"))

    def readAugmented: Either[DspyError, Boolean] =
      DynamicValues.recordGet(state, "augmented") match
        case None | Some(_: DynamicValue.Null.type)                  => Right(false)
        case Some(DynamicValue.Primitive(PrimitiveValue.Boolean(b))) => Right(b)
        case Some(_) => Left(ValidationError("Example state 'augmented' must be a boolean"))

    for
      values    <- readValues
      inputKeys <- readInputKeys
      augmented <- readAugmented
    yield Example(values = values, inputKeys = inputKeys, augmented = augmented)
