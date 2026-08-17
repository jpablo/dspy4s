/** Talk to Your Data: a type-safe analytics agent that answers questions about a dataset and gets the math right.
  *
  * An original dspy4s flagship example. It threads three ideas into one useful workflow:
  *
  *   1. SIGNATURES The model's intent is a `QueryPlan`: a validated Scala value with enums and nested lists. The same
  *      types constrain the LM, decode its reply, drive the optimizer's metric, and render the report. Change a field
  *      and the compiler finds every site that breaks. (See Schemas.scala.)
  *   2. RLM The dataset (10k rows of CSV, too large to put in a prompt) is handed to a sandboxed Python REPL, and the
  *      model writes code to compute the answer. The arithmetic happens in the sandbox, and the JVM independently
  *      re-computes the same plan and must agree.
  *   3. GEPA The planner's instruction is evolved against a grounded metric (the Scala engine compared to a
  *      by-construction gold answer key), turning a vague baseline into a reliable planner.
  *
  * Two parts run here:
  *   - GEPA optimization of the planner, which needs only an LM (the metric is the deterministic Scala engine).
  *   - The full plan/act/verify/refine agent on a few questions, which needs Deno for the RLM sandbox.
  *
  * Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain
  * dspy4s.examples.tutorials.talk_to_your_data.talkToYourDataMain" Tune: DSPY_MODEL (default gpt-5.5),
  * GEPA_METRIC_CALLS (default 40), GEPA_MINIBATCH (default 3). Offline foundation check (no LM or Deno):
  * ...talk_to_your_data.tytdSelfCheckMain
  */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.DenoPyodideInterpreter
import dspy4s.lm.providers.OpenAiLanguageModel
import dspy4s.programs.{LivePredictionBackend, LiveReplExecutionBackend, PredictionBackend, ReplExecutionBackend}

import scala.util.control.NonFatal

object TalkToYourData:

  /** Paraphrased (not verbatim training) questions, to show the optimized planner generalizing. */
  val demoQuestions: Vector[String] = Vector(
    "How much total revenue did the West region generate?",
    "Which product category brings in the most money?",
    "What is the average order value for Electronics?"
  )

  def envInt(name: String, default: Int): Int =
    sys.env.get(name).flatMap(_.toIntOption).filter(_ > 0).getOrElse(default)

  def denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

  def runAndPrint(question: String, plannerInstructions: String)(using
      PredictionBackend,
      ReplExecutionBackend
  ): Unit =
    Agent.ask(question, plannerInstructions = plannerInstructions) match
      case Left(err) => println(s"Q: $question\n  [error] ${err.message}\n")
      case Right(a)  =>
        val valueStr = a.result.value.fold("")(v => f" (value=$v%.2f)")
        val verify   = if a.verdict.ok then "JVM cross-check AGREED" else s"MISMATCH: ${a.verdict.issues.mkString("; ")}"
        println(s"Q: $question")
        println(Agent.describePlan(a.plan).linesIterator.map("    " + _).mkString("  plan:\n", "\n", ""))
        println(s"  answer:  ${a.result.answer}$valueStr")
        println(s"  method:  ${a.result.method}")
        println(s"  verify:  $verify  (after ${a.attempts} attempt(s))")
        if a.result.caveats.nonEmpty then println(s"  caveats: ${a.result.caveats.mkString("; ")}")
        println()

@main def talkToYourDataMain(): Unit =
  import TalkToYourData.*

  val model     = sys.env.getOrElse("DSPY_MODEL", "gpt-5.5")
  val budget    = envInt("GEPA_METRIC_CALLS", 40)
  val minibatch = envInt("GEPA_MINIBATCH", 3)

  OpenAiLanguageModel.fromEnv(model) match
    case Left(err) =>
      println(s"[talk-to-your-data] Skipping (no live LM): ${err.message}")
      println("""[talk-to-your-data] Set OPENAI_API_KEY and re-run; or try the offline foundation check:""")
      println("""    sbt "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"""")

    case Right(lm) =>
        given context: RuntimeContext       = RuntimeContext(lm = Some(lm), adapter = Some(JSONAdapter()))
        given backend: PredictionBackend    = new LivePredictionBackend(lm, JSONAdapter(), context)

        println(s"Dataset: ${Dataset.orders.size} synthetic e-commerce orders (${Dataset.csv.length} chars of CSV).")
        println(
          s"Model: $model.  Gold questions: ${Dataset.goldset.size} (train ${Optimize.trainset.size} / val ${Optimize.valset.size}).\n"
        )

        // Part 1: GEPA evolves the planner instruction (LM only; metric is the Scala engine).
        println("== Optimizing the planner with GEPA (grounded metric: planned query vs. gold answer) ==")
        Optimize.run(budget = budget, minibatch = minibatch) match
          case Left(error) => println(s"[talk-to-your-data] GEPA failed: ${error.message}")
          case Right(report) =>
            println(f"  baseline planner accuracy:  ${report.baselineAccuracy * 100}%5.1f%%  (held-out val split)")
            println(
              f"  optimized planner accuracy: ${report.optimizedAccuracy * 100}%5.1f%%  (${report.numCandidates} candidates explored)"
            )
            val instruction = report.optimizedInstruction
            val shown       = if instruction.length > 700 then instruction.take(700) + " …" else instruction
            println(s"  discovered instruction:\n${shown.linesIterator.map("    " + _).mkString("\n")}\n")

            // Part 2: the full agent answers questions with the optimized planner (needs Deno).
            if denoAvailable then
              val interpreter = new DenoPyodideInterpreter(outputFields = Vector(
                DenoPyodideInterpreter.OutputField("answer"),
                DenoPyodideInterpreter.OutputField("value"),
                DenoPyodideInterpreter.OutputField("caveats"),
                DenoPyodideInterpreter.OutputField("method")
              ))
              given ReplExecutionBackend = new LiveReplExecutionBackend(interpreter)
              try
                println("== The agent: plan -> act (RLM writes Python over the CSV) -> verify (JVM re-computes) ==")
                demoQuestions.foreach(runAndPrint(_, instruction))
              finally interpreter.close()
            else
              println("== Agent demo SKIPPED: the RLM act stage needs Deno on the PATH (https://deno.com) ==")
              println("   (The GEPA optimization above ran without it, since its metric is the Scala engine.)")
