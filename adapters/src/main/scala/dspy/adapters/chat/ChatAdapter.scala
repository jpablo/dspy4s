package dspy.adapters.chat

import dspy.clients.{Completion, LM, Prompt}
import dspy.signatures.{Field, Signature}

import scala.concurrent.{ExecutionContext, Future}

final class ChatAdapter {
  def call(
      lm: LM,
      signature: Signature,
      inputs: Map[String, String],
      demos: List[Map[String, String]] = Nil,
      lmParams: Map[String, String] = Map.empty
  )(implicit ec: ExecutionContext): Future[Completion] = {
    val rendered = renderPrompt(signature, inputs, demos)
    lm.complete(Prompt(rendered), lmParams)
  }

  def renderPrompt(
      signature: Signature,
      inputs: Map[String, String],
      demos: List[Map[String, String]]
  ): String = {
    val instr = signature.instructions.getOrElse("Fill the required output fields based on inputs.")
    val sb    = new StringBuilder
    sb.append("System: You are a helpful assistant.\n")
    sb.append("Instructions:\n").append(instr).append("\n\n")

    if (demos.nonEmpty) {
      sb.append("Demos:\n")
      demos.foreach { d =>
        sb.append("- demo: ")
        sb.append(d.map { case (k, v) => s"$k=$v" }.mkString(", "))
        sb.append("\n")
      }
      sb.append("\n")
    }

    sb.append("Inputs:\n")
    signature.inputs.foreach { f =>
      val v = inputs.getOrElse(f.name, "")
      sb.append(s"- ${f.name}: $v\n")
    }
    sb.result()
  }
}

