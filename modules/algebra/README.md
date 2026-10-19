# dspy4s `algebra`

`algebra` contains the algebraic structures that dspy4s uses to describe composition. The module contains no model,
adapter, schema, or runtime code, and its main code uses no external library. The `core` module depends on it.

Most users do not use this module directly. Use it to add a composition rule, a type-class instance, or a law test. To
make and run a program, start with the [`programs`](../programs/README.md) module.

## Main concepts

- **Operations and laws.** A structure has operations, for example the identity and the composition of a `Category`.
  It also has laws: equations that the operations must satisfy. The laws are methods on the trait, thus each instance
  gets them.
- **A test checks a law.** `IsEq[A]` holds the two sides of an equation, `<->` makes an `IsEq`, and `@Law` marks a law
  method. The test compares the two sides: with `==` for plain values, or on sample inputs for functions.
- **The weakest true structure.** Give a type the weakest structure whose laws are all true for it. Then code cannot
  use a law that is false for the type.

`f >>> g` means "run `f`, then run `g`":

```scala
import dspy4s.algebra.*

val functions = summon[Category[AnyObject, Function1]]

val normalize: String => String = _.trim.toLowerCase
val length: String => Int       = _.length
val positive: Int => Boolean    = _ > 0

val normalizedLength: String => Int = functions.>>>(normalize)(length)

val law = functions.associativity(normalize, length, positive)
assert(law.lhs("  Hello  ") == law.rhs("  Hello  "))
```

A law method in a trait:

```scala
@Law("associativity")
def associativity(a: M, b: M, c: M): IsEq[M] =
  a.combine(b).combine(c) <-> a.combine(b.combine(c))
```

## Main types

### Values and optics

| Type | Description |
|------|-------------|
| `Monoid[M]` | `combine` joins two values, and the `empty` value changes nothing. `(a combine b) combine c` is equal to `a combine (b combine c)`. |
| `MonoidAction[M, A]` | Applies the values of a monoid `M` to values of `A`. |
| `Optic` | An optic reads a part (the focus) of a value, and builds a new value with a new focus. `andThen` composes two optics. |
| `Lens[S, A]` | An optic that reads and replaces one part `A` of `S`. It has six laws, for example: to write back the part that you read changes nothing. |

### Categories and mappings

A category has morphisms `Hom[A, B]` from an object `A` to an object `B`, for example the functions `A => B`. You can
compose two morphisms when the output of the first is the input of the second.

| Type | Description |
|------|-------------|
| `Category[P, Hom]` | The identity `id` and the composition `>>>`. `P[A]` can limit the objects. `functionCategory` is the category of Scala functions. |
| `Isomorphism`, `Opposite` | A morphism and its inverse. A category with the direction of each morphism reversed. |
| `Functor` | Maps one category to a different category, and keeps identities and compositions. An `Endofunctor[F]` gives a `map` for `F`. |
| `NaturalTransformation` | Maps one functor to a different functor. |
| `Profunctor` | A type `F[A, B]` whose input and output you can change with `dimap`. |
| `Monad`, `ScalaMonad` | A monad runs steps in sequence, and each step returns an `F[B]`. `ScalaMonad` gives `pure` and `flatMap` for Scala types. In its `Either` instance, the first `Left` stops the steps after it. |
| `Kleisli` | A function `A => F[B]` whose result has an effect `F`. `kleisliCategory` composes these morphisms with a `ScalaMonad`. |

### Graded and ordered execution

A graded category gives a natural number, the grade, to each morphism. Composition adds the grades. In dspy4s, the
grade of a `Program` is the number of its `OptimizableParameters` values, and the result type keeps the sum.

| Type | Description |
|------|-------------|
| `NatGradedCategory` | A category whose morphisms `Hom[A, B, N]` have a grade `N`. `AnyGrade` hides the grade. |
| `GradedFunctor`, `GradePreservingFunctor` | Map a graded category to an ordinary category, or to a graded category with the same grades. |
| `TensorOps` | `tensor` runs two morphisms on the two parts of a pair. It adds no laws to the category laws. |
| `OrderedTensorOps` | Adds `swap`, `copy`, and `discard`, with no symmetry or naturality laws. |
| `OrderedFanout` | `fanout` gives one input to two graded morphisms and runs them from left to right. |

## Weak and strong structures

An effect makes the order visible: the first program can fail before the second program runs. Thus the ordered types
state no laws that effects can break. Use a type of the strong branch only when all its laws are true:

| Type | Laws that it adds |
|------|-------------------|
| `MonoidalCategory` | `tensor` keeps identities and compositions. The associator and the unitors satisfy the pentagon and the triangle laws. |
| `SymmetricMonoidalCategory` | A natural `braiding` swaps the two parts of a pair. |
| `CopyDiscardCategory` | Each object has a `copy` and a `discard`. To copy a value and discard one copy gives the value. |
| `MarkovCategory` | To discard the output of a morphism is the same as to discard its input. |
| `CartesianCategory` | To copy the output of a morphism is the same as to copy its input and run the morphism on each copy. |

`MonoidalCategory` extends `TensorOps`, and each other type extends the type above it.

## How dspy4s uses this module

| Type | Structure |
|------|-----------|
| `LmUsage`, `RawPrediction`, `RuntimeDelta` | `Monoid` |
| `Executed`, `Prediction` | `ScalaMonad`: it runs two steps and keeps the records of the two steps. |
| `ProgramCall`, `SizedVector` | `Endofunctor` |
| `OptimizableLeaf`, `OptimizableStructure` | `Lens` on the parameters of a program |
| `Mode` | `Monoid` and `MonoidAction` on `Module` |
| `Module` | `OrderedTensorOps` |
| `Program` | `NatGradedCategory` and `OrderedFanout` |

For the program instances, see [`programs/algebra`](../programs/src/main/scala/dspy4s/programs/algebra/) and the
[`Program` guide](../programs/src/main/scala/dspy4s/programs/algebra/Program.md).

## Source layout

All files are in the package `dspy4s.algebra`. Most files have the name of their main type. `Laws.scala` contains
`IsEq` and `@Law`, and `CopyDiscardCategory.scala` contains the strong branch.

## Tests

Run `sbt algebra/test` from the root of the repository. The suites check the laws on Scala functions, `Either`, the
Kleisli category, and lenses. `UnlawfulOpticFixturesSuite` shows that the lens laws find an incorrect lens.
