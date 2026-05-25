# Phase Progress Snapshots

Per-phase implementation reports, written at the time the phase shipped.
**Historical snapshots** — symbol names and APIs referenced inside may
have been renamed since (notably the original `Signature` is now
`SignatureLayout` and the typed wrapper carries that name).

For current state, see:

- [../ARCHITECTURE.md](../ARCHITECTURE.md) — overall architecture
- [../TYPED_SIGNATURES_GUIDE.md](../TYPED_SIGNATURES_GUIDE.md) — user-facing API
- [../PORT_MAP.md](../PORT_MAP.md) — per-symbol mapping ledger
- [../PORT_BACKLOG.md](../PORT_BACKLOG.md) — phase plan + status

## Contents

| Phase | Doc | Scope |
|---|---|---|
| 0 | [PHASE0_CONTRACTS.md](PHASE0_CONTRACTS.md) | Module scaffolding + interface-first contracts |
| 1 | [PHASE1_PROGRESS.md](PHASE1_PROGRESS.md) | Signatures + primitives (`core`) |
| 2 | [PHASE2_PROGRESS.md](PHASE2_PROGRESS.md) | Settings + callbacks + parallel context |
| 3 | [PHASE3_PROGRESS.md](PHASE3_PROGRESS.md) | LM runtime (cache, retry, history, usage) |
| 4 | [PHASE4_PROGRESS.md](PHASE4_PROGRESS.md) | Adapters (Chat, JSON, XML) |
| 5 | [PHASE5_PROGRESS.md](PHASE5_PROGRESS.md) | Programs (`Predict`, `ChainOfThought`, `ReAct`, ...) |
| 6 | [PHASE6_PROGRESS.md](PHASE6_PROGRESS.md) | Evaluation (`Evaluate` runner + metrics) |
| 7 | [PHASE7_PROGRESS.md](PHASE7_PROGRESS.md) | First optimizers (`BootstrapFewShot[WithRandomSearch]`) |
| 8 | [PHASE8_PROGRESS.md](PHASE8_PROGRESS.md) | Streaming v1 |
