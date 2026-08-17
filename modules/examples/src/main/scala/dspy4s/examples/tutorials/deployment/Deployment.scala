/** Deploying a functional dspy4s program.
  *
  * FastAPI and MLflow are Python-specific shells. In Scala, keep the `Program` as application data and interpret it in
  * the HTTP framework at the edge. The `endpoint` function below is the complete framework-neutral route body.
  */
package dspy4s.examples.tutorials.deployment

import dspy4s.core.contracts.DspyError
import dspy4s.examples.Demo
import dspy4s.optimize.ProgramPersistence
import dspy4s.programs.{ChainOfThought, PredictionBackend, ProgramRunner}
import dspy4s.signatures.Signature
import zio.ZIO
import zio.blocks.schema.Schema

final case class DeploymentRequest(text: String) derives Schema
final case class DeploymentAnswer(answer: String) derives Schema
final case class DeploymentResponse(status: String, data: DeploymentAnswer) derives Schema

object Deployment:

  val signature = Signature.derived[DeploymentRequest, DeploymentAnswer](
    "DeployedQA",
    "Answer the question accurately and concisely."
  )

  // Python: dspy_program = dspy.ChainOfThought("question -> answer")
  val program = ChainOfThought(signature)

  /** Use this effect in an http4s, Tapir, ZIO HTTP, or Pekko HTTP route. */
  def endpoint(request: DeploymentRequest): ZIO[PredictionBackend, DspyError, DeploymentResponse] =
    ProgramRunner.run(program, request).map(prediction =>
      DeploymentResponse("success", DeploymentAnswer(prediction.output.answer))
    )

  /** The optimizer state is the portable deployment artifact. The application supplies fresh code and backends. */
  def saveParameters(path: String): Either[DspyError, Unit] = ProgramPersistence.save(program, path)

  def loadParameters(path: String) = ProgramPersistence.load(program, path)

// Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain ...deploymentMain"
@main def deploymentMain(): Unit =
  Demo.withLm {
    println(Demo.runEffect(Deployment.endpoint(DeploymentRequest("What is the capital of France?"))))
  }
