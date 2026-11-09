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

/** A rule for the calls of one operation of the guest API (5.5). */
final case class GuardRule(op: String, args: ArgMatch) derives Schema, CanEqual:

  /** True when the rule matches the call. Pure and total. */
  def matches(callOp: String, callArgs: Json): Boolean = ???

  /** True when a grant of this rule allows the pending call `call`. A pending call has `Exact` arguments. */
  def covers(call: GuardRule): Boolean = ???

/** What a rule says about a call (5.5). The verdicts form a chain, so `join` is a semilattice. */
enum Verdict derives Schema, CanEqual:
  case Allow, Ask, Deny

  /** The stronger verdict of the two. */
  def join(that: Verdict): Verdict = ???

final case class VerdictRule(rule: GuardRule, verdict: Verdict) derives Schema, CanEqual

/** The rules of a session (5.5). `and` is a monoid, and `allowAll` is its identity. */
final case class Guard(rules: Vector[VerdictRule]) derives Schema, CanEqual:

  /** The join of the verdicts of the rules that match the call, or `Allow` when no rule matches it. */
  def verdict(op: String, args: Json): Verdict = ???

  /** The rules of both guards. */
  def and(that: Guard): Guard = ???

object Guard:
  val allowAll: Guard = Guard(Vector.empty)

/** How long a grant lasts (5.5). `Once` ends at the outcome of the first call that the rule matches, `Turn` at the next
  * `TurnEnded`, and `Session` at `Ended`.
  */
enum GrantLifetime derives Schema, CanEqual:
  case Once, Turn, Session
