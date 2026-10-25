package dspy4s.core.decisions

import dspy4s.core.collections.Traverse
import dspy4s.core.contracts.{
  :=,
  ConfigurationError,
  DspyError,
  DynamicValues,
  FieldSpec,
  SignatureLayout,
  TypeMarker,
  TypeRef,
  ValidationError
}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.{Json, JsonCodec, JsonCodecDeriver}
import zio.blocks.schema.{DynamicValue, Modifier, PrimitiveValue, Schema}

import scala.collection.immutable.TreeMap

/** Experimental: the decision outputs of upstream `dspy.experimental` (`Noul`, `Score`, `Choice`). The API can change
  * without notice, as upstream says.
  *
  * A decision output gets a probability distribution (the evidence) from the backend: a System One model such as
  * TypeSafe Jev gives it directly, and a generative model writes it as a JSON object. Per-field settings
  * ([[DecisionSettings]]) then give the value: a threshold for a [[Noul]], cuts for a [[Score]], and weights for a
  * [[Choice]]. [[DecisionPlan]] holds the rules (upstream `DecisionState` in `dspy/adapters/decision_state.py`).
  *
  * Each decision type has its marker ([[dspy4s.core.contracts.TypeMarker]]), thus a typed signature finds a decision
  * field by its schema.
  */

/** A Boolean decision (upstream `Noul`).
  *
  * @param value
  *   `probability >= threshold`
  * @param confidence
  *   the distance from the threshold, `|p - t| / max(t, 1 - t)`. It is not a calibrated probability.
  * @param probability
  *   the probability of `true` that the backend gave. `None` for a value that did not come from evidence, for example a
  *   demo label.
  */
@Modifier.config(TypeMarker.Key, TypeMarker.noul)
final case class Noul(value: Boolean, confidence: Double, probability: Option[Double] = None) derives Schema

/** A continuous score over the ordered levels of the enum `L` (upstream `Score`). The cases of `L`, in order, are the
  * levels 0 to N - 1, with 2 to 10 levels. The case names of `L` are the levels: the typed signature reads them from
  * the reflect of the `level` field, as it reads the labels of a [[Choice]] from its `value` field.
  *
  * @param value
  *   the mean level index, `sum(i * p(i)) / sum(p)`. The cuts do not change it.
  * @param confidence
  *   the confidence that the backend gave
  * @param probabilities
  *   the distribution over the level indexes
  * @param level
  *   the level that the cuts select: the case of `L` at the index that is the count of cuts that are less than or equal
  *   to `value`
  */
@Modifier.config(TypeMarker.Key, TypeMarker.score)
final case class Score[L](
    value        : Double,
    confidence   : Double,
    probabilities: Option[Map[Int, Double]] = None,
    level        : Option[L]                = None
) derives Schema

/** One option of the enum `O` (upstream `Choice`). The case names of `O` are the option labels.
  *
  * @param value
  *   the option with the largest `probability * weight`
  * @param confidence
  *   the confidence that the backend gave
  * @param probabilities
  *   the distribution over the option labels
  */
@Modifier.config(TypeMarker.Key, TypeMarker.choice)
final case class Choice[O](value: O, confidence: Double, probabilities: Option[Map[String, Double]] = None)
    derives Schema

/** The kind of a decision output, from the type of its field (upstream `decision_type`). */
enum DecisionKind derives CanEqual:

  /** A Boolean decision: a [[dspy4s.core.contracts.TypeRef.noul]] field, or a `bool` field. */
  case Noul

  /** A score over 2 to 10 ordered levels: a [[dspy4s.core.contracts.TypeRef.score]] field. */
  case Score(levels: Vector[String])

  /** One option of a closed set: a [[dspy4s.core.contracts.TypeRef.choice]] field, or a `string` field with enum
    * values.
    */
  case Choice(options: Vector[String])

  /** The name of the kind in a question: `noul`, `score`, or `choice`. */
  private[decisions] def typeName: String =
    this match
      case DecisionKind.Noul      => "noul"
      case DecisionKind.Score(_)  => "score"
      case DecisionKind.Choice(_) => "choice"

  /** The parameter of the kind: the threshold of a Noul, the cuts of a Score, and the weights of a Choice. */
  def parameter: DecisionParameter[?] =
    this match
      case DecisionKind.Noul      => DecisionParameter.Threshold
      case DecisionKind.Score(_)  => DecisionParameter.Cuts
      case DecisionKind.Choice(_) => DecisionParameter.Weights

  /** The keys of a distribution of the kind: the level indexes `"0"` to `"N-1"` of a Score, and the labels of a Choice.
    * A Noul has no distribution, thus no keys.
    */
  private[decisions] def keys: Vector[String] =
    this match
      case DecisionKind.Noul           => Vector.empty
      case DecisionKind.Score(levels)  => levels.indices.map(_.toString).toVector
      case DecisionKind.Choice(labels) => labels

