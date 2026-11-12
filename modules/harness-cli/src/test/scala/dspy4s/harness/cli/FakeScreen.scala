package dspy4s.harness.cli

/** A small model of the screen of a terminal for the laws: it reads the text and the escape codes that JLine writes,
  * and it gives the rows that a person sees. It knows the moves of the cursor, the erase of a line or of the screen,
  * the insert and the delete of lines and characters, a scroll region, a save and a restore of the cursor, and the
  * line-drawing characters of the DEC set (`ESC ( 0`), which JLine writes for a box character such as `─`. A character
  * at the last column waits for the next character before the line wraps, as in an xterm. Each other code changes
  * nothing.
  */
final class FakeScreen(width: Int, height: Int):

  private val cells  = Array.fill(height)(Array.fill(width)(' '))
  private var x      = 0
  private var y      = 0
  private var wrap   = false
  private var top    = 0
  private var bottom = height - 1
  private var saved  = (0, 0)
  private var lines  = false // the DEC set of line-drawing characters is on

  /** The rows of the screen, with no space at their ends. */
  def rows: Vector[String] = cells.toVector.map(_.mkString.stripTrailing)

  /** Reads `text`, the output of a terminal. */
  def feed(text: String): Unit =
    var i = 0
    while i < text.length do
      val c = text.charAt(i)
      if c == '\u001b' && i + 1 < text.length then i = escape(text, i + 1)
      else
        c match
          case '\r' =>
            x = 0
            wrap = false
          case '\n' => lineFeed()
          case '\b' =>
            x = (x - 1).max(0)
            wrap = false
          case '\t'                                      => x = ((x / 8 + 1) * 8).min(width - 1)
          case other if other < ' ' || other == '\u007f' => ()
          case other                                     => put(other)
        i += 1

  private def put(raw: Char): Unit =
    val c = if lines then FakeScreen.LineDrawing.getOrElse(raw, raw) else raw
    if wrap then
      x = 0
      lineFeed()
      wrap = false
    cells(y)(x) = c
    if x == width - 1 then wrap = true else x += 1

  private def lineFeed(): Unit =
    wrap = false
    if y == bottom then scrollUp(top, bottom, 1) else y = (y + 1).min(height - 1)

  private def scrollUp(from: Int, to: Int, n: Int): Unit =
    for _ <- 0 until n do
      for row <- from until to do cells(row) = cells(row + 1)
      cells(to) = Array.fill(width)(' ')

  private def scrollDown(from: Int, to: Int, n: Int): Unit =
    for _ <- 0 until n do
      for row <- (from + 1 to to).reverse do cells(row) = cells(row - 1)
      cells(from) = Array.fill(width)(' ')

  /** Reads the code after an ESC at `start`, and gives the index after it. */
  private def escape(text: String, start: Int): Int =
    text.charAt(start) match
      case '[' =>
        var j = start + 1
        while j < text.length && !text.charAt(j).isLetter && "@`{|}~".indexOf(text.charAt(j)) < 0 do j += 1
        if j < text.length then csi(text.substring(start + 1, j), text.charAt(j))
        j + 1
      case ']' =>
        // An OSC ends with BEL or with ESC \.
        var j = start + 1
        while j < text.length && text.charAt(j) != '\u0007' && !(text.charAt(j) == '\u001b' && j + 1 < text.length)
        do j += 1
        if j < text.length && text.charAt(j) == '\u001b' then j + 2 else j + 1
      case '(' if start + 1 < text.length =>
        lines = text.charAt(start + 1) == '0'
        start + 2
      case '7' =>
        saved = (x, y)
        start + 1
      case '8' =>
        x = saved._1
        y = saved._2
        wrap = false
        start + 1
      case 'M' =>
        if y == top then scrollDown(top, bottom, 1) else y = (y - 1).max(0)
        start + 1
      case _ => start + 1

  private def csi(params: String, op: Char): Unit =
    val plain                        = !params.startsWith("?") && !params.exists(c => c == ' ' || c == '>' || c == '=')
    val nums                         = params.filter(c => c.isDigit || c == ';').split(';').toVector.map(_.toIntOption)
    def n(k: Int, default: Int): Int = nums.lift(k).flatten.filter(_ > 0).getOrElse(default)
    if plain then
      op match
        case 'A'       => y = (y - n(0, 1)).max(0); wrap = false
        case 'B'       => y = (y + n(0, 1)).min(height - 1); wrap = false
        case 'C'       => x = (x + n(0, 1)).min(width - 1); wrap = false
        case 'D'       => x = (x - n(0, 1)).max(0); wrap = false
        case 'G'       => x = (n(0, 1) - 1).min(width - 1); wrap = false
        case 'H' | 'f' =>
          y = (n(0, 1) - 1).min(height - 1)
          x = (n(1, 1) - 1).min(width - 1)
          wrap = false
        case 'K' => nums.headOption.flatten.getOrElse(0) match
            case 1 => for col <- 0 to x do cells(y)(col) = ' '
            case 2 => cells(y) = Array.fill(width)(' ')
            case _ => for col <- x until width do cells(y)(col) = ' '
        case 'J' => nums.headOption.flatten.getOrElse(0) match
            case 1 =>
              for row <- 0 until y do cells(row) = Array.fill(width)(' ')
              for col <- 0 to x do cells(y)(col) = ' '
            case 2 => for row <- 0 until height do cells(row) = Array.fill(width)(' ')
            case _ =>
              for col <- x until width do cells(y)(col) = ' '
              for row <- y + 1 until height do cells(row) = Array.fill(width)(' ')
        case 'L' => if y >= top && y <= bottom then scrollDown(y, bottom, n(0, 1))
        case 'M' => if y >= top && y <= bottom then scrollUp(y, bottom, n(0, 1))
        case 'P' =>
          val k   = n(0, 1)
          val row = cells(y)
          for col <- x until width do row(col) = if col + k < width then row(col + k) else ' '
        case '@' =>
          val k   = n(0, 1)
          val row = cells(y)
          for col <- (x until width).reverse do row(col) = if col - k >= x then row(col - k) else ' '
        case 'r' =>
          top = n(0, 1) - 1
          bottom = (n(1, height) - 1).min(height - 1)
          x = 0
          y = 0
        case 's' => saved = (x, y)
        case 'u' =>
          x = saved._1
          y = saved._2
        case _ => ()

object FakeScreen:

  /** The characters of the DEC set of line-drawing characters, as Unicode. */
  private val LineDrawing: Map[Char, Char] = Map(
    'q' -> '─',
    'x' -> '│',
    'l' -> '┌',
    'k' -> '┐',
    'm' -> '└',
    'j' -> '┘',
    't' -> '├',
    'u' -> '┤',
    'v' -> '┴',
    'w' -> '┬',
    'n' -> '┼'
  )

  /** The rows that a person sees after `output`, on a screen of `width` columns and `height` rows. */
  def of(output: String, width: Int, height: Int): Vector[String] =
    val screen = FakeScreen(width, height)
    screen.feed(output)
    screen.rows
