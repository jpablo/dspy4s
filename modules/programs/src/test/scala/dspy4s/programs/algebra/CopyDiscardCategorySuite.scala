package dspy4s.programs.algebra

import dspy4s.core.algebra.{AnyObject, IsEq, Isomorphism}
import munit.FunSuite

/** Executes the complete symmetric-monoidal and copy/discard law vocabulary on Scala's cartesian product. */
class CopyDiscardCategorySuite extends FunSuite:

  private object Functions extends CartesianCategory[Function1]:
    def id[A: AnyObject]: A => A = identity

    extension [A, B](f: A => B)
      infix def >>>[C](g: B => C): A => C = f.andThen(g)

    def tensor[A, B, C, D](f: A => C, g: B => D): ((A, B)) => (C, D) =
      case (a, b) => (f(a), g(b))

    private def iso[A, B](forward: A => B, backward: B => A): Isomorphism[AnyObject, Function1, A, B] =
      Isomorphism[AnyObject, Function1, A, B](forward, backward)(using summon, summon, this)

    def associator[A, B, C]: Isomorphism[AnyObject, Function1, ((A, B), C), (A, (B, C))] =
      iso[((A, B), C), (A, (B, C))](
        { case ((a, b), c) => (a, (b, c)) },
        { case (a, (b, c)) => ((a, b), c) }
      )

    def leftUnitor[A]: Isomorphism[AnyObject, Function1, (Unit, A), A] =
      iso[(Unit, A), A]({ case ((), value) => value }, value => ((), value))

    def rightUnitor[A]: Isomorphism[AnyObject, Function1, (A, Unit), A] =
      iso[(A, Unit), A]({ case (value, ()) => value }, value => (value, ()))

    def braiding[A, B]: Isomorphism[AnyObject, Function1, (A, B), (B, A)] =
      iso[(A, B), (B, A)]({ case (a, b) => (b, a) }, { case (b, a) => (a, b) })

    def copy[A]: A => (A, A)  = value => (value, value)
    def discard[A]: A => Unit = _ => ()

  private def assertFunctionLaw[A, B](law: IsEq[A => B], samples: Vector[A]): Unit =
    samples.foreach(value => assertEquals(law.lhs(value), law.rhs(value)))

  test("Scala functions satisfy all structural isomorphism laws") {
    val associator  = Functions.associator[Int, String, Boolean]
    val leftUnitor  = Functions.leftUnitor[Int]
    val rightUnitor = Functions.rightUnitor[Int]
    val braiding    = Functions.braiding[Int, String]

    assertFunctionLaw(associator.forwardBackward, Vector(((1, "a"), true)))
    assertFunctionLaw(associator.backwardForward, Vector((1, ("a", true))))
    assertFunctionLaw(leftUnitor.forwardBackward, Vector(((), 1)))
    assertFunctionLaw(leftUnitor.backwardForward, Vector(1))
    assertFunctionLaw(rightUnitor.forwardBackward, Vector((1, ())))
    assertFunctionLaw(rightUnitor.backwardForward, Vector(1))
    assertFunctionLaw(braiding.forwardBackward, Vector((1, "a")))
    assertFunctionLaw(braiding.backwardForward, Vector(("a", 1)))
  }

  test("Scala functions satisfy the monoidal coherence laws") {
    assertFunctionLaw(
      Functions.tensorInterchange[Int, String, Int, Int, String, Int](_ + 1, _.length, _.toString, _ * 2),
      Vector((1, "abc"))
    )
    assertFunctionLaw(Functions.tensorIdentity[Int, String], Vector((1, "a")))
    assertFunctionLaw(
      Functions.associatorNaturality[Int, String, Boolean, String, Int, String](_.toString, _.length, _.toString),
      Vector(((1, "abc"), true))
    )
    assertFunctionLaw(Functions.leftUnitorNaturality[Int, String](_.toString), Vector(((), 1)))
    assertFunctionLaw(Functions.rightUnitorNaturality[Int, String](_.toString), Vector((1, ())))
    assertFunctionLaw(Functions.pentagon[Int, String, Boolean, Double], Vector((((1, "a"), true), 2.0)))
    assertFunctionLaw(Functions.triangle[Int, String], Vector(((1, ()), "a")))
  }

  test("Scala functions satisfy the symmetric coherence laws") {
    assertFunctionLaw(
      Functions.braidingNaturality[Int, String, String, Int](_.toString, _.length),
      Vector((1, "abc"))
    )
    assertFunctionLaw(Functions.symmetry[Int, String], Vector((1, "a")))
    assertFunctionLaw(Functions.hexagon[Int, String, Boolean], Vector(((1, "a"), true)))
  }

  test("Scala functions satisfy every copy/discard and cartesian naturality law") {
    assertFunctionLaw(Functions.copyCoassociativity[Int], Vector(1, 2))
    assertFunctionLaw(Functions.copyLeftCounit[Int], Vector(1, 2))
    assertFunctionLaw(Functions.copyRightCounit[Int], Vector(1, 2))
    assertFunctionLaw(Functions.copyCocommutativity[Int], Vector(1, 2))
    assertFunctionLaw(Functions.copyTensor[Int, String], Vector((1, "a")))
    assertFunctionLaw(Functions.discardTensor[Int, String], Vector((1, "a")))
    assertFunctionLaw(Functions.copyUnit, Vector(()))
    assertFunctionLaw(Functions.discardUnit, Vector(()))
    assertFunctionLaw(Functions.copyNaturality[Int, String](_.toString), Vector(1, 2))
    assertFunctionLaw(Functions.discardNaturality[Int, String](_.toString), Vector(1, 2))
  }
