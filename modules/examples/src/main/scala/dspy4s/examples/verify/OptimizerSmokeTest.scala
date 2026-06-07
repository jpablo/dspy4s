/*
 * Real-LM smoke / efficacy harness for the optimizer stack (COPRO + MIPROv2).
 *
 * Status:   verification harness — NOT a doc port. Runs the G-1 -> spine -> optimizer tower
 *           end-to-end against a REAL model and prints baseline vs optimized metric score,
 *           the chosen instruction, and demo count.
 *
 * Task:     instruction-SENSITIVE classification — label whether the text contains a digit
 *           ("HAS_NUM" / "NO_NUM"). The vague baseline instruction ("Answer the question.")
 *           gives the model no idea what label scheme to emit, so the baseline genuinely
 *           under-scores and a good instruction (COPRO) or few-shot demos (MIPROv2) help.
 *
 * Deterministic eval: the task program pins `temperature = 0` via the module-level config
 *           (G-3), so the SAME program scores identically every run — the before/after
 *           numbers are real, not sampling noise.
 *
 * Live progress: a CallbackHandler is registered in the RuntimeContext that prints a dot
 *           per completed LM call ("x" on a failed call), so you can see the process working
 *           during the otherwise-silent optimizer phases. This is the general dspy4s
 *           mechanism for observability — register your own `CallbackHandler` to log/trace.
 *
 * Run:      OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.optimizerSmokeMain"
 * Tune:     OPENAI_MODEL (default gpt-4o-mini), SMOKE_BREADTH (default 3), SMOKE_TRIALS (default 4).
 * Cost:     ~50-70 LM calls on the defaults; a few minutes; well under a cent on gpt-4o-mini.
 */
package dspy4s.examples.verify

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{CallbackEvent, CallbackHandler, DynamicValues, Example, LmEndEvent, RuntimeContext}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.optimize.{COPRO, COPROConfig, MIPROv2, MIPROv2Config, Predictors, Runnable}
import dspy4s.programs.DynamicPredict

import java.util.concurrent.atomic.AtomicInteger

object OptimizerSmokeTest:

  val signatureDsl = "text -> label"
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

  private def hasNum(s: String): Example = example(s, "HAS_NUM")
  private def noNumEx(s: String): Example = example(s, "NO_NUM")

  // Train: 7 of each, interleaved (so bootstrap demos are class-balanced). Val: the remaining 5 of each.
  val trainset: Vector[Example] =
    withNum.take(7).zip(noNum.take(7)).flatMap((a, b) => Vector(hasNum(a), noNumEx(b)))
  val valset: Vector[Example] =
    withNum.drop(7).map(hasNum) ++ noNum.drop(7).map(noNumEx)

  val metric = new ExactMatch(answerField = "label")

  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

  /** A live-progress callback: prints one char per completed LM call (`.` ok, `x` failed) and tracks the count,
    * so the otherwise-silent optimizer phases visibly make progress. Registering a `CallbackHandler` in the
    * `RuntimeContext` is the general dspy4s observability hook — swap this for a logger/tracer as needed. */
  final class ProgressLmCallback extends CallbackHandler:
    private val calls  = new AtomicInteger(0)
    private val errors = new AtomicInteger(0)
    def count: Int     = calls.get()
    def errorCount: Int = errors.get()
    override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
      case e: LmEndEvent =>
        val _ = calls.incrementAndGet()
        e.response match
          case Left(_) => val _ = errors.incrementAndGet(); System.out.print("x")
          case _       => System.out.print(".")
        System.out.flush()
      case _ => ()

