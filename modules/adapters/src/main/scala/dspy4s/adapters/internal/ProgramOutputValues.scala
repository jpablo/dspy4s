package dspy4s.adapters.internal

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import zio.blocks.schema.DynamicValue

/** The output value rules of the adapters that the programs use. `RLM` reads the values of `SUBMIT` with these rules,
  * so an output value has one rule in a reply of the model and in a `SUBMIT` (decision D7). This object makes only
  * these rules visible outside the adapters module.
  */
private[dspy4s] object ProgramOutputValues:

  /** `true` when the output field `field` takes only its allowed values ([[AdapterTextSupport.hasAllowedValues]]). */
  def hasAllowedValues(field: FieldSpec): Boolean =
    AdapterTextSupport.hasAllowedValues(field)

  /** The allowed value of the output field `field` that the JSON value `value` gives, with the rule of the JSON adapter
    * ([[AdapterTextSupport.allowedJsonValue]], upstream `parse_value` with a `Literal` annotation in
    * `dspy/adapters/utils.py`). Use it only for a field for which [[hasAllowedValues]] is `true`.
    */
  def allowedValue(field: FieldSpec, value: DynamicValue): Either[DspyError, DynamicValue] =
    AdapterTextSupport.allowedJsonValue(field, value)
