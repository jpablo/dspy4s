package dspy4s.gepa

/** GEPA's reflective mutation operator for a single component: prompt the reflection LM with the component's CURRENT
  * instruction plus its reflective dataset (the failures/feedback), and extract the rewritten instruction. A faithful
  * port of gepa's `InstructionProposalSignature` default proposer (the prompt template + ``` extraction). See PORT_GAPS
  * G-12.
  */
object InstructionProposer:

  /** Typed input for the reflection program supplied to [[Gepa]]. */
  final case class Input(currentInstruction: String, records: Vector[ReflectiveRecord])

  /** Typed output from the reflection program. */
  final case class Output(instruction: String)

  /** The reflection prompt (paraphrase of gepa's default `InstructionProposalSignature` template). */
  private[gepa] def buildPrompt(currentInstruction: String, records: Vector[ReflectiveRecord]): String =
    s"""I provided an assistant with the following instructions to perform a task for me:
       |```
       |$currentInstruction
       |```
       |
       |The following are examples of different task inputs provided to the assistant along with the assistant's
       |response for each of them, and some feedback on how the assistant's response could be better:
       |```
       |${renderRecords(records)}
       |```
       |
       |Your task is to write a new instruction for the assistant.
       |
       |Read the inputs carefully and infer a detailed description of the task I wish to solve. Read all the
       |assistant responses and the corresponding feedback. Identify all niche and domain-specific factual
       |information about the task and include it in the instruction, as a lot of it may not be available to the
       |assistant in the future. If the assistant used a generalizable strategy, include that too.
       |
       |Provide the new instruction within ``` blocks.""".stripMargin

  private def renderRecords(records: Vector[ReflectiveRecord]): String =
    records.iterator.zipWithIndex.map { (r, i) =>
      s"""Example ${i + 1}:
         |Inputs: ${r.inputs}
         |Generated Outputs: ${r.generatedOutputs}
         |Feedback: ${r.feedback}""".stripMargin
    }.mkString("\n\n")

  /** Extract the instruction from the model's response: the text inside the first ``` fenced block, or — if the model
    * omitted the fences — the whole trimmed response. Tolerates a missing closing fence.
    */
  private[gepa] def extractInstruction(response: String): String =
    val fence = "```"
    val open  = response.indexOf(fence)
    if open < 0 then response.trim
    else
      val bodyStart = open + fence.length
      val close     = response.indexOf(fence, bodyStart)
      val body      = if close < 0 then response.substring(bodyStart) else response.substring(bodyStart, close)
      body.stripPrefix("\n").trim
