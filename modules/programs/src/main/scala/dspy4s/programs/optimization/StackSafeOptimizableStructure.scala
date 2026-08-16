package dspy4s.programs.optimization

import scala.collection.mutable.ArrayDeque
import scala.compiletime.ops.int.+

/** Explicit-heap interpreter for structural optimizer trees.
  *
  * Composite instances describe only their immediate children and rebuild operation. This interpreter performs arity,
  * inspection, and replacement without recursive calls between composite instances. Runtime erasure is confined to
  * this file. Each erased structure witness stays paired with the value whose static type produced that witness.
  */
private[dspy4s] object StackSafeOptimizableStructure:

  private final case class Child(
      value    : Any,
      structure: OptimizableStructure[Any],
      segment  : Option[String]
  )

  private trait Branch:
    def children(value: Any): Vector[Child]
    def rebuild(value: Any, children: Vector[Any]): Any

  private final class Unary[P, A, N <: Int](
      label       : String,
      get         : P => A,
      replaceInner: (P, A) => P,
      inner       : OptimizableStructure.WithArity[A, N]
  ) extends OptimizableStructure.Of[P, N]
      with Branch:
    def arity(program: P): Int = arityFrom(program, this)

    def inspect(program: P): Vector[OptimizableView] =
      inspectNamedFrom(program, this).map(_._2)

    override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
      inspectNamedFrom(program, this)

    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      replaceFrom(label, program, updates, this).asInstanceOf[P]

    def children(value: Any): Vector[Child] =
      val program = value.asInstanceOf[P]
      Vector(Child(get(program), erase(inner), None))

    def rebuild(value: Any, children: Vector[Any]): Any =
      replaceInner(value.asInstanceOf[P], children.head.asInstanceOf[A])

  private final class Pair[P, A, B, N <: Int](
      label      : String,
      leftName  : Option[String],
      rightName : Option[String],
      getLeft   : P => A,
      getRight  : P => B,
      replacePair: (P, A, B) => P,
      left      : OptimizableStructure[A],
      right     : OptimizableStructure[B]
  ) extends OptimizableStructure.Of[P, N]
      with Branch:
    def arity(program: P): Int = arityFrom(program, this)

    def inspect(program: P): Vector[OptimizableView] =
      inspectNamedFrom(program, this).map(_._2)

    override def inspectNamed(program: P): Vector[(String, OptimizableView)] =
      inspectNamedFrom(program, this)

    def replace(program: P, updates: Vector[OptimizableParameters]): P =
      replaceFrom(label, program, updates, this).asInstanceOf[P]

    def children(value: Any): Vector[Child] =
      val program = value.asInstanceOf[P]
      Vector(
        Child(getLeft(program), erase(left), leftName),
        Child(getRight(program), erase(right), rightName)
      )

    def rebuild(value: Any, children: Vector[Any]): Any =
      replacePair(value.asInstanceOf[P], children(0).asInstanceOf[A], children(1).asInstanceOf[B])

  private final case class NamePath(parent: Option[NamePath], segment: String)

  private object NamePath:
    def append(path: Option[NamePath], segment: Option[String]): Option[NamePath] =
      segment.fold(path)(part => Some(NamePath(path, part)))

    def qualify(path: Option[NamePath], name: String): String =
      path match
        case None => name
        case Some(_) =>
          val parts   = ArrayDeque.empty[String]
          var current = path
          while current.nonEmpty do
            val element = current.get
            parts.prepend(element.segment)
            current = element.parent
          if name != "self" then parts.append(name)
          parts.mkString(".")

  private final case class Visit(value: Any, structure: OptimizableStructure[Any], path: Option[NamePath])

  private sealed trait ReplaceWork
  private final case class ReplaceVisit(value: Any, structure: OptimizableStructure[Any]) extends ReplaceWork
  private final case class Rebuild(branch: Branch, value: Any, childCount: Int)          extends ReplaceWork

  private def erase[P](structure: OptimizableStructure[P]): OptimizableStructure[Any] =
    structure.asInstanceOf[OptimizableStructure[Any]]

  private def arityFrom(value: Any, structure: OptimizableStructure[?]): Int =
    val pending = ArrayDeque(Visit(value, erase(structure), None))
    var result  = 0

    while pending.nonEmpty do
      val visit = pending.removeHead()
      visit.structure match
        case branch: Branch =>
          val children = branch.children(visit.value)
          children.reverseIterator.foreach(child => pending.prepend(Visit(child.value, child.structure, None)))
        case leaf => result += leaf.arity(visit.value)

    result

  private def inspectNamedFrom(
      value    : Any,
      structure: OptimizableStructure[?]
  ): Vector[(String, OptimizableView)] =
    val pending = ArrayDeque(Visit(value, erase(structure), None))
    val result  = Vector.newBuilder[(String, OptimizableView)]

    while pending.nonEmpty do
      val visit = pending.removeHead()
      visit.structure match
        case branch: Branch =>
          val children = branch.children(visit.value)
          children.reverseIterator.foreach { child =>
            pending.prepend(Visit(child.value, child.structure, NamePath.append(visit.path, child.segment)))
          }
        case leaf =>
          leaf.inspectNamed(visit.value).foreach { case (name, view) =>
            result.addOne(NamePath.qualify(visit.path, name) -> view)
          }

    result.result()

  private def replaceFrom(
      label    : String,
      value    : Any,
      updates  : Vector[OptimizableParameters],
      structure: OptimizableStructure[?]
  ): Any =
    val expected = arityFrom(value, structure)
    require(expected == updates.size, s"$label expects $expected updates, got ${updates.size}")

    val pending = ArrayDeque[ReplaceWork](ReplaceVisit(value, erase(structure)))
    val results = ArrayDeque.empty[Any]
    var cursor  = 0

    while pending.nonEmpty do
      pending.removeHead() match
        case ReplaceVisit(currentValue, currentStructure) =>
          currentStructure match
            case branch: Branch =>
              val children = branch.children(currentValue)
              pending.prepend(Rebuild(branch, currentValue, children.size))
              children.reverseIterator.foreach(child => pending.prepend(ReplaceVisit(child.value, child.structure)))
            case leaf =>
              val childArity = leaf.arity(currentValue)
              val end        = cursor + childArity
              results.prepend(leaf.replace(currentValue, updates.slice(cursor, end)))
              cursor = end
        case Rebuild(branch, original, childCount) =>
          val rebuilt = Array.ofDim[Any](childCount)
          var index   = childCount - 1
          while index >= 0 do
            rebuilt(index) = results.removeHead()
            index -= 1
          results.prepend(branch.rebuild(original, rebuilt.toVector))

    require(cursor == updates.size, s"$label consumed $cursor updates, but received ${updates.size}")
    results.removeHead()

  def unary[P, A, N <: Int](
      label       : String,
      get         : P => A,
      replaceInner: (P, A) => P,
      inner       : OptimizableStructure.WithArity[A, N]
  ): OptimizableStructure.Of[P, N] =
    new Unary(label, get, replaceInner, inner)

  def pair[P, A, B, NA <: Int, NB <: Int](
      label      : String,
      leftName  : Option[String],
      rightName : Option[String],
      getLeft   : P => A,
      getRight  : P => B,
      replacePair: (P, A, B) => P,
      left      : OptimizableStructure.WithArity[A, NA],
      right     : OptimizableStructure.WithArity[B, NB]
  ): OptimizableStructure.Of[P, NA + NB] =
    new Pair[P, A, B, NA + NB](label, leftName, rightName, getLeft, getRight, replacePair, left, right)
