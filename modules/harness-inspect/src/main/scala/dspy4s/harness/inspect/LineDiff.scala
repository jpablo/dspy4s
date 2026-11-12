package dspy4s.harness.inspect

import scala.annotation.tailrec

/** A change of one line in a diff (docs/code-harness/OBSERVABILITY.md, 5.4). */
enum Edit derives CanEqual:
  case Keep(line: String)
  case Remove(line: String)
  case Add(line: String)

/** A part of a diff with the lines around its changes. `fromA` and `fromB` are the numbers of its first lines in the
  * two texts, from 1.
  */
final case class Hunk(fromA: Int, fromB: Int, edits: Vector[Edit]) derives CanEqual

/** The diff of two texts by lines (5.4).
  *
  *   - The common start and the common end of the two texts are kept lines. Each step of a work session sends the whole
  *     prompt again, so these parts are most of a prompt.
  *   - The middle comes from the table of the longest common subsequence, so the diff keeps as many lines as possible.
  *   - A middle whose table has more than `Cells` cells is one removal and one addition, so a diff never needs much
  *     memory. It is still a correct diff, only not the smallest one.
  */
object LineDiff:

  /** The most cells of the table of the longest common subsequence: 16 MB of `Int`s. */
  val Cells: Long = 4_000_000L

  def diff(a: Vector[String], b: Vector[String]): Vector[Edit] =
    val start = a.iterator.zip(b.iterator).takeWhile((x, y) => x == y).size
    val end   = a.drop(start).reverseIterator.zip(b.drop(start).reverseIterator).takeWhile((x, y) => x == y).size
    val left  = a.slice(start, a.size - end)
    val right = b.slice(start, b.size - end)
    a.take(start).map(Edit.Keep(_)) ++ middle(left, right) ++ a.takeRight(end).map(Edit.Keep(_))

  /** `a` with `edits`: each kept and each removed line must be the next line of `a`, and the result has the kept and
    * the added lines. None when the edits do not fit `a`.
    */
  def patch(a: Vector[String], edits: Vector[Edit]): Option[Vector[String]] =
    @tailrec
    def walk(rest: List[Edit], at: Int, out: Vector[String]): Option[Vector[String]] =
      rest match
        case Nil                                                    => Option.when(at == a.size)(out)
        case Edit.Keep(line) :: more if a.lift(at).contains(line)   => walk(more, at + 1, out :+ line)
        case Edit.Remove(line) :: more if a.lift(at).contains(line) => walk(more, at + 1, out)
        case Edit.Add(line) :: more                                 => walk(more, at, out :+ line)
        case _                                                      => None
    walk(edits.toList, 0, Vector.empty)

  /** The changes of `edits` in parts, each with at most `context` kept lines before its first change and after its last
    * one. Two changes with at most `2 * context` kept lines between them are in one part.
    */
  def hunks(edits: Vector[Edit], context: Int = 3): Vector[Hunk] =
    val changes = edits.indices.filter(i => !isKeep(edits(i))).toVector
    if changes.isEmpty then Vector.empty
    else
      // The numbers of the lines of a and b before each edit.
      val before = edits.scanLeft((0, 0)) { case ((x, y), edit) =>
        edit match
          case Edit.Keep(_)   => (x + 1, y + 1)
          case Edit.Remove(_) => (x + 1, y)
          case Edit.Add(_)    => (x, y + 1)
      }
      val groups = changes.tail.foldLeft(Vector(Vector(changes.head))) { (done, i) =>
        if i - done.last.last - 1 <= 2 * context then done.init :+ (done.last :+ i) else done :+ Vector(i)
      }
      groups.map { group =>
        val from   = (group.head - context).max(0)
        val to     = (group.last + context).min(edits.size - 1)
        val (x, y) = before(from)
        Hunk(x + 1, y + 1, edits.slice(from, to + 1))
      }

  private def isKeep(edit: Edit): Boolean =
    edit match
      case Edit.Keep(_) => true
      case _            => false

  private def middle(a: Vector[String], b: Vector[String]): Vector[Edit] =
    if a.isEmpty then b.map(Edit.Add(_))
    else if b.isEmpty then a.map(Edit.Remove(_))
    else if a.size.toLong * b.size > Cells then a.map(Edit.Remove(_)) ++ b.map(Edit.Add(_))
    else common(a, b)

  /** The diff of `a` and `b` from the table of the lengths of the longest common subsequences of their ends. A removal
    * comes before an addition when both keep as many lines.
    */
  private def common(a: Vector[String], b: Vector[String]): Vector[Edit] =
    val width = b.size + 1
    val table = new Array[Int]((a.size + 1) * width)
    for i <- a.indices.reverse; j <- b.indices.reverse do
      table(i * width + j) =
        if a(i) == b(j) then table((i + 1) * width + j + 1) + 1
        else table((i + 1) * width + j).max(table(i * width + j + 1))
    val out = Vector.newBuilder[Edit]
    var i   = 0
    var j   = 0
    while i < a.size || j < b.size do
      if i < a.size && j < b.size && a(i) == b(j) then
        out += Edit.Keep(a(i))
        i += 1
        j += 1
      else if j >= b.size || (i < a.size && table((i + 1) * width + j) >= table(i * width + j + 1)) then
        out += Edit.Remove(a(i))
        i += 1
      else
        out += Edit.Add(b(j))
        j += 1
    out.result()
