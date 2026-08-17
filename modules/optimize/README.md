# dspy4s optimize

Optimizers accept immutable `RecordProgramWithEnv` values and return `OptimizationReport` values. They update demos,
instructions, or configuration through stable `ParameterId` values. They do not mutate a student and they do not use
structural position as identity.

Available optimizers:

- `LabeledFewShot`
- `BootstrapFewShot`
- `BootstrapRandomSearch`
- `KNNFewShot`
- `COPRO`
- `MIPROv2`
- `InferRules`

Each optimizer composes `ProgramRunner`, `Evaluate`, typed proposal programs, and `ProgramParameters`. Effects remain in
ZIO. Proposal failures and candidate scores remain explicit report data.

`ProgramPersistence` saves and loads only optimizer-writable state. The JSON object is keyed by `ParameterId`. Loading
rejects missing, extra, duplicate, and malformed parameter entries.
