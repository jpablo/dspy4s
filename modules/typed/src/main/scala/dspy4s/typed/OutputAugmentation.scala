package dspy4s.typed

import scala.NamedTuple
import scala.deriving.Mirror

/** Reusable, type-directed output augmentation for typed programs: prepend a single named field `Name: T` to a
  * program's output `O`, producing a named tuple — **idempotently** (skipped if `O` already declares `Name`) and
  * **cast-free**.
  *
  * `O` is normalized to its named-tuple view via `NamedTuple.From` (identity for named tuples, the field tuple
  * for case classes), so case-class outputs are supported and the result is always a named tuple. A case-class
  * output is therefore *not* echoed back as the same nominal type — that type can't be synthesized with an extra
  * field. The only unsupported `O` is one with no static fields (e.g. `DynamicValue.Record` from
  * `Signature.fromString`), handled by the low-priority [[PrependField.fallback]] returning `None`.
  *
  * Used by [[dspy4s.programs.ChainOfThought]] (`reasoning: String`) and available to any other program that adds
  * an output field (e.g. a future `MultiChainComparison` with `rationale: String`). */
object OutputAugmentation:

  /** Type-level membership: is `X` one of the names in tuple `T`? Drives the idempotence check. */
  type Contains[T <: Tuple, X] <: Boolean = T match
    case X *: _     => true
    case _ *: rest  => Contains[rest, X]
    case EmptyTuple => false

  /** The augmented output type — **always a named tuple**. Normalizes `O` to its named-tuple view
    * (`NamedTuple.From`), then prepends `Name: T` unless a `Name` field is already present (idempotent). */
  type WithField[O, Name <: String & Singleton, T] = NamedTuple.From[O] match
    case NamedTuple.NamedTuple[n, v] =>
      Contains[n, Name] match
        case true  => NamedTuple.NamedTuple[n, v]
        case false => NamedTuple.NamedTuple[Name *: n, T *: v]

  /** Type-directed construction of [[WithField]] from a base output value, with no `asInstanceOf`. The abstract
    * `Out` member lets each instance pin the exact type it builds (sidestepping match-type/`Mirror` alignment);
    * consumers constrain it via `Aux[Name, T, O, WithField[O, Name, T]]`. Instances resolve where `O` is
    * concrete (the program's call site), never against an abstract `O` — there only the fallback would match. */
  trait PrependField[Name <: String & Singleton, T, O]:
    type Out
    def prepend(value: T, base: O): Option[Out]

  trait LowPriorityPrependField:
    /** Fallback for an `O` that is neither a named tuple nor a product — e.g. the `DynamicValue.Record` output of
      * `Signature.fromString`, which has no static fields. Unsupported: yields `None`. */
    given fallback[Name <: String & Singleton, T, O]
        : (PrependField[Name, T, O] { type Out = WithField[O, Name, T] }) =
      new PrependField[Name, T, O]:
        type Out = WithField[O, Name, T]
        def prepend(value: T, base: O): Option[Out] = None

  object PrependField extends LowPriorityPrependField:
    type Aux[Name <: String & Singleton, T, O, O2] = PrependField[Name, T, O] { type Out = O2 }

    /** Named-tuple output without a `Name` field: prepend it via the supported whole-tuple constructor
      * `NamedTuple.build`. */
    given ntAbsent[Name <: String & Singleton, T, N <: Tuple, V <: Tuple](using
        Contains[N, Name] =:= false
    ): Aux[Name, T, NamedTuple.NamedTuple[N, V], NamedTuple.NamedTuple[Name *: N, T *: V]] =
      new PrependField[Name, T, NamedTuple.NamedTuple[N, V]]:
        type Out = NamedTuple.NamedTuple[Name *: N, T *: V]
        def prepend(value: T, base: NamedTuple.NamedTuple[N, V]): Option[Out] =
          Some(NamedTuple.build[Name *: N]()(value *: NamedTuple.toTuple(base)))

    /** Named-tuple output that already declares `Name`: idempotent — kept unchanged. */
    given ntPresent[Name <: String & Singleton, T, N <: Tuple, V <: Tuple](using
        Contains[N, Name] =:= true
    ): Aux[Name, T, NamedTuple.NamedTuple[N, V], NamedTuple.NamedTuple[N, V]] =
      new PrependField[Name, T, NamedTuple.NamedTuple[N, V]]:
        type Out = NamedTuple.NamedTuple[N, V]
        def prepend(value: T, base: NamedTuple.NamedTuple[N, V]): Option[Out] = Some(base)

    /** Any product (case class): normalize to its named-tuple view through the `Mirror` and delegate to the
      * named-tuple instances, so case-class outputs are supported and the result is always a named tuple. */
    given product[Name <: String & Singleton, T, O <: Product, N <: Tuple, V <: Tuple](using
        m: Mirror.ProductOf[O] { type MirroredElemLabels = N; type MirroredElemTypes = V },
        inner: PrependField[Name, T, NamedTuple.NamedTuple[N, V]]
    ): Aux[Name, T, O, inner.Out] =
      new PrependField[Name, T, O]:
        type Out = inner.Out
        def prepend(value: T, base: O): Option[Out] =
          inner.prepend(value, NamedTuple.build[N]()(Tuple.fromProductTyped(base)(using m)))
