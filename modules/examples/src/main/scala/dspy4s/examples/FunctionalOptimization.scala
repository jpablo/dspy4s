package dspy4s.examples

import dspy4s.core.contracts.{DynamicValues, :=}
import dspy4s.core.data.Example
import dspy4s.optimize.{DemoCount, LabeledFewShot, LabeledFewShotConfig}
import dspy4s.programs.Program
import dspy4s.signatures.Signature
import zio.{Runtime, Unsafe}

final case class TrainingQuestion(question: String)
final case class TrainingAnswer(answer: String)

/** Optimizers return a new program. They do not mutate modules or global settings. */
@main def functionalOptimization(): Unit =
  val signature = Signature.derived[TrainingQuestion, TrainingAnswer]("TrainingAnswer", "Answer exactly.")
  val student   = Program.predict(signature).fromRecords(signature.inputShape)
  val trainset  = Vector(
    Example(DynamicValues.record("question" := "2 + 2", "answer" := "4"), Set("question")),
    Example(DynamicValues.record("question" := "3 + 3", "answer" := "6"), Set("question"))
  )

  val report = Unsafe.unsafe { implicit unsafe =>
    Runtime.default.unsafe
      .run(LabeledFewShot(
        student,
        trainset,
        LabeledFewShotConfig(k = DemoCount(2), sample = false)
      ))
      .getOrThrowFiberFailure()
  }

  val binding = report.bestProgram.program.parameters.all.head
  println(s"${binding.id.value}: ${binding.value.demos.size} demonstrations")
