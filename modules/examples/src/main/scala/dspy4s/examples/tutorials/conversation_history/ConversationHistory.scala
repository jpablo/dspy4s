/** Managing Conversation History
  *
  * Source: docs/docs/tutorials/conversation_history/index.md Upstream:
  * https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/conversation_history/index.md Status: translated
  * (2/2 snippets). A signature takes the conversation as one input field of type `History`. An adapter replays the
  * messages of that field as earlier turns, before the current request. The program does not keep the history: the
  * caller adds each turn to a `History` value and gives that value to the next call.
  *
  * dspy4s programs are immutable values, so `predict.demos.append(...)` becomes `predict.copy(demos = ...)`.
  * `dspy.inspect_history()` has no translation here: the dspy4s LM history holds no prompt (PORT_GAPS G-27).
  */
package dspy4s.examples.tutorials.conversation_history

import dspy4s.core.contracts.{:=, DspyError, DynamicValues, History, RuntimeContext}
import dspy4s.core.data.Example
import dspy4s.examples.Demo
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.{InputField, OutputField, Signature, Spec}

// Snippets 1 + 2 signature (top-level for Mirror derivation).
// | class QA(dspy.Signature):
// |     question: str = dspy.InputField()
// |     history: dspy.History = dspy.InputField()
// |     answer: str = dspy.OutputField()
// --8<-- [start:signature]
trait QA extends Spec:
  def question: InputField[String]
  def history: InputField[History]
  def answer: OutputField[String]
// --8<-- [end:signature]

object ConversationHistory:

  // ── Snippet 1 (lines 9–34) — a conversation that keeps its history ──
  // | import dspy
  // | import os
  // |
  // | os.environ["OPENAI_API_KEY"] = "{your_openai_api_key}"
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // | class QA(dspy.Signature):
  // |     question: str = dspy.InputField()
  // |     history: dspy.History = dspy.InputField()
  // |     answer: str = dspy.OutputField()
  // |
  // | predict = dspy.Predict(QA)
  // | history = dspy.History(messages=[])
  // |
  // | while True:
  // |     question = input("Type your question, end conversation by typing 'finish': ")
  // |     if question == "finish":
  // |         break
  // |     outputs = predict(question=question, history=history)
  // |     print(f"\n{outputs.answer}\n")
  // |     history.messages.append({"question": question, **outputs})
  // |
  // | dspy.inspect_history()
  //
  // The interactive loop becomes a function that takes the questions as a list. A message of the history is a record
  // whose keys are field names of the signature: the input fields make the earlier user turn, and the output fields
  // make the assistant turn that answered it.
  // --8<-- [start:conversation]
  private val predict = Predict(Signature.of[QA])

  /** Asks each question in order. Each call gets the turns before it as its history. Returns the answers. */
  def converse(questions: List[String])(using RuntimeContext): Either[DspyError, List[String]] =
    def loop(remaining: List[String], history: History, answers: List[String]): Either[DspyError, List[String]] =
      remaining match
        case Nil              => Right(answers)
        case question :: rest => predict((question = question, history = history)).flatMap { outputs =>
            val answer = outputs.output.answer
            loop(rest, history :+ DynamicValues.record("question" := question, "answer" := answer), answers :+ answer)
          }
    loop(questions, History.empty, Nil)
  // --8<-- [end:conversation]

  // ── Snippet 2 (lines 121–148) — a history in a few-shot example ──
  // | import dspy
  // |
  // | dspy.configure(lm=dspy.LM("openai/gpt-4o-mini"))
  // |
  // |
  // | class QA(dspy.Signature):
  // |     question: str = dspy.InputField()
  // |     history: dspy.History = dspy.InputField()
  // |     answer: str = dspy.OutputField()
  // |
  // |
  // | predict = dspy.Predict(QA)
  // | history = dspy.History(messages=[])
  // |
  // | predict.demos.append(
  // |     dspy.Example(
  // |         question="What is the capital of France?",
  // |         history=dspy.History(
  // |             messages=[{"question": "What is the capital of Germany?", "answer": "The capital of Germany is Berlin."}]
  // |         ),
  // |         answer="The capital of France is Paris.",
  // |     )
  // | )
  // |
  // | predict(question="What is the capital of America?", history=dspy.History(messages=[]))
  // | dspy.inspect_history()
  //
  // An adapter does not replay the history of a demo. It shows that history as one field of the demo, so each demo
  // stays one user turn and one assistant turn.
  // --8<-- [start:demo-history]
  private val earlierTurn = DynamicValues.record(
    "question" := "What is the capital of Germany?",
    "answer"   := "The capital of Germany is Berlin."
  )

  private val demo = Example(
    "question" := "What is the capital of France?",
    "history"  := (History.empty :+ earlierTurn),
    "answer"   := "The capital of France is Paris."
  ).withInputs(Set("question", "history"))

  private val predictWithDemo = predict.copy(demos = predict.demos :+ demo)

  def askWithDemo(question: String)(using RuntimeContext): Either[DspyError, String] =
    predictWithDemo((question = question, history = History.empty)).map(_.output.answer)
  // --8<-- [end:demo-history]

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.conversation_history.conversationHistoryMain"
@main def conversationHistoryMain(): Unit =
  Demo.withLm {
    println("=== a conversation that keeps its history ===")
    val questions = List(
      "Do you know the competition between PyTorch and TensorFlow?",
      "Which one won the battle? Just tell me the result, with no reasoning."
    )
    ConversationHistory.converse(questions) match
      case Right(answers) => questions.zip(answers).foreach((question, answer) => println(s"> $question\n$answer\n"))
      case Left(err)      => println(s"error: ${err.message}")

    println("=== a history in a few-shot example ===")
    println(ConversationHistory.askWithDemo("What is the capital of America?"))
  }
