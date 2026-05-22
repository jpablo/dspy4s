package dspy4s.streaming

trait StatusMessageProvider:
  def moduleStart(instanceName: String, inputs: Map[String, Any]): Option[String] = None
  def moduleEnd(instanceName: String, output: Any): Option[String] = None
  def lmStart(modelId: String, inputs: Map[String, Any]): Option[String] = None
  def lmEnd(modelId: String, output: Any): Option[String] = None
  def toolStart(toolName: String, args: Map[String, Any]): Option[String] =
    Some(s"Calling tool $toolName...")
  def toolEnd(toolName: String, output: Any): Option[String] =
    Some("Tool calling finished! Querying the LLM with tool calling results...")

object StatusMessageProvider:
  val default: StatusMessageProvider = new StatusMessageProvider {}
