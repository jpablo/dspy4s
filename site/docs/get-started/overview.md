# How it fits together

1. A `Signature[I, O]` defines the record encoding and decoding boundary.
2. A `ProgramWithEnv[I, O, R]` describes typed work and its required services.
3. `ProgramRunner` interprets the program in ZIO.
4. `RecordProgramWithEnv` adds dataset input decoding for evaluation and optimization.
5. `ParameterStore` holds immutable optimizer values. Ordinary predictions get ordinal IDs. Named declarations get
   stable semantic IDs.

Low-level LM providers and adapters remain behind `LivePredictionBackend`. They are not part of program composition.
