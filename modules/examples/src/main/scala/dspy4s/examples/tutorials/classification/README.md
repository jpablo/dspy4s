# Classification

> Adapted for **dspy4s** from the DSPy docs page
> [`tutorials/classification/index.md`](https://github.com/stanfordnlp/dspy/blob/main/docs/docs/tutorials/classification/index.md)
> (MIT-licensed, © Stanford Future Data Systems). Rewritten for the Scala port.

The DSPy classification tutorial is an external write-up
([Drew Breunig, *Pipelines & Prompt Optimization with DSPy*](https://www.dbreunig.com/2024/12/12/pipelines-prompt-optimization-with-dspy.html))
that categorizes historic events with a tiny LM and then optimizes the pipeline.

There's no separate dspy4s port of that external article, but classification is fully expressible in dspy4s:

- Declare the label set as a Scala `enum` (it `derives Schema`) and make it an output field — the allowed
  values reach the LM via the signature and decode back to the typed enum. See
  [`typed/CaseClassExample.scala`](../../typed/CaseClassExample.scala) (an `Emotion` enum) and the
  classification snippets in [`learn/programming/Signatures.scala`](../../learn/programming/Signatures.scala).
- Wrap it in `Predict` or `ChainOfThought`, evaluate with a metric ([`learn/evaluation`](../../learn/evaluation/README.md)),
  and tune demos with `BootstrapFewShot*` ([`learn/optimization`](../../learn/optimization/README.md)).
- The [email extraction example](../email_extraction/EmailExtraction.scala) classifies emails by type/urgency
  enums as one step of a larger pipeline.
