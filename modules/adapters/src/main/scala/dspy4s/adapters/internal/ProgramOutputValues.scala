package dspy4s.adapters.internal

import dspy4s.core.contracts.DspyError
import dspy4s.core.contracts.FieldSpec
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The output value rules of the adapters that the programs use. `RLM` reads the values of `SUBMIT` with these rules,
  * so an output value has one rule in a reply of the model and in a `SUBMIT` (decision D7). This object makes only
  * these rules visible outside the adapters module.
  */
private[dspy4s] object ProgramOutputValues:

  /** `true` when the output field `field` takes only its allowed values ([[AdapterTextSupport.hasAllowedValues]]). */
  def hasAllowedValues(field: FieldSpec): Boolean =
    AdapterTextSupport.hasAllowedValues(field)

  /** The allowed value of the output field `field` that the value `value` gives, with the rule of the JSON adapter for
    * a JSON value (upstream `parse_value` with a `Literal` annotation, `dspy/adapters/utils.py`). Use it only for a
    * field for which [[hasAllowedValues]] is `true`:
    *   - A null of an optional field is a null.
    *   - A string takes the steps of [[AdapterTextSupport.allowedValue]], and the result is the allowed value itself.
    *   - Each other value is an error, because each allowed value is a string.
    */
  def allowedValue(field: FieldSpec, value: DynamicValue): Either[DspyError, DynamicValue] =
    value match
      case _: DynamicValue.Null.type if field.optional         => Right(DynamicValue.Null)
      case DynamicValue.Primitive(PrimitiveValue.String(text)) => AdapterTextSupport.outputTextValue(field, text)
      case other                                               =>
        val text = PythonText.json(other)
        Left(AdapterTextSupport.notAllowedValue(field, text))