object DecisionKind:

  /** The type refs of the rich decision outputs. */
  private val richTypes: Set[TypeRef] = Set(TypeRef.noul, TypeRef.score, TypeRef.choice)

  /** The kind of `field`, or `None` when the field cannot be a decision output. A `noul`, `score`, or `choice` field is
    * a rich decision output. A `bool` field, and a `string` field with enum values, is a native output that can be a
    * decision output. An optional field cannot be a decision output: upstream `decision_type` gives `None` for an
    * `Optional[...]` annotation.
    */
  def of(field: FieldSpec): Option[DecisionKind] =
    if field.optional then None
    else if field.typeRef == TypeRef.noul || field.typeRef == TypeRef.bool then Some(DecisionKind.Noul)
    else if field.typeRef == TypeRef.score then Some(DecisionKind.Score(field.enumValues))
    else if field.typeRef == TypeRef.choice || (field.typeRef == TypeRef.string && field.enumValues.nonEmpty) then
      Some(DecisionKind.Choice(field.enumValues))
    else None

  /** The outputs of `layout` that can be decision outputs, with their kinds, in the order of the layout (upstream
    * `decision_outputs`).
    */
  def outputsOf(layout: SignatureLayout): Vector[(String, DecisionKind)] =
    layout.outputFields.flatMap(field => of(field).map(field.name -> _))

  /** `true` for a `noul`, `score`, or `choice` field: such an output always uses evidence decoding, and its value is
    * the result record ([[Noul]], [[Score]], or [[Choice]]). A native output gives the plain value. An optional field
    * is not a decision output, thus it is not rich.
    */
  def isRich(field: FieldSpec): Boolean = !field.optional && richTypes.contains(field.typeRef)

/** A parameter of a decision output, with the type `A` of its value: the threshold of a [[DecisionKind.Noul]], the cuts
  * of a [[DecisionKind.Score]], or the weights of a [[DecisionKind.Choice]]. ReAnchor fits it.
  *
  * @param name
  *   the key of the parameter in the JSON form of [[DecisionSettings]], and its name in a message or a report
  */
enum DecisionParameter[A](val name: String, read: DecisionSettings => Option[A]):

  /** The cut point of a [[DecisionKind.Noul]] on P(true). */
  case Threshold extends DecisionParameter[Double]("threshold", _.threshold)

  /** The boundaries of a [[DecisionKind.Score]] on the mean level index. */
  case Cuts extends DecisionParameter[Vector[Double]]("cuts", _.cuts)

  /** The multiplier of each label of a [[DecisionKind.Choice]]. */
  case Weights extends DecisionParameter[Map[String, Double]]("weights", _.weights)

  /** The value of this parameter in `settings`. `None` when `settings` do not set it. */
  def valueIn(settings: DecisionSettings): Option[A] = read(settings)

  /** The JSON form of the value of this parameter in `settings`: its entry in [[DecisionSettings.toDynamic]]. `None`
    * when `settings` do not set it.
    */
  def jsonIn(settings: DecisionSettings): Option[DynamicValue] = DynamicValues.recordGet(settings.toDynamic, name)

object DecisionParameter:

  given CanEqual[DecisionParameter[?], DecisionParameter[?]] = CanEqual.derived

/** The explicit settings of one decision output (upstream `Predict.fields[name]`). Each value that is `None` takes its
  * default.
  *
  * @param threshold
  *   a [[DecisionKind.Noul]] cut point on P(true), in [0, 1]. The default is 0.5.
  * @param cuts
  *   the N - 1 boundaries of a [[DecisionKind.Score]] on the mean level index: strictly increasing, each strictly
  *   between 0 and N - 1. The default is `[0.5, 1.5, ...]`.
  * @param weights
  *   a multiplier for each label of a [[DecisionKind.Choice]]: finite and not negative, with at least one positive
  *   effective weight. A label that is not in the map has the weight 1.0.
  * @param instructions
  *   the question text for the backend (a JSON string, object, array, or null). The default is the description of the
  *   field.
  * @param criteria
  *   the descriptions of the outcomes. A [[DecisionKind.Noul]] takes null or an object with the keys `true` and `false`
  *   (both optional). A [[DecisionKind.Choice]] takes an object with exactly the labels as keys. A
  *   [[DecisionKind.Score]] takes an array of one description for each level, with no null. Each description is a JSON
  *   string, object, array, or null.
  */
final case class DecisionSettings(
    threshold   : Option[Double]              = None,
    cuts        : Option[Vector[Double]]      = None,
    weights     : Option[Map[String, Double]] = None,
    instructions: Option[DynamicValue]        = None,
    criteria    : Option[DynamicValue]        = None
) derives CanEqual:

  /** The JSON form of the explicit settings, from the derived schema: the keys `threshold`, `cuts`, `weights`,
    * `instructions`, and `criteria`, in this order, for each value that is set. An explicit null stays null. The
    * weights are in the order of their labels, so that the JSON form of equal settings is the same text. JSON has no
    * number that is not finite, thus the codec throws an exception for such a number. [[DecisionPlan.of]] rejects such
    * a number.
    */
  def toDynamic: DynamicValue.Record =
    // A `TreeMap` gives its entries in the order of the keys, and the codec writes a map in the order of its entries.
    // The JSON text keeps the shortest decimal form of each number. The `Json` form of a `Double` can have one more
    // digit, for example 0.250.
    val text = DecisionSettings.codec.encodeToString(copy(weights = weights.map(w => TreeMap.from(w))))
    DynamicValue.Record(Json.parseUnsafe(text).fields.map((key, entry) => key -> entry.toDynamicValue))

