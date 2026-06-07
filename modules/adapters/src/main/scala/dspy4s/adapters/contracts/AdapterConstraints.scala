package dspy4s.adapters.contracts

import dspy4s.core.contracts.FieldSpec

/** Renders a consolidated prose constraints block from the output fields that carry
  * [[dspy4s.core.contracts.FieldSpec.constraints]]. ChatAdapter embeds constraints per-field in its
  * `get_field_description_string` analog; XMLAdapter/JSONAdapter have no such block, so they append THIS block to
  * their system instruction instead (G-9 follow-up). Shared so both render constraints identically. */
object AdapterConstraints:

  /** A `Field constraints:` block listing each constrained output field and its constraint strings, or `None` when
    * no output field has constraints (so the prompt is unchanged for unconstrained signatures). */
  def block(outputFields: Vector[FieldSpec]): Option[String] =
    val lines = outputFields.collect {
      case f if f.constraints.nonEmpty => s"- `${f.name}`: ${f.constraints.mkString(", ")}"
    }
    if lines.isEmpty then None else Some("Field constraints:\n" + lines.mkString("\n"))

  /** Append [[block]] to a system instruction when any output field is constrained; otherwise return it unchanged. */
  def appendTo(systemText: String, outputFields: Vector[FieldSpec]): String =
    block(outputFields) match
      case Some(b) => s"$systemText\n\n$b"
      case None    => systemText
