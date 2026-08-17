package dspy4s.programs

import dspy4s.adapters.contracts.ToolSpec
import dspy4s.core.data.Example
import dspy4s.signatures.Signature
import zio.blocks.schema.DynamicValue

/** A named prediction declaration and its stable parameter reference.
  *
  * Use [[program]] in composition. Use this declaration itself when an optimizer, refinement strategy, or application
  * operation must address the prediction. This prevents repeated string-based ID lookup.
  */
final class PredictionDef[I, O] private[programs] (
    val id     : ParameterId,
    val program: Program[I, O]
) extends ParameterRef

/** A semantic namespace for stable prediction declarations. */
final class ParameterNamespace private (val value: String):

  /** Add one namespace segment. */
  def child(segment: String): ParameterNamespace =
    ParameterNamespace(s"$value/${ParameterNamespace.segment(segment)}")

  /** Create a stable parameter ID inside this namespace. */
  def id(localName: String): ParameterId =
    ParameterId(s"$value/${ParameterNamespace.segment(localName)}")

  /** Declare one named prediction and retain a first-class reference to it. */
  def declare[I, O](
      localName: String,
      signature: Signature[I, O],
      demos    : Vector[Example]     = Vector.empty,
      config   : DynamicValue.Record = DynamicValue.Record.empty,
      name     : String              = "predict",
      tools    : Vector[ToolSpec]    = Vector.empty
  ): PredictionDef[I, O] =
    val parameterId = id(localName)
    new PredictionDef(parameterId, Program.predictStable(parameterId, signature, demos, config, name, tools))

  /** Declare one stable prediction when no later operation needs its first-class reference. */
  def predict[I, O](
      localName: String,
      signature: Signature[I, O],
      demos    : Vector[Example]     = Vector.empty,
      config   : DynamicValue.Record = DynamicValue.Record.empty,
      name     : String              = "predict",
      tools    : Vector[ToolSpec]    = Vector.empty
  ): Program[I, O] =
    declare(localName, signature, demos, config, name, tools).program

  override def equals(other: Any): Boolean =
    other match
      case that: ParameterNamespace => value == that.value
      case _                        => false

  override def hashCode(): Int = value.hashCode

  override def toString: String = s"ParameterNamespace($value)"

object ParameterNamespace:
  def apply(value: String): ParameterNamespace =
    val normalized = value.trim.stripSuffix("/")
    require(normalized.nonEmpty, "Parameter namespace must not be empty")
    normalized.split('/').foreach(segment)
    val _ = ParameterId(normalized)
    new ParameterNamespace(normalized)

  private def segment(value: String): String =
    val normalized = value.trim
    require(normalized.nonEmpty, "Parameter namespace segment must not be empty")
    require(!normalized.contains('/'), s"Parameter namespace segment '$normalized' must not contain '/'")
    normalized
