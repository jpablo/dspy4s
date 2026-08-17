# Coming from DSPy

dspy4s uses DSPy concepts but not its object model.

| DSPy style | dspy4s functional style |
|---|---|
| Subclass `Module` and implement `forward` | Build `Program` syntax with constructors and combinators |
| Call a module directly | Run syntax with `ProgramRunner` |
| Mutable predictor fields | Immutable `ParameterStore` values |
| Predictor position or object identity | Stable `ParameterId` |
| Global LM configuration | Explicit backend service |
| Trace in ambient context | Explicit `ProgramEvent` journal |
| Optimizer mutates or copies modules | Optimizer returns a new `RecordProgramWithEnv` |

Python DSPy remains a source of algorithms and product ideas. Scala types and algebraic composition determine this
library's API.
