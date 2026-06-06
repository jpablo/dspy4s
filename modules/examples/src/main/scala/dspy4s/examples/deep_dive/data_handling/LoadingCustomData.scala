/**
 * Creating a Custom Dataset
 *
 * Source:   docs/docs/deep-dive/data-handling/loading-custom-data.md
 * Upstream: https://github.com/stanfordnlp/dspy/blob/main/docs/docs/deep-dive/data-handling/loading-custom-data.md
 * Status:   translated (the DSPy part: building `Example`s from rows + a train/dev split, snippets 2/4).
 *           The `pandas.read_csv` I/O (snippet 1) and snippet 3's repr output are out of scope — CSV/dataframe
 *           loading is plain I/O, not a dspy feature; rows are supplied in-memory here. dspy4s has no
 *           `dspy.datasets.dataset.Dataset` base class, so the `CSVDataset(Dataset)` subclass becomes a plain
 *           holder with `train` / `dev` produced by slicing the row list.
 *
 * `dspy.Example(context=..., question=..., answer=...).with_inputs("context", "question")` →
 * `Example("context" := ..., "question" := ..., "answer" := ...).withInputs(Set("context", "question"))`.
 */
package dspy4s.examples.deep_dive.data_handling

import dspy4s.core.contracts.{Example, :=}

object LoadingCustomData:

  /** One source row — what a `pandas` dataframe row (`context, question, answer`) carries. In a real program
    * you'd read these from a CSV/Parquet/HTTP source; that loading is plain I/O and out of scope here. */
  final case class Row(context: String, question: String, answer: String)

  // ── Snippet 2 (lines 30–37) — turn rows into input-marked Examples ──
  // | for context, question, answer in df.values:
  // |     dataset.append(dspy.Example(context=context, question=question, answer=answer).with_inputs("context", "question"))
  def toExamples(rows: Vector[Row]): Vector[Example] =
    rows.map { r =>
      Example("context" := r.context, "question" := r.question, "answer" := r.answer)
        .withInputs(Set("context", "question"))
    }

  // ── Snippet 4 (lines 57–72) — a CSVDataset with a train/dev split ──
  // | class CSVDataset(Dataset):
  // |     df = pd.read_csv(file_path)
  // |     self._train = df.iloc[0:700].to_dict(orient='records')
  // |     self._dev   = df.iloc[700:].to_dict(orient='records')
  // dspy4s has no Dataset base class; a plain holder split by index is the direct equivalent.
  final case class CustomDataset(train: Vector[Example], dev: Vector[Example])

  object CustomDataset:
    /** Split rows into train/dev at `trainSize` (Python used a fixed 700), mirroring `iloc[0:n]` / `iloc[n:]`. */
    def fromRows(rows: Vector[Row], trainSize: Int): CustomDataset =
      val examples = toExamples(rows)
      CustomDataset(train = examples.take(trainSize), dev = examples.drop(trainSize))

  // ── Snippet 1 (lines 20–25) — `pd.read_csv("sample.csv")` ──
  // Out of scope: dataframe/CSV loading is plain I/O. Read your rows however you like and hand them to
  // `toExamples` / `CustomDataset.fromRows`.

// Run with: sbt "examples/runMain dspy4s.examples.deep_dive.data_handling.loadingCustomDataMain"
// Pure data construction — no LM, no API key needed.
@main def loadingCustomDataMain(): Unit =
  val rows = Vector(
    LoadingCustomData.Row("", "Which is a species of fish? Tope or Rope", "Tope"),
    LoadingCustomData.Row("", "Why can camels survive for long without water?",
      "Camels use the fat in their humps to keep them filled with energy and hydration for long periods."),
    LoadingCustomData.Row("", "Alice's parents have three daughters: Amy, Jessy, and what's the third's name?",
      "Alice")
  )
  val dataset = LoadingCustomData.CustomDataset.fromRows(rows, trainSize = 2)
  println(s"train=${dataset.train.size}, dev=${dataset.dev.size}")
  dataset.train.foreach(ex => println("  " + ex.inputs))
