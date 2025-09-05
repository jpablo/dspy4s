package dspy.predict

import dspy.primitives.{Module, Prediction}
import dspy.signatures.Signature
import dspy.clients.LM
import dspy.utils.DspyError
import dspy.adapters.json.JsonAdapter

import scala.concurrent.{ExecutionContext, Future}

final class Predict(signature: Signature, lm: LM, adapter: JsonAdapter = new JsonAdapter)
    extends Module {

  override def forward(
      inputs: Map[String, String]
  )(implicit ec: ExecutionContext): Future[Prediction] = {
    val missing = Signature.missingInputKeys(signature, inputs.keySet)
    if (missing.nonEmpty)
      return Future.failed(
        DspyError.ParseError(s"Missing input keys: ${missing.mkString(", ")}", inputs.toString)
      )

    adapter.call(lm, signature, inputs).flatMap { obj =>
      val allowed     = Signature.outputNames(signature).toSet
      val present     = obj.keySet
      val missingOut  = Signature.missingOutputKeys(signature, present)
      val extraFields = present.diff(allowed)
      if (missingOut.nonEmpty)
        Future.failed(
          DspyError.ParseError(
            s"Missing output keys: ${missingOut.mkString(", ")}",
            obj.toString
          )
        )
      else if (extraFields.nonEmpty)
        Future.failed(
          DspyError.ParseError(
            s"Unexpected output keys: ${extraFields.mkString(", ")}",
            obj.toString
          )
        )
      else Future.successful(Prediction(obj))
    }
  }
}
