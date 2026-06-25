/**
 * Talk to Your Data — typed schemas.
 *
 * The whole pipeline is type-safe end to end. The model's *intent* (what to compute) is a [[QueryPlan]] — a
 * validated Scala value, not a string — and the *result* is an [[AnalysisResult]]. Both are `derives Schema`,
 * so there is zero hand-written codec; the same types constrain the LM's output, decode its reply, drive the
 * GEPA metric, and render the report. Change a field and the compiler walks you through every site that breaks.
 *
 * This file is pure data definitions — no LM, no I/O.
 */
package dspy4s.examples.tutorials.talk_to_your_data

import zio.blocks.schema.Schema

/** The aggregation to compute. A Scala 3 enum, so the model can only ever pick one of these — the prompt can't
  * drift into "avg" vs "mean" vs "average". */
enum Agg derives Schema, CanEqual:
  case Count, Sum, Average, Min, Max, Median

/** Comparison operator for a [[Filter]]. */
enum FilterOp derives Schema, CanEqual:
  case Eq, Ne, Gt, Gte, Lt, Lte, Contains

/** What kind of answer the question wants — a number ("how much…") or a category label ("which…"). Lets one
  * grouped+top-1 plan answer either "which category sold most?" (Category) or "what did the top category sell?"
  * (Number). */
enum AnswerKind derives Schema, CanEqual:
  case Number, Category

/** A single row filter, e.g. `region = "West"` or `total > 100`. */
final case class Filter(column: String, op: FilterOp, value: String) derives Schema

/** Inclusive date window over a date column (ISO `yyyy-MM-dd` strings; lexicographic compare is correct). */
final case class TimeRange(column: String, start: Option[String], end: Option[String]) derives Schema

/** Ordering for grouped results; `by = "value"` sorts by the aggregate, otherwise by a group column. */
final case class Sort(by: String, descending: Boolean) derives Schema

/** The typed intent of a natural-language question — the "plan" the agent commits to before computing anything.
  * This is the heart of the type-safety story: an English question becomes a structured, inspectable value that
  * the rest of the program (and the optimizer) can reason about. */
final case class QueryPlan(
    agg: Agg,
    column: Option[String],
    groupBy: List[String],
    filters: List[Filter],
    timeRange: Option[TimeRange],
    sort: Option[Sort],
    limit: Option[Int],
    answerKind: AnswerKind
) derives Schema

/** The planner's input: the question plus a description of the dataset's columns (so it knows what it can ask). */
final case class Question(question: String, schema: String) derives Schema

/** The computed answer. `answer` is the canonical one-line result; `value` is the headline number (when the
  * question is numeric); `caveats` and `method` make the computation auditable. Single-word fields so the RLM
  * stage's `SUBMIT(...)` is easy for the model to get right. */
final case class AnalysisResult(
    answer: String,
    value: Option[Double],
    caveats: List[String],
    method: String
) derives Schema

/** The verifier's verdict over an [[AnalysisResult]]. */
final case class Verdict(ok: Boolean, issues: List[String]) derives Schema

/** One row of the synthetic e-commerce dataset. Pure Scala (not `derives Schema`) — it's the internal data the
  * Scala query engine operates on; the agent sees the data as CSV text. */
final case class Order(
    orderId: Int,
    date: String,
    region: String,
    category: String,
    product: String,
    quantity: Int,
    unitPrice: Double,
    discount: Double,
    total: Double
)
