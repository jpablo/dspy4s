# dspy4s documents

The documents are in one folder for each topic. The plan of the documentation site is in
[`site/PLAN.md`](../site/PLAN.md).

| Folder | Topic | Documents |
|---|---|---|
| [`architecture/`](architecture/) | The design of dspy4s | [ARCHITECTURE.md](architecture/ARCHITECTURE.md): the design choices and the module graph. [DOMAIN_CONCEPTS.md](architecture/DOMAIN_CONCEPTS.md): the domain terms. [TYPE_BRIDGE.md](architecture/TYPE_BRIDGE.md): how Scala types become the types that the LM sees. |
| [`signatures/`](signatures/) | Typed signatures | [TYPED_SIGNATURES.md](signatures/TYPED_SIGNATURES.md): the design. [TYPED_SIGNATURES_GUIDE.md](signatures/TYPED_SIGNATURES_GUIDE.md): the user guide. [TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md](signatures/TYPED_SIGNATURES_IMPLEMENTATION_PLAN.md): the plan, as a record. |
| [`streaming/`](streaming/) | Streaming | [STREAMING_POSTPONED.md](streaming/STREAMING_POSTPONED.md): the streaming features for a later version. |
| [`code-harness/`](code-harness/) | The code harness, a coding agent that acts through Scala code | [CODE_HARNESS_DESIGN.md](code-harness/CODE_HARNESS_DESIGN.md): the current design. [CODE_HARNESS.md](code-harness/CODE_HARNESS.md): the analysis and the earlier design proposal. [CODE_HARNESS_LOOPS.md](code-harness/CODE_HARNESS_LOOPS.md): the agent loops compared. [CODE_HARNESS_PHASE0.md](code-harness/CODE_HARNESS_PHASE0.md): the report of the Phase 0 experiment. [CODE_HARNESS_DESIGN_SPACE.md](code-harness/CODE_HARNESS_DESIGN_SPACE.md): the main aspects of the design space, and the questions for each. [CODE_HARNESS_DURABILITY.md](code-harness/CODE_HARNESS_DURABILITY.md): what survives a crash in each harness. [CODE_HARNESS_RUNTIME.md](code-harness/CODE_HARNESS_RUNTIME.md): how each project executes its work, and the fit of Pekko event sourcing. [CODE_HARNESS_CONCEPTS.md](code-harness/CODE_HARNESS_CONCEPTS.md): the inventory of the domain concepts and their kinds of state. [CODE_HARNESS_KYO.md](code-harness/CODE_HARNESS_KYO.md): which parts of Kyo the harness can reuse. |
| [`port/`](port/) | The port from Python DSPy | [README.md](port/README.md) is the index. [SOURCE_STUDY.md](port/SOURCE_STUDY.md) is the study of the DSPy source. |
| [`progress/`](progress/) | The phases of the port | [README.md](progress/README.md) is the index. The reports are historical records. |
| [`refactor/`](refactor/) | The algebraic refactor | [algebra.md](refactor/algebra.md), [algebra-2-program-composition.md](refactor/algebra-2-program-composition.md), [composite-primitives.md](refactor/composite-primitives.md), [program-ast-interpreters.md](refactor/program-ast-interpreters.md), [kyo-ai-comparison.md](refactor/kyo-ai-comparison.md), and [reading-list.md](refactor/reading-list.md). |

Put a new document into the folder of its topic. For a new topic, make a new folder, and add a row to this table.
