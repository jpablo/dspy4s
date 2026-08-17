# dspy4s optimize

Optimizers accept immutable `RecordProgramWithEnv` values and return `OptimizationReport` values. They update demos,
instructions, or configuration through the program's elaborated parameter IDs. They do not mutate a student.
Anonymous declarations use ordinal IDs. Named declarations use stable semantic IDs.

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

`ProgramPersistence` saves and loads only optimizer-writable state. The JSON object is keyed by the public parameter
IDs and contains a declaration-shape fingerprint. Loading rejects a different program shape and missing, extra,
duplicate, or malformed parameter entries.
