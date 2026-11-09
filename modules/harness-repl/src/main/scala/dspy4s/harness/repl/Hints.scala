package dspy4s.harness.repl

/** The hint for a compile error that models cannot read (6), or none. The host adds it after the error. */
def hint(error: String): Option[String] =
  if error.contains("Maximal number of successive inlines") then
    Some("A recursive type needs one more line, for example: given Data[T] = Data.derived")
  else if error.contains("is not included in the allowed capture set") then
    val names = capabilityName.findAllMatchIn(error).map(_.group(1)).filterNot(_.isEmpty).toVector.distinct
    val which = if names.isEmpty then "a capability" else names.mkString("the capability ", ", ", "")
    Some(s"The cell uses $which in a place that must be pure. Pass the value that you need, not the capability.")
  else if error.contains("Vector[String]{val prefix") then
    Some("A Vector in a pair, for example in groupBy(f).map { case (k, v) => ... }. Use .view.mapValues, " +
      "groupMapReduce or .toList.")
  else if error.contains("Array[") && error.contains("^{any.rd}") then
    Some("case Array(x, y) inside collect. Use .toList, and then case List(x, y).")
  else None

/** The capture set of a type in an error, for example `^{files}`. */
private val capabilityName = """\^\{([A-Za-z_][\w.]*)\}""".r
