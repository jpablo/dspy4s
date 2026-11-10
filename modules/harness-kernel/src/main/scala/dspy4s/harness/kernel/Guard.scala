package dspy4s.harness.kernel

import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

/** Which arguments of an operation a rule covers (5.5). */
enum ArgMatch derives Schema, CanEqual:

  /** Only these arguments. */
  case Exact(value: Json)

  /** A text argument `field` that starts with `text`. The match compares a normal form of both texts, so a cell cannot
    * go around a rule with `//etc`, `/./etc` or two spaces.
    *
    * When `text` starts with `/`, the rule is a path rule, and the value matches only when it starts with `/` too. The
    * normal form splits a path on `/`, and removes the empty parts and the `.` parts. A `..` part removes the part
    * before it, and at the root it stays at the root. The value matches when its parts start with all the parts of
    * `text`. Thus `/etc` matches `/etc`, `/etc/` and `/etc/hosts`, and it does not match `/etcetera` or `/tmp/etc`.
    *
    * The kernel is pure, so the normal form uses only the text and reads no file system. Thus a relative path never
    * matches a path rule, and a symbolic link goes around it. The handler of a file call must put absolute normal paths
    * in the arguments of the call.
    *
    * Else the rule is a text rule. The normal form changes each run of whitespace to one space, with no space at the
    * start or at the end. The value matches when it starts with `text`, and the next character of the value, if there
    * is one, cannot go on with the last word of `text`. A letter, a digit, `-`, `_` and `.` go on with a word, and each
    * other character ends it. Thus `git push` matches `git push origin`, `git push;x` and `git push|x`, and it does not
    * match `git pushx`, `git push-x` or `git pull`. A rule on `https://a.example` matches `https://a.example/x`, and it
    * does not match `https://a.example.other.net`.
    *
    * When the last character of `text` already ends a word, for example the `/` of `rm -rf /`, each next character is
    * valid. An empty `text` matches each value.
    */
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
                case (name, Json.String(value)) => name == field && startsWith(value, text)
                case _                          => false
              }
            case _ => false
        case ArgMatch.AnyArgs => true)

  /** The match of `ArgMatch.Prefix`: a path rule when `text` starts with `/`, else a text rule. */
  private def startsWith(value: String, text: String): Boolean =
    if text.startsWith("/") then value.startsWith("/") && GuardRule.parts(value).startsWith(GuardRule.parts(text))
    else
      val (v, t) = (GuardRule.spaced(value), GuardRule.spaced(text))
      def ends   = v.length == t.length || !GuardRule.inWord(t.last) || !GuardRule.inWord(v(t.length))
      t.isEmpty || (v.startsWith(t) && ends)

  /** True when a grant of this rule allows the pending call `call`. A pending call has `Exact` arguments. */
  def covers(call: GuardRule): Boolean =
    call.args match
      case ArgMatch.Exact(value) => matches(call.op, value)
      case _                     => this == call

object GuardRule:

  /** The parts of a path in the normal form of `ArgMatch.Prefix`: no empty part, no `.` part and no `..` part. */
  private def parts(path: String): Vector[String] =
    path.split('/').foldLeft(Vector.empty[String]) {
      case (found, "" | ".") => found
      case (found, "..")     => found.dropRight(1)
      case (found, part)     => found :+ part
    }

  /** A text in the normal form of `ArgMatch.Prefix`: one space for each run of whitespace, and no space at the ends. */
  private def spaced(text: String): String = text.split("\\s+").filter(_.nonEmpty).mkString(" ")

  /** True when `c` goes on with a word: a letter, a digit, `-`, `_` or `.`. */
  private def inWord(c: Char): Boolean = c.isLetterOrDigit || c == '-' || c == '_' || c == '.'

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
