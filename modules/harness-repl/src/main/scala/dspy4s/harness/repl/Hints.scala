package dspy4s.harness.repl

/** The hint for a compile error that models cannot read (6), or none. The host adds it after the error. */
def hint(error: String): Option[String] =
  if error.contains("Maximal number of successive inlines") then
    Some("The derivation of Data went too deep. Give the type a Data with one line: given Data[T] = Data.derived")
  else if error.contains("is defined in a class that does not extend `Stateful`") then
    Some("A cell cannot have a top-level var. Use a val, or put the var in a block: locally { var n = 0; ... }")
  else if error.contains("is not included in the allowed capture set") then
    // The field that holds the capability names it best, for example `files`. A root capability such as `any.rd`
    // is not a name that a model can use.
    val fields = capabilityField.findAllMatchIn(error).map(_.group(1)).toVector
    val names  = (fields ++ capabilityName.findAllMatchIn(error).map(_.group(1)))
      .filterNot(n => n.isEmpty || n.startsWith("any") || n.startsWith("cap")).distinct
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

/** The field that holds a capability, in the note of a capture error: "it contains a field files: ...". */
private val capabilityField = """contains a field ([A-Za-z_]\w*):""".r
