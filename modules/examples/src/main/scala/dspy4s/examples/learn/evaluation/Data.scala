/**
 * Data
 *
 * Source:   docs/docs/learn/evaluation/data.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/learn/evaluation/data.md
 * Status:   scaffold (8 python snippets — TODO translate)
 */
package dspy4s.examples.learn.evaluation

object Data {

  // ── Snippet 1 (lines 18–24) ────────────────────
  // | qa_pair = dspy.Example(question="This is a question?", answer="This is an answer.")
  // |
  // | print(qa_pair)
  // | print(qa_pair.question)
  // | print(qa_pair.answer)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 40–42) ────────────────────
  // | trainset = [dspy.Example(report="LONG REPORT 1", summary="short summary 1"), ...]
  // TODO translate snippet 2

  // ── Snippet 3 (lines 51–57) ────────────────────
  // | # Single Input.
  // | print(qa_pair.with_inputs("question"))
  // |
  // | # Multiple Inputs; be careful about marking your labels as inputs unless you mean it.
  // | print(qa_pair.with_inputs("question", "answer"))
  // TODO translate snippet 3

  // ── Snippet 4 (lines 63–71) ────────────────────
  // | article_summary = dspy.Example(article= "This is an article.", summary= "This is a summary.").with_inputs("article")
  // |
  // | input_key_only = article_summary.inputs()
  // | non_input_key_only = article_summary.labels()
  // |
  // | print("Example object with Input fields only:", input_key_only)
  // | print("Example object with Non-Input fields only:", non_input_key_only)
  // TODO translate snippet 4

  // ── Snippet 5 (lines 83–87) ────────────────────
  // | from dspy.datasets import DataLoader
  // |
  // | dl = DataLoader()
  // TODO translate snippet 5

  // ── Snippet 6 (lines 91–117) ────────────────────
  // | import pandas as pd
  // |
  // | csv_dataset = dl.from_csv(
  // |     "sample_dataset.csv",
  // |     fields=("instruction", "context", "response"),
  // |     input_keys=("instruction", "context")
  // | )
  // |
  // | json_dataset = dl.from_json(
  // |     "sample_dataset.json",
  // |     fields=("instruction", "context", "response"),
  // |     input_keys=("instruction", "context")
  // | )
  // |
  // | parquet_dataset = dl.from_parquet(
  // |     "sample_dataset.parquet",
  // |     fields=("instruction", "context", "response"),
  // |     input_keys=("instruction", "context")
  // | )
  // |
  // | pandas_dataset = dl.from_pandas(
  // |     pd.read_csv("sample_dataset.csv"),    # DataFrame
  // |     fields=("instruction", "context", "response"),
  // |     input_keys=("instruction", "context")
  // | )
  // TODO translate snippet 6

  // ── Snippet 7 (lines 121–126) ────────────────────
  // | blog_alpaca = dl.from_huggingface(
  // |     "intertwine-expel/expel-blog",
  // |     input_keys=("title",)
  // | )
  // TODO translate snippet 7

  // ── Snippet 8 (lines 130–138) ────────────────────
  // | train_split = blog_alpaca['train']
  // |
  // | # Since this is the only split in the dataset we can split this into
  // | # train and test split ourselves by slicing or sampling 75 rows from the train
  // | # split for testing.
  // | testset = train_split[:75]
  // | trainset = train_split[75:]
  // TODO translate snippet 8
}
