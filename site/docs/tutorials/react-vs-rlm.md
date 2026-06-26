# ReAct vs RLM for compositional tool calling

This example gives the same drug-safety task and the same set of tools to two agent programs, `ReAct` and `RLM`, and measures how each one decides to use the tools. It demonstrates the difference between a text-protocol tool loop and a code-writing tool loop on a task that requires many systematic checks.

The task is a clinical drug-safety report over a list of seven medications and two patient conditions. A thorough answer needs every pairwise drug-drug interaction check (21 unique pairs) and every drug-condition contraindication check (14 combinations). The instruction asks for a thorough report without naming a strategy, so each program picks its own method.

## One shared signature

```scala
--8<-- "tutorials/react_vs_rlm/ReactVsRlm.scala:shared-signature"
```

Both programs are built from the same `Signature`. Inputs are comma-separated `medications` and `conditions` lists; the output is a `risk_report`. The task instruction is deliberately neutral about how to be thorough, so the comparison reflects each program's own tool-selection behavior rather than a hand-fed plan.

## ReAct: the text-protocol loop

```scala
--8<-- "tutorials/react_vs_rlm/ReactVsRlm.scala:react-agent"
```

`ReAct` runs a think, pick a tool, observe loop. Each turn re-reads the growing trajectory, then emits one tool call, and the LM is invoked again to read the result. The tool list is the same set passed to `RLM`, and `maxIterations` is a generous ceiling rather than a target: ReAct has enough budget to check every pair, so any gaps come from its own heuristic tool selection.

## RLM: the code-writing loop

```scala
--8<-- "tutorials/react_vs_rlm/ReactVsRlm.scala:rlm-agent"
```

`RLM` exposes the program inputs as variables inside a sandboxed Python REPL and the same tools as callable functions. The model writes Python that calls those tools in a loop and submits the result. Because one code-generation step can write a loop that enumerates every combination, RLM reaches full coverage in a few LM round-trips instead of one round-trip per tool call. `RLM` runs its sandbox on Deno, so the Python side runs only when Deno is on the PATH.

## What is measured

Each run records two things. Coverage is tracked by a `CallRecorder` that observes every tool call and reports how many of the 21 interaction pairs and 14 contraindication pairs were actually checked. Effort is tracked by counting LM round-trips (via `LmStartEvent`) and wall time. The durable result is the effort gap: even when a capable model lets ReAct reach full coverage, RLM gets there in far fewer LM calls.

## Running it

```bash
OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.react_vs_rlm.reactVsRlmMain"
```

## Notes

The interaction, contraindication, and drug-class tables are small illustrative fixtures, not a real clinical database, and this example is not medical advice. The example uses the OpenAI-compatible route through `Demo` (`OPENAI_API_KEY`, with an optional `DSPY_MODEL`); any OpenAI-compatible endpoint works. The `RLM` side needs Deno on the PATH for its Pyodide sandbox; without it, the run executes the ReAct side only and prints how to enable the RLM side. Exact coverage numbers vary from run to run, so the example prints what each program achieved rather than asserting fixed totals.

Full source: [ReactVsRlm.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/tutorials/react_vs_rlm/ReactVsRlm.scala)
