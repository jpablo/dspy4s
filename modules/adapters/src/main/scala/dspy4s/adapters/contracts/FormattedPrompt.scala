package dspy4s.adapters.contracts

import dspy4s.core.contracts.DynamicValues
import dspy4s.lm.contracts.Message
import zio.blocks.schema.DynamicValue

/** The rendered prompt an adapter produces from an [[AdapterInvocation]].
  *
  * `requestOptions` holds the provider request fields that the adapter adds, for example the structured output
  * configuration and the native function definitions. The engine merges this record under the existing per-call/module
  * request options, so explicit configuration wins on key collision.
  *
  * `removedOptions` holds the keys that the request must not have, also when the per-call/module options have them
  * (upstream `Adapter._call_preprocess` pops the tool options when native function calling is not active). The engine
  * removes them from the existing request options before the merge. [[options]] does the two steps.
  */
final case class FormattedPrompt(
    messages      : Vector[Message],
    requestOptions: DynamicValue.Record = DynamicValue.Record.empty,
    removedOptions: Set[String]         = Set.empty
):

  /** The options of the request: `request` with no key of [[removedOptions]], over [[requestOptions]]. A key of
    * `request` replaces the same key of [[requestOptions]] in its position ([[DynamicValues.mergeRecords]]).
    */
  def options(request: DynamicValue.Record): DynamicValue.Record =
    DynamicValues.mergeRecords(
      requestOptions,
      DynamicValues.recordFilterKeys(request, key => !removedOptions.contains(key))
    )
