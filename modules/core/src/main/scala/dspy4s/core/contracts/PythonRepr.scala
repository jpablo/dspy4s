package dspy4s.core.contracts

/** Python `repr` of a float. The adapters, the provider layer, and GEPA use it, so one number gives one text. */
private[dspy4s] object PythonRepr:

  /** Python `repr(number)`: `nan`, `inf`, or `-inf` for a value that is not finite. Else the shortest digits that give
    * the same float, a fixed point form for an exponent from -4 to 15 (with `.0` for an integral value), and else the
    * exponent form, for example `1e-05` and `1e+16`.
    */
  def float(number: Double): String =
    if number.isNaN then "nan"
    else if number.isInfinite then (if number > 0 then "inf" else "-inf")
    else
      val sign            = if java.lang.Double.compare(number, 0.0) < 0 then "-" else ""
      val magnitude       = math.abs(number)
      val (digits, point) = shortest(java.lang.Double.toString(magnitude), text => text.toDouble == magnitude)
      sign + layout(digits, point)

  /** [[float]] for a 32-bit float: the shortest digits that give the same 32-bit float. */
  def float32(number: Float): String =
    if number.isNaN then "nan"
    else if number.isInfinite then (if number > 0 then "inf" else "-inf")
    else
      val sign            = if java.lang.Float.compare(number, 0.0f) < 0 then "-" else ""
      val magnitude       = math.abs(number)
      val (digits, point) = shortest(java.lang.Float.toString(magnitude), text => text.toFloat == magnitude)
      sign + layout(digits, point)

  /** The digits and the decimal point of the Java text of a positive number: the number is `0.<digits> × 10^point`.
    * Java gives the shortest digits, but it can give two digits where one digit also gives the same number. Python then
    * uses the one digit, so this function checks the one-digit forms. `same` tells if a decimal text gives the same
    * number.
    */
  private def shortest(javaText: String, same: String => Boolean): (String, Int) =
    val (mantissa, exponent) = javaText.indexOf('E') match
      case -1 => (javaText, 0)
      case at => (javaText.substring(0, at), javaText.substring(at + 1).toInt)
    val dot     = mantissa.indexOf('.')
    val whole   = if dot < 0 then mantissa else mantissa.substring(0, dot)
    val all     = if dot < 0 then mantissa else whole + mantissa.substring(dot + 1)
    val leading = all.indexWhere(_ != '0')
    if leading < 0 then ("0", 1)
    else
      val digits = all.substring(leading).reverse.dropWhile(_ == '0').reverse
      val point  = whole.length + exponent - leading
      if digits.length != 2 then (digits, point)
      else
        val first      = digits.head.asDigit
        val candidates =
          Vector((first.toString, point), (if first == 9 then ("1", point + 1) else ((first + 1).toString, point)))
            .filter((digit, at) => same(s"0.${digit}E$at"))
        val exact = BigDecimal(s"0.${digits}E$point")
        candidates.minByOption((digit, at) => (BigDecimal(s"0.${digit}E$at") - exact).abs).getOrElse((digits, point))

  /** The Python `repr` layout of `0.<digits> × 10^point` (`format_float_short` with the mode `r`). */
  private def layout(digits: String, point: Int): String =
    if point <= -4 || point > 16 then
      val exponent = point - 1
      val mantissa = if digits.length > 1 then s"${digits.head}.${digits.tail}" else digits
      f"${mantissa}e${if exponent < 0 then "-" else "+"}${math.abs(exponent)}%02d"
    else if point <= 0 then "0." + "0" * -point + digits
    else if point >= digits.length then digits + "0" * (point - digits.length) + ".0"
    else s"${digits.substring(0, point)}.${digits.substring(point)}"
