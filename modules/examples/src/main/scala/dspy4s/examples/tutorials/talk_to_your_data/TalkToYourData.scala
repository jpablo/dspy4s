/** Talk to Your Data: a type-safe analytics agent that answers questions about a dataset.
  *
  * An original dspy4s flagship example. It threads three ideas into one useful workflow:
  *
  *   1. SIGNATURES The model's intent is a `QueryPlan`: a validated Scala value with enums and nested lists. The same
  *      types constrain the LM, decode its reply, drive the optimizer's metric, and render the report. Change a field
  *      and the compiler finds every site that breaks. (See Schemas.scala.)
  *   2. RLM The dataset (10k rows of CSV, too large to put in a prompt) is handed to a sandboxed Python REPL, and the
  *      model writes code to compute the answer. At the iteration limit, RLM asks the model for the outputs with no
  *      more code, so the model can then write the values itself. The JVM computes the same plan, and the two answers
  *      must agree.
  *   3. GEPA The planner's instruction is evolved against a metric in code: the Scala engine compared to an answer key
  *      that comes from hand-written plans and the same engine. SelfCheck compares some answers with a direct
  *      computation.
  *
  * Two parts run here:
  *   - GEPA optimization of the planner, which needs only an LM (the metric is the deterministic Scala engine).
  *   - The full plan/act/verify/refine agent on a few questions, which needs Deno for the RLM sandbox.
  *
  * Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain
  * dspy4s.examples.tutorials.talk_to_your_data.talkToYourDataMain" Tune: DSPY_MODEL (default gpt-5.5), OPENAI_BASE_URL,
  * GEPA_METRIC_CALLS (default 40), GEPA_MINIBATCH (default 3). Offline foundation check (no LM or Deno): sbt
  * "examples/runMain dspy4s.examples.tutorials.talk_to_your_data.tytdSelfCheckMain"
  */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.adapters.JSONAdapter
import dspy4s.core.contracts.RuntimeContext
import dspy4s.examples.Demo

object TalkToYourData:

  /** Paraphrased (not verbatim training) questions, to show the optimized planner generalizing. */
  val demoQuestions: Vector[String] = Vector(
    "How much total revenue did the West region generate?",
    "Which product category brings in the most money?",
    "What is the average order value for Electronics?"
  )

  def runAndPrint(question: String, plannerInstructions: String)(using RuntimeContext): Unit =
    Agent.ask(question, plannerInstructions = plannerInstructions) match
      case Left(err) => println(s"Q: $question\n  [error] ${err.message}\n")
      case Right(a)  =>
        val valueStr = a.result.value.fold("")(v => f" (value=$v%.2f)")
        val verify   =
          if a.verdict.ok then "JVM cross-check AGREED"
          else
            // The issues go to the sandbox, so they do not hold the JVM answer. The reader gets it here.
            val jvmAnswer =
              QueryEngine.run(a.plan, Dataset.orders).toOption.fold("")(r => s" (JVM answer: ${r.answer})")
            s"MISMATCH: ${a.verdict.issues.mkString("; ")}$jvmAnswer"
        println(s"Q: $question")
        println(Agent.describePlan(a.plan).linesIterator.map("    " + _).mkString("  plan:\n", "\n", ""))
        println(s"  answer:  ${a.result.answer}$valueStr")
        println(s"  method:  ${a.result.method}")
        println(s"  verify:  $verify  (after ${a.attempts} attempt(s))")
        if a.result.caveats.nonEmpty then println(s"  caveats: ${a.result.caveats.mkString("; ")}")
        println()

  /** A line for one accuracy, with the count of failed examples and the first error when an example failed. */
  private[talk_to_your_data] def printAccuracy(label: String, accuracy: Optimize.Accuracy, note: String): Unit =
    println(f"  $label ${accuracy.score}%5.1f%%  ($note)")
    accuracy.errors.headOption.foreach(first =>
      println(s"    ${accuracy.errors.size} example(s) failed, with the score 0. The first error: $first")
    )

@main def talkToYourDataMain(): Unit =
  import TalkToYourData.*

  val budget    = Demo.envInt("GEPA_METRIC_CALLS", 40)
  val minibatch = Demo.envInt("GEPA_MINIBATCH", 3)

  Demo.withOpenAi(adapter = JSONAdapter()) { lm =>
    println(s"Dataset: ${Dataset.orders.size} synthetic e-commerce orders (${Dataset.csv.length} chars of CSV).")
    println(
      s"Model: ${lm.model}.  Gold questions: ${Dataset.goldset.size} (train ${Optimize.trainset.size} / val ${Optimize.valset.size}).\n"
    )

    // Part 1: GEPA evolves the planner instruction (LM only; metric is the Scala engine).
    println("== Optimizing the planner with GEPA (grounded metric: planned query vs. gold answer) ==")
    Optimize.run(reflectionLm = lm, budget = budget, minibatch = minibatch) match
      case Left(err)     => println(s"  [error] GEPA stopped: ${err.message}")
      case Right(report) =>
        printAccuracy(
          "baseline planner accuracy: ",
          report.baselineAccuracy,
          "the val split, which GEPA also uses to select the best candidate"
        )
        printAccuracy(
          "optimized planner accuracy:",
          report.optimizedAccuracy,
          s"${report.numCandidates} candidates explored"
        )
        val instr = report.optimizedInstruction
        println(s"  discovered instruction:\n${(if instr.length > 700 then instr.take(700) + " …"
                                                else instr).linesIterator.map("    " + _).mkString("\n")}\n")

        // Part 2: the full agent answers questions with the optimized planner (needs Deno).
        if Demo.denoAvailable then
          println("== The agent: plan -> act (RLM writes Python over the CSV) -> verify (JVM re-computes) ==")
          demoQuestions.foreach(runAndPrint(_, report.optimizedInstruction))
        else
          println("== Agent demo SKIPPED: the RLM act stage needs Deno on the PATH (https://deno.com) ==")
          println("   (The GEPA optimization above ran without it, since its metric is the Scala engine.)")
  }
