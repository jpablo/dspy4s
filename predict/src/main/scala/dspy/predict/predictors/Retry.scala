package dspy.predict.predictors

import dspy.primitives.{Module, Prediction}
import dspy.utils.DspyError

import scala.concurrent.{ExecutionContext, Future}

final class Retry(
    underlying: Module,
    maxRetries: Int = 2,
    delayMs: Long = 0L,
    accept: Prediction => Boolean = _ => true
) extends Module {

  override def forward(inputs: Map[String, String])(implicit ec: ExecutionContext): Future[Prediction] = {
    def after(ms: Long): Future[Unit] = Future { if (ms > 0) Thread.sleep(ms) }

    def loop(attempt: Int): Future[Prediction] =
      underlying.forward(inputs).flatMap { p =>
        if (accept(p)) Future.successful(p)
        else if (attempt < maxRetries) after(delayMs).flatMap(_ => loop(attempt + 1))
        else Future.successful(p)
      }.recoverWith {
        case _: Throwable if attempt < maxRetries => after(delayMs).flatMap(_ => loop(attempt + 1))
      }

    loop(0)
  }
}

