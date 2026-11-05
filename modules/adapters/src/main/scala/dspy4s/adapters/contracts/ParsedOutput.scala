package dspy4s.adapters.contracts

import dspy4s.core.decisions.DecisionEvidence
import zio.blocks.schema.DynamicValue

/** Adapter parse result. `values` is the structured record of output field values produced from the LM completion.
  *
  * `evidence` is experimental: the decision evidence that the parse decoded, by output name, in the order of the layout
  * (`dspy4s.adapters.DecisionAdapter`). It is empty for a parse with no decision outputs. The predictor records it in
  * the [[dspy4s.core.decisions.DecisionEvidenceLog]].
  */
final case class ParsedOutput(
    values  : DynamicValue.Record,
    evidence: Vector[(String, DecisionEvidence)] = Vector.empty
)
