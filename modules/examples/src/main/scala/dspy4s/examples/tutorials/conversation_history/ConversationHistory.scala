/** Managing conversation history with typed program input.
  *
  * Python DSPy uses `dspy.History`. In dspy4s, history is ordinary immutable data. The caller owns state, and the
  * prediction program remains referentially transparent.
  */
package dspy4s.examples.tutorials.conversation_history

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.examples.Demo
import dspy4s.programs.{ParameterId, PredictionBackend, Program}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

final case class Turn(question: String, answer: String) derives Schema
final case class ConversationInput(question: String, history: List[Turn]) derives Schema
final case class ConversationAnswer(answer: String) derives Schema

object ConversationHistory:

  val signature = Signature.derived[ConversationInput, ConversationAnswer](
    "ConversationQA",
    "Answer the question. Use relevant facts from the ordered conversation history."
  )

  /** Python appends to `predict.demos`. Scala puts demonstrations in immutable program parameters. */
  val demo = Example(
    values = DynamicValues.record(
      "question" := "What is the capital of France?",
      "history"  := List(Map("question" -> "What is the capital of Germany?", "answer" -> "Berlin")),
      "answer"   := "Paris"
    ),
    inputKeys = Set("question", "history")
  )

  val program = Program.predict(ParameterId("conversation/answer"), signature, demos = Vector(demo))

  final case class Conversation(turns: Vector[Turn] = Vector.empty):
    def ask(question: String)(using PredictionBackend): Either[DspyError, (Conversation, String)] =
      Demo.run(program, ConversationInput(question, turns.toList)).map { prediction =>
        val answer = prediction.output.answer
        copy(turns = turns :+ Turn(question, answer)) -> answer
      }

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain ...conversationHistoryMain"
@main def conversationHistoryMain(): Unit =
  Demo.withLm {
    val first = ConversationHistory.Conversation().ask("My name is Ada. What is a fun fact about that name?")
    val second = first.flatMap((conversation, _) => conversation.ask("What name did I tell you?"))
    println(second.map(_._2))
  }