object DecisionSettings:

  /** The schema of the instructions and of the criteria in the JSON form. A JSON null is the explicit value null, and a
    * key that is not in the JSON form takes the default `None`. The schema of zio-blocks for an `Option` reads a JSON
    * null as `None`, thus it does not keep an explicit null.
    */
  private given questionSchema: Schema[Option[DynamicValue]] =
    Schema[DynamicValue].transform(Some(_), _.getOrElse(DynamicValue.Null))

  /** The derived schema. The instructions and the criteria use `questionSchema`. It is private:
    * [[DecisionSettings.toDynamic]] and [[fromDynamic]] give the one form of the settings, because the `DynamicValue`
    * path of a schema writes an `Option` as a variant.
    */
  private given Schema[DecisionSettings] = Schema.derived

  /** The JSON codec of the derived schema. A key that is not a setting is an error. */
  private val codec: JsonCodec[DecisionSettings] =
    Schema[DecisionSettings].derive(JsonCodecDeriver.withRejectExtraFields(true))

  /** The settings of the JSON form of [[DecisionSettings.toDynamic]], with the derived schema. Each of these is a
    * `Left`: a value that is not JSON (also a number that is not finite, and a key that occurs two times in an object),
    * a value that is not an object, an unknown key, and a value of the wrong JSON type. A number can be an integer or a
    * decimal number. A JSON null for the threshold, the cuts, or the weights is no value. The instructions and the
    * criteria must be a JSON string, object, array, or null. [[DecisionPlan.of]] checks the other rules.
    */
  def fromDynamic(value: DynamicValue): Either[DspyError, DecisionSettings] =
    for
      // A value that is not JSON has no `Json` form, and the codec keeps the last weight of a label that occurs two
      // times. Thus this check comes first.
      _        <- DecisionJson.json("the decision settings", value)
      settings <- codec.decode(Json.fromDynamicValue(value)).left.map(error =>
                    ValidationError(s"Invalid decision settings: ${error.message}")
                  )
      _ <- DecisionJson.questions(settings)
    yield settings

/** The evidence of one decision output: the distribution that the backend gave. */
enum DecisionEvidence derives CanEqual:

  /** The probability of `true`. */
  case Noul(probability: Double)

  /** The probability of each level index, and the confidence. */
  case Score(probabilities: Map[Int, Double], confidence: Double)

  /** The probability of each option label, and the confidence. */
  case Choice(probabilities: Map[String, Double], confidence: Double)

/** The decision outputs of one layout, with their effective settings (upstream `DecisionState`).
  *
  * @param layout
  *   the layout of the signature. Its output fields give the default instructions, and they tell which outputs are
  *   rich.
  * @param kinds
  *   the decision outputs, in the order of the layout
  * @param settings
  *   the explicit settings, by output name
  */
