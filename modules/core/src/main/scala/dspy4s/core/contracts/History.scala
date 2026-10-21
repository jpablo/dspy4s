package dspy4s.core.contracts

import zio.blocks.chunk.Chunk
import zio.blocks.schema.{DynamicValue, Schema}

/** The conversation history of a signature (upstream `dspy.History`).
  *
  * A signature takes the history as one input field of this type. An adapter does not show that field as a value. It
  * replays each message as earlier turns of the conversation, before the current request:
  *
  *   - Each message is a record whose keys are field names of the same signature. The input fields of a message make an
  *     earlier user turn, and its output fields make the assistant turn that answered it.
  *   - A message with output fields only makes an assistant turn with no user turn.
  *   - A message can hold the tool calls of that turn in the tool calls output field of the signature, and the results
  *     of those calls under [[History.ToolCallResultsKey]].
  *
  * {{{
  * val history = History.empty :+ DynamicValues.record("question" := "What is 1+1?", "answer" := "2")
  * predict((question = "What is 2+2?", history = history))
  * }}}
  */
final case class History(messages: Vector[DynamicValue.Record] = Vector.empty) derives Schema:

  /** This history with one more message at its end. */
  def :+(message: DynamicValue.Record): History = History(messages :+ message)

object History:
  val empty: History = History()

  /** The key of a message that holds the results of its tool calls: a sequence of [[ToolCallResult]] records. The key
    * is not a field of the signature.
    */
  val ToolCallResultsKey: String = "tool_call_results"

  /** `message` with the results of its tool calls under [[ToolCallResultsKey]]. */
  def withToolCallResults(message: DynamicValue.Record, results: Vector[ToolCallResult]): DynamicValue.Record =
    message.updated(ToolCallResultsKey, DynamicValue.Sequence(Chunk.from(results.map(_.toRecord))))

  /** The results of the tool calls of `message`. Empty when the message has none, or when an entry is not a
    * [[ToolCallResult]] record.
    */
  def toolCallResults(message: DynamicValue.Record): Vector[ToolCallResult] =
    DynamicValues.recordGet(message, ToolCallResultsKey) match
      case Some(sequence: DynamicValue.Sequence) =>
        val decoded = sequence.elements.toVector.map {
          case record: DynamicValue.Record => ToolCallResult.fromRecord(record)
          case _                           => None
        }
        if decoded.forall(_.isDefined) then decoded.flatten else Vector.empty
      case _ => Vector.empty

  /** The history that an input record holds for `fieldName`: the encoded form of a [[History]] value. `None` when the
    * record has no such field or the value has a different shape.
    */
  def fromInput(inputs: DynamicValue.Record, fieldName: String): Option[History] =
    DynamicValues.recordGet(inputs, fieldName).flatMap(value =>
      summon[Schema[History]].fromDynamicValue(value).toOption
    )
