/**
 * Talk to Your Data — the plan → act → verify → refine agent.
 *
 *   PLAN  (typed signatures)  English question -> a typed [[QueryPlan]]. The model's intent is a validated Scala
 *                             value, not a string; the rest of the program reasons about it directly.
 *   ACT   (RLM)               The dataset (10k rows of CSV — too big to prompt) is injected into a sandboxed
 *                             Python REPL; the model writes code to compute the answer honoring the plan. The LM
 *                             never does the arithmetic — the sandbox does — so the number is computed, not guessed.
 *   VERIFY                    The same plan is re-executed independently on the JVM by [[QueryEngine]]; the two
 *                             engines must agree. Two independent computations matching is a real trust signal.
 *   REFINE                    On a mismatch, the discrepancy is fed back and the ACT stage retries (bounded).
 *
 * The planner's INSTRUCTION is what GEPA later optimizes (Stage 3); everything else is fixed. The executor and
 * the JVM cross-check are deliberately NOT optimized — they're the ground the optimizer is measured against.
 */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.programs.{Predict, RLM}
import dspy4s.typed.Signature
import zio.blocks.schema.Schema

import scala.annotation.tailrec

/** The ACT stage's inputs — each becomes a variable in the Python REPL. `data` is the whole CSV. */
final case class ActInput(data: String, plan: String, question: String, feedback: String) derives Schema

/** What the agent returns for one question: the typed plan, the computed result, the verifier's verdict, and how
  * many ACT attempts it took. */
final case class AgentAnswer(question: String, plan: QueryPlan, result: AnalysisResult, verdict: Verdict, attempts: Int)

object Agent:

  /** Deliberately THIN — types already pin the *shape* of a plan, but not how to map an English question onto the
    * right aggregation/filters/grouping. That mapping is exactly what GEPA learns to spell out (Stage 3). */
  val plannerInstructionsBaseline: String =
    "Translate the user's question about the dataset into a query plan."

  /** The planner signature, parameterized by instruction so the agent can run the baseline or the GEPA-optimized
    * planner. Output is the full [[QueryPlan]] — a rich typed structure (enums, nested lists) the model must fill. */
  def plannerSignature(instructions: String): Signature[Question, QueryPlan] =
    Signature.derived[Question, QueryPlan](name = "AnalystPlanner", instructions = instructions)

  private val actInstructions: String =
    """You answer a question about a dataset by WRITING AND RUNNING PYTHON over it — never by guessing numbers.
      |
      |Variables in the REPL:
      |  data     - the full dataset as CSV text (parse it with the `csv` module: csv.DictReader(io.StringIO(data)))
      |  question - the user's natural-language question
      |  plan     - a precise description of the computation to perform (honor it exactly)
      |  feedback - if non-empty, a previous attempt was wrong for this reason; recompute carefully
      |
      |Steps: parse the CSV, apply the plan's filters/time-range/grouping, compute the aggregate in code, then
      |finish with SUBMIT(answer, value, caveats, method) where:
      |  answer  - the one-line result (a number formatted plainly with no thousands separators or currency symbol,
      |            OR a category label for "which ...?" questions)
      |  value   - the numeric result as a float, or None for a category answer
      |  caveats - a list of short strings noting any assumptions (empty list if none)
      |  method  - one sentence on how you computed it
      |Always print intermediate results to check your work before SUBMIT.""".stripMargin

  /** The ACT executor: an [[RLM]] producing a typed [[AnalysisResult]] from the SUBMIT payload. No host tools and
    * no `llm_query` — it's pure deterministic computation in the sandbox. */
  val executor: RLM[ActInput, AnalysisResult] =
    RLM(
      baseSignature = Signature.derived[ActInput, AnalysisResult](name = "AnalystExecutor", instructions = actInstructions),
      maxIterations = 8,
      maxLlmCalls = 1
    )

  // ── stages ──────────────────────────────────────────────────────────────────────────────────────────────

  def plan(question: String, instructions: String)(using RuntimeContext): Either[DspyError, QueryPlan] =
    Predict(plannerSignature(instructions)).apply(Question(question, Dataset.schemaDescription)).map(_.output)

  def act(plan: QueryPlan, question: String, feedback: String)(using RuntimeContext): Either[DspyError, AnalysisResult] =
    executor.apply(ActInput(Dataset.csv, describePlan(plan), question, feedback)).map(_.output)

  /** Independent JVM re-computation of the same plan; the sandbox's answer must match. Pure — no LM. */
  def verify(plan: QueryPlan, result: AnalysisResult): Verdict =
    val oracle = QueryEngine.run(plan, Dataset.orders)
    val ok = plan.answerKind match
      case AnswerKind.Category => oracle.answer.trim.equalsIgnoreCase(result.answer.trim)
      case AnswerKind.Number =>
        val got = result.value.orElse(normalizeNumeric(result.answer))
        oracle.value.exists(e => got.exists(g => math.abs(e - g) <= 0.01 + 1e-6 * math.abs(e)))
    if ok then Verdict(ok = true, issues = Nil)
    else
      Verdict(
        ok = false,
        issues = List(
          s"Independent JVM re-computation of this plan expected '${oracle.answer}', " +
            s"but the sandbox produced '${result.answer}'. Re-read the data and recompute carefully."
        )
      )

  /** Full pipeline for one question: plan once, then act→verify, refining the ACT stage up to `maxAttempts`. */
  def ask(
      question: String,
      plannerInstructions: String = plannerInstructionsBaseline,
      maxAttempts: Int = 2
  )(using RuntimeContext): Either[DspyError, AgentAnswer] =
    plan(question, plannerInstructions).flatMap { qp =>
      @tailrec def loop(feedback: String, attempt: Int): Either[DspyError, AgentAnswer] =
        act(qp, question, feedback) match
          case Left(err) => Left(err)
          case Right(result) =>
            val verdict = verify(qp, result)
            if verdict.ok || attempt >= maxAttempts then Right(AgentAnswer(question, qp, result, verdict, attempt))
            else loop(verdict.issues.mkString(" "), attempt + 1)
      loop(feedback = "", attempt = 1)
    }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

  private def normalizeNumeric(s: String): Option[Double] = s.replaceAll("[$,%]", "").trim.toDoubleOption

  /** Render a [[QueryPlan]] into the precise instruction the executor must honor. */
  def describePlan(p: QueryPlan): String =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    parts += (p.agg match
      case Agg.Count => "Aggregation: COUNT of matching rows."
      case other     => s"Aggregation: ${other.toString.toUpperCase} of column `${p.column.getOrElse("total")}`.")
    if p.groupBy.nonEmpty then parts += s"Group by: ${p.groupBy.mkString(", ")}."
    if p.filters.nonEmpty then
      parts += "Filters: " + p.filters.map(f => s"`${f.column}` ${f.op} ${f.value}").mkString("; ") + "."
    p.timeRange.foreach(tr => parts += s"Date window on `${tr.column}`: ${tr.start.getOrElse("(open)")} to ${tr.end.getOrElse("(open)")}.")
    p.sort.foreach(s => parts += s"Sort by ${s.by} ${if s.descending then "descending" else "ascending"}.")
    p.limit.foreach(l => parts += s"Take the top $l.")
    parts += (p.answerKind match
      case AnswerKind.Category => "Answer with the group LABEL (e.g. the category/region name)."
      case AnswerKind.Number   => "Answer with the NUMERIC value.")
    parts.mkString("\n")
