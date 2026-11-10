package dspy4s.harness

import dspy4s.harness.kernel.Entry

/** The known secret values of CODE_HARNESS_DESIGN.md, 5.6, by name: the values behind `secretHeaders`, and the values
  * of the secret-like variables of the harness process. The writer masks each one in each string of an entry, as
  * `«secret:NAME»`. The mask is a second guard: it does not find a value that a command cuts or encodes.
  *
  * A proposal for the review: a value shorter than `Secrets.minLength` is not a known value, because its mask would
  * change common text. A value that is a part of a mask, for example a part of a name, is not a known value either,
  * because a second mask would change the first one.
  */
final case class Secrets(values: Map[String, String]) derives CanEqual:

  /** The text with each known value as `«secret:NAME»`. A longer value goes first, so a value inside another value does
    * not cut it.
    */
  def maskText(text: String): String = ???

  /** The entry with each string masked: each text field, each key of a map, and each string and key in a `Json`. */
  def mask(entry: Entry): Entry = ???

object Secrets:
  val none: Secrets = Secrets(Map.empty)

  val minLength: Int = 8

  /** The known values: the value of each variable of `env` that `Environment.isSecret` names, and the value of each
    * header of `headers`, apart from the values that are too short or that are a part of a mask.
    */
  def known(env: Map[String, String], headers: Map[String, String]): Secrets = ???
