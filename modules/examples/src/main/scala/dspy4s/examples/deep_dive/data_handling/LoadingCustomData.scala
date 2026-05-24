/**
 * Creating a Custom Dataset
 *
 * Source:   docs/docs/deep-dive/data-handling/loading-custom-data.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/deep-dive/data-handling/loading-custom-data.md
 * Status:   scaffold (4 python snippets — TODO translate)
 */
package dspy4s.examples.deep_dive.data_handling

object LoadingCustomData {

  // ── Snippet 1 (lines 20–25) ────────────────────
  // | import pandas as pd
  // |
  // | df = pd.read_csv("sample.csv")
  // | print(df.shape)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 30–37) ────────────────────
  // | dataset = []
  // |
  // | for context, question, answer in df.values:
  // |     dataset.append(dspy.Example(context=context, question=question, answer=answer).with_inputs("context", "question"))
  // |
  // | print(dataset[:3])
  // TODO translate snippet 2

  // ── Snippet 3 (lines 39–43) ────────────────────
  // | [Example({'context': nan, 'question': 'Which is a species of fish? Tope or Rope', 'answer': 'Tope'}) (input_keys={'question', 'context'}),
  // |  Example({'context': nan, 'question': 'Why can camels survive for long without water?', 'answer': 'Camels use the fat in their humps to keep them filled with energy and hydration for long periods of time.'}) (input_keys={'question', 'context'}),
  // |  Example({'context': nan, 'question': "Alice's parents have three daughters: Amy, Jessy, and what’s the name of the third daughter?", 'answer': 'The name of the third daughter is Alice'}) (input_keys={'question', 'context'})]
  // TODO translate snippet 3

  // ── Snippet 4 (lines 57–72) ────────────────────
  // | import pandas as pd
  // | from dspy.datasets.dataset import Dataset
  // |
  // | class CSVDataset(Dataset):
  // |     def __init__(self, file_path, *args, **kwargs) -> None:
  // |         super().__init__(*args, **kwargs)
  // |
  // |         df = pd.read_csv(file_path)
  // |         self._train = df.iloc[0:700].to_dict(orient='records')
  // |
  // |         self._dev = df.iloc[700:].to_dict(orient='records')
  // |
  // | dataset = CSVDataset("sample.csv")
  // | print(dataset.train[:3])
  // TODO translate snippet 4
}
