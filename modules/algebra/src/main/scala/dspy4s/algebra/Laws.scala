package dspy4s.algebra

/** An equation between two `A`s, as a VALUE (the `IsEq` shape from jpablo/math-with-scala): the lhs/rhs of a law,
  * stated as a method WHERE THE STRUCTURE LIVES and executed by the law suites. The statement carries no notion of
  * equality of its own; the executing suite chooses the honest check for each law (structural `==` for pure carriers,
  * observational equality — run output / params / decode — for effectful ones). This is the deliberate split from a
  * formalization library: there the equations are the deliverable, here they are executable specifications.
  */
final case class IsEq[A](lhs: A, rhs: A)

extension [A](lhs: A) infix def <->(rhs: A): IsEq[A] = IsEq(lhs, rhs)

/** Marks a method as a LAW STATEMENT: a definitional equation of the enclosing structure, returning [[IsEq]]. Metadata
  * only (mirrors math-with-scala's annotation); the law suites execute the statements.
  */
final case class Law(description: String = "") extends scala.annotation.StaticAnnotation
