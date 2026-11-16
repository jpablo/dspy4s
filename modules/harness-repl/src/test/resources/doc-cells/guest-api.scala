// mode: work
// The cells of site/docs/harness/guest-api.md. DocCellLaws runs them in order, in one REPL.

// --8<-- [start:read-and-count]
val readme = read("README.md")
val lines  = readme.linesIterator.toVector
println(s"README.md has ${lines.size} lines")
println(lines.filter(_.startsWith("#")).mkString("\n"))
// --8<-- [end:read-and-count]

// --8<-- [start:write-and-edit]
write("notes/plan.md", "# Plan\n\n- read the build\n")
edit("notes/plan.md", "- read the build\n", "- read the build\n- run the tests\n")
println(read("notes/plan.md"))
// --8<-- [end:write-and-edit]

// --8<-- [start:run-a-job]
println(exec("git log --oneline -3"))
val job = spawn("sbt -batch test")
waitJob(job, 120) match
  case JobStatus.Exited(code) => println(s"the tests ended with the exit code $code")
  case status                 => println(s"the job is $status")
println(jobOutput(job).linesIterator.toVector.takeRight(5).mkString("\n"))
// --8<-- [end:run-a-job]

// --8<-- [start:store-a-value]
case class Progress(done: Vector[String], next: String)
store("progress", Progress(Vector("read the build", "run the tests"), "fix the failed test"))
store("test-job", job)
println(keys())
// --8<-- [end:store-a-value]

// --8<-- [start:load-a-value]
// A later turn can run in a new REPL. Define the type again, with the same fields.
case class Progress(done: Vector[String], next: String)
load[Progress]("progress") match
  case Some(progress) => println(s"next: ${progress.next}")
  case None           => println("no progress yet")
// --8<-- [end:load-a-value]

// --8<-- [start:ask-a-model]
val paths   = Vector("README.md", "build.sbt")
val prompts = paths.map(path => s"Give one line about this file.\n\n${read(path).take(4000)}")
val answers = llmQueryBatched(prompts)
paths.zip(answers).foreach((path, answer) => println(s"$path: $answer"))
// --8<-- [end:ask-a-model]

// --8<-- [start:submit-a-result]
val started = now()
val count   = read("build.sbt").linesIterator.size
println(s"the read took ${now() - started} ms")
submit(s"build.sbt has $count lines.")
// --8<-- [end:submit-a-result]

// expect: compile-error
// --8<-- [start:refused-by-safe-mode]
sys.error("the build failed")
// --8<-- [end:refused-by-safe-mode]
