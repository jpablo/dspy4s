# Quickstart

Declare ordinary Scala input and output types, derive a signature, and build a program value:

```scala
import dspy4s.programs.{Program, ProgramRunner}
import dspy4s.signatures.Signature

final case class Question(question: String)
final case class Answer(answer: String)

val signature = Signature.derived[Question, Answer]("Answer", "Answer briefly.")
val answer     = Program.predict(signature)
val text       = answer >>> Program.lift[Answer, String](_.answer)

val effect = ProgramRunner.run(text, Question("What is a typed program?"))
```

`effect` requires a `PredictionBackend`. Provide a live backend or a test backend through ZIO. The program does not
look up a global model.

See the runnable offline [FunctionalQuickstart.scala](https://github.com/jpablo/dspy4s/blob/main/modules/examples/src/main/scala/dspy4s/examples/FunctionalQuickstart.scala).
