package dspy4s.optimize

import dspy4s.core.contracts.{DspyError, DynamicValues, :=}
import dspy4s.core.data.{Example, RawPrediction}
import dspy4s.programs.*
import dspy4s.signatures.Signature
import munit.FunSuite
import zio.{Runtime, Unsafe, ZEnvironment, ZIO}

import scala.collection.mutable.ArrayBuffer

final class KNNFewShotSuite extends FunSuite:

  private final case class Question(question: String)
  private final case class Answer(answer: String)

  private val answerId  = ParameterId("answer")
  private val signature = Signature.derived[Question, Answer]("Answer")
  private val student   = Program.predictStable(answerId, signature).fromRecords(signature.inputShape)

  private def example(question: String, answer: String): Example =
    Example(DynamicValues.record("question" := question, "answer" := answer), Set("question"))

  private val clusterA = Vector(example("a1", "A1"), example("a2", "A2"))
  private val clusterB = Vector(example("b1", "B1"), example("b2", "B2"))

  test("program KNN few-shot applies selected demos per run without mutating the student") {
    val requests = ArrayBuffer.empty[PredictionRequest]
    val backend  = new PredictionBackend:
      def generate(request: PredictionRequest): ZIO[Any, DspyError, RawPrediction] =
        requests += request
        val answer = request.demos.flatMap(example =>
          DynamicValues.requireString(example.values, "answer", "KNN test").toOption
        ).mkString(",")
        ZIO.succeed(RawPrediction(DynamicValues.record("answer" := answer)))

    val neighbors = Program.lift[Question, Vector[Example]] { input =>
      if input.question.endsWith("a") then clusterA else clusterB
    }
    val compiled = KNNFewShot(student, neighbors)

    val results = Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(
          (for
            first  <- ProgramRunner.run(compiled.program, Question("query-a"))
            second <- ProgramRunner.run(compiled.program, Question("query-b"))
          yield first.output -> second.output).provideEnvironment(ZEnvironment(backend))
        )
        .getOrThrowFiberFailure()
    }

    assertEquals(results, Answer("A1,A2") -> Answer("B1,B2"))
    assertEquals(requests.map(_.demos).toVector, Vector(clusterA, clusterB))
    assertEquals(student.program.parameters.get(answerId).map(_.demos), Some(Vector.empty))
    assertEquals(compiled.program.parameters.all.map(_.id), Vector(answerId))
    assertEquals(
      ProgramGraph.from(compiled.program).nodes.map(_.kind),
      Vector("local_parameters", "lift", "predict")
    )
  }
