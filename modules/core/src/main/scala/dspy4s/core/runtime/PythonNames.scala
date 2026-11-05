package dspy4s.core.runtime

/** The Python rules for a name that the host puts in a sandbox namespace (a variable or a tool). */
private[dspy4s] object PythonNames:

  /** The keywords of Python 3.12 (`keyword.kwlist`). Python code cannot bind a keyword as a name. */
  val Keywords: Set[String] = Set(
    "False",
    "None",
    "True",
    "and",
    "as",
    "assert",
    "async",
    "await",
    "break",
    "class",
    "continue",
    "def",
    "del",
    "elif",
    "else",
    "except",
    "finally",
    "for",
    "from",
    "global",
    "if",
    "import",
    "in",
    "is",
    "lambda",
    "nonlocal",
    "not",
    "or",
    "pass",
    "raise",
    "return",
    "try",
    "while",
    "with",
    "yield"
  )

  /** True when `name` is a Python identifier (Python `str.isidentifier`): the first character is a letter or `_`, and
    * each other character is a letter, a digit, or `_`. Unicode letters and digits are permitted. A keyword is an
    * identifier too; see [[Keywords]].
    */
  def isIdentifier(name: String): Boolean =
    val codePoints = name.codePoints.toArray
    codePoints.nonEmpty &&
    (Character.isLetter(codePoints(0)) || codePoints(0) == Underscore) &&
    codePoints.iterator.drop(1).forall(c => Character.isLetterOrDigit(c) || c == Underscore)

  /** True when Python code can bind `name`: an identifier that is not a keyword. */
  def isBindable(name: String): Boolean = isIdentifier(name) && !Keywords.contains(name)

  private val Underscore: Int = '_'.toInt
