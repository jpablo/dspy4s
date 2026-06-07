# Privacy-Conscious Delegation (PAPILLON)

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/papillon/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/papillon/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

The DSPy PAPILLON tutorial is an external notebook
([from the PAPILLON authors, Columbia-NLP-Lab](https://colab.research.google.com/github/Columbia-NLP-Lab/PAPILLON/blob/main/papillon_tutorial.ipynb)).
It demonstrates three advanced patterns:

1. A multi-stage `Module` where a small local LM uses an external tool.
2. A multi-stage *judge* built as a program and used as the evaluation metric.
3. Optimizing the module with that judge, using a large model as a teacher for the small local LM.

There's no dspy4s port of the notebook, but the building blocks exist:

- Multi-stage modules: compose `Module[I, O]` values (the [email extraction](../email_extraction/EmailExtraction.scala)
  and [llms.txt](../llms_txt_generation/LlmsTxtGeneration.scala) examples thread several `ChainOfThought`s).
- A small local LM: point `OpenAiLanguageModel` at an OpenAI-compatible endpoint (Ollama/vLLM) — see
  [`learn/programming/LanguageModels.scala`](../../learn/programming/LanguageModels.scala).
- A judge-as-metric: implement the `Metric` trait around a judge program (note the metric layer can't itself
  call an LM — run the judge program and score its output; see [`learn/evaluation`](../../learn/evaluation/README.md)).

The teacher-led optimization of a local student is **not** reproducible yet — dspy4s's optimizers are
`BootstrapFewShot*` only (no teacher-distillation / finetuning).
