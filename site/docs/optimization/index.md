# Optimization

Optimizers return new program values. They edit `OptimizableParameters` through the program's elaborated parameter
IDs. Ordinary predictions use structural ordinal IDs. Named predictions use stable semantic IDs.

The module includes labeled few-shot, bootstrap few-shot, bootstrap random search, KNN few-shot, COPRO, MIPROv2, and
InferRules. GEPA is in its own module and uses a feedback metric plus a visible reflection program.

`ProgramPersistence` stores only ID-keyed optimizer state plus a declaration-shape fingerprint. Anonymous state
requires the same declaration shape. Named state can use stable semantic keys. Persistence does not serialize
executable syntax or services.
