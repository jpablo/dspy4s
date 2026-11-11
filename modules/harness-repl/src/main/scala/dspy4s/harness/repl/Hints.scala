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
  else if confusesCompiler(error) then
    // The error of a top-level val of a new Array also shows `Array[...]^{any.rd}`, so this check comes before the
    // check of an Array pattern.
    Some("In safe mode, a top-level val of a new Array or of a capability needs an explicit type, for example " +
      "val xs: Array[Int] = Array(1, 2, 3) or val f: Files^ = summon[Files]. A Vector or a List needs no type.")
  else if error.contains("Array[") && error.contains("^{any.rd}") && !showsArrayDefect(error) then
    // The error of the compiler defect (`showsArrayDefect`) also shows `Array[...]^{any.rd}`, but its cell has no
    // Array pattern, so the hint of a pattern would be wrong. After an earlier cell, the REPL asks for a new REPL.
    Some("case Array(x, y) inside collect. Use .toList, and then case List(x, y).")
  else if error.contains("Cannot refer to method error in package object sys") then
    // Safe mode refuses `sys.error`. A throw of a new exception compiles, and the cell fails with its message. So do
    // `require(cond, "...")` and `assert(cond, "...")`.
    Some(
      "Safe mode refuses sys.error. To fail the cell with a message, use throw IllegalStateException(\"the reason\")."
    )
  else if error.contains("empty quoted identifier") then
    // A Markdown fence starts with ``, an empty quoted identifier. The harness removes one outer fence of the code
    // field, so this error comes from a fence that the harness did not find, or from a cell of the MCP server.
    Some("The code field holds only Scala code, with no ``` fence. Write the code again with no ``` line.")
  else None

/** True for a compile error after which the compiler cannot go on (6). In safe mode, the compiler refuses a top-level
  * val of a new Array or of a capability with no type. After that cell, the compiler of the 3.10 nightly keeps some
  * state, and each later Array chain in the same REPL fails to compile. Only a new REPL recovers, so the REPL sets
  * `needsRestart`. The hint for the same error reads this function, so the hint and the restart see the same text.
  */
def confusesCompiler(error: String): Boolean =
  error.contains("needs an explicit type because the inferred type does not conform")

/** True for a compile error that shows the defect of `confusesCompiler` (6): a `Found:` type of `Array[...]` with a
  * capture set `^{...}`, and a `Required:` type of `Array[...]`, for example for `Array(4, 5).map(_ + 1).sum`. Some
  * cells cause the defect with no error of their own, for example a top-level `def` whose signature names a type of the
  * same cell. Then the error of a later Array chain is the first sign, and only a new REPL recovers.
  *
  * The same error can also be a real error. A new REPL has no state of an earlier cell, so the REPL reads this function
  * only after an earlier cell. In the first cell of a new REPL, a restart would only repeat the error.
  */
def showsArrayDefect(error: String): Boolean =
  arrayFound.findFirstIn(error).isDefined && arrayRequired.findFirstIn(error).isDefined

/** The `Found:` line of an Array type with a capture set, for example `Found: Array[Int]^{any.rd}`. */
private val arrayFound = """Found:\s*Array\[.*\]\^\{[^}]*\}""".r

/** The `Required:` line of an Array type, for example `Required: Array[Int]`. */
private val arrayRequired = """Required:\s*Array\[""".r

/** The capture set of a type in an error, for example `^{files}`. */
private val capabilityName = """\^\{([A-Za-z_][\w.]*)\}""".r

/** The field that holds a capability, in the note of a capture error: "it contains a field files: ...". */
private val capabilityField = """contains a field ([A-Za-z_]\w*):""".r