final case class DecisionPlan private (
    layout  : SignatureLayout,
    kinds   : Vector[(String, DecisionKind)],
    settings: Map[String, DecisionSettings]
):

  /** The criteria of `name`: the explicit criteria, else the default of the kind. A [[DecisionKind.Noul]] has no
    * default (`None`). A [[DecisionKind.Score]] has its level names. A [[DecisionKind.Choice]] has each label with a
    * null description (upstream `get_criteria`). `None` for a name that is not a decision output.
    */
  def criteria(name: String): Option[DynamicValue] =
    kindOf(name).flatMap { kind =>
      settingsOf(name).criteria.orElse(
        kind match
          case DecisionKind.Noul           => None
          case DecisionKind.Score(levels)  => Some(DynamicValues.sequence(levels.map(DynamicValues.string)))
          case DecisionKind.Choice(labels) => Some(DynamicValue.Record(Chunk.from(labels.map(_ -> DynamicValue.Null))))
      )
    }

  /** The effective settings of `name` (upstream `DecisionState.fields[name]`): the explicit settings, with the default
    * parameter of the kind when they do not set it. The default is the threshold 0.5 for a [[DecisionKind.Noul]], the
    * cuts `[0.5, 1.5, ...]` for a [[DecisionKind.Score]], and the weight 1.0 for each label of a
    * [[DecisionKind.Choice]] (explicit weights replace all of these weights). The instructions and the criteria stay as
    * they are. `None` for a name that is not a decision output of this plan.
    */
  def effectiveSettings(name: String): Option[DecisionSettings] =
    kindOf(name).map { kind =>
      val explicit = settingsOf(name)
      kind match
        case DecisionKind.Noul           => explicit.copy(threshold = Some(DecisionPlan.thresholdOf(explicit)))
        case DecisionKind.Score(levels)  => explicit.copy(cuts = Some(DecisionPlan.cutsOf(explicit, levels.size)))
        case DecisionKind.Choice(labels) =>
          explicit.copy(weights = explicit.weights.orElse(Some(labels.map(_ -> DecisionPlan.defaultWeight).toMap)))
    }

  /** The question of `name` for the backend (upstream `_question`): a record with `instructions` (the explicit
    * instructions, else the description of the field), `type` (`noul`, `score`, or `choice`), and `criteria` when
    * [[criteria]] gives a value or the settings set the criteria (an explicit null stays null). A field with no
    * description (a placeholder or a blank text) and no explicit instructions is a `Left`.
    */
  def question(name: String): Either[DspyError, DynamicValue.Record] =
    for
      kind         <- kindOf(name).toRight(notDecision(name))
      instructions <- settingsOf(name).instructions.orElse(description(name).map(DynamicValues.string)).toRight(
                        ConfigurationError(
                          s"Decision output '$name' requires a field description or explicit instructions"
                        )
                      )
    yield
      val base = Vector("instructions" -> instructions, "type" := kind.typeName)
      DynamicValue.Record(Chunk.from(base ++ criteria(name).map("criteria" -> _)))

  /** The evidence of `name` from the JSON value of an answer. A [[DecisionKind.Noul]] answer has `noul`. A
    * [[DecisionKind.Score]] answer has `probabilities`, keyed by the level indexes `"0"` to `"N-1"` (exactly these
    * keys, in the decimal form with no leading zero), and `confidence`. A [[DecisionKind.Choice]] answer has
    * `probabilities`, keyed by exactly the labels, and `confidence`. Each probability and the confidence is a finite
    * number in [0, 1]; a Boolean is not a number. The other keys of an answer (for example `type`, `score`, or `choice`
    * from Jev) are ignored. The JSON types come first, then the rules of the evidence (`checked`, the same rules as the
    * decode).
    */
  def evidence(name: String, answer: DynamicValue): Either[DspyError, DecisionEvidence] =
    def number(record: DynamicValue.Record, key: String): Either[DspyError, Double] =
      DynamicValues.recordGet(record, key) match
        case None        => Left(ValidationError(s"The evidence of '$name' has no '$key'"))
        case Some(entry) => DynamicValues.asDouble(entry).toRight(
            ValidationError(
              s"The '$key' of the evidence of '$name' must be a number, got ${DecisionJson.describe(entry)}"
            )
          )

    /** The probabilities, in the order of `keys`, and the confidence. The keys of the JSON object come first: a key
      * that occurs two times, or a key that is not in the decimal form, cannot go into the evidence.
      */
    def distribution(record: DynamicValue.Record, keys: Vector[String]): Either[DspyError, (Vector[Double], Double)] =
      DynamicValues.recordGet(record, "probabilities") match
        case Some(probabilities: DynamicValue.Record) =>
          for
            _          <- DecisionPlan.keysRule(name, keys, DynamicValues.recordKeys(probabilities))
            read       <- Traverse.either(keys)(number(probabilities, _))
            confidence <- number(record, "confidence")
          yield (read, confidence)
        case Some(other) => Left(
            ValidationError(s"The probabilities of '$name' must be a JSON object, got ${DecisionJson.describe(other)}")
          )
        case None => Left(ValidationError(s"The evidence of '$name' has no 'probabilities'"))

    def read(kind: DecisionKind, record: DynamicValue.Record): Either[DspyError, DecisionEvidence] =
      kind match
        case DecisionKind.Noul     => number(record, "noul").map(DecisionEvidence.Noul(_))
        case DecisionKind.Score(_) => distribution(record, kind.keys).map((probabilities, confidence) =>
            DecisionEvidence.Score(probabilities.zipWithIndex.map((p, i) => i -> p).toMap, confidence)
          )
        case DecisionKind.Choice(labels) => distribution(record, labels).map((probabilities, confidence) =>
            DecisionEvidence.Choice(labels.zip(probabilities).toMap, confidence)
          )

    kindOf(name).toRight(notDecision(name)).flatMap { kind =>
      answer match
        case record: DynamicValue.Record =>
          read(kind, record).flatMap(found => checked(name, kind, found).map(_ => found))
        case other =>
          Left(ValidationError(s"The evidence of '$name' must be a JSON object, got ${DecisionJson.describe(other)}"))
    }

  /** The output values of the evidence of each decision output (upstream `DecisionState._decode`):
    *   - a [[DecisionKind.Noul]] gives `value = p >= threshold`, the confidence of [[Noul]], and the probability;
    *   - a [[DecisionKind.Score]] needs a total probability above zero. It gives the mean level index, the confidence,
    *     the distribution, and the name of the level that the cuts select;
    *   - a [[DecisionKind.Choice]] needs a total probability above zero. It picks the label with the largest
    *     `probability * weight`. A weighted tie goes to the raw winner (the label with the largest probability, the
    *     first in the order of declaration on a tie), then to the order of declaration. A pick with a weighted score of
    *     zero or less is a `Left` ("no positive probability mass").
    *
    * A rich output gives its result record: `{value, confidence, probability}` for a Noul, `{value, confidence,
    * probabilities, level}` for a Score (the probabilities keyed by `"0"` to `"N-1"`, and the level as its name), and
    * `{value, confidence, probabilities}` for a Choice. A native output gives the plain value: a Boolean, or the label.
    * Evidence that lacks a decision output is a `Left`. The evidence must obey the rules of [[evidence]] (`checked`):
    * the keys of each distribution, and a finite number in [0, 1] for each probability and each confidence.
    */
  def decode(answers: Map[String, DecisionEvidence]): Either[DspyError, DynamicValue.Record] =
    kinds.foldLeft[Either[DspyError, Vector[(String, DynamicValue)]]](Right(Vector.empty)) {
      case (acc, (name, kind)) =>
        for
          outputs <- acc
          answer  <- answers.get(name).toRight(ValidationError(s"No evidence for the decision output '$name'"))
          output  <- decodeOne(name, kind, answer)
        yield outputs :+ (name -> output)
    }.map(outputs => DynamicValue.Record(Chunk.from(outputs)))

  private def decodeOne(name: String, kind: DecisionKind, answer: DecisionEvidence): Either[DspyError, DynamicValue] =
    val rich = outputFieldsByName.get(name).exists(DecisionKind.isRich)
    (kind, answer) match
      case (DecisionKind.Noul, DecisionEvidence.Noul(p)) => checked(name, kind, answer).map { _ =>
          val t     = DecisionPlan.thresholdOf(settingsOf(name))
          val value = DecisionJson.boolean(p >= t)
          if !rich then value
          else
            val confidence = math.abs(p - t) / math.max(t, 1 - t)
            DynamicValue.Record("value" -> value, "confidence" := confidence, "probability" := p)
        }
      case (DecisionKind.Score(levels), DecisionEvidence.Score(probabilities, confidence)) =>
        val indexes = levels.indices.toVector
        for
          _   <- checked(name, kind, answer)
          read = indexes.map(probabilities)
          _   <- positive(name, read)
        yield
          val value = DecisionPlan.meanLevel(read)
          val cuts  = DecisionPlan.cutsOf(settingsOf(name), levels.size)
          DynamicValue.Record(
            "value"         := value,
            "confidence"    := confidence,
            "probabilities" -> DynamicValue.Record(Chunk.from(indexes.map(i => i.toString := read(i)))),
            "level"         := levels(cuts.count(_ <= value))
          )
      case (DecisionKind.Choice(labels), DecisionEvidence.Choice(probabilities, confidence)) =>
        for
          _        <- checked(name, kind, answer)
          read      = labels.map(probabilities)
          _        <- positive(name, read)
          selected <- pick(name, labels, read)
        yield
          val value = DynamicValues.string(labels(selected))
          if !rich then value
          else
            DynamicValue.Record(
              "value"         -> value,
              "confidence"    := confidence,
              "probabilities" -> DynamicValue.Record(Chunk.from(labels.zip(read).map((label, p) => label := p)))
            )
      case _ => Left(ValidationError(s"The evidence of '$name' is not the evidence of a ${kind.typeName} output"))

  /** The index of the label with the largest `probability * weight`. A weighted tie goes to the raw winner, then to the
    * first label (upstream `DecisionState._decode`).
    */
  private def pick(name: String, labels: Vector[String], probabilities: Vector[Double]): Either[DspyError, Int] =
    val explicit  = settingsOf(name)
    val scores    = labels.indices.map(i => probabilities(i) * DecisionPlan.weightOf(explicit, labels(i))).toVector
    val rawWinner = probabilities.indexOf(probabilities.max)
    val selected  = labels.indices.maxBy(i => (scores(i), i == rawWinner, -i))
    Either.cond(
      scores(selected) > 0,
      selected,
      ValidationError(s"Choice weights for '$name' leave no positive probability mass")
    )

  /** The rules of the evidence `evidence` of the decision output `name`, which has the kind `kind` (upstream
    * `Probability` and the evidence types). Each distribution has exactly the keys of the kind (`DecisionKind.keys`),
    * and each probability and the confidence is a finite number in [0, 1]. A NaN breaks the rule. [[evidence]] and
    * [[decode]] use this one check, thus a fault gives the same message on the two paths. The decode also needs a total
    * probability above zero (`positive`): the evidence of a System One model goes to the evidence log before the
    * decode, as upstream, thus the evidence can have a total of zero.
    */
  private def checked(name: String, kind: DecisionKind, evidence: DecisionEvidence): Either[DspyError, Unit] =
    def distribution(probabilities: Map[String, Double], confidence: Double): Either[DspyError, Unit] =
      for
        _ <- DecisionPlan.keysRule(name, kind.keys, probabilities.keys.toVector)
        _ <- Traverse.either(kind.keys)(key => probability(name, key, probabilities(key)))
        _ <- probability(name, "confidence", confidence)
      yield ()
    evidence match
      case DecisionEvidence.Noul(p)                          => probability(name, "noul", p)
      case DecisionEvidence.Score(probabilities, confidence) =>
        distribution(probabilities.map((index, p) => index.toString -> p), confidence)
      case DecisionEvidence.Choice(probabilities, confidence) => distribution(probabilities, confidence)

  /** A `Left` when the total of the probabilities is not above zero (upstream `DecisionState._decode`). */
  private def positive(name: String, probabilities: Vector[Double]): Either[DspyError, Unit] =
    Either.cond(
      probabilities.sum > 0,
      (),
      ValidationError(s"The probabilities of '$name' must have a total above zero")
    )

  /** A `Left` when `p` is not a finite number in [0, 1] (upstream `Probability`). A NaN breaks the rule. `key` names
    * the probability or the confidence in the evidence.
    */
  private def probability(name: String, key: String, p: Double): Either[DspyError, Unit] =
    Either.cond(
      p >= 0 && p <= 1,
      (),
      ValidationError(s"The '$key' of the evidence of '$name' must be a finite number in [0, 1], got: $p")
    )

  /** The kinds of the decision outputs, by name. For two outputs with the same name, the map keeps the first. */
  private val kindsByName: Map[String, DecisionKind] = kinds.distinctBy(_._1).toMap

  /** The output fields of [[layout]], by name. For two fields with the same name, the map keeps the first. */
  private val outputFieldsByName: Map[String, FieldSpec] =
    layout.outputFields.distinctBy(_.name).map(field => field.name -> field).toMap

  private def kindOf(name: String): Option[DecisionKind] = kindsByName.get(name)

  private def settingsOf(name: String): DecisionSettings = settings.getOrElse(name, DecisionSettings())

  /** The description of the field `name`, or `None` for a placeholder (`${name}`) or a blank text (upstream
    * `_question`).
    */
  private def description(name: String): Option[String] =
    outputFieldsByName.get(name).flatMap(_.description)
      .filter(text => text != s"$${$name}" && text.trim.nonEmpty)

  private def notDecision(name: String): DspyError = ConfigurationError(s"'$name' is not a decision output")

