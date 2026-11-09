package dspy4s.harness.guest

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The laws of the modes (5.2): a bounded meet-semilattice, and the closed law. */
class ModeLaws extends ScalaCheckSuite:

  private val access: Gen[Access] = Gen.oneOf(Access.values.toSeq)

  private val modes: Gen[Mode] =
    for
      files    <- access
      shell    <- access
      network  <- access
      llm      <- access
      sessions <- access
      children <- access
      store    <- access
    yield Mode(files, shell, network, llm, sessions, children, store)

  property("Mode lattice: meet is associative") {
    forAll(modes, modes, modes)((a, b, c) => assertEquals(a.meet(b).meet(c), a.meet(b.meet(c))))
  }

  property("Mode lattice: meet is commutative") {
    forAll(modes, modes)((a, b) => assertEquals(a.meet(b), b.meet(a)))
  }

  property("Mode lattice: meet is idempotent") {
    forAll(modes)(a => assertEquals(a.meet(a), a))
  }

  property("Mode lattice: Mode.full is the identity of meet") {
    forAll(modes)(a => assertEquals(a.meet(Mode.full), a))
  }

  property("Mode lattice: a <= b exactly when a.meet(b) == a") {
    forAll(modes, modes)((a, b) => assertEquals(a <= b, a.meet(b) == a))
  }

  property("Closed mode: a meet with Mode.closed gives sessions at most Read, and it keeps children") {
    forAll(modes) { base =>
      val closed = base.meet(Mode.closed)
      assert(closed.sessions <= Access.Read, s"sessions is ${closed.sessions}")
      assertEquals(closed.children, base.children)
    }
  }
