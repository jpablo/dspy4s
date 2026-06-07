/*
 * Real-LM smoke test for the optimizer stack (COPRO + MIPROv2).
 *
 * Status:   verification harness — NOT a doc port. Confirms the G-1 -> spine -> optimizer
 *           tower runs end-to-end against a REAL language model without errors, and prints
 *           the baseline vs optimized metric score plus the instruction each optimizer chose.
 *
 * Purpose:  the optimizer unit suites all use scripted mock LMs (they prove the search/apply/
 *           score plumbing). This harness exercises the same code against a live model so a
 *           semantic divergence a mock can't surface shows up. On an easy task a capable model
 *           may already max out the baseline — the primary signal is "it runs cleanly and the
 *           outputs are sane", and ideally a visible lift from the format-sensitive task below.
 *
 * Run:      OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.verify.optimizerSmokeMain"
 * Tune:     OPENAI_MODEL (default gpt-4o-mini), SMOKE_BREADTH (COPRO breadth, default 3),
 *           SMOKE_TRIALS (MIPROv2 trials, default 4). For an OpenAI-compatible server (Ollama,
 *           vLLM, ...) swap `OpenAiLanguageModel.fromEnv` for the baseUrl overload.
 *
 * Cost:     small but non-zero — on the defaults, roughly 50-90 LM calls total. Keep the knobs
 *           low. Nothing here is wired into CI (no API key in CI -> it just prints a notice).
 */
package dspy4s.examples.verify

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DynamicValues, Example, RuntimeContext}
import dspy4s.core.contracts.:=
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.core.signatures.SignatureDsl
import dspy4s.evaluate.Evaluate
import dspy4s.evaluate.metrics.ExactMatch
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.optimize.{COPRO, COPROConfig, MIPROv2, MIPROv2Config, Predictors, Runnable}
import dspy4s.programs.DynamicPredict

object OptimizerSmokeTest:

  /** A deliberately format-sensitive task: extract the capital city, output ONLY the name. A vague
    * baseline instruction tends to make the model answer in a full sentence (failing exact match);
    * a better instruction / few-shot demos fix the format. That gives the optimizers room to lift. */
  val signatureDsl = "sentence -> city"
  val vagueBaselineInstruction = "Answer the question."

  def example(sentence: String, city: String): Example =
    Example(
      values    = DynamicValues.record("sentence" := sentence, "city" := city),
      inputKeys = Set("sentence")
    )

  val trainset: Vector[Example] = Vector(
    example("The capital of France is Paris.", "Paris"),
    example("Tokyo is the capital of Japan.", "Tokyo"),
    example("Germany's capital is Berlin.", "Berlin"),
    example("The capital of Italy is Rome.", "Rome"),
    example("Madrid is the capital of Spain.", "Madrid"),
    example("The capital of Egypt is Cairo.", "Cairo")
  )

  val valset: Vector[Example] = Vector(
    example("Canada's capital is Ottawa.", "Ottawa"),
    example("Lisbon is the capital of Portugal.", "Lisbon"),
    example("The capital of Norway is Oslo.", "Oslo"),
    example("Seoul is the capital of South Korea.", "Seoul")
  )

  val metric = new ExactMatch(answerField = "city")

  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

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
      val student    = DynamicPredict(layout = baseLayout)

      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current

        // Score a program on the valset via the same Runnable + Evaluate path the optimizers use.
        def scoreOf(program: DynamicPredict): Double =
          val runner = summon[Runnable[DynamicPredict]]
          Evaluate(devset = valset, metric = metric)()((ex: Example) => runner.run(program, ex.inputs)) match
            case Right(result) => result.score
            case Left(err)     => println(s"[smoke] eval failed: ${err.message}"); -1.0

        def instructionOf(program: DynamicPredict): String =
          summon[Predictors[DynamicPredict]].read(program).headOption.flatMap(_.layout.instructions).getOrElse("(none)")

        println(s"[smoke] model=$model  breadth=$breadth  trials=$trials")
        println(s"[smoke] task: '$signatureDsl'  (baseline instruction: \"$vagueBaselineInstruction\")")
        println(s"[smoke] train=${trainset.size} examples, val=${valset.size} examples\n")

        val baseScore = scoreOf(student)
        println(f"[smoke] BASELINE score: $baseScore%.1f%%\n")

        // ── COPRO ──
        println("[smoke] running COPRO ...")
        val copro = new COPRO[DynamicPredict](COPROConfig(metric = metric, breadth = breadth, depth = 1))
        copro.compile(student, trainset, valset = Some(valset)) match
          case Right(report) =>
            val best  = report.bestProgram
            val score = report.metadata.get("best_score").collect { case d: Double => d }.getOrElse(scoreOf(best))
            println(f"[smoke] COPRO    score: $score%.1f%%   (baseline $baseScore%.1f%%)")
            println(s"""[smoke] COPRO    chose instruction: "${instructionOf(best)}"\n""")
          case Left(err) => println(s"[smoke] COPRO failed: ${err.message}\n")

        // ── MIPROv2 ──
        println("[smoke] running MIPROv2 ...")
        val mipro = new MIPROv2[DynamicPredict](
          MIPROv2Config(metric = metric, numCandidates = breadth, numTrials = trials,
            maxBootstrappedDemos = 2, maxLabeledDemos = 2)
        )
        mipro.compile(student, trainset, teacher = Some(student), valset = Some(valset)) match
          case Right(report) =>
            val best  = report.bestProgram
            val score = report.metadata.get("best_score").collect { case d: Double => d }.getOrElse(scoreOf(best))
            val demos = summon[Predictors[DynamicPredict]].read(best).headOption.map(_.demos.size).getOrElse(0)
            println(f"[smoke] MIPROv2  score: $score%.1f%%   (baseline $baseScore%.1f%%)")
            println(s"""[smoke] MIPROv2  chose instruction: "${instructionOf(best)}"  (+ $demos demos)\n""")
          case Left(err) => println(s"[smoke] MIPROv2 failed: ${err.message}\n")

        println("[smoke] done — the stack ran end-to-end against a live model.")
        val _ = baseScore
      }
