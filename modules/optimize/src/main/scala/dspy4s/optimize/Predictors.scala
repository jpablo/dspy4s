package dspy4s.optimize

import dspy4s.programs.DynamicPredict
import scala.compiletime.erasedValue
import scala.compiletime.summonFrom
import scala.deriving.Mirror
import scala.util.NotGiven

/** A program that IS one learnable predictor (the leaf of the introspection tree). */
trait Predictor[P]:
  def get(program: P): DynamicPredict
  def set(program: P, updated: DynamicPredict): P

object Predictor:
  /** A [[DynamicPredict]] is itself a learnable predictor leaf. Defined in the [[Predictor]] companion
    * so it is in implicit scope wherever a `Predictor[DynamicPredict]` (or its `NotGiven`) is sought. */
  given Predictor[DynamicPredict] with
    def get(program: DynamicPredict): DynamicPredict                         = program
    def set(program: DynamicPredict, updated: DynamicPredict): DynamicPredict = updated

/** The general introspection contract optimizers consume -- typed analogue of Python's
  * named_predictors()/map_named_predictors(). `read` enumerates contained predictors in a stable
  * order; `replace` swaps them positionally and rebuilds the program immutably. Invariant:
  * read(p).length and indices are stable across replace, and replace(p, read(p)) == p. */
trait Predictors[P]:
  def read(program: P): Vector[DynamicPredict]
  def replace(program: P, updates: Vector[DynamicPredict]): P

object Predictors:

  /** Lifts a single [[Predictor]] leaf to a 1-element [[Predictors]]. */
  given fromPredictor[P](using leaf: Predictor[P]): Predictors[P] with
    def read(program: P): Vector[DynamicPredict] = Vector(leaf.get(program))
    def replace(program: P, updates: Vector[DynamicPredict]): P =
      require(updates.size == 1, s"Predictor leaf expects exactly 1 update, got ${updates.size}")
      leaf.set(program, updates.head)

  /** Identity instance for fields that contain no predictors. */
  def empty[P]: Predictors[P] = new Predictors[P]:
    def read(program: P): Vector[DynamicPredict]                  = Vector.empty
    def replace(program: P, updates: Vector[DynamicPredict]): P = program

  /** Mirror derivation over a case class: each field's `Predictors` instances are concatenated
    * (left -> right field order) for `read`, and `replace` slices the updates by per-field read-arity,
    * rebuilding via `m.fromProduct`. Fields with no `Predictors` instance fall back to [[empty]].
    *
    * The `NotGiven[Predictor[P]]` guard keeps the structural derivation from competing with
    * [[fromPredictor]]: a type that is itself a leaf (e.g. [[DynamicPredict]]) must resolve to the
    * 1-element leaf instance, not be torn apart into its case-class fields. Without the guard both
    * givens match every case class (every case class is a `Product`) and resolution wrongly favours
    * this one. */
  inline given derived[P <: Product](using
      m: Mirror.ProductOf[P],
      @annotation.unused notLeaf: NotGiven[Predictor[P]]
  ): Predictors[P] =
    new DerivedPredictors[P](m, summonFieldInstances[m.MirroredElemTypes])

  /** Named (non-inline) carrier of the derived behaviour. Keeping it a top-level class -- rather than
    * an anonymous class inside `derived` -- avoids `-Werror` rejecting an inline-duplicated anonymous
    * class definition at each use site. */
  private[optimize] final class DerivedPredictors[P <: Product](
      m: Mirror.ProductOf[P],
      fieldInstances: List[Predictors[Any]]
  ) extends Predictors[P]:
    def read(program: P): Vector[DynamicPredict] =
      fieldInstances.zipWithIndex.foldLeft(Vector.empty[DynamicPredict]) { case (acc, (inst, i)) =>
        acc ++ inst.read(program.productElement(i))
      }

    def replace(program: P, updates: Vector[DynamicPredict]): P =
      var cursor      = 0
      val rebuiltArgs = fieldInstances.zipWithIndex.map { case (inst, i) =>
        val value = program.productElement(i)
        val arity = inst.read(value).size
        val slice = updates.slice(cursor, cursor + arity)
        cursor += arity
        inst.replace(value, slice)
      }
      m.fromProduct(Tuple.fromArray(rebuiltArgs.toArray))

  /** Recurse over the Mirror's element types, summoning each field's `Predictors` (or [[empty]]).
    *
    * The widening to `Predictors[Any]` is the single, narrowly-scoped accommodation needed to hold
    * the heterogeneous per-field instances in one homogeneous list. It is type-safe: the i-th
    * instance is only ever applied to `program.productElement(i)`, whose runtime value the Mirror
    * guarantees to be of the corresponding element type. No `asInstanceOf` is used on program values;
    * the cast is confined to the instance witness, which never inspects more than its own field. */
  private[optimize] inline def summonFieldInstances[Elems <: Tuple]: List[Predictors[Any]] =
    inline erasedValue[Elems] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        val instance: Predictors[Any] = summonFieldInstance[head]
        instance :: summonFieldInstances[tail]

  private[optimize] inline def summonFieldInstance[A]: Predictors[Any] =
    summonFrom {
      case inst: Predictors[A] => widen(inst)
      case _                   => empty[Any]
    }

  /** Confines the unavoidable widening of a per-field `Predictors[A]` to a `Predictors[Any]` to one
    * private helper. Safe because the Mirror pairs this instance positionally with a value of type
    * `A` (see [[summonFieldInstances]]); `Predictors` is invariant so the compiler cannot prove the
    * subtype, but the runtime contract holds. */
  private[optimize] def widen[A](inst: Predictors[A]): Predictors[Any] =
    inst.asInstanceOf[Predictors[Any]]
