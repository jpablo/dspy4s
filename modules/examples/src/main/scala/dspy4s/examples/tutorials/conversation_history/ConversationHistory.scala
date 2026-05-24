/**
 * Managing Conversation History
 *
 * Source:   docs/docs/tutorials/conversation_history/index.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/conversation_history/index.md
 * Status:   scaffold (2 python snippets — TODO translate)
 */
package dspy4s.examples.tutorials.conversation_history

object ConversationHistory {

  // ── Snippet 1 (lines 9–34) ────────────────────
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
  // TODO translate snippet 1

  // ── Snippet 2 (lines 121–148) ────────────────────
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
  // TODO translate snippet 2
}
