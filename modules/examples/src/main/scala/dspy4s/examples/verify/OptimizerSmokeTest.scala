/*
 * Real-LM smoke harness for the optimizer stack (COPRO + MIPROv2).
 *
 * Status:   verification harness, NOT a doc port. It runs the optimizer stack against a REAL model. It prints the
 *           baseline and optimized metric scores, the chosen instruction, and the demo count. At the end, it
 *           calls sys.error if a step or an LM call failed.
 *
 * Task:     instruction-SENSITIVE classification: label whether the text contains a digit
 *           ("HAS_NUM" / "NO_NUM"). The vague baseline instruction ("Answer the question.")
 *           gives the model no label scheme, so the baseline usually has a low score. Few-shot demos (MIPROv2)
 *           can teach the labels. COPRO cannot learn the labels on this task, because the input of its proposer
 *           is only the basic instruction, and HAS_NUM/NO_NUM never reach it. So the COPRO step only checks
 *           that the run completes.
 *
 * Eval:     the task program pins `temperature = 0` via the module-level config (G-3), to reduce the
 *           sampling noise in the before and after scores.
 *
 * Live progress: a CallbackHandler is registered in the RuntimeContext that prints a dot
 *           per completed LM call ("x" on a failed call), so you can see the process working
 *           during the otherwise-silent optimizer phases. This is the general dspy4s
 *           mechanism for observability: register your own `CallbackHandler` to log or trace.
 *
 * Run:      OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.optimizerSmokeMain"
 * Tune:     DSPY_MODEL (default gpt-4o-mini), OPENAI_BASE_URL, SMOKE_BREADTH (default 3), SMOKE_TRIALS (default 4).
 * Cost:     about 100 LM calls on the defaults; a few minutes.
 */
package dspy4s.examples.verify

import dspy4s.core.contracts.{:=, DspyError, DynamicValues}
import dspy4s.core.data.Example
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.examples.Demo
import dspy4s.optimize.{
  COPRO,
  COPROConfig,
  CandidateCount,
  CoproBreadth,
  DemoCount,
  MIPROv2,
  MIPROv2Config,
  RoundCount,
  TrialCount
}
import dspy4s.programs.ProgramRunner
import dspy4s.programs.strategies.DynamicPredict
import dspy4s.signatures.Signature

object OptimizerSmokeTest:

  val vagueBaselineInstruction = "Answer the question."

  def example(text: String, label: String): Example =
    Example(values = DynamicValues.record("text" := text, "label" := label), inputKeys = Set("text"))

  /** Texts that contain a digit -> "HAS_NUM". */
  private val withNum: Vector[String] = Vector(
    "I bought 3 apples at the market",
    "There are 12 months in a year",
    "She ran 5 miles this morning",
    "The recipe needs 2 cups of flour",
    "We have 7 days until the trip",
    "He scored 21 points in the game",
    "The box weighs 9 kilograms",
    "They planted 40 trees last spring",
    "My phone has 64 gigabytes of storage",
    "The train leaves at 6 in the evening",
    "There were 100 people at the concert",
    "The car drove 80 kilometers"
  )

  /** Texts with no digit -> "NO_NUM". */
  private val noNum: Vector[String] = Vector(
    "The sky is clear and blue today",
    "Dogs love to play in the park",
    "She enjoys reading mystery novels",
    "The coffee smells wonderful this morning",
    "Birds were singing in the trees",
    "He painted the fence a bright color",
    "We walked along the sandy beach",
    "The soup tastes a little salty",
    "They watched a film about the ocean",
    "A gentle breeze moved the curtains",
    "The library was quiet and calm",
    "Children laughed on the playground"
  )

  private def hasNum(s : String): Example = example(s, "HAS_NUM")
  private def noNumEx(s: String): Example = example(s, "NO_NUM")

  // Train: 7 of each, interleaved (so bootstrap demos are class-balanced). Val: the remaining 5 of each.
  val trainset: Vector[Example] = withNum.take(7).zip(noNum.take(7)).flatMap((a, b) => Vector(hasNum(a), noNumEx(b)))
  val valset: Vector[Example]   = withNum.drop(7).map(hasNum) ++ noNum.drop(7).map(noNumEx)

  val metric = new ExactMatch(answerField = "label")

