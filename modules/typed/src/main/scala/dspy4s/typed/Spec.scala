package dspy4s.typed

/** Marker trait that declares a type as a typed-signature **spec**. Subclass
  * with abstract methods returning [[InputField]] or [[OutputField]] to
  * describe the signature; pass the trait as the type parameter to
  * `TypedSignature.of[T]` to materialize the runtime metadata.
  *
  * Example:
  * {{{
  *   trait Emotion extends TypedSignature.Spec:
  *     def sentence:  InputField[String]
  *     def sentiment: OutputField[Sentiment]
  *
  *   val sig = TypedSignature.of[Emotion]
  * }}}
  *
  * The macro behind `TypedSignature.of` validates the trait at compile
  * time: every member must return `InputField[A]` or `OutputField[A]`,
  * the field name must be unique, and a `ValueDecoder[A]` must be in
  * scope. */
trait Spec

/** Phantom wrapper marking a method as an **input** field of the signature.
  * `InputField[A]` erases to `A` at runtime — the marker only exists so
  * the trait macro can classify members by role. */
opaque type InputField[+A] = A

object InputField:
  def apply[A](a: A): InputField[A] = a

/** Phantom wrapper marking a method as an **output** field of the
  * signature. `OutputField[A]` erases to `A` at runtime. */
opaque type OutputField[+A] = A

object OutputField:
  def apply[A](a: A): OutputField[A] = a
