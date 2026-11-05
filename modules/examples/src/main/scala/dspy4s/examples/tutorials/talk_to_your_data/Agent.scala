/** Talk to Your Data: the plan, act, verify, refine agent.
  *
  * PLAN (Signatures) English question to a [[QueryPlan]]. The intent of the model is a validated Scala value that the
  * rest of the program reads directly. ACT (RLM) The dataset (10k rows of CSV, too large to put in a prompt) goes into
  * a sandboxed Python REPL, and the model writes code to compute the answer that the plan asks for. Usually the code
  * computes the number. At the iteration limit, RLM asks the model for the outputs with no more code, and the model can
  * then write the values itself. VERIFY [[QueryEngine]] runs the same plan on the JVM, and the two answers must agree.
  * This shows that two computations agree, and it does not show that the plan is correct. REFINE On a mismatch, the
  * agent gives the discrepancy to the ACT stage and retries (bounded).
  *
  * The planner's INSTRUCTION is what GEPA later optimizes (Stage 3); everything else is fixed. The executor and the JVM
  * cross-check are deliberately left unoptimized, since they are the ground the optimizer is measured against.
  */
package dspy4s.examples.tutorials.talk_to_your_data

import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.programs.{IterationLimit, LlmCallLimit}
import dspy4s.programs.strategies.{Predict, RLM}
import dspy4s.signatures.Signature
import zio.blocks.schema.Schema

import scala.annotation.tailrec

/** The ACT stage's inputs; each becomes a variable in the Python REPL. `data` is the whole CSV. */
final case class ActInput(data: String, plan: String, question: String, feedback: String) derives Schema

/** What the agent returns for one question: the plan, the computed result, the verifier's verdict, and how many ACT
  * attempts it took.
  */
final case class AgentAnswer(question: String, plan: QueryPlan, result: AnalysisResult, verdict: Verdict, attempts: Int)

object Agent:

  /** Deliberately THIN: types already pin the *shape* of a plan, but not how to map an English question onto the right
    * aggregation/filters/grouping. That mapping is exactly what GEPA learns to spell out (Stage 3).
    */
  val plannerInstructionsBaseline: String = "Translate the user's question about the dataset into a query plan."

  /** The planner signature, parameterized by instruction so the agent can run the baseline or the GEPA-optimized
    * planner. Output is the full [[QueryPlan]], a rich domain structure (enums, nested lists) the model must fill.
    */
  def plannerSignature(instructions: String): Signature[Question, QueryPlan] =
    Signature.derived[Question, QueryPlan](name = "AnalystPlanner", instructions = instructions)

  /** The planner program. The agent runs it, and GEPA tunes it, so the two have the same prompt. */
  def planner(instructions: String): Predict[Question, QueryPlan] = Predict(plannerSignature(instructions))

  private val actInstructions: String =
    """You answer a question about a dataset by WRITING AND RUNNING PYTHON over it, never by guessing numbers.
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

  /** The ACT executor: an [[RLM]] producing an [[AnalysisResult]] from the SUBMIT payload. It has no host tools. RLM
    * always offers `llm_query`, and [[LlmCallLimit]] must be positive, so the code can make one sub-LM call.
    */
  val executor: RLM[ActInput, AnalysisResult] = RLM(
    baseSignature =
      Signature.derived[ActInput, AnalysisResult](name = "AnalystExecutor", instructions = actInstructions),
    maxIterations = IterationLimit(8),
    maxLlmCalls = LlmCallLimit(1)
  )

  // ── stages ──────────────────────────────────────────────────────────────────────────────────────────────

  // --8<-- [start:plan-act]
  def plan(question: String, instructions: String)(using RuntimeContext): Either[DspyError, QueryPlan] =
    planner(instructions)(Question(question, Dataset.schemaDescription)).map(_.output)

  def act(plan: QueryPlan, question: String, feedback: String)(using
      RuntimeContext
  ): Either[DspyError, AnalysisResult] =
    executor(ActInput(Dataset.csv, describePlan(plan), question, feedback)).map(_.output)
  // --8<-- [end:plan-act]

  /** Independent JVM computation of the same plan; the answer of the sandbox must match. Pure, no LM.
    *
    * The text of an issue goes to the sandbox as feedback ([[ask]]), so it does not hold the JVM answer. A model could
    * copy that answer, and a later agreement would then not be independent.
    */
  def verify(plan: QueryPlan, result: AnalysisResult): Verdict =
    QueryEngine.run(plan, Dataset.orders) match
      case Left(problem) => Verdict(ok = false, issues = List(problem))
      case Right(oracle) =>
        val ok = plan.answerKind match
          case AnswerKind.Category => oracle.answer.trim.equalsIgnoreCase(result.answer.trim)
          case AnswerKind.Number   =>
            val got = result.value.orElse(normalizeNumeric(result.answer))
            oracle.value.exists(e => got.exists(QueryEngine.withinCent(e, _)))
        if ok then Verdict(ok = true, issues = Nil)
        else
          Verdict(
            ok = false,
            issues = List(
              s"An independent JVM computation of this plan does not agree with the sandbox answer '${result.answer}'. " +
                "Read the data again and compute the answer again."
            )
          )

  /** Full pipeline for one question: plan once, then act→verify, refining the ACT stage up to `maxAttempts`. A plan
    * with a problem cannot pass verify, so it is a `Left`, and the sandbox does not run.
    */
  def ask(
      question           : String,
      plannerInstructions: String = plannerInstructionsBaseline,
      maxAttempts        : Int    = 2
  )(using RuntimeContext): Either[DspyError, AgentAnswer] =
    plan(question, plannerInstructions).flatMap { qp =>
      @tailrec def loop(feedback: String, attempt: Int): Either[DspyError, AgentAnswer] =
        act(qp, question, feedback) match
          case Left(err)     => Left(err)
          case Right(result) =>
            val verdict = verify(qp, result)
            if verdict.ok || attempt >= maxAttempts then Right(AgentAnswer(question, qp, result, verdict, attempt))
            else loop(verdict.issues.mkString(" "), attempt + 1)
      QueryEngine.run(qp, Dataset.orders) match
        case Left(problem) => Left(RuntimeError("planner", problem))
        case Right(_)      => loop(feedback = "", attempt = 1)
    }

  // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────

  private def normalizeNumeric(s: String): Option[Double] = s.replaceAll("[$,%]", "").trim.toDoubleOption

  /** Render a [[QueryPlan]] into the precise instruction the executor must honor. */
  def describePlan(p: QueryPlan): String =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    parts +=
      (p.agg match
        case Agg.Count => "Aggregation: COUNT of matching rows."
        case other     => s"Aggregation: ${other.toString.toUpperCase} of column `${p.column.getOrElse("total")}`.")
    if p.groupBy.nonEmpty then parts += s"Group by: ${p.groupBy.mkString(", ")}."
    if p.filters.nonEmpty then
      parts += "Filters: " + p.filters.map(f => s"`${f.column}` ${f.op} ${f.value}").mkString("; ") + "."
    p.timeRange.foreach(tr =>
      parts += s"Date window on `${tr.column}`: ${tr.start.getOrElse("(open)")} to ${tr.end.getOrElse("(open)")}."
    )
    p.sort.foreach(s =>
      parts += s"Sort the groups by the aggregate value, ${if s.descending then "descending" else "ascending"}."
    )
    p.limit.foreach(l => parts += s"Take the top $l.")
    parts +=
      (p.answerKind match
        case AnswerKind.Category => "Answer with the group LABEL (e.g. the category/region name)."
        case AnswerKind.Number   => "Answer with the NUMERIC value.")
    parts.mkString("\n")