object DecisionPlan:

  /** The plan of `layout` for a System One model (upstream `DecisionState(..., system_one=True)`): each output must be
    * a decision output, so each native `Boolean` or enum output is one with no settings entry. An output that cannot be
    * a decision output is a `Left` ("Unsupported System One output '<name>'; use a decision type or native
    * equivalent."). The other rules are the rules of [[of]].
    */
  def forSystemOne(
      layout  : SignatureLayout,
      settings: Map[String, DecisionSettings]
  ): Either[DspyError, DecisionPlan] =
    val kinds = DecisionKind.outputsOf(layout)
    // `outputsOf` drops each field with no kind, thus a shorter result means an unsupported field.
    val unsupported =
      if kinds.size == layout.outputFields.size then None
      else layout.outputFields.find(field => DecisionKind.of(field).isEmpty)
    unsupported match
      case Some(field) => Left(ConfigurationError(
          s"Unsupported System One output '${field.name}'; use a decision type or native equivalent."
        ))
      case None => planOf(layout, settings, kinds)

  /** The default threshold of a [[DecisionKind.Noul]] (upstream `DecisionState`). */
  private val defaultThreshold: Double = 0.5

  /** The default weight of a label of a [[DecisionKind.Choice]]. */
  private val defaultWeight: Double = 1.0

  /** The default cuts of a [[DecisionKind.Score]] with `levels` levels: `[0.5, 1.5, ...]` (upstream `DecisionState`).
    */
  private def defaultCuts(levels: Int): Vector[Double] = (0 until levels - 1).map(_ + 0.5).toVector

  /** The threshold of `settings`: the explicit threshold, else the default. */
  private def thresholdOf(settings: DecisionSettings): Double = settings.threshold.getOrElse(defaultThreshold)

  /** The cuts of `settings` for a Score with `levels` levels: the explicit cuts, else the default. */
  private def cutsOf(settings: DecisionSettings, levels: Int): Vector[Double] =
    settings.cuts.getOrElse(defaultCuts(levels))

  /** The weight of `label` in `settings`: the explicit weight, else the default. */
  private def weightOf(settings: DecisionSettings, label: String): Double =
    settings.weights.flatMap(_.get(label)).getOrElse(defaultWeight)

  /** The mean level index of a Score distribution, `sum(i * p(i)) / sum(p)`, with `probabilities(i)` the probability of
    * the level index `i` (upstream `DecisionState._decode`). The decode and ReAnchor use this one function, so that a
    * fitted cut compares with the same value as the decode.
    */
  private[dspy4s] def meanLevel(probabilities: Vector[Double]): Double =
    probabilities.indices.map(i => i * probabilities(i)).sum / probabilities.sum

  /** A `Left` when the keys `found` of a distribution of `name` are not exactly the keys `keys`. A key that occurs two
    * times breaks the rule.
    */
  private def keysRule(name: String, keys: Vector[String], found: Vector[String]): Either[DspyError, Unit] =
    Either.cond(
      DecisionJson.sameKeys(found, keys),
      (),
      ValidationError(
        s"The probabilities of '$name' must have exactly the keys ${keys.mkString(", ")}, got: ${found.mkString(", ")}"
      )
    )

  /** The plan of `layout` with the explicit `settings`. Each rich output is a decision output. A native output is one
    * only when `settings` has an entry for it. A `Left` comes when a settings entry names an output that cannot be a
    * decision output, when a settings entry sets the parameter of another kind (for example `cuts` for a Noul), when a
    * value breaks a rule of [[DecisionSettings]], when a Score does not have 2 to 10 levels, or when a Choice has no
    * label or two equal labels. Instructions and criteria must be JSON strings, objects, arrays, or null, with JSON
    * values in them (no number that is not finite, and no key two times in an object).
    */
  def of(
      layout  : SignatureLayout,
      settings: Map[String, DecisionSettings]
  ): Either[DspyError, DecisionPlan] =
    val kinds = layout.outputFields.flatMap { field =>
      if DecisionKind.isRich(field) || settings.contains(field.name) then DecisionKind.of(field).map(field.name -> _)
      else None
    }
    planOf(layout, settings, kinds)

  /** The plan of `layout` with the decision outputs `kinds` and the explicit `settings`, with the checks of [[of]]. */
  private def planOf(
      layout  : SignatureLayout,
      settings: Map[String, DecisionSettings],
      kinds   : Vector[(String, DecisionKind)]
  ): Either[DspyError, DecisionPlan] =
    val unsupported = settings.keySet.diff(kinds.map(_._1).toSet).toVector.sorted
    if unsupported.nonEmpty then
      Left(ConfigurationError(s"Decision settings refer to unsupported outputs: ${unsupported.mkString(", ")}"))
    else
      kinds
        .foldLeft[Either[DspyError, Unit]](Right(())) { case (acc, (name, kind)) =>
          acc.flatMap(_ => validate(name, kind, settings.getOrElse(name, DecisionSettings())))
        }
        .map(_ => new DecisionPlan(layout, kinds, settings))

  /** The rules of the kind and of the settings of one decision output (upstream `DecisionState._validate_parameters`
    * and `_validate_question_config`, and the `Score[...]` and `Choice[...]` declarations).
    */
  private def validate(name: String, kind: DecisionKind, settings: DecisionSettings): Either[DspyError, Unit] =
    val foreign = DecisionParameter.values.toVector.filter(other =>
      other != kind.parameter && other.valueIn(settings).isDefined
    )
    for
      _ <- check(
             foreign.isEmpty,
             s"Invalid settings for '$name': require '${kind.parameter.name}', with optional instructions and " +
               s"criteria, not ${foreign.map(_.name).mkString(", ")}"
           )
      _ <- validateKind(name, kind)
      _ <- DecisionJson.questions(settings)
      _ <- settings.criteria.fold(Right(()))(criteria =>
             check(validCriteria(kind, criteria), s"Invalid criteria for '$name': must match the decision type")
           )
      _ <- validateParameter(name, kind, settings)
    yield ()

  /** A Score has 2 to 10 levels (upstream `DecisionState._validate_parameters`). A Choice has at least one label, and
    * no two equal labels (upstream `Choice.__class_getitem__`).
    */
  private def validateKind(name: String, kind: DecisionKind): Either[DspyError, Unit] =
    kind match
      case DecisionKind.Noul          => Right(())
      case DecisionKind.Score(levels) =>
        check(levels.size >= 2 && levels.size <= 10, s"The Score '$name' must have 2 to 10 levels, got ${levels.size}")
      case DecisionKind.Choice(labels) =>
        check(labels.nonEmpty, s"The Choice '$name' must have at least one label").flatMap(_ =>
          check(labels.distinct.size == labels.size, s"The labels of the Choice '$name' must be distinct")
        )

  /** The rules of the threshold, the cuts, and the weights (upstream `DecisionState._validate_parameters`). A NaN
    * breaks each rule.
    */
  private def validateParameter(name: String, kind: DecisionKind, settings: DecisionSettings): Either[DspyError, Unit] =
    kind match
      case DecisionKind.Noul =>
        val t = thresholdOf(settings)
        check(t >= 0 && t <= 1, s"Threshold for '$name' must be in [0, 1]")
      case DecisionKind.Score(levels) =>
        val cuts = cutsOf(settings, levels.size)
        val top  = levels.size - 1
        check(
          cuts.size == top && cuts.forall(c => c > 0 && c < top) && cuts.zip(cuts.drop(1)).forall((a, b) => a < b),
          s"Invalid cuts for '$name': require $top ordered boundaries inside the Score index range"
        )
      case DecisionKind.Choice(labels) =>
        val weights = settings.weights.getOrElse(Map.empty)
        check(
          weights.keySet.subsetOf(labels.toSet) &&
            weights.values.forall(w => w >= 0 && w < Double.PositiveInfinity) &&
            labels.exists(label => weightOf(settings, label) > 0),
          s"Choice weights for '$name' must map known labels to finite, nonnegative numbers " +
            "with at least one positive effective weight"
        )

  private def check(rule: Boolean, message: => String): Either[DspyError, Unit] =
    Either.cond(rule, (), ConfigurationError(message))

  /** The shape rules of the criteria (upstream `DecisionState._validate_question_config`). `validate` checks the kind
    * first, thus a Score here has 2 to 10 levels.
    */
  private def validCriteria(kind: DecisionKind, criteria: DynamicValue): Boolean =
    kind match
      case DecisionKind.Noul => criteria match
          case _: DynamicValue.Null.type   => true
          case record: DynamicValue.Record =>
            DynamicValues.recordKeys(record).forall(key => key == "true" || key == "false") &&
            record.fields.forall((_, entry) => DecisionJson.isDescription(entry))
          case _ => false
      case DecisionKind.Choice(labels) => criteria match
          case record: DynamicValue.Record => DecisionJson.sameKeys(DynamicValues.recordKeys(record), labels) &&
            record.fields.forall((_, entry) => DecisionJson.isDescription(entry))
          case _ => false
      case DecisionKind.Score(levels) => criteria match
          case sequence: DynamicValue.Sequence => sequence.elements.size == levels.size &&
            sequence.elements.forall {
              case _: DynamicValue.Null.type => false
              case entry                     => DecisionJson.isDescription(entry)
            }
          case _ => false

