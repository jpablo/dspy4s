# Evaluation

`Evaluate` runs a `RecordProgramWithEnv` over examples with bounded parallelism. A `Metric` returns a ZIO effect and
receives the prediction plus explicit `ProgramEvent` evidence.

Program and metric failures become scored rows. The aggregate result is the mean score times 100. Built-in metrics
include exact match, contains, token F1, passage match, semantic F1, and completeness plus groundedness.
