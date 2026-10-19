package dspy4s.core.decisions

import dspy4s.core.contracts.{
  ConfigurationError,
  DspyError,
  DynamicValues,
  FieldSpec,
  SignatureLayout,
  TypeRef,
  ValidationError
}
import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Modifier, PrimitiveValue, Reflect, Schema}

/** Experimental: the decision outputs of upstream `dspy.experimental` (`Noul`, `Score`, `Choice`). The API can change
  * without notice, as upstream says.
  *
  * A decision output gets a probability distribution (the evidence) from the backend: a System One model such as
  * TypeSafe Jev gives it directly, and a generative model writes it as a JSON object. Per-field settings
  * ([[DecisionSettings]]) then give the value: a threshold for a [[Noul]], cuts for a [[Score]], and weights for a
  * [[Choice]]. [[DecisionPlan]] holds the rules (upstream `DecisionState` in `dspy/adapters/decision_state.py`).
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
final case class Noul(value: Boolean, confidence: Double, probability: Option[Double] = None) derives Schema

/** A continuous score over the ordered levels of the enum `L` (upstream `Score`). The cases of `L`, in order, are the
  * levels 0 to N - 1, with 2 to 10 levels.
  *
  * @param value
  *   the mean level index, `sum(i * p(i)) / sum(p)`. The cuts do not change it.
  * @param confidence
  *   the confidence that the backend gave
  * @param probabilities
  *   the distribution over the level indexes
  * @param level
  *   the index of the level that the cuts select: the count of cuts that are less than or equal to `value`
  */
final case class Score[L](
    value        : Double,
    confidence   : Double,
    probabilities: Option[Map[Int, Double]] = None,
    level        : Option[Int]              = None
):

  /** The level as a case of `L`, for example `score.levelIn(Severity.values)`. */
  def levelIn[C >: L](cases: IndexedSeq[C]): Option[C] = level.flatMap(cases.lift)

object Score:

  /** The key of the `Modifier.config` entries that hold the levels. The schema has one entry for each level, in the
    * order of the levels, thus a level name needs no escape.
    */
  private val levelKey = "dspy4s.score.level"

  /** The schema of a `Score[L]`. The case names of `L` are the levels: the typed signature reads them from this schema,
    * so a field of type `Score[L]` has the levels of `L`.
    */
  given [L](using levels: Schema[L]): Schema[Score[L]] = schemaOf(levels)

  /** The schema of a `Score[L]` with the case names of `levels` as the levels. The canonical schema of a typed
    * signature (`dspy4s.signatures.Shape.canonicalSchema`) blocks the given of this object, thus it calls this function
    * with the structural schema of `L`.
    */
  private[dspy4s] def schemaOf[L](levels: Schema[L]): Schema[Score[L]] =
    Schema.derived[Score[L]].modifiers(caseNames(levels.reflect).map(Modifier.config(levelKey, _)))

  /** The levels that the schema of a `Score[L]` holds, in order. Empty for the reflect of each other type. */
  private[dspy4s] def levelsOf(reflect: Reflect[?, ?]): Vector[String] =
    reflect.modifiers.iterator.collect { case Modifier.config(key, level) if key == levelKey => level }.toVector

  /** The case names of an enum with no parameters. Empty for each other type. */
  private def caseNames(reflect: Reflect[?, ?]): Vector[String] =
    reflect match
      case deferred: Reflect.Deferred[?, ?] => caseNames(deferred.value)
      case variant: Reflect.Variant[?, ?]   =>
        val cases = variant.cases.toVector
        if cases.forall(term => isEmptyRecord(term.value)) then cases.map(_.name) else Vector.empty
      case _ => Vector.empty

  private def isEmptyRecord(reflect: Reflect[?, ?]): Boolean =
    reflect match
      case deferred: Reflect.Deferred[?, ?] => isEmptyRecord(deferred.value)
      case record: Reflect.Record[?, ?]     => record.fields.isEmpty
      case _                                => false

