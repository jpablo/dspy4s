package dspy.adapters.json

import dspy.adapters.chat.ChatAdapter
import dspy.clients.LM
import dspy.signatures.Signature
import dspy.utils.DspyError

import scala.concurrent.{ExecutionContext, Future}

final class JsonAdapter(chat: ChatAdapter = new ChatAdapter) {
  def call(
      lm: LM,
      signature: Signature,
      inputs: Map[String, String],
      demos: List[Map[String, String]] = Nil,
      lmParams: Map[String, String] = Map.empty
  )(implicit ec: ExecutionContext): Future[Map[String, ujson.Value]] = {
    val prompt = chat.renderPrompt(signature, inputs, demos) +
      s"\nRespond ONLY with a valid compact JSON object containing exactly the following keys: ${signature.outputs.map(_.name).mkString(", ")}.\n"

    chat
      .call(lm, signature, inputs, demos, lmParams)
      .flatMap { completion =>
        parseJson(completion.text).map(coerceTypes(signature, _))
      }
  }

  private def parseJson(s: String)(implicit ec: ExecutionContext): Future[Map[String, ujson.Value]] =
    Future {
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

  private def coerceTypes(sig: Signature, obj: Map[String, ujson.Value]): Map[String, ujson.Value] = {
    val kinds = sig.outputs.map(f => f.name -> f.kind).toMap
    obj.map { case (k, v) =>
      kinds.get(k) match {
        case Some("int") =>
          val coerced = v match {
            case ujson.Num(n)                          => ujson.Num(n.toInt)
            case ujson.Str(s) if s.forall(_.isDigit)    => ujson.Num(s.toInt)
            case other                                  => other
          }
          k -> coerced
        case Some("bool") =>
          val coerced = v match {
            case ujson.Bool(b)                          => ujson.Bool(b)
            case ujson.Str(s) if s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false") =>
              ujson.Bool(s.equalsIgnoreCase("true"))
            case other                                  => other
          }
          k -> coerced
        case _ => k -> v
      }
    }
  }
}

