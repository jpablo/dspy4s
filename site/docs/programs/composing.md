# Composing programs

Programs compose as typed values:

| Operator | Meaning |
|---|---|
| `first >>> second` | Sequential composition |
| `left &&& right` | Same input, paired outputs |
| `left *** right` | Paired inputs, paired outputs |
| `left ||| right` | Typed `Either` choice |
| `map` | Change output |
| `contramap` | Change input |

Composition also combines service requirements. A prediction followed by code execution requires
`PredictionBackend & CodeExecutionBackend`.

Bounded control flow uses `iterate`, `collectAll`, `collectAllPar`, `bestOfN`, and `repeatUntil`.
