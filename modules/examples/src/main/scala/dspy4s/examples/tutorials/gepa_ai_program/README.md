# Reflective Prompt Evolution with GEPA

GEPA uses scored execution feedback to propose new instructions. In dspy4s it operates on an immutable
`RecordProgram`. It addresses each optimizer-writable slot by `ParameterId` and receives the reflection model as an
ordinary child `Program`.

GEPA needs:

- a `FeedbackMetric`, which returns `ScoreWithFeedback` for a prediction and its `ProgramEvent` journal;
- a reflector of type `ProgramWithEnv[InstructionProposer.Input, InstructionProposer.Output, R]`;
- training and validation `Example` values;
- a metric-call budget in `GepaConfig`.

Minimal shape:

```scala
val resultEffect = Gepa(
  student = student,
  trainset = trainset,
  valset = valset,
  metric = metric,
  reflector = reflector,
  config = GepaConfig(
    maxMetricCalls = MetricCallCount.applyUnsafe(60),
    reflectionMinibatchSize = MinibatchSize.applyUnsafe(3),
    seed = 0L
  )
)

// result.bestProgram is a new RecordProgram with evolved parameter values.
// result.bestCandidate is the immutable candidate parameter map.
// result.bestScore is the mean validation score.
```

Run the complete live-model example with:

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.gepaSmokeMain"
```

See [`GepaSmokeTest.scala`](../../verify/GepaSmokeTest.scala) and the larger grounded planner example in
[`talk_to_your_data/Optimize.scala`](../talk_to_your_data/Optimize.scala).
