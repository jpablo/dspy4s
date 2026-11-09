package dspy4s.harness.kernel

import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** Which arguments of an operation a rule covers (5.5). */
enum ArgMatch derives Schema, CanEqual:

  /** Only these arguments. */
  case Exact(value: Json)

  /** A text argument that starts with `text`. */
  case Prefix(field: String, text: String)

  /** Each call of the operation. */
  case AnyArgs

/** `Exact` compares the `Json` of the arguments, so a grant matches its call before and after a restart (5.5). */
private given CanEqual[Json, Json] = CanEqual.derived

/** A rule for the calls of one operation of the guest API (5.5). */
final case class GuardRule(op: String, args: ArgMatch) derives Schema, CanEqual:

  /** True when the rule matches the call. Pure and total. */
  def matches(callOp: String, callArgs: Json): Boolean =
    callOp == op &&
      (args match
        case ArgMatch.Exact(value)        => value == callArgs
        case ArgMatch.Prefix(field, text) => callArgs match
            case Json.Object(fields) => fields.exists {
                case (name, Json.String(value)) => name == field && value.startsWith(text)
                case _                          => false
              }
            case _ => false
        case ArgMatch.AnyArgs => true)

  /** True when a grant of this rule allows the pending call `call`. A pending call has `Exact` arguments. */
  def covers(call: GuardRule): Boolean =
    call.args match
      case ArgMatch.Exact(value) => matches(call.op, value)
      case _                     => this == call

/** What a rule says about a call (5.5). The verdicts form a chain, so `join` is a semilattice. */
enum Verdict derives Schema, CanEqual:
  case Allow, Ask, Deny

  /** The stronger verdict of the two. */
  def join(that: Verdict): Verdict = if ordinal >= that.ordinal then this else that

final case class VerdictRule(rule: GuardRule, verdict: Verdict) derives Schema, CanEqual

/** The rules of a session (5.5). `and` is a monoid, and `allowAll` is its identity. */
final case class Guard(rules: Vector[VerdictRule]) derives Schema, CanEqual:

  /** The join of the verdicts of the rules that match the call, or `Allow` when no rule matches it. */
  def verdict(op: String, args: Json): Verdict =
    rules.foldLeft(Verdict.Allow) { (found, rule) =>
      if rule.rule.matches(op, args) then found.join(rule.verdict) else found
    }

  /** The rules of both guards. */
  def and(that: Guard): Guard = Guard(rules ++ that.rules)

object Guard:
  val allowAll: Guard = Guard(Vector.empty)

/** How long a grant lasts (5.5). `Once` ends at the outcome of the first call that the rule matches, `Turn` at the next
  * `TurnEnded`, and `Session` at `Ended`.
  */
enum GrantLifetime derives Schema, CanEqual:
  case Once, Turn, Session
