package dspy4s.streaming

import dspy4s.core.contracts.DspyError
import zio.blocks.schema.DynamicValue

import scala.annotation.nowarn

trait StatusMessageProvider:
  // Start hooks receive the observability input bag as a `DynamicValue.Record` (spine-typed, not free-form Map).
  // End hooks still receive the raw domain result (`DynamicPrediction` / `LmResponse`) erased to `Any`.
  def moduleStart(instanceName: String, inputs: DynamicValue.Record): Option[String] = None
  def moduleEnd(instanceName: String, output: Any): Option[String] = None
  def lmStart(modelId: String, inputs: DynamicValue.Record): Option[String] = None
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
