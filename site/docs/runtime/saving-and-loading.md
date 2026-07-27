# Saving & loading

An [optimized program](../optimization/index.md) carries a small, explicit set of
prompt parameters. `ProgramPersistence` writes those parameters to JSON and applies
them to a freshly constructed program, so optimization can run once and the result
can be loaded at startup.

## Save and load

```scala
--8<-- "learn/optimization/Optimizers.scala:save-load"
```

`load` does not reconstruct an executable program. It takes a fresh program whose
architecture and runtime bindings were created normally in Scala, then returns a new
immutable value with the saved optimizable parameters applied.

## The optimizable-parameter contract

Every optimizer-addressable leaf exposes the same writable `OptimizableParameters`:

| Field | Meaning |
|---|---|
| `instructions: Option[String]` | The signature-level prompt instructions. |
| `demos: Vector[Example]` | Few-shot examples rendered by the adapter. |
| `config: DynamicValue.Record` | Module-level LM option defaults; per-call options still win. |

This contract is shared by typed `Predict`, typed `ChainOfThought`, and
`DynamicPredict`. Framework composites expose the same parameters for each stable
leaf—for example, `ProgramOfThought` exposes its generator, regenerator, and
answerer. The executable predictor is not used as a parameter carrier.

`OptimizableLeaf[P]` is a lens onto these parameters. Its instances obey:

- Get-Put: writing the parameters just read is a no-op.
- Put-Get: reading after a write returns the written parameters.
- Put-Put: only the last parameters written matter.
- Frame: writing parameters does not change predictor metadata.

At the composite level, `OptimizableTraversal.replace(program, OptimizableTraversal.read(program))`
returns the original program, and an arity-matched replacement reads back as the
same parameter vector.

Algebraically, `OptimizableLeaf[P]` is a lawful lens focused on one `OptimizableParameters`,
while `OptimizableTraversal[P]` is an ordered finite traversal. Composition concatenates
the child traversals, parameter-free structure contributes the empty vector, and
`Vector[OptimizableParameters]` forms the parameter monoid under concatenation. Named
inspection is checked against the canonical traversal so labels cannot silently
reorder or substitute parameters.

## Metadata and execution resources

`OptimizableMetadata` is inspectable but not writable through optimizer replacement.
It contains the signature field structure (with instructions removed) and module
name. Loading preserves that metadata from the fresh target program.

The following are also not persisted:

- program code and composite structure;
- runtime/model resolution and adapter configuration;
- output JSON schemas, bound LMs, and tools;
- callbacks, traces, history, and prior predictions.

This boundary prevents a saved prompt artifact from replacing architecture or live
execution resources. Recreate and configure those normally, then load state into it.

## OptimizableLeaf IDs and compatibility

The JSON object keys are `predictor-0`, `predictor-1`, and so on. They are ordinal
IDs derived from the root `OptimizableTraversal` traversal:

- JSON object order does not matter.
- Missing and unknown ordinals are rejected.
- An equal-size reorder cannot be detected, because the IDs are not semantic names.

Load only into a compatible program with the same optimizable-leaf count and traversal
order. Structural display names are useful diagnostics, but they are not persisted
identity.

Each entry has this shape:

```json
{
  "optimizableParameters": {
    "optimizable-0": {
      "instructions": "Answer concisely.",
      "demos": [],
      "config": { "temperature": 0.2 }
    }
  }
}
```

The former entry format containing a full `signature` layout is unsupported. There
is no legacy migration path: regenerate the artifact with the current code. The
field structure now belongs exclusively to the fresh program's metadata.

## State-only versus whole-program persistence

dspy4s supports state-only JSON. It does not implement Python DSPy's
`save_program=True`, pickle/cloudpickle artifacts, or `modules_to_serialize`.

Next: [Streaming](streaming.md).