/** One option of the enum `O` (upstream `Choice`). The case names of `O` are the option labels.
  *
  * @param value
  *   the option with the largest `probability * weight`
  * @param confidence
  *   the confidence that the backend gave
  * @param probabilities
  *   the distribution over the option labels
  */
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

object DecisionKind:

  /** The kind of `field`, or `None` when the field cannot be a decision output. A `noul`, `score`, or `choice` field is
    * a rich decision output. A `bool` field, and a `string` field with enum values, is a native output that can be a
    * decision output. An optional field cannot be a decision output: upstream `decision_type` gives `None` for an
    * `Optional[...]` annotation.
    */
  def of(field: FieldSpec): Option[DecisionKind] =
    if field.optional then None
    else if field.typeRef == TypeRef.noul || field.typeRef == TypeRef.bool then Some(DecisionKind.Noul)
    else if field.typeRef == TypeRef.score then Some(DecisionKind.Score(field.enumValues))
    else if field.typeRef == TypeRef.choice then Some(DecisionKind.Choice(field.enumValues))
    else if field.typeRef == TypeRef.string && field.enumValues.nonEmpty then
      Some(DecisionKind.Choice(field.enumValues))
    else None

  /** `true` for a `noul`, `score`, or `choice` field: such an output always uses evidence decoding, and its value is
    * the result record ([[Noul]], [[Score]], or [[Choice]]). A native output gives the plain value. An optional field
    * is not a decision output, thus it is not rich.
    */
  def isRich(field: FieldSpec): Boolean =
    !field.optional &&
      (field.typeRef == TypeRef.noul || field.typeRef == TypeRef.score || field.typeRef == TypeRef.choice)

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

  /** The JSON form of the explicit settings: the keys `threshold`, `cuts`, `weights`, `instructions`, and `criteria`,
    * in this order, for each value that is set. An explicit null stays null. The weights are in the order of their
    * labels, so that the JSON form of equal settings is the same text.
    */
  def toDynamic: DynamicValue.Record =
    val entries = Vector(
      threshold.map(t => "threshold" -> DecisionJson.number(t)),
      cuts.map(c => "cuts" -> DynamicValue.Sequence(Chunk.from(c.map(DecisionJson.number)))),
      weights.map(w =>
        "weights" -> DynamicValue.Record(Chunk.from(w.toVector.sortBy(_._1).map((label, weight) =>
          label -> DecisionJson.number(weight)
        )))
      ),
      instructions.map("instructions" -> _),
      criteria.map("criteria" -> _)
    ).flatten
    DynamicValue.Record(Chunk.from(entries))