@main def optimizerSmokeMain(): Unit =
  import OptimizerSmokeTest.*

  val model   = sys.env.getOrElse("OPENAI_MODEL", "gpt-4o-mini")
  val breadth = envInt("SMOKE_BREADTH", 3)
  val trials  = envInt("SMOKE_TRIALS", 4)

  OpenAiLanguageModel.fromEnv(model) match
    case Left(err) =>
      println(s"[smoke] Skipping — no live LM available: ${err.message}")
      println("[smoke] Set OPENAI_API_KEY (and optionally OPENAI_MODEL) and re-run:")
      println("""        OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.optimizerSmokeMain"""")

    case Right(lm) =>
      val baseLayout = SignatureDsl.parse(signatureDsl).toOption.get.withInstructions(Some(vagueBaselineInstruction))
      // Pin temperature=0 (module-level config, G-3) so the SAME program scores identically every eval —
      // the before/after numbers are real, not sampling noise.
      val student = DynamicPredict(layout = baseLayout, config = DynamicValues.record("temperature" := 0.0))

      val progress = new ProgressLmCallback
      RuntimeEnvironment.withSettings(
        RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()), callbacks = Vector(progress))
      ) {
        given RuntimeContext = RuntimeEnvironment.current

        def scoreOf(program: DynamicPredict): Double =
          val runner = summon[Runnable[DynamicPredict]]
          Evaluate(devset = valset, metric = metric)()((ex: Example) => runner.run(program, ex.inputs)) match
            case Right(result) => result.score
            case Left(err)     => println(s"\n[smoke] eval failed: ${err.message}"); -1.0

        def instructionOf(program: DynamicPredict): String =
          summon[Predictors[DynamicPredict]].read(program).headOption.flatMap(_.layout.instructions).getOrElse("(none)")

        def checkpoint(label: String): Unit =
          println(s"\n[smoke] $label  (${progress.count} LM calls so far${
              if progress.errorCount > 0 then s", ${progress.errorCount} failed" else ""})")

        println(s"[smoke] model=$model  breadth=$breadth  trials=$trials  (temperature=0, deterministic)")
        println(s"[smoke] task: classify HAS_NUM/NO_NUM  (baseline instruction: \"$vagueBaselineInstruction\")")
        println(s"[smoke] train=${trainset.size} examples, val=${valset.size} examples")
        println("[smoke] live progress below — one '.' per LM call ('x' = a failed call):\n")

        val baseScore = scoreOf(student)
        checkpoint(f"BASELINE score: $baseScore%.1f%%")

        // ── COPRO ──
        println("\n[smoke] running COPRO ...")
        val copro = new COPRO[DynamicPredict](COPROConfig(metric = metric, breadth = breadth, depth = 1))
        copro.compile(student, trainset, valset = Some(valset)) match
          case Right(report) =>
            val best  = report.bestProgram
            val score = report.metadata.get("best_score").collect { case d: Double => d }.getOrElse(scoreOf(best))
            checkpoint(f"COPRO    score: $score%.1f%%   (baseline $baseScore%.1f%%)")
            println(s"""[smoke] COPRO    chose instruction: "${instructionOf(best)}"""")
          case Left(err) => println(s"\n[smoke] COPRO failed: ${err.message}")

        // ── MIPROv2 ──
        println("\n[smoke] running MIPROv2 ...")
        val mipro = new MIPROv2[DynamicPredict](
          MIPROv2Config(metric = metric, numCandidates = breadth, numTrials = trials,
            maxBootstrappedDemos = 2, maxLabeledDemos = 2)
        )
        mipro.compile(student, trainset, teacher = Some(student), valset = Some(valset)) match
          case Right(report) =>
            val best  = report.bestProgram
            val score = report.metadata.get("best_score").collect { case d: Double => d }.getOrElse(scoreOf(best))
            val demos = summon[Predictors[DynamicPredict]].read(best).headOption.map(_.demos.size).getOrElse(0)
            checkpoint(f"MIPROv2  score: $score%.1f%%   (baseline $baseScore%.1f%%)")
            println(s"""[smoke] MIPROv2  chose instruction: "${instructionOf(best)}"  (+ $demos demos)""")
          case Left(err) => println(s"\n[smoke] MIPROv2 failed: ${err.message}")

        println(s"\n[smoke] done — ${progress.count} LM calls total. The stack ran end-to-end against a live model.")
        val _ = baseScore
      }
