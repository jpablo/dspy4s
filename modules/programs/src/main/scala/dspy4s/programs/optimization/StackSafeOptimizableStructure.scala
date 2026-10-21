package dspy4s.programs.optimization

import scala.collection.mutable.ArrayDeque
import scala.compiletime.ops.int.+
import scala.util.control.TailCalls.{TailRec, done, tailcall}

/** Explicit-heap interpreter for structural optimizer trees.
  *
  * Composite instances describe only their immediate children and rebuild operation. This interpreter performs arity,
  * inspection, and replacement without recursive calls between composite instances. Each [[Node]] keeps a value and the
  * structure witness for its static type together, so the interpreter needs no cast.
  */
private[dspy4s] object StackSafeOptimizableStructure:

  private final case class NamePath(parent: Option[NamePath], segment: String)

  private object NamePath:
    def append(path: Option[NamePath], segment: Option[String]): Option[NamePath] =
      segment.fold(path)(part => Some(NamePath(path, part)))

    def qualify(path: Option[NamePath], name: String): String =
      path match
        case None    => name
        case Some(_) =>
          val parts   = ArrayDeque.empty[String]
          var current = path
          while current.nonEmpty do
            val element = current.get
            parts.prepend(element.segment)
            current = element.parent
          if name != "self" then parts.append(name)
          parts.mkString(".")

  /** A value with the structure witness for its static type. A work list hides `P`, but the value and the witness of
    * one node always agree.
    *
    * `parent` and `segment` are the unresolved name of the node. Only the inspection resolves [[path]], so the arity
    * count allocates no name.
    */
  private final case class Node[P](
      value    : P,
      structure: OptimizableStructure[P],
      parent   : Option[NamePath],
      segment  : Option[String]
  ):
    def path: Option[NamePath] = NamePath.append(parent, segment)

    /** Put the child nodes at the front of `pending` when the witness is a [[Branch]]. Report whether it is one. */
    def expandInto(pending: ArrayDeque[Node[?]], childParent: Option[NamePath]): Boolean =
      structure match
        case branch: Branch[P] =>
          branch.pushChildren(value, childParent, pending)
          true
        case _ => false

    def leafArity: Int                               = structure.arity(value)
    def leafNamed: Vector[(String, OptimizableView)] = structure.inspectNamed(value)

  /** The ordered updates of one replacement. Each leaf takes its slice in read order. */
  private final class Updates(values: Vector[OptimizableParameters]):
    private var cursor = 0

    def take(count: Int): Vector[OptimizableParameters] =
      val end   = cursor + count
      val slice = values.slice(cursor, end)
      cursor = end
      slice

    def consumed: Int = cursor

  /** A structure that the interpreter opens. It extends [[OptimizableStructure]] so the compiler can check the type
    * test `case branch: Branch[P]` against an `OptimizableStructure[P]`.
    */
  private sealed trait Branch[P] extends OptimizableStructure[P]:
    protected def label: String

    /** Put the children of `value` at the front of `pending`, in read order. `path` is the name path of `value`. */
    def pushChildren(value: P, path: Option[NamePath], pending: ArrayDeque[Node[?]]): Unit

    /** Rebuild `value` from its replaced children. The trampoline keeps the recursion on the heap. */
    def replaceChildren(value: P, updates: Updates): TailRec[P]

    final def arity(program: P): Int = arityFrom(Node(program, this, None, None))

    final def inspect(program: P): Vector[OptimizableView] =
      inspectNamed(program).map(_._2)

    final override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
      inspectNamedFrom(Node(program, this, None, None))

    final def replace(program: P, updates: Vector[OptimizableParameters]): P =
      val expected = arity(program)
      require(expected == updates.size, s"$label expects $expected updates, got ${updates.size}")

      val remaining = Updates(updates)
      val rebuilt   = replaceChildren(program, remaining).result
      require(
        remaining.consumed == updates.size,
        s"$label consumed ${remaining.consumed} updates, but received ${updates.size}"
      )
      rebuilt

  private final class Unary[P, A, N <: Int](
      protected val label: String,
      get                : P => A,
      replaceInner       : (P, A) => P,
      inner              : OptimizableStructure.WithArity[A, N]
  ) extends OptimizableStructure.Of[P, N]
      with Branch[P]:
    def pushChildren(value: P, path: Option[NamePath], pending: ArrayDeque[Node[?]]): Unit =
      pending.prepend(Node(get(value), inner, path, None))

    def replaceChildren(value: P, updates: Updates): TailRec[P] =
      replaceNode(get(value), inner, updates).map(replaceInner(value, _))

  private final class Pair[P, A, B, N <: Int](
      protected val label: String,
      leftName           : Option[String],
      rightName          : Option[String],
      getLeft            : P => A,
      getRight           : P => B,
      replacePair        : (P, A, B) => P,
      left               : OptimizableStructure[A],
      right              : OptimizableStructure[B]
  ) extends OptimizableStructure.Of[P, N]
      with Branch[P]:
    def pushChildren(value: P, path: Option[NamePath], pending: ArrayDeque[Node[?]]): Unit =
      pending.prepend(Node(getRight(value), right, path, rightName))
      pending.prepend(Node(getLeft(value), left, path, leftName))

    def replaceChildren(value: P, updates: Updates): TailRec[P] =
      for
        rebuiltLeft  <- replaceNode(getLeft(value), left, updates)
        rebuiltRight <- replaceNode(getRight(value), right, updates)
      yield replacePair(value, rebuiltLeft, rebuiltRight)

  /** Replace one subtree. A branch is suspended, so the descent stays on the heap. A leaf takes its slice now, which
    * keeps the read order: `replaceChildren` asks for the right child only after the left child is complete.
    */
  private def replaceNode[P](value: P, structure: OptimizableStructure[P], updates: Updates): TailRec[P] =
    structure match
      case branch: Branch[P] => tailcall(branch.replaceChildren(value, updates))
      case leaf              => done(leaf.replace(value, updates.take(leaf.arity(value))))

  private def arityFrom(root: Node[?]): Int =
    val pending = ArrayDeque[Node[?]](root)
    var result  = 0

    while pending.nonEmpty do
      val node = pending.removeHead()
      if !node.expandInto(pending, None) then result += node.leafArity

    result

  private def inspectNamedFrom(root: Node[?]): Vector[(String, OptimizableView)] =
    val pending = ArrayDeque[Node[?]](root)
    val result  = Vector.newBuilder[(String, OptimizableView)]

    while pending.nonEmpty do
      val node = pending.removeHead()
      val path = node.path
      if !node.expandInto(pending, path) then
        node.leafNamed.foreach { case (name, view) => result.addOne(NamePath.qualify(path, name) -> view) }

    result.result()

  def unary[P, A, N <: Int](
      label       : String,
      get         : P => A,
      replaceInner: (P, A) => P,
      inner       : OptimizableStructure.WithArity[A, N]
  ): OptimizableStructure.Of[P, N] =
    new Unary(label, get, replaceInner, inner)

  def pair[P, A, B, NA <: Int, NB <: Int](
      label      : String,
      leftName   : Option[String],
      rightName  : Option[String],
      getLeft    : P => A,
      getRight   : P => B,
      replacePair: (P, A, B) => P,
      left       : OptimizableStructure.WithArity[A, NA],
      right      : OptimizableStructure.WithArity[B, NB]
  ): OptimizableStructure.Of[P, NA + NB] =
    new Pair[P, A, B, NA + NB](label, leftName, rightName, getLeft, getRight, replacePair, left, right)
