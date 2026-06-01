package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import zio.blocks.schema.DynamicValue

import scala.annotation.nowarn

trait StatusMessageProvider:
  def moduleStart(instanceName: String, inputs: Map[String, Any]): Option[String] = None
  def moduleEnd(instanceName: String, output: Any): Option[String] = None
  def lmStart(modelId: String, inputs: Map[String, Any]): Option[String] = None
  def lmEnd(modelId: String, output: Any): Option[String] = None
  // Tool args/result travel the spine as DynamicValue (unlike the module/lm payloads above, which are still
  // free-form Maps); the tool callbacks are typed to match.
  @nowarn("msg=unused")
  def toolStart(toolName: String, args: DynamicValue.Record): Option[String] =
    Some(s"Calling tool $toolName...")
  @nowarn("msg=unused")
  def toolEnd(toolName: String, output: Either[DspyError, DynamicValue]): Option[String] =
    Some("Tool calling finished! Querying the LLM with tool calling results...")

object StatusMessageProvider:
  val default: StatusMessageProvider = new StatusMessageProvider {}
