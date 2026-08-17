# Optimization

Optimizers return new program values. They edit `OptimizableParameters` through stable `ParameterId` keys.

The module includes labeled few-shot, bootstrap few-shot, bootstrap random search, KNN few-shot, COPRO, MIPROv2, and
InferRules. GEPA is in its own module and uses a feedback metric plus a visible reflection program.

`ProgramPersistence` stores only ID-keyed optimizer state. It does not serialize executable syntax or services.
