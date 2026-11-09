package dspy4s.harness.kernel

/** A view of a journal as a fold, with its own state (4.2). Folds compose, so several views need one pass: the note of
  * lost names (6), the ledger, the usage totals and the deterministic part of a `SessionCard`.
  */
final case class Fold[-E, S, +A](initial: S, step: (S, E) => S, done: S => A):

  /** The fold of the entries. */
  def apply(entries: Iterable[E]): A = ???

  def map[B](f: A => B): Fold[E, S, B] = ???

  /** Both folds in one pass. */
  def zip[F <: E, T, B](that: Fold[F, T, B]): Fold[F, (S, T), (A, B)] = ???
