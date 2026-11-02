# dspy4s `evaluate`

`evaluate` runs a program on each example of a dev set, gives a score to each prediction with a metric, and
calculates the mean score. It also contains the metrics and the functions that save the results. It uses the `core`
and `programs` modules.

## Main concepts

- **A program** is a function of type `Example => Either[DspyError, RawPrediction]`.
- **A dev set** is a `Vector[Example]`. Each example has the input fields and the labels.
- **A metric** compares one prediction with its example and returns a `Double`.

`Evaluate` runs the program on the examples in parallel and gives each prediction to the metric. The `score` of the
result is the mean score as a percentage (`mean(score) * 100`).

## Run an evaluation

`Evaluate(devset, metric, …)` makes an evaluator. Call the evaluator with a program.

```scala
import dspy4s.core.contracts.{RuntimeContext, :=}
import dspy4s.core.data.Example
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.metrics.ExactMatch

val devset = Vector(
  Example("question" := "Capital of France?", "answer" := "Paris"),
  Example("question" := "Capital of Italy?",  "answer" := "Rome")
)

val evaluator = Evaluate(devset = devset, metric = new ExactMatch())

given RuntimeContext = RuntimeEnvironment.current

val result = evaluator() { example =>
  myProgram(example)   // Example => Either[DspyError, RawPrediction]
}

result.foreach { eval =>
  println(eval.score)        // aggregate, 0–100
  println(eval.metricName)   // "exact_match"
  eval.results.foreach(r => println(r.score))  // per-example
}
```

The arguments of `evaluator(metric, devset, …)(program)` replace the values of the evaluator for one run.

- **Threads.** The thread count is `numThreads`, else the value of the `RuntimeContext`, else 8. Each worker thread
  gets the context. Thus a metric can call an LM.
- **Errors.** A failed example gets the `failureScore` (default `0.0`), and the executor writes its cause to the
  standard error stream. The run stops with an error after `maxErrors` failures (default 10). An empty dev set is a
  `ValidationError`.
- **Events.** Each run sends an evaluate start event and an end event. The callback handlers can read the
  `callbackMetadata` of the run in the `RuntimeContext`.
- **Output.** `displayProgress` prints the score. `displayTable` prints a table of the results: `TableDisplay.Off`,
  `TableDisplay.All`, or `TableDisplay.Rows(n)`. `saveAsCsv` and `saveAsJson` write the results to a file.

The result is an `EvaluationResult`: the `score`, the `metricName`, and one `ExampleEvaluation` for each example, in
order. The `score` is `round(100 * ncorrect / ntotal, 2)`, as upstream. An `ExampleEvaluation` has the example, the
prediction, and its score. For a failed example, it also has the failure as `[code] message`.

## Metrics

```scala
trait Metric:
  def name: String
  def score(example: Example, prediction: RawPrediction, trace: Vector[TraceEntry] = Vector.empty)(using
      RuntimeContext): Either[DspyError, Double]
```

The `RuntimeContext` lets a metric call an LM. The `trace` is empty in an evaluation. `BootstrapFewShot` gives the
trace of each run.

### Metrics that compare text

These metrics compare the prediction with the reference answers of the example (default field `answer`). They first
normalize the text with `NormalizeText`, as upstream `normalize_text`: NFD, lower case, no punctuation, no articles,
and one space between the words. An accent stays as its NFD mark. A null value is a `ValidationError`. A number or a
bool reads as its text.

| Metric | Score |
|--------|-------|
| `ExactMatch` | `1.0` if the prediction is equal to a reference answer |
| `ContainsMatch` | `1.0` if the prediction contains a reference answer |
| `F1Score` | the token F1 score for the best reference answer |
| `AnswerMatch(frac)` | `ExactMatch` if `frac >= 1.0`, else `1.0` if the F1 score is `frac` or more |
| `PassageMatch` | `1.0` if the tokens of a retrieved passage of the prediction contain the tokens of a reference answer, in sequence |

Use `FunctionMetric` to make a metric from a function:

```scala
import dspy4s.evaluate.metrics.FunctionMetric

val lenient = FunctionMetric.bool("nonempty") { (example, prediction) =>
  prediction.get("answer").exists(_ != null)
}
```

### Metrics that use an LM as a judge

`SemanticF1` and `CompleteAndGrounded` use an LM as a judge. The judge is a `DynamicPredict` with a `reasoning`
field. It uses the LM and the adapter of the `RuntimeContext`.

- **`SemanticF1`** gets the recall and the precision of the response from the LM, and returns their F1 score. With
  `decompositional = true`, the LM first lists the key ideas of each response.
- **`CompleteAndGrounded`** gets the completeness against the reference answer and the groundedness against the
  retrieved context, and returns their F1 score. The program must put the retrieved context in a field of the
  prediction (default `context`).

The default field names are `question` and `response`. The values go to the judge with no conversion, so a list
context stays a list. With a trace, these metrics return `1.0` when the score reaches the `threshold` (default `0.66`),
else `0.0`.

## Save the results

`EvaluationResultPersistence` writes `EvaluationResult.rows`, one row for each example, as JSON or as CSV. These are
the rows of upstream `merge_dicts`. A field gets the prefix `example_` or `pred_` only if the example and the prediction
have a field with the same name. The score is in a column with the name of the metric, and it replaces a field with
that name. The JSON text has the form of Python `json.dump`, and the CSV text has the form of the Python `csv` module.
The table of `displayTable` has the same columns.

## Source layout

| Package | Contents |
|---------|----------|
| `evaluate/` | `Evaluate`, `EvaluateConfig`, and `EvaluationResultPersistence` |
| `contracts/` | `Metric`, `Evaluator`, `EvaluationResult`, and `ExampleEvaluation` |
| `metrics/` | the metrics that compare text, `FunctionMetric`, the LM-judge metrics, and `NormalizeText` |

## Relation to dspy

`evaluate` ports `dspy/evaluate/`. These are the main differences:

- A metric reads a field by its name. Python DSPy uses attribute access.
- A metric always returns a `Double`. With a trace, the LM-judge metrics return `1.0` or `0.0`, where Python DSPy
  returns a `bool`.
- dspy4s makes the `SignatureLayout` of each judge program at runtime.

`AutoEvaluation.scala` describes these differences. Section 22 of `docs/port/PORT_DIFFERENCES.md` has the others.
