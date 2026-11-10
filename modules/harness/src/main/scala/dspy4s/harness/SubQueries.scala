package dspy4s.harness

import dspy4s.harness.guest.Llm
import dspy4s.programs.strategies.Predict
import zio.blocks.schema.Schema

/** The input of the `subQuery` leaf (CODE_HARNESS_DESIGN.md, 9): the prompt of an `llmQuery` of a cell.
  *
  * Section 9 names the types `Query` and `Answer`. The review of phase 5 kept the names `SubQuery` and `SubAnswer`,
  * because the signature of a user often has its own type `Answer`, and a wildcard import would clash with it.
  */
final case class SubQuery(prompt: String) derives Schema

/** The output of the `subQuery` leaf (9): the answer to the prompt. */
final case class SubAnswer(answer: String) derives Schema

/** The handler of `Llm^` (5.1, 6, 9): the `subQuery` leaf answers each query of a cell.
  *
  *   - `query` spends one call of `budget`, and runs the leaf through `host`, so the call has the context of the
  *     program call and its trace reaches the trace of the call. A spent budget and a failed model call throw an
  *     exception with a plain message, so the cell fails.
  *   - `queryBatched` runs at most `width` queries at the same time on host threads, and keeps the order of the
  *     prompts. Each prompt spends one call of the budget. When one query fails, it throws, and the message has the
  *     index of that prompt. A cell cannot start a thread, but the host can (6).
  */
final class LeafLlm(leaf: Predict[SubQuery, SubAnswer], budget: Budget, host: HostThreads, width: Int) extends Llm:

  def query(prompt: String): String =
    budget.spend().left.foreach(error => throw IllegalStateException(s"llmQuery: $error"))
    host.run(leaf(SubQuery(prompt))) match
      case Right(prediction) => prediction.output.answer
      case Left(error)       => throw IllegalStateException(s"llmQuery: the model call failed: ${error.message}")

  def queryBatched(prompts: Vector[String]): Vector[String] =
    inBatch(prompts, width)(query) match
      case Right(answers)     => answers
      case Left((i, failure)) =>
        throw IllegalStateException(s"llmQueryBatched: the prompt $i failed: ${plainMessage(failure)}")

/** The message of an exception, or its name when it has no message. */
private[harness] def plainMessage(e: Exception): String = Option(e.getMessage).getOrElse(e.toString)
