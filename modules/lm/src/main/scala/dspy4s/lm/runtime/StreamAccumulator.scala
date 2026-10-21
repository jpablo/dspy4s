package dspy4s.lm.runtime

import dspy4s.lm.contracts.LmChunk
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.contracts.LmOutput
import dspy4s.lm.contracts.LmToolCallDelta
import dspy4s.lm.providers.ResponsesWire
import zio.blocks.schema.DynamicValue

import scala.collection.mutable

/** The output of a stream, built from its chunks in order (upstream lm15 `StreamAccumulator` in
  * `dspy/_vendor/lm15/result.py`, read through `legacy_output` in `dspy/clients/call_result.py`):
  *
  *   - The text is the joined text of the chunks.
  *   - The tool calls come from the tool-call deltas of the chunks, with the rule of `mode`
  *     ([[ToolCallAssembler.assemble]]).
  *   - The metadata has the joined reasoning of the chunks under `reasoning_content`, and the citations of the chunks
  *     under `citations`, as the metadata of a plain call in the Responses mode. A key without a value is absent.
  *
  * [[ManagedLanguageModel]] and the stream wrapper of `streamify` use this class, so the two give the same output.
  *
  * @param mode
  *   the mode of the model that gives the stream.
  */
final class StreamAccumulator(mode: LmMode):
  private val text       = new StringBuilder
  private val reasoning  = new StringBuilder
  private val citations  = mutable.ArrayBuffer.empty[DynamicValue.Record]
  private val toolDeltas = mutable.ArrayBuffer.empty[LmToolCallDelta]

  /** Add the text, the reasoning, the citations, and the tool-call deltas of `chunk`. */
  def push(chunk: LmChunk): Unit =
    text.append(chunk.text)
    reasoning.append(chunk.reasoning)
    citations ++= chunk.citations
    toolDeltas ++= chunk.toolCalls

  /** Remove the parts of all chunks, for a new attempt. */
  def clear(): Unit =
    text.clear()
    reasoning.clear()
    citations.clear()
    toolDeltas.clear()

  /** The output of the chunks so far. */
  def output: LmOutput =
    LmOutput(
      text = text.toString,
      toolCalls = ToolCallAssembler.assemble(toolDeltas, mode),
      metadata = ResponsesWire.outputMetadata(reasoning.toString, citations.toVector)
    )
