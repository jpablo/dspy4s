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

  /** The names that contain a secret word but do not hold a secret: AUTHOR contains AUTH. Each name must be the same,
    * with the same case. They go through as other names do, when they are on the allowlist or in `SessionConfig.env`.
    * Each other name with a secret word stays out, for example HTTP_AUTHORIZATION.
    */
  val notSecret: Set[String] = Set("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_AUTHOR_DATE")

  def isSecret(name: String): Boolean =
    val upper = name.toUpperCase(Locale.ROOT)
    !notSecret.contains(name) && secretWords.exists(upper.contains)

  /** The variables of a command: the names of the allowlist and the names `extra` of `SessionConfig.env`, with the
    * values of `parent`, apart from each secret name.
    */
  def forChild(parent: Map[String, String], extra: Set[String]): Map[String, String] =
    parent.filter((name, _) => (allowed.contains(name) || extra.contains(name)) && !isSecret(name))
