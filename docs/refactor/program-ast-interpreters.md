# Program syntax and interpreters

**Status:** implemented

**Decision date:** 2026-08-16

## Decision

dspy4s uses a passive typed program syntax and separate interpreters. It does not use an executable `Module` base class,
`apply`/`forward` lifecycle hooks, or per-composite optimizer traversal.

`ProgramWithEnv[I, O, R]` records input, output, and required services. Composition combines service requirements with
intersection types. Prediction, code, tool, and REPL effects use separate capabilities. Pure nodes require `Any`.

## Main design

| Concern | Design |
|---|---|
| Syntax | Closed typed `Program.Node[I, O]` tree |
| Execution | Stateless ZIO `ProgramRunner` |
| Effects | Explicit backend services in environment `R` |
| Parameters | Separate immutable `ParameterStore` |
| Identity | Private declaration keys; anonymous ordinals or optional stable names |
| Dataset boundary | `RecordProgramWithEnv` plus `Shape[I]` |
| Evidence | `Prediction[O]` and `Vector[ProgramEvent]` |
| Graph view | `ProgramGraph` interpreter over the same syntax |
| Streaming | `ProgramEventStream` over the execution observer path |

The syntax does not contain a model, adapter, callback registry, runner, thread-local context, or mutable prompt value.
`LivePredictionBackend` is a boundary adapter for the older blocking LM and adapter contracts.

## Why this replaced the earlier design

The old design placed execution in `Module.forward` and lifecycle work in `Module.apply`. Composition required executable
wrapper classes. Each structural form also needed separate optimizer traversal and replacement logic. This caused the
same tree structure to appear in execution, parameter inspection, persistence, and composition code.

The new design represents structure once. Interpreters consume that structure. Optimizers use one stable parameter
store instead of an `OptimizableStructure` instance for each composite. Ordinary prediction declarations do not need a
public ID. A private declaration token preserves sharing, and the store assigns optimizer-facing ordinal IDs. Named
`PredictionDef` values provide stable semantic identity only when it is necessary. Strategy constructors reuse generic
nodes and bounded loops.

The existential optic work was useful, but it solved a symptom. Generic optics reduced repeated traversal code in the
old representation. A separate parameter store removes that traversal from the main program design. The generic optic
library remains useful in the algebra module for other heterogeneous structures.

## Verification

The main program suite checks typed composition, stable parameter replacement, state round trips, explicit capability
requirements, interpreter events, functional strategies, and streaming.

`DeepProgramSuite` verifies a 20,000-node sequential program, 20,000 loop steps, 20,000 `collectAll` members, and
graph interpretation:

```bash
sbt "programs/testOnly dspy4s.programs.DeepProgramSuite"
```

Evaluation, optimization, GEPA, and streaming tests use only the functional program boundary.
