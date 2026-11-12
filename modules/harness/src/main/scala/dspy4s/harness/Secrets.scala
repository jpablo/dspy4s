package dspy4s.harness

import dspy4s.harness.kernel.{
  ArgMatch, CellOutcome, EffectResult, Entry, GuardRule, ReleaseItem, ReleaseResult, ReleaseStep
}
import dspy4s.harness.kernel.SessionOutcome
import dspy4s.harness.kernel.TurnOutcome
import dspy4s.harness.sandbox.Environment
import zio.blocks.schema.json.Json

/** The known secret values of CODE_HARNESS_DESIGN.md, 5.6, by name: the values behind `secretHeaders`, and the values
  * of the secret-like variables of the harness process. The writer masks each one in each string of an entry, as
  * `«secret:NAME»`. The mask is a second guard: it does not find a value that a command cuts or encodes.
  *
  * A proposal for the review: a value shorter than `Secrets.minLength` is not a known value, because its mask would
  * change common text. A value that is a part of a mask, for example a part of a name, is not a known value either,
  * because a second mask would change the first one. Nor is a value with the marks « or », because the end of a mask
  * and the text after it could make it.
  */
final case class Secrets(values: Map[String, String]) derives CanEqual:

  /** The values, longest first, and by name for two values of one length. */
  private val ordered: Vector[(String, String)] =
    values.toVector.filter((_, value) => value.nonEmpty).sortBy((name, value) => (-value.length, name))

  /** The text with each known value as `«secret:NAME»`. A longer value goes first, so a value inside another value does
    * not cut it.
    */
  def maskText(text: String): String =
    ordered.foldLeft(text) { case (masked, (name, value)) => masked.replace(value, Secrets.mark(name)) }

  /** The entry with each string masked: each text field, each key of a map, and each string and key in a `Json`. With
    * no known value, the entry stays as it is. The configuration of `Activated` holds no secret (5.6), so only its free
    * texts get the mask: the preamble, the goal and the options of the model.
    */
  def mask(entry: Entry): Entry =
    if ordered.isEmpty then entry
    else
      entry match
        case Entry.Activated(versions, config) =>
          val model = config.model.copy(options = fields(config.model.options))
          Entry.Activated(
            versions,
            config.copy(preamble = maskText(config.preamble), goal = config.goal.map(maskText), model = model)
          )
        case Entry.Input(request, text, inputs)       => Entry.Input(request, maskText(text), fields(inputs))
        case Entry.Acted(reasoning, text, code)       => Entry.Acted(maskText(reasoning), maskText(text), code.map(maskText))
        case Entry.EffectStarted(id, op, args)        => Entry.EffectStarted(id, op, json(args))
        case Entry.EffectDone(id, result)             => Entry.EffectDone(id, effect(result))
        case Entry.Observed(output, outcome, ws)      => Entry.Observed(maskText(output), cell(outcome), fields(ws))
        case Entry.StopRequested(reason)              => Entry.StopRequested(maskText(reason))
        case Entry.JobsStopRequested(which, why)      => Entry.JobsStopRequested(which, maskText(why))
        case Entry.Granted(request, r, lifetime)      => Entry.Granted(request, rule(r), lifetime)
        case Entry.ScopeRecorded(head, clean)         => Entry.ScopeRecorded(maskText(head), clean)
        case Entry.Releasing(plan)                    => Entry.Releasing(plan.map(releaseItem))
        case Entry.Released(step)                     => Entry.Released(released(step))
        case Entry.TurnEnded(outcome)                 => Entry.TurnEnded(turn(outcome))
        case Entry.Ended(outcome, reason)             => Entry.Ended(session(outcome), maskText(reason))
        case Entry.TurnStarted | Entry.StepStarted(_) => entry

  /** A step of a release with each text masked: the paths, the names, the commit and the error (8.7). */
  private def released(step: ReleaseStep): ReleaseStep =
    val result = step.result match
      case ReleaseResult.Failed(error)   => ReleaseResult.Failed(maskText(error))
      case ReleaseResult.Skipped(reason) => ReleaseResult.Skipped(maskText(reason))
      case ReleaseResult.Done            => ReleaseResult.Done
    ReleaseStep(releaseItem(step.item), result)

  /** An item of a release with each text masked. */
  private def releaseItem(item: ReleaseItem): ReleaseItem =
    item match
      case ReleaseItem.RemoveWorktree(repo, path)       => ReleaseItem.RemoveWorktree(maskText(repo), maskText(path))
      case ReleaseItem.DeleteBranch(repo, name, commit) =>
        ReleaseItem.DeleteBranch(maskText(repo), maskText(name), maskText(commit))
      case ReleaseItem.KeepRef(repo, name, branch, commit) =>
        ReleaseItem.KeepRef(maskText(repo), maskText(name), maskText(branch), maskText(commit))
      case ReleaseItem.ArchiveDirectory(path, to) => ReleaseItem.ArchiveDirectory(maskText(path), maskText(to))
      case ReleaseItem.Keep(resource, reason)     => ReleaseItem.Keep(maskText(resource), maskText(reason))

  /** `value` with each known value masked, also in its keys. */
  def maskJson(value: Json): Json = json(value)

  private def json(value: Json): Json =
    value match
      case Json.String(text)  => Json.String(maskText(text))
      case Json.Array(items)  => Json.Array(items.map(json))
      case Json.Object(pairs) => Json.Object(pairs.map((key, item) => (maskText(key), json(item))))
      case other              => other

  private def fields(map: Map[String, Json]): Map[String, Json] = map.map((key, item) => maskText(key) -> json(item))

  private def rule(r: GuardRule): GuardRule =
    r.args match
      case ArgMatch.Exact(args)         => r.copy(args = ArgMatch.Exact(json(args)))
      case ArgMatch.Prefix(field, text) => r.copy(args = ArgMatch.Prefix(field, maskText(text)))
      case ArgMatch.AnyArgs             => r

  private def effect(result: EffectResult): EffectResult =
    result match
      case EffectResult.Value(value, rendered) => EffectResult.Value(json(value), maskText(rendered))
      case EffectResult.Error(message)         => EffectResult.Error(maskText(message))
      case EffectResult.NeedsApproval(r)       => EffectResult.NeedsApproval(rule(r))
      case EffectResult.Unknown                => result

  private def cell(outcome: CellOutcome): CellOutcome =
    outcome match
      case CellOutcome.CompileError(lines)          => CellOutcome.CompileError(lines.map(maskText))
      case CellOutcome.Failed(exception)            => CellOutcome.Failed(maskText(exception))
      case CellOutcome.Submitted(value)             => CellOutcome.Submitted(json(value))
      case CellOutcome.Ok | CellOutcome.TimedOut(_) => outcome

  private def turn(outcome: TurnOutcome): TurnOutcome =
    outcome match
      case TurnOutcome.Replied(text)                                         => TurnOutcome.Replied(maskText(text))
      case TurnOutcome.Submitted(value)                                      => TurnOutcome.Submitted(json(value))
      case TurnOutcome.Failed(error)                                         => TurnOutcome.Failed(maskText(error))
      case TurnOutcome.Exhausted | TurnOutcome.Aborted | TurnOutcome.Crashed => outcome

  private def session(outcome: SessionOutcome): SessionOutcome =
    outcome match
      case SessionOutcome.Abandoned(reason)                                           => SessionOutcome.Abandoned(maskText(reason))
      case SessionOutcome.Merged | SessionOutcome.Parked | SessionOutcome.Replaced(_) => outcome

object Secrets:
  val none: Secrets = Secrets(Map.empty)

  val minLength: Int = 8

  /** The text that takes the place of the value with the name `name`. */
  def mark(name: String): String = s"«secret:$name»"

  /** The known values: the value of each variable of `env` that `Environment.isSecret` names, and the value of each
    * header of `headers`, apart from the values that are too short, that hold « or », or that are a part of a mask.
    */
  def known(env: Map[String, String], headers: Map[String, String]): Secrets =
    val all   = env.filter((name, _) => Environment.isSecret(name)) ++ headers
    val marks = all.keys.map(mark)
    Secrets(all.filter { (_, value) =>
      value.length >= minLength && !value.exists(c => c == '«' || c == '»') && !marks.exists(_.contains(value))
    })
