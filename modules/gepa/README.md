# dspy4s GEPA

GEPA performs reflective Genetic-Pareto instruction optimization over a functional `RecordProgramWithEnv`.

A `Candidate` is `Map[ParameterId, Option[String]]`. A `FeedbackMetric` returns a score and feedback from explicit
`ProgramEvent` evidence. A user-supplied reflection `ProgramWithEnv` converts the current instruction and reflective
records into a proposed instruction.

```scala
Gepa(
  student = student,
  trainset = trainset,
  valset = valset,
  metric = feedbackMetric,
  reflector = reflectionProgram,
  config = GepaConfig(maxMetricCalls = MetricCallCount(200))
)
```

The result is a ZIO effect whose environment is the intersection of the student and reflector requirements.

`GepaAdapter` runs candidates and groups evidence by `ParameterId`. `GepaEvalCache` avoids duplicate candidate-example
evaluations. `GepaState` holds candidates, validation subscores, lineage, and metric-call count.
`GepaStatePersistence` supports JSON checkpoints. `MergeProposer` combines complementary changes from two descendants.
