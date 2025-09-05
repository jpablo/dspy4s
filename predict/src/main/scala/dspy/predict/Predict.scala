package dspy.predict

import dspy.primitives.{Module, Prediction}
import dspy.signatures.{Field, Signature}
import dspy.clients.{LM, Prompt}
import dspy.utils.DspyError

import scala.concurrent.{ExecutionContext, Future}

final class Predict(signature: Signature, lm: LM) extends Module {

  override def forward(
      inputs: Map[String, String]
  )(implicit ec: ExecutionContext): Future[Prediction] = {
    val missing = Signature.missingInputKeys(signature, inputs.keySet)
    if (missing.nonEmpty)
      return Future.failed(
        DspyError.ParseError(s"Missing input keys: ${missing.mkString(", ")}", inputs.toString)
      )

    val rendered = renderPrompt(inputs)
    lm.complete(Prompt(rendered)).flatMap { completion =>
      parseJson(completion.text).flatMap { obj =>
        val allowed     = Signature.outputNames(signature).toSet
        val present     = obj.keySet
        val missingOut  = Signature.missingOutputKeys(signature, present)
        val extraFields = present.diff(allowed)
        if (missingOut.nonEmpty)
          Future.failed(
            DspyError.ParseError(
              s"Missing output keys: ${missingOut.mkString(", ")}",
              completion.text
            )
          )
        else if (extraFields.nonEmpty)
          Future.failed(
            DspyError.ParseError(
              s"Unexpected output keys: ${extraFields.mkString(", ")}",
              completion.text
            )
          )
        else Future.successful(Prediction(coerceTypes(obj)))
      }
    }
  }

  private def renderPrompt(inputs: Map[String, String]): String = {
    val instr = signature.instructions.getOrElse("Fill the required output fields based on inputs.")
    val sb    = new StringBuilder
    sb.append("Instructions:\n").append(instr).append("\n\n")
    sb.append("Inputs:\n")
    signature.inputs.foreach { f =>
      val v = inputs.getOrElse(f.name, "")
      sb.append(s"- ${f.name}: $v\n")
    }
    sb.append(
      "\nRespond ONLY with a valid compact JSON object containing exactly the following keys: "
    )
    sb.append(signature.outputs.map(_.name).mkString(", ")).append(".\n")
    sb.append("Example: {\"")
      .append(signature.outputs.headOption.map(_.name).getOrElse("field"))
      .append("\": \"...\"}\n")
    sb.result()
  }

  private def parseJson(
      s: String
  )(implicit ec: ExecutionContext): Future[Map[String, ujson.Value]] =
    Future {
      // Try reading as raw JSON first; if it fails, try to extract a JSON object substring.
      def read(objStr: String) = ujson.read(objStr) match {
        case obj: ujson.Obj => obj.value.toMap
        case _              => throw DspyError.ParseError("Expected a JSON object", s)
      }

      try read(s)
      catch {
        case _: Throwable =>
          val start = s.indexOf('{')
          val end   = s.lastIndexOf('}')
          if (start >= 0 && end > start) read(s.substring(start, end + 1))
          else throw DspyError.ParseError("No JSON object found in model output", s)
      }
    }

  private def coerceTypes(obj: Map[String, ujson.Value]): Map[String, ujson.Value] = {
    val kinds = signature.outputs.map(f => f.name -> f.kind).toMap
    obj.map { case (k, v) =>
      kinds.get(k) match {
        case Some("int") =>
          val coerced = v match {
            case ujson.Num(n)          => ujson.Num(n.toInt)
            case ujson.Str(s) if s.forall(_.isDigit) => ujson.Num(s.toInt)
            case other                  => other
          }
          k -> coerced
        case Some("bool") =>
          val coerced = v match {
            case ujson.Bool(b)          => ujson.Bool(b)
            case ujson.Str(s) if s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false") =>
              ujson.Bool(s.equalsIgnoreCase("true"))
            case other => other
          }
          k -> coerced
        case _ => k -> v
      }
    }
  }
}
