/**
 * Utilizing Built-in Datasets
 *
 * Source:   docs/docs/deep-dive/data-handling/built-in-datasets.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/deep-dive/data-handling/built-in-datasets.md
 * Status:   scaffold (4 python snippets — TODO translate)
 */
package dspy4s.examples.deep_dive.data_handling

object BuiltInDatasets {

  // ── Snippet 1 (lines 22–28) ────────────────────
  // | from dspy.datasets import HotPotQA
  // |
  // | dataset = HotPotQA(train_seed=1, train_size=5, eval_seed=2023, dev_size=50, test_size=0)
  // |
  // | print(dataset.train)
  // TODO translate snippet 1

  // ── Snippet 2 (lines 40–45) ────────────────────
  // | trainset = [x.with_inputs('question') for x in dataset.train]
  // | devset = [x.with_inputs('question') for x in dataset.dev]
  // |
  // | print(trainset)
  // TODO translate snippet 2

  // ── Snippet 3 (lines 66–73) ────────────────────
  // | @property
  // | def train(self):
  // |     if not hasattr(self, '_train_'):
  // |         self._train_ = self._shuffle_and_sample('train', self._train, self.train_size, self.train_seed)
  // |
  // |     return self._train_
  // TODO translate snippet 3

  // ── Snippet 4 (lines 77–92) ────────────────────
  // | def _shuffle_and_sample(self, split, data, size, seed=0):
  // |     data = list(data)
  // |     base_rng = random.Random(seed)
  // |
  // |     if self.do_shuffle:
  // |         base_rng.shuffle(data)
  // |
  // |     data = data[:size]
  // |     output = []
  // |
  // |     for example in data:
  // |         output.append(Example(**example, dspy_uuid=str(uuid.uuid4()), dspy_split=split))
  // |
  // |         return output
  // TODO translate snippet 4
}