/** The JSON rules of the decision values. */
private object DecisionJson:

  def boolean(value: Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(value))

  /** `true` when `keys` and `labels` have the same size and the same elements. */
  def sameKeys(keys: Vector[String], labels: Vector[String]): Boolean =
    keys.size == labels.size && keys.toSet == labels.toSet

  /** The rule of [[question]] for the instructions and for the criteria of `settings`, in this order. */
  def questions(settings: DecisionSettings): Either[DspyError, Unit] =
    for
      _ <- settings.instructions.fold(Right(()))(question("instructions", _))
      _ <- settings.criteria.fold(Right(()))(question("criteria", _))
    yield ()

  /** A `Left` when `value` is not a JSON string, object, array, or null, with JSON values in it (upstream
    * `_validate_question_config`). `key` names the setting in the message of the `Left`.
    */
  def question(key: String, value: DynamicValue): Either[DspyError, Unit] =
    if !isDescription(value) then
      Left(ValidationError(s"Invalid $key: expected a JSON string, object, array, or null, got ${describe(value)}"))
    else json(key, value)

  /** A `Left` when `value` is not a JSON value ([[isJson]]). `key` names the value in the message of the `Left`. */
  def json(key: String, value: DynamicValue): Either[DspyError, Unit] =
    Either.cond(
      isJson(value),
      (),
      ValidationError(
        s"Invalid JSON in $key: each number must be finite, each key must occur one time, and each value must be JSON"
      )
    )

  /** The JSON type of `value` for a message. A message does not show the value: the text of a value with a number that
    * is not finite is an error in zio-blocks.
    */
  def describe(value: DynamicValue): String =
    value match
      case _: DynamicValue.Null.type                         => "null"
      case DynamicValue.Primitive(PrimitiveValue.String(_))  => "a string"
      case DynamicValue.Primitive(PrimitiveValue.Boolean(_)) => "a Boolean"
      case _: DynamicValue.Record                            => "an object"
      case _: DynamicValue.Sequence                          => "an array"
      case _ if DynamicValues.asDouble(value).isDefined      => "a number"
      case _                                                 => "a value that is not JSON"

  /** `true` for a JSON string, object, array, or null: the forms of a description (upstream `_description_json`). */
  def isDescription(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type                        => true
      case DynamicValue.Primitive(PrimitiveValue.String(_)) => true
      case _: DynamicValue.Record                           => true
      case _: DynamicValue.Sequence                         => true
      case _                                                => false

  /** `true` for a JSON value: a string, a Boolean, a finite number, null, an array of JSON values, or an object of JSON
    * values with no key two times.
    */
  def isJson(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type   => true
      case record: DynamicValue.Record => record.fields.map(_._1).distinct.size == record.fields.size &&
        record.fields.forall((_, entry) => isJson(entry))
      case sequence: DynamicValue.Sequence   => sequence.elements.forall(isJson)
      case DynamicValue.Primitive(primitive) => primitive match
          case _: PrimitiveValue.String  => true
          case _: PrimitiveValue.Boolean => true
          case PrimitiveValue.Double(n)  => n.isFinite
          case PrimitiveValue.Float(n)   => n.isFinite
          case _                         => DynamicValues.asDouble(value).isDefined
      case _ => false
