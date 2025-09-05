package dspy.utils

trait Logger {
  def debug(msg: => String): Unit
  def info(msg: => String): Unit
  def warn(msg: => String): Unit
  def error(msg: => String): Unit
}

object ConsoleLogger extends Logger {
  private def ts = java.time.Instant.now().toString
  override def debug(msg: => String): Unit = println(s"[DEBUG] $ts $msg")
  override def info(msg: => String): Unit  = println(s"[INFO]  $ts $msg")
  override def warn(msg: => String): Unit  = println(s"[WARN]  $ts $msg")
  override def error(msg: => String): Unit = println(s"[ERROR] $ts $msg")
}

object Redaction {
  def redact(input: String, secrets: Seq[String]): String =
    secrets.filter(_.nonEmpty).foldLeft(input) { case (acc, s) => acc.replace(s, "***") }

  def truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "…"
}