object DecisionSettings:

  /** The settings of the JSON form of [[DecisionSettings.toDynamic]]. An unknown key, or a value of the wrong JSON
    * type, is a `Left`. A number can be an integer or a decimal number. The instructions and the criteria must be a
    * JSON string, object, array, or null, with no number that is not finite. [[DecisionPlan.of]] checks the other
    * rules.
    */
  def fromDynamic(value: DynamicValue): Either[DspyError, DecisionSettings] =
    value match
      case record: DynamicValue.Record =>
        record.fields.foldLeft[Either[DspyError, DecisionSettings]](Right(DecisionSettings())) {
          case (acc, (key, entry)) => acc.flatMap(withEntry(_, key, entry))
        }
      case other =>
        Left(ValidationError(s"The decision settings must be a JSON object, got ${DecisionJson.describe(other)}"))

  /** `settings` with the value of the JSON entry `key`. */
  private def withEntry(
      settings: DecisionSettings,
      key     : String,
      entry   : DynamicValue
  ): Either[DspyError, DecisionSettings] =
    key match
      case "threshold" =>
        DecisionJson.numberOf(entry).map(t => settings.copy(threshold = Some(t))).toRight(wrongType(key, "a number"))
      case "cuts" =>
        numbers(entry).map(c => settings.copy(cuts = Some(c))).toRight(wrongType(key, "an array of numbers"))
      case "weights" =>
        weightsOf(entry).map(w => settings.copy(weights = Some(w))).toRight(wrongType(key, "an object of numbers"))
      case "instructions" => DecisionJson.question(key, entry).map(i => settings.copy(instructions = Some(i)))
      case "criteria"     => DecisionJson.question(key, entry).map(c => settings.copy(criteria = Some(c)))
      case other          => Left(ValidationError(
          s"Unknown decision setting '$other': use threshold, cuts, weights, instructions, or criteria"
        ))

  private def wrongType(key: String, expected: String): DspyError =
    ValidationError(s"The decision setting '$key' must be $expected")

  private def numbers(value: DynamicValue): Option[Vector[Double]] =
    value match
      case sequence: DynamicValue.Sequence =>
        val read = sequence.elements.toVector.map(DecisionJson.numberOf)
        if read.forall(_.isDefined) then Some(read.flatten) else None
      case _ => None

  private def weightsOf(value: DynamicValue): Option[Map[String, Double]] =
    value match
      case record: DynamicValue.Record =>
        val read = record.fields.toVector.map((label, weight) => DecisionJson.numberOf(weight).map(label -> _))
        if read.forall(_.isDefined) then Some(read.flatten.toMap) else None
      case _ => None

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
          case DecisionKind.Score(levels)  => Some(DynamicValue.Sequence(Chunk.from(levels.map(DecisionJson.string))))
          case DecisionKind.Choice(labels) => Some(DynamicValue.Record(Chunk.from(labels.map(_ -> DynamicValue.Null))))
      )
    }

  /** The question of `name` for the backend (upstream `_question`): a record with `instructions` (the explicit
    * instructions, else the description of the field), `type` (`noul`, `score`, or `choice`), and `criteria` when
    * [[criteria]] gives a value or the settings set the criteria (an explicit null stays null). A field with no
    * description (a placeholder or a blank text) and no explicit instructions is a `Left`.
    */
  def question(name: String): Either[DspyError, DynamicValue.Record] =
    for
      kind         <- kindOf(name).toRight(notDecision(name))
      instructions <- settingsOf(name).instructions.orElse(description(name).map(DecisionJson.string)).toRight(
                        ConfigurationError(
                          s"Decision output '$name' requires a field description or explicit instructions"
                        )
                      )
    yield
      val base = Vector("instructions" -> instructions, "type" -> DecisionJson.string(kind.typeName))
      DynamicValue.Record(Chunk.from(base ++ criteria(name).map("criteria" -> _)))

  /** The evidence of `name` from the JSON value of an answer. A [[DecisionKind.Noul]] answer has `noul`. A
    * [[DecisionKind.Score]] answer has `probabilities`, keyed by the level indexes `"0"` to `"N-1"` (exactly these
    * keys, in the decimal form with no leading zero), and `confidence`. A [[DecisionKind.Choice]] answer has
    * `probabilities`, keyed by exactly the labels, and `confidence`. Each probability and the confidence is a finite
    * number in [0, 1]; a Boolean is not a number. The other keys of an answer (for example `type`, `score`, or `choice`
    * from Jev) are ignored.
    */
  def evidence(name: String, answer: DynamicValue): Either[DspyError, DecisionEvidence] =
    def probabilityAt(record: DynamicValue.Record, key: String): Either[DspyError, Double] =
      DynamicValues.recordGet(record, key) match
        case None        => Left(ValidationError(s"The evidence of '$name' has no '$key'"))
        case Some(entry) => DecisionJson.numberOf(entry).toRight(
            ValidationError(
              s"The '$key' of the evidence of '$name' must be a number, got ${DecisionJson.describe(entry)}"
            )
          ).flatMap(probability(name, key, _))

    def distribution(record: DynamicValue.Record, labels: Vector[String]): Either[DspyError, Vector[Double]] =
      DynamicValues.recordGet(record, "probabilities") match
        case Some(probabilities: DynamicValue.Record) =>
          val keys = DynamicValues.recordKeys(probabilities)
          if keys.size != labels.size || keys.toSet != labels.toSet then
            Left(ValidationError(
              s"The probabilities of '$name' must have exactly the keys ${labels.mkString(", ")}, " +
                s"got: ${keys.mkString(", ")}"
            ))
          else
            labels.foldLeft[Either[DspyError, Vector[Double]]](Right(Vector.empty)) { (acc, label) =>
              acc.flatMap(read => probabilityAt(probabilities, label).map(read :+ _))
            }
        case Some(other) => Left(
            ValidationError(s"The probabilities of '$name' must be a JSON object, got ${DecisionJson.describe(other)}")
          )
        case None => Left(ValidationError(s"The evidence of '$name' has no 'probabilities'"))

    kindOf(name).toRight(notDecision(name)).flatMap { kind =>
      answer match
        case record: DynamicValue.Record => kind match
            case DecisionKind.Noul          => probabilityAt(record, "noul").map(DecisionEvidence.Noul(_))
            case DecisionKind.Score(levels) =>
              for
                probabilities <- distribution(record, levels.indices.map(_.toString).toVector)
                confidence    <- probabilityAt(record, "confidence")
              yield DecisionEvidence.Score(probabilities.zipWithIndex.map((p, i) => i -> p).toMap, confidence)
            case DecisionKind.Choice(labels) =>
              for
                probabilities <- distribution(record, labels)
                confidence    <- probabilityAt(record, "confidence")
              yield DecisionEvidence.Choice(labels.zip(probabilities).toMap, confidence)
        case other =>
          Left(ValidationError(s"The evidence of '$name' must be a JSON object, got ${DecisionJson.describe(other)}"))
    }

  /** The output values of the evidence of each decision output (upstream `DecisionState._decode`):
    *   - a [[DecisionKind.Noul]] gives `value = p >= threshold`, the confidence of [[Noul]], and the probability;
    *   - a [[DecisionKind.Score]] needs a total probability above zero. It gives the mean level index, the confidence,
    *     the distribution, and the level of the cuts;
    *   - a [[DecisionKind.Choice]] needs a total probability above zero. It picks the label with the largest
    *     `probability * weight`. A weighted tie goes to the raw winner (the label with the largest probability, the
    *     first in the order of declaration on a tie), then to the order of declaration. A pick with a weighted score of
    *     zero or less is a `Left` ("no positive probability mass").
    *
    * A rich output gives its result record: `{value, confidence, probability}` for a Noul, `{value, confidence,
    * probabilities, level}` for a Score (the probabilities keyed by `"0"` to `"N-1"`), and `{value, confidence,
    * probabilities}` for a Choice. A native output gives the plain value: a Boolean, or the label. Evidence that lacks
    * a decision output is a `Left`. The evidence must obey the rules of [[evidence]]: the keys of each distribution,
    * and a finite number in [0, 1] for each probability and each confidence.
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
    val rich = layout.outputFields.find(_.name == name).exists(DecisionKind.isRich)
    (kind, answer) match
      case (DecisionKind.Noul, DecisionEvidence.Noul(p)) => probability(name, "noul", p).map { _ =>
          val t     = settingsOf(name).threshold.getOrElse(DecisionPlan.defaultThreshold)
          val value = DecisionJson.boolean(p >= t)
          if !rich then value
          else
            val confidence = math.abs(p - t) / math.max(t, 1 - t)
            DynamicValue.Record(
              "value"       -> value,
              "confidence"  -> DecisionJson.number(confidence),
              "probability" -> DecisionJson.number(p)
            )
        }
      case (DecisionKind.Score(levels), DecisionEvidence.Score(probabilities, confidence)) =>
        val indexes = levels.indices.toVector
        for
          _ <- Either.cond(
                 probabilities.keySet == indexes.toSet,
                 (),
                 ValidationError(s"Invalid Score distribution for '$name': the keys must be 0 to ${levels.size - 1}")
               )
          read  <- checked(name, indexes.map(probabilities), confidence)
          total <- positive(name, "Score", read)
        yield
          val value = indexes.map(i => i * read(i)).sum / total
          val cuts  = settingsOf(name).cuts.getOrElse(DecisionPlan.defaultCuts(levels.size))
          DynamicValue.Record(
            "value"         -> DecisionJson.number(value),
            "confidence"    -> DecisionJson.number(confidence),
            "probabilities" ->
              DynamicValue.Record(Chunk.from(indexes.map(i => i.toString -> DecisionJson.number(read(i))))),
            "level" -> DynamicValue.Primitive(PrimitiveValue.Int(cuts.count(_ <= value)))
          )
      case (DecisionKind.Choice(labels), DecisionEvidence.Choice(probabilities, confidence)) =>
        for
          _ <- Either.cond(
                 probabilities.keySet == labels.toSet,
                 (),
                 ValidationError(s"Invalid Choice answer for '$name': the keys must be ${labels.mkString(", ")}")
               )
          read     <- checked(name, labels.map(probabilities), confidence)
          _        <- positive(name, "Choice", read)
          selected <- pick(name, labels, read)
        yield
          val value = DecisionJson.string(labels(selected))
          if !rich then value
          else
            DynamicValue.Record(
              "value"         -> value,
              "confidence"    -> DecisionJson.number(confidence),
              "probabilities" ->
                DynamicValue.Record(Chunk.from(labels.zip(read).map((l, p) => l -> DecisionJson.number(p))))
            )
      case _ => Left(ValidationError(s"The evidence of '$name' is not the evidence of a ${kind.typeName} output"))

  /** The index of the label with the largest `probability * weight`. A weighted tie goes to the raw winner, then to the
    * first label (upstream `DecisionState._decode`).
    */
  private def pick(name: String, labels: Vector[String], probabilities: Vector[Double]): Either[DspyError, Int] =
    val weights   = settingsOf(name).weights.getOrElse(Map.empty)
    val scores    = labels.indices.map(i => probabilities(i) * weights.getOrElse(labels(i), 1.0)).toVector
    val rawWinner = probabilities.indexOf(probabilities.max)
    val selected  = labels.indices.foldLeft(0) { (best, i) =>
      val better = scores(i) > scores(best) || (scores(i) == scores(best) && i == rawWinner && best != rawWinner)
      if better then i else best
    }
    Either.cond(
      scores(selected) > 0,
      selected,
      ValidationError(s"Choice weights for '$name' leave no positive probability mass")
    )

  /** The probabilities, when each probability and the confidence is a finite number in [0, 1]. */
  private def checked(
      name         : String,
      probabilities: Vector[Double],
      confidence   : Double
  ): Either[DspyError, Vector[Double]] =
    probabilities.find(p => probability(name, "probability", p).isLeft) match
      case Some(p) => probability(name, "probability", p).map(_ => probabilities)
      case None    => probability(name, "confidence", confidence).map(_ => probabilities)

  /** The total of the probabilities, when it is above zero (upstream `DecisionState._decode`). */
  private def positive(name: String, label: String, probabilities: Vector[Double]): Either[DspyError, Double] =
    val total = probabilities.sum
    Either.cond(
      total > 0,
      total,
      ValidationError(s"Invalid $label distribution for '$name': the total is not positive")
    )

  /** `p` when it is a finite number in [0, 1] (upstream `Probability`). A NaN breaks the rule. */
  private def probability(name: String, key: String, p: Double): Either[DspyError, Double] =
    Either.cond(
      p >= 0 && p <= 1,
      p,
      ValidationError(s"The '$key' of the evidence of '$name' must be a finite number in [0, 1], got: $p")
    )

  private def kindOf(name: String): Option[DecisionKind] = kinds.collectFirst { case (n, kind) if n == name => kind }

  private def settingsOf(name: String): DecisionSettings = settings.getOrElse(name, DecisionSettings())

  /** The description of the field `name`, or `None` for a placeholder (`${name}`) or a blank text (upstream
    * `_question`).
    */
  private def description(name: String): Option[String] =
    layout.outputFields.find(_.name == name).flatMap(_.description)
      .filter(text => text != s"$${$name}" && text.trim.nonEmpty)

  private def notDecision(name: String): DspyError = ConfigurationError(s"'$name' is not a decision output")

