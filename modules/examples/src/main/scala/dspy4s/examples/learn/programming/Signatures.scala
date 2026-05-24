/**
 * Signatures
 *
 * Source:   docs/docs/learn/programming/signatures.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/programming/signatures.md
 * Status:   scaffold (8 python snippets — TODO translate)
 */
package dspy4s.examples.learn.programming

object Signatures {

  // ── Snippet 1 (lines 37–46) ────────────────────
  // | toxicity = dspy.Predict(
  // |     dspy.Signature(
  // |         "comment -> toxic: bool",
  // |         instructions="Mark as 'toxic' if the comment includes insults, harassment, or sarcastic derogatory remarks.",
  // |     )
  // | )
  // | comment = "you are beautiful."
  // | toxicity(comment=comment).toxic
  // TODO translate snippet 1

  // ── Snippet 2 (lines 56–61) ────────────────────
  // | sentence = "it's a charming and often affecting journey."  # example from the SST-2 dataset.
  // |
  // | classify = dspy.Predict('sentence -> sentiment: bool')  # we'll see an example with Literal[] later
  // | classify(sentence=sentence).sentiment
  // TODO translate snippet 2

  // ── Snippet 3 (lines 69–77) ────────────────────
  // | # Example from the XSum dataset.
  // | document = """The 21-year-old made seven appearances for the Hammers and netted his only goal for them in a Europa League qualification round match against Andorran side FC Lustrains last season. Lee had two loan spells in League One last term, with Blackpool and then Colchester United. He scored twice for the U's but was unable to save them from relegation. The length of Lee's contract with the promoted Tykes has not been revealed. Find all the latest football transfers on our dedicated page."""
  // |
  // | summarize = dspy.ChainOfThought('document -> summary')
  // | response = summarize(document=document)
  // |
  // | print(response.summary)
  // TODO translate snippet 3

  // ── Snippet 4 (lines 87–89) ────────────────────
  // | print("Reasoning:", response.reasoning)
  // TODO translate snippet 4

  // ── Snippet 5 (lines 107–120) ────────────────────
  // | from typing import Literal
  // |
  // | class Emotion(dspy.Signature):
  // |     """Classify emotion."""
  // |
  // |     sentence: str = dspy.InputField()
  // |     sentiment: Literal['sadness', 'joy', 'love', 'anger', 'fear', 'surprise'] = dspy.OutputField()
  // |
  // | sentence = "i started feeling a little vulnerable when the giant spotlight started blinding me"  # from dair-ai/emotion
  // |
  // | classify = dspy.Predict(Emotion)
  // | classify(sentence=sentence)
  // TODO translate snippet 5

  // ── Snippet 6 (lines 132–147) ────────────────────
  // | class CheckCitationFaithfulness(dspy.Signature):
  // |     """Verify that the text is based on the provided context."""
  // |
  // |     context: str = dspy.InputField(desc="facts here are assumed to be true")
  // |     text: str = dspy.InputField()
  // |     faithfulness: bool = dspy.OutputField()
  // |     evidence: dict[str, list[str]] = dspy.OutputField(desc="Supporting evidence for claims")
  // |
  // | context = "The 21-year-old made seven appearances for the Hammers and netted his only goal for them in a Europa League qualification round match against Andorran side FC Lustrains last season. Lee had two loan spells in League One last term, with Blackpool and then Colchester United. He scored twice for the U's but was unable to save them from relegation. The length of Lee's contract with the promoted Tykes has not been revealed. Find all the latest football transfers on our dedicated page."
  // |
  // | text = "Lee scored 3 goals for Colchester United."
  // |
  // | faithfulness = dspy.ChainOfThought(CheckCitationFaithfulness)
  // | faithfulness(context=context, text=text)
  // TODO translate snippet 6

  // ── Snippet 7 (lines 159–168) ────────────────────
  // | class DogPictureSignature(dspy.Signature):
  // |     """Output the dog breed of the dog in the image."""
  // |     image_1: dspy.Image = dspy.InputField(desc="An image of a dog")
  // |     answer: str = dspy.OutputField(desc="The dog breed of the dog in the image")
  // |
  // | image_url = "https://picsum.photos/id/237/200/300"
  // | classify = dspy.Predict(DogPictureSignature)
  // | classify(image_1=dspy.Image.from_url(image_url))
  // TODO translate snippet 7

  // ── Snippet 8 (lines 190–205) ────────────────────
  // | # Simple custom type
  // | class QueryResult(pydantic.BaseModel):
  // |     text: str
  // |     score: float
  // |
  // | signature = dspy.Signature("query: str -> result: QueryResult")
  // |
  // | class MyContainer:
  // |     class Query(pydantic.BaseModel):
  // |         text: str
  // |     class Score(pydantic.BaseModel):
  // |         score: float
  // |
  // | signature = dspy.Signature("query: MyContainer.Query -> score: MyContainer.Score")
  // TODO translate snippet 8
}
