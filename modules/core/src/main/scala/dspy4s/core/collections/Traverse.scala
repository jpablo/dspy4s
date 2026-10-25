package dspy4s.core.collections

import scala.annotation.tailrec

/** Apply a function to each item, in the order of the items, and collect the results. */
private[dspy4s] object Traverse:

  /** The results of `f` for the items, in the order of the items. The first `Left` stops the traverse, and the result
    * is that `Left`. `f` does not run on the items after that item.
    */
  def either[A, E, B](items: IterableOnce[A])(f: A => Either[E, B]): Either[E, Vector[B]] =
    val iterator = items.iterator

    @tailrec def loop(done: Vector[B]): Either[E, Vector[B]] =
      if !iterator.hasNext then Right(done)
      else
        f(iterator.next()) match
          case Right(result) => loop(done :+ result)
          case Left(error)   => Left(error)
    loop(Vector.empty)

  /** The results of `f` for the items, in the order of the items, when `f` gives a result for each item. `None` when
    * `f` gives `None` for one item. `f` does not run on the items after that item.
    */
  def option[A, B](items: IterableOnce[A])(f: A => Option[B]): Option[Vector[B]] =
    either(items)(item => f(item).toRight(())).toOption