object DecisionPlan:

  /** The default threshold of a [[DecisionKind.Noul]] (upstream `DecisionState`). */
  private val defaultThreshold: Double = 0.5

  /** The default cuts of a [[DecisionKind.Score]] with `levels` levels: `[0.5, 1.5, ...]` (upstream `DecisionState`).
    */
  private def defaultCuts(levels: Int): Vector[Double] = (0 until levels - 1).map(_ + 0.5).toVector

  /** The plan of `layout` with the explicit `settings`. Each rich output is a decision output. A native output is one
    * only when `settings` has an entry for it. A `Left` comes when a settings entry names an output that cannot be a
    * decision output, when a settings entry sets the parameter of another kind (for example `cuts` for a Noul), when a
    * value breaks a rule of [[DecisionSettings]], when a Score does not have 2 to 10 levels, or when a Choice has no
    * label or two equal labels. Instructions and criteria must be JSON strings, objects, arrays, or null, with no
    * number that is not finite.
    */
  def of(
      layout  : SignatureLayout,
      settings: Map[String, DecisionSettings]
  ): Either[DspyError, DecisionPlan] =
    val kinds = layout.outputFields.flatMap { field =>
      if DecisionKind.isRich(field) || settings.contains(field.name) then DecisionKind.of(field).map(field.name -> _)
      else None
    }
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
    val (parameter, others) = kind match
      case DecisionKind.Noul      => ("threshold", Vector("cuts" -> settings.cuts, "weights" -> settings.weights))
      case DecisionKind.Score(_)  => ("cuts", Vector("threshold" -> settings.threshold, "weights" -> settings.weights))
      case DecisionKind.Choice(_) => ("weights", Vector("threshold" -> settings.threshold, "cuts" -> settings.cuts))
    val foreign = others.collect { case (key, Some(_)) => key }
    for
      _ <- check(
             foreign.isEmpty,
             s"Invalid settings for '$name': require '$parameter', with optional instructions and criteria, " +
               s"not ${foreign.mkString(", ")}"
           )
      _ <- validateKind(name, kind)
      _ <- settings.instructions.fold(Right(()))(DecisionJson.question("instructions", _).map(_ => ()))
      _ <- settings.criteria.fold(Right(()))(DecisionJson.question("criteria", _).map(_ => ()))
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
        val t = settings.threshold.getOrElse(defaultThreshold)
        check(t >= 0 && t <= 1, s"Threshold for '$name' must be in [0, 1]")
      case DecisionKind.Score(levels) =>
        val cuts = settings.cuts.getOrElse(defaultCuts(levels.size))
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
            labels.exists(label => weights.getOrElse(label, 1.0) > 0),
          s"Choice weights for '$name' must map known labels to finite, nonnegative numbers " +
            "with at least one positive effective weight"
        )

  private def check(rule: Boolean, message: => String): Either[DspyError, Unit] =
    Either.cond(rule, (), ConfigurationError(message))

  /** The shape rules of the criteria (upstream `DecisionState._validate_question_config`). */
  private def validCriteria(kind: DecisionKind, criteria: DynamicValue): Boolean =
    kind match
      case DecisionKind.Noul => criteria match
          case _: DynamicValue.Null.type   => true
          case record: DynamicValue.Record =>
            DynamicValues.recordKeys(record).forall(key => key == "true" || key == "false") &&
            record.fields.forall((_, entry) => DecisionJson.isDescription(entry))
          case _ => false
      case DecisionKind.Choice(labels) => criteria match
          case record: DynamicValue.Record =>
            val keys = DynamicValues.recordKeys(record)
            keys.size == labels.size && keys.toSet == labels.toSet &&
            record.fields.forall((_, entry) => DecisionJson.isDescription(entry))
          case _ => false
      case DecisionKind.Score(levels) => criteria match
          case sequence: DynamicValue.Sequence => sequence.elements.size == levels.size && levels.size >= 2 &&
            levels.size <= 10 &&
            sequence.elements.forall {
              case _: DynamicValue.Null.type => false
              case entry                     => DecisionJson.isDescription(entry)
            }
          case _ => false

