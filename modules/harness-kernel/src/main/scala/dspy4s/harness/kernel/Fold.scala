package dspy4s.harness.kernel

/** A view of a journal as a fold, with its own state (4.2). Folds compose, so several views need one pass: the note of
  * lost names (6), the ledger, the usage totals and the deterministic part of a `SessionCard`.
  */
final case class Fold[-E, S, +A](initial: S, step: (S, E) => S, done: S => A):

  /** The fold of the entries. */
  def apply(entries: Iterable[E]): A = done(entries.foldLeft(initial)(step))

  def map[B](f: A => B): Fold[E, S, B] = Fold(initial, step, s => f(done(s)))

  /** Both folds in one pass. */
  def zip[F <: E, T, B](that: Fold[F, T, B]): Fold[F, (S, T), (A, B)] =
    Fold(
      (initial, that.initial),
      (both, entry) => (step(both._1, entry), that.step(both._2, entry)),
      both => (done(both._1), that.done(both._2))
    )
