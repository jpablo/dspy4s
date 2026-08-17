/** A local equivalent of DSPy's built-in dataset split handling.
  *
  * DSPy can download `HotPotQA` and other named datasets. dspy4s does not bundle those downloads. After data is read
  * from a file or service, the split, shuffle, sample, and `withInputs` operations map directly to `Example` values.
  */
package dspy4s.examples.deep_dive.data_handling

import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.core.data.Example

import java.nio.charset.StandardCharsets
import java.util.UUID

object BuiltInDatasets:

  final case class SourceRow(question: String, answer: String)
  final case class Dataset(train: Vector[Example], dev: Vector[Example], test: Vector[Example])

  /** Python: `[x.with_inputs("question") for x in dataset.train]`. */
  def asExamples(rows: Vector[SourceRow], split: String, size: Int, seed: Long): Vector[Example] =
    val random = new scala.util.Random(seed)
    Vector.from(random.shuffle(rows)).take(size).zipWithIndex.map { (row, index) =>
      val stableId = UUID.nameUUIDFromBytes(s"$split:$seed:$index:${row.question}".getBytes(StandardCharsets.UTF_8))
      Example(
        values = DynamicValues.record(
          "question"   := row.question,
          "answer"     := row.answer,
          "dspy_uuid"  := stableId.toString,
          "dspy_split" := split
        ),
        inputKeys = Set("question")
      )
    }

  /** Python: `HotPotQA(train_seed=1, train_size=5, eval_seed=2023, dev_size=50)` after rows are loaded. */
  def split(rows: Vector[SourceRow]): Dataset =
    val midpoint = math.max(1, rows.size / 2)
    Dataset(
      train = asExamples(rows.take(midpoint), "train", size = 5, seed = 1L),
      dev = asExamples(rows.drop(midpoint), "dev", size = 50, seed = 2023L),
      test = Vector.empty
    )

  val fixture: Vector[SourceRow] = Vector(
    SourceRow("What city is the Eiffel Tower in?", "Paris"),
    SourceRow("Who wrote The Hobbit?", "J. R. R. Tolkien"),
    SourceRow("What is the largest ocean?", "Pacific Ocean"),
    SourceRow("What is the capital of Japan?", "Tokyo")
  )

@main def builtInDatasetsMain(): Unit =
  val dataset = BuiltInDatasets.split(BuiltInDatasets.fixture)
  println(s"train=${dataset.train.size}, dev=${dataset.dev.size}, test=${dataset.test.size}")
  println("train input keys: " + dataset.train.map(_.inputKeys))