/** The JSON rules of the decision values. */
private object DecisionJson:

  def string(text: String): DynamicValue = DynamicValue.Primitive(PrimitiveValue.String(text))

  def boolean(value: Boolean): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Boolean(value))

  def number(value: Double): DynamicValue = DynamicValue.Primitive(PrimitiveValue.Double(value))

  /** The value of a number. The dynamic JSON codec of zio-blocks gives an `Int`, a `Long`, or a `BigDecimal`, and the
    * other sources can give each other number type. A Boolean is not a number.
    */
  def numberOf(value: DynamicValue): Option[Double] =
    value match
      case DynamicValue.Primitive(primitive) => primitive match
          case PrimitiveValue.Int(n)        => Some(n.toDouble)
          case PrimitiveValue.Long(n)       => Some(n.toDouble)
          case PrimitiveValue.Double(n)     => Some(n)
          case PrimitiveValue.Float(n)      => Some(n.toDouble)
          case PrimitiveValue.BigDecimal(n) => Some(n.toDouble)
          case PrimitiveValue.BigInt(n)     => Some(n.toDouble)
          case PrimitiveValue.Short(n)      => Some(n.toDouble)
          case PrimitiveValue.Byte(n)       => Some(n.toDouble)
          case _                            => None
      case _ => None

  /** `value` when it is a JSON string, object, array, or null, with JSON values in it (upstream
    * `_validate_question_config`). `key` names the setting in the message of the `Left`.
    */
  def question(key: String, value: DynamicValue): Either[DspyError, DynamicValue] =
    if !isDescription(value) then
      Left(ValidationError(s"Invalid $key: expected a JSON string, object, array, or null, got ${describe(value)}"))
    else if !isJson(value) then
      Left(ValidationError(s"Invalid JSON in $key: each number must be finite, and each value must be JSON"))
    else Right(value)

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
      case _ if numberOf(value).isDefined                    => "a number"
      case _                                                 => "a value that is not JSON"

  /** `true` for a JSON string, object, array, or null: the forms of a description (upstream `_description_json`). */
  def isDescription(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type                        => true
      case DynamicValue.Primitive(PrimitiveValue.String(_)) => true
      case _: DynamicValue.Record                           => true
      case _: DynamicValue.Sequence                         => true
      case _                                                => false

  /** `true` for a JSON value: a string, a Boolean, a finite number, null, or an object or an array of JSON values. */
  def isJson(value: DynamicValue): Boolean =
    value match
      case _: DynamicValue.Null.type         => true
      case record: DynamicValue.Record       => record.fields.forall((_, entry) => isJson(entry))
      case sequence: DynamicValue.Sequence   => sequence.elements.forall(isJson)
      case DynamicValue.Primitive(primitive) => primitive match
          case _: PrimitiveValue.String  => true
          case _: PrimitiveValue.Boolean => true
          case PrimitiveValue.Double(n)  => n.isFinite
          case PrimitiveValue.Float(n)   => n.isFinite
          case _                         => numberOf(value).isDefined
      case _ => false
