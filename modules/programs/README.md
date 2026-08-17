# dspy4s programs

`ProgramWithEnv[I, O, R]` is a pure typed description of work. `I` is the input, `O` is the output, and `R` is the
required service environment. The common `Program[I, O]` alias requires `PredictionBackend`.

## Construction

- `Program.predict` declares one typed prediction and one stable parameter slot.
- `identity`, `lift`, and `liftEither` add pure local work.
- `>>>`, `&&&`, `***`, and `|||` compose typed programs.
- `map`, `contramap`, `recoverWith`, and `attempt` adapt boundaries and failures.
- `iterate`, `collectAll`, and `collectAllPar` provide bounded control flow.
- `executeCode`, `invokeTool`, and `executeRepl` add explicit service requirements.

`ChainOfThought`, `MultiChainComparison`, `ProgramOfThought`, `ReAct`, `CodeAct`, `RLM`, `Refine`, and `Ensemble` are
constructors over this common syntax. They are not self-executing module classes.

## Interpretation

`ProgramRunner` is the ZIO interpreter. It returns `Prediction[O]` and can record `ProgramEvent` values. Prediction
events contain a `ParameterId`, so evaluators and optimizers can connect evidence to a stable parameter slot.

`ProgramEventStream` in the streaming module exposes the same event path as a `ZStream`. There is no second streaming
runtime.

## Parameters

Syntax and optimizer state are separate:

- `ParameterStore` holds `OptimizableParameters`.
- `ParameterId` gives each slot a stable identity.
- `ProgramParameters[P]` provides generic read and replacement operations.
- `RecordProgramWithEnv` adds only the input decoder needed by datasets and optimizers.

State persistence stores ID-to-value data. It does not serialize Scala functions, syntax, tools, or services.

## Stack safety

`DeepProgramSuite` executes a chain of 20,000 nodes. It also checks 20,000 loop steps, 20,000 `collectAll` members,
and graph interpretation. Run it with:

```bash
sbt "programs/testOnly dspy4s.programs.DeepProgramSuite"
```
