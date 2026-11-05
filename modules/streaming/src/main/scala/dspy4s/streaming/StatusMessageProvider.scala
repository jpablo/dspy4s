package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import zio.blocks.schema.DynamicValue

trait StatusMessageProvider:
  def moduleStart(instanceName: String, inputs: DynamicValue.Record): Option[String]            = None
  def moduleEnd(instanceName  : String, output: Either[DspyError, Any]): Option[String]         = None
  def lmStart(modelId         : String, inputs: DynamicValue.Record): Option[String]            = None
  def lmEnd(modelId           : String, output: Either[DspyError, Any]): Option[String]         = None
  def toolStart(toolName: String, @annotation.unused args: DynamicValue.Record): Option[String] =
    Some(s"Calling tool $toolName...")
  def toolEnd(
      @annotation.unused toolName: String,
      @annotation.unused output  : Either[DspyError, DynamicValue]
  ): Option[String] =
    Some("Tool calling finished! Querying the LLM with tool calling results...")

object StatusMessageProvider:
  val default: StatusMessageProvider = new StatusMessageProvider {}
