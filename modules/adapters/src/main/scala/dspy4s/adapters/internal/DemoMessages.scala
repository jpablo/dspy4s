package dspy4s.adapters.internal

import dspy4s.adapters.contracts.AdapterDemos
import dspy4s.core.contracts.DynamicValues
import dspy4s.core.contracts.FieldSpec
import dspy4s.core.contracts.SignatureLayout
import dspy4s.core.contracts.updated
import dspy4s.core.data.Example
import dspy4s.lm.contracts.Message
import dspy4s.lm.contracts.MessageRole
import zio.blocks.schema.{DynamicValue, PrimitiveValue}

/** The messages of the demos of a request (upstream `Adapter.format_demos`). [[AdapterDemos]] gives the rule and its
  * texts. The Chat, JSON, XML, and TwoStep adapters use this one implementation. A demo shows each value that it has,
  * also a null value.
  */
private[adapters] object DemoMessages:

  /** A user message and an assistant message for each demo that the rule keeps: the incomplete demos first, then the
    * complete demos, each group in the order of `demos`.
    *
    * @param layout
    *   the layout that the demos show. A demo is complete when it has a value for each field of this layout.
    * @param missingOutput
    *   the value that an incomplete demo shows for an output field that it does not have (upstream
    *   `missing_field_message`), or `None` when the assistant turn does not show such a field
    * @param user
    *   the text of a user turn for the values of a demo
    * @param assistant
    *   the text of an assistant turn for the values of a demo
    */
  def messages(layout: SignatureLayout, demos: Vector[Example], missingOutput: Option[String])(
      user     : DynamicValue.Record => String,
      assistant: DynamicValue.Record => String
  ): Vector[Message] =
    val (complete, others) = demos.map(_.values).partition(values => hasValues(layout.fields, values))
    // Upstream counts a key with a None value as an input or an output here.
    val incomplete = others.filter { values =>
      layout.inputFields.exists(hasKey(_, values)) && layout.outputFields.exists(hasKey(_, values))
    }
    incomplete.flatMap { values =>
      val shown = incompleteValues(layout, values, missingOutput)
      turns(Vector(AdapterDemos.IncompletePrefix, user(values)).filter(_.nonEmpty).mkString("\n\n"), assistant(shown))
    } ++ complete.flatMap(values => turns(user(values), assistant(values)))

  private def turns(userText: String, assistantText: String): Vector[Message] =
    Vector(
      Message(role = MessageRole.User, text = Some(userText)),
      Message(role = MessageRole.Assistant, text = Some(assistantText))
    )

  private def hasValues(fields: Vector[FieldSpec], values: DynamicValue.Record): Boolean =
    fields.forall(hasValue(_, values))

  /** `true` when `values` has the key of `field`, also with a null value. */
  private def hasKey(field: FieldSpec, values: DynamicValue.Record): Boolean =
    DynamicValues.recordGet(values, field.name).isDefined

  /** `true` when `values` has a value that is not null for `field`. */
  private def hasValue(field: FieldSpec, values: DynamicValue.Record): Boolean =
    DynamicValues.recordGet(values, field.name) match
      case None | Some(_: DynamicValue.Null.type) => false
      case Some(_)                                => true

  /** The values that the assistant turn of an incomplete demo shows: `missingOutput` for each output field that the
    * demo does not have. A null value stays, as upstream `outputs.get(k, missing_field_message)`.
    */
  private def incompleteValues(
      layout       : SignatureLayout,
      values       : DynamicValue.Record,
      missingOutput: Option[String]
  ): DynamicValue.Record =
    missingOutput.fold(values) { text =>
      layout.outputFields.filterNot(hasKey(_, values)).foldLeft(values) { (shown, field) =>
        shown.updated(field.name, DynamicValue.Primitive(PrimitiveValue.String(text)))
      }
    }
