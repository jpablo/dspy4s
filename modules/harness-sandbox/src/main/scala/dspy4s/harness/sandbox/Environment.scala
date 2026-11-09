package dspy4s.harness.sandbox

import java.util.Locale

/** The environment variables of a command (CODE_HARNESS_DESIGN.md, 5.6). The harness process holds the keys of the LM
  * provider, and Java and Codex pass all variables to a child by default. So a command gets only an allowlist.
  */
object Environment:

  /** The names that each command gets from the harness process. */
  val allowed: Set[String] =
    Set("PATH", "HOME", "SHELL", "TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL", "LC_CTYPE", "LOGNAME", "USER", "TERM")

  /** A name that contains one of these words, with no case, never goes through, also when the session adds it. */
  val secretWords: Vector[String] = Vector("KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH")

  def isSecret(name: String): Boolean =
    val upper = name.toUpperCase(Locale.ROOT)
    secretWords.exists(upper.contains)

  /** The variables of a command: the names of the allowlist and the names `extra` of `SessionConfig.env`, with the
    * values of `parent`, apart from each secret name.
    */
  def forChild(parent: Map[String, String], extra: Set[String]): Map[String, String] =
    parent.filter((name, _) => (allowed.contains(name) || extra.contains(name)) && !isSecret(name))
