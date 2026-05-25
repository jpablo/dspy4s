# Port Reference

Everything about how dspy4s relates to upstream Python DSPy. Four
docs at four levels of zoom; pick the one that matches what you're
looking for.

| Doc | Zoom level | Use when |
|---|---|---|
| [PORT_SIMILARITIES.md](PORT_SIMILARITIES.md) | Narrative — what stayed the same | You know Python DSPy and want to map your mental model onto dspy4s |
| [PORT_DIFFERENCES.md](PORT_DIFFERENCES.md) | Narrative — what changed shape and why | You hit something that looks different and want to understand the reasoning |
| [PORT_MAP.md](PORT_MAP.md) | Symbol-level ledger | You're hunting for the dspy4s name of a specific Python symbol, or checking the documented behavioral deltas |
| [PORT_LANGUAGE_NOTES.md](PORT_LANGUAGE_NOTES.md) | Idiom-level mechanics with code | You're translating a Python pattern (async, decorators, metaclasses, etc.) and want to see the Scala equivalent |
| [PORT_SCOPE.md](PORT_SCOPE.md) | v1 scope envelope | You want to know what's in scope and what's deferred at the feature level |
| [PORT_BACKLOG.md](PORT_BACKLOG.md) | Phase plan + status | You want to know which phase a feature shipped in or what's still pending |

## Upstream pin

DSPy **3.1.3** (released 2026-02-05). Bump procedure in
[PORT_BACKLOG.md](PORT_BACKLOG.md#upstream-parity-target).

## Related (outside this directory)

- [../ARCHITECTURE.md](../ARCHITECTURE.md) — dspy4s on its own terms (not framed against Python).
- [../STREAMING_POSTPONED.md](../STREAMING_POSTPONED.md) — streaming-specific deferred work and shipped slices.
- [../progress/](../progress/) — per-phase historical snapshots.