@main def optimizerSmokeMain(): Unit =
  import OptimizerSmokeTest.*

  val breadth  = Demo.envInt("SMOKE_BREADTH", 3)
  val trials   = Demo.envInt("SMOKE_TRIALS", 4)
  val progress = new ProgressLmCallback

  // The default model is gpt-4o-mini, because this run pins temperature 0, and a reasoning model rejects that value.
  Demo.withOpenAi(defaultModel = "gpt-4o-mini", callbacks = Vector(progress)) { lm =>
    val baseLayout = Signature.fromString("text -> label", vagueBaselineInstruction).layout
    val student    = DynamicPredict(layout = baseLayout, config = DynamicValues.record("temperature" := 0.0))
    val runner     = summon[ProgramRunner[DynamicPredict]]

    var failures = Vector.empty[String]

    def record(step: String, error: DspyError): Unit =
      println(s"\n[smoke] $step failed: ${error.message}")
      failures :+= s"$step: ${error.message}"

    def scoreOf(program: DynamicPredict): Either[DspyError, Double] =
      Evaluate(devset = valset, metric = metric)()((ex: Example) => runner.run(program, ex.inputs)).map(_.score)

    def instructionOf(program: DynamicPredict): String = program.layout.instructions.getOrElse("(none)")

    def checkpoint(label: String): Unit =
      println(s"\n[smoke] $label  (${progress.count} LM calls so far${
          if progress.errorCount > 0 then s", ${progress.errorCount} failed" else ""
        })")

    println(s"[smoke] model=${lm.model}  breadth=$breadth  trials=$trials  (temperature=0)")
    println(s"[smoke] task: classify HAS_NUM/NO_NUM  (baseline instruction: \"$vagueBaselineInstruction\")")
    println(s"[smoke] train=${trainset.size} examples, val=${valset.size} examples")
    println("[smoke] live progress below: one '.' per LM call ('x' = a failed call):\n")

    val baseline = scoreOf(student).fold(
      error =>
        record("baseline", error)
        "(failed)"
      ,
      score => f"$score%.1f%%"
    )
    checkpoint(s"BASELINE score: $baseline")

    // ── COPRO ──
    println(
      "\n[smoke] running COPRO ... (this step only checks that the run completes: the labels never reach its proposer)"
    )
    val copro = new COPRO[DynamicPredict](COPROConfig(
      metric = metric,
      breadth = CoproBreadth.applyUnsafe(breadth),
      depth = RoundCount(1)
    ))
    copro.compile(student, trainset, valset = Some(valset)) match
      case Right(report) =>
        checkpoint(f"COPRO    score: ${report.candidates.head.score}%.1f%%   (baseline $baseline)")
        println(s"""[smoke] COPRO    chose instruction: "${instructionOf(report.bestProgram)}"""")
      case Left(err) => record("COPRO", err)

    // ── MIPROv2 ──
    println("\n[smoke] running MIPROv2 ...")
    val mipro = new MIPROv2[DynamicPredict](
      MIPROv2Config(
        metric = metric,
        numCandidates = CandidateCount.applyUnsafe(breadth),
        numTrials = TrialCount.applyUnsafe(trials),
        maxBootstrappedDemos = DemoCount(2),
        maxLabeledDemos = DemoCount(2)
      )
    )
    mipro.compile(student, trainset, valset = Some(valset)) match
      case Right(report) =>
        val best = report.bestProgram
        checkpoint(f"MIPROv2  score: ${report.candidates.head.score}%.1f%%   (baseline $baseline)")
        println(s"""[smoke] MIPROv2  chose instruction: "${instructionOf(best)}"  (+ ${best.demos.size} demos)""")
      case Left(err) => record("MIPROv2", err)

    val failedCalls =
      Option.when(progress.errorCount > 0)(s"${progress.errorCount} of ${progress.count} LM calls failed")
    val problems = failures ++ failedCalls
    if problems.nonEmpty then sys.error(s"[smoke] the run had failures: ${problems.mkString("; ")}")
    println(s"\n[smoke] done: ${progress.count} LM calls in all. The stack ran end-to-end against a live model.")
  }
