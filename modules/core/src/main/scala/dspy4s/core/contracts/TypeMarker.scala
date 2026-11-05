package dspy4s.core.contracts

import zio.blocks.schema.{Modifier, Reflect}

/** The marker of a framework type: a type-level `Modifier.config` entry with the key [[TypeMarker.Key]] and the name of
  * the type as its value, for example `@Modifier.config(TypeMarker.Key, TypeMarker.noul)` on `Noul`. `Schema.derived`
  * keeps the entry in the modifiers of the reflect of the type, also in a field, in the element of a sequence, and on a
  * generic type. Thus the codec of the signatures module finds a framework type in a schema, and the signature macros
  * find it in the annotations of the type symbol. Neither of them keeps a list of the types.
  *
  * The key and the names are constants (a `final val` with no type), thus an annotation can use them.
  */
object TypeMarker:

  /** The key of the marker entry. */
  final val Key = "dspy4s.typeRef"

  /** The marker of [[History]]. A field of this type has the wire type [[TypeRef.history]]. */
  final val history = "history"

  /** The marker of [[ToolCall]]. A sequence of tool calls has the wire type [[TypeRef.toolCalls]]. */
  final val toolCall = "tool_call"

  /** The marker of `dspy4s.core.decisions.Noul`. A field of this type has the wire type [[TypeRef.noul]]. */
  final val noul = "noul"

  /** The marker of `dspy4s.core.decisions.Score`. A field of this type has the wire type [[TypeRef.score]]. */
  final val score = "score"

  /** The marker of `dspy4s.core.decisions.Choice`. A field of this type has the wire type [[TypeRef.choice]]. */
  final val choice = "choice"

  /** The markers of the types that a signature reads as one value, not as a record of fields: the decision types and
    * [[History]]. A function that returns such a type has one `result` output.
    */
  private[dspy4s] val singleValue: Set[String] = Set(history, noul, score, choice)

  /** The marker of the type of `reflect`. `None` for a type with no marker. */
  private[dspy4s] def of(reflect: Reflect[?, ?]): Option[String] = in(Reflects.withoutDeferred(reflect).modifiers)

  /** The marker in the type-level modifiers `modifiers`. `None` when they have no marker. */
  private[dspy4s] def in(modifiers: Iterable[Modifier]): Option[String] =
    modifiers.collectFirst { case Modifier.config(Key, value) => value }
