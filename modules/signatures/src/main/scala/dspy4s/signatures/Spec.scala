package dspy4s.signatures

/** Marker trait that declares a type as a signature **spec**. Subclass with abstract methods returning [[InputField]]
  * or [[OutputField]] to describe the signature; pass the trait as the type parameter to `Signature.of[T]` to
  * materialize the runtime metadata.
  *
  * Example:
  * {{{
  *   trait Emotion extends Spec:
  *     def sentence:  InputField[String]
  *     def sentiment: OutputField[Sentiment]
  *
  *   val sig = Signature.of[Emotion]
  * }}}
  *
  * The macro behind `Signature.of` validates the trait at compile time: each member must return `InputField[A]` or
  * `OutputField[A]`, each field name must be unique, and the structural derivation must give a schema for each `A`. The
  * derivation does not need a `Schema[A]` given, as for `Signature.derived`.
  */
trait Spec

/** A phantom wrapper that marks a method as an input field of the signature. `InputField[A]` erases to `A` at runtime.
  * The marker only tells the trait macro the role of each member.
  */
opaque type InputField[+A] = A

object InputField:
  def apply[A](a: A): InputField[A] = a

/** A phantom wrapper that marks a method as an output field of the signature. `OutputField[A]` erases to `A` at
  * runtime.
  */
opaque type OutputField[+A] = A

object OutputField:
  def apply[A](a: A): OutputField[A] = a
