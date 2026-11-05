package dspy4s.programs.strategies

import dspy4s.core.contracts.{
  DspyError,
  DynamicValues,
  ErrorLimit,
  RuntimeContext,
  RuntimeError,
  SandboxTool,
  ThreadCount
}
import dspy4s.core.runtime.PythonNames
import dspy4s.lm.contracts.{LanguageModel, LmRequest, Message, MessageRole}
import dspy4s.programs.LlmCallLimit
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.ParallelExecutor

import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec

/** Builds the sandbox functions of generated RLM code: the calls of a subordinate language model and the user tools. */
private[programs] object RLMSandboxTools:

  /** The tools `llm_query` and `llm_query_batched`. Each sub-LM call sends the LM callback events, and its usage goes
    * to the usage scope of the RLM call ([[LanguageModel.callAndRecordUsage]]). The batched calls run on worker
    * threads. The usage scope and the open call scopes travel to them, so the LM events of a batched call have the
    * scope of the tool as their parent. Upstream counts these calls in the usage of the RLM call, because they run in
    * the context of the call (`dspy/predict/rlm.py`, `_make_llm_tools`, with `contextvars.copy_context` for the batched
    * calls), and `target_lm(prompt)` is a `BaseLM.__call__`, which has `with_callbacks`.
    */
  def build(
      maxLlmCalls: LlmCallLimit,
      subLm      : Option[LanguageModel],
      ctx        : RuntimeContext
  ): Vector[SandboxTool] =
    val counter = new AtomicInteger(0)

    // The check and the add are one atomic step (upstream `_LLMCallBudget.reserve` holds a lock for them).
    @tailrec
    def checkAndIncrement(n: Int): Either[DspyError, Unit] =
      val count = counter.get()
      if count + n > maxLlmCalls then
        Left(RuntimeError(
          "rlm",
          s"LLM call limit exceeded: $count + $n > $maxLlmCalls. " +
            "Use Python code for aggregation instead of making more LLM calls."
        ))
      else if counter.compareAndSet(count, count + n) then Right(())
      else checkAndIncrement(n)

    // A reply with no text is an error (upstream `_query_lm` raises when the text of the sub-LM is not a string).
    def queryLm(prompt: String): Either[DspyError, String] =
      val lm = subLm.orElse(ctx.lm.collect { case m: LanguageModel => m })
      lm match
        case None        => Left(RuntimeError("rlm", "No LM configured. Configure an ambient LM or pass subLm to RLM."))
        case Some(model) => LanguageModel
            .callAndRecordUsage(
              model,
              LmRequest(model = model.id, messages = Vector(Message(role = MessageRole.User, text = Some(prompt))))
            )(using ctx)
            .flatMap(
              _.outputs.headOption
                .map(_.text)
                .filter(_.nonEmpty)
                .toRight(RuntimeError("rlm", "The sub-LM returned no outputs."))
            )

    val llmQuery = SandboxTool(
      name = "llm_query",
      parameters = Vector(SandboxTool.Param("prompt", Some("str"))),
      invoke = kwargs =>
        val prompt = DynamicValues.recordGet(kwargs, "prompt")
          .filterNot(DynamicValues.isNull)
          .map(DynamicValues.renderText)
          .getOrElse("")
        if prompt.isEmpty then Left(RuntimeError("rlm", "prompt cannot be empty"))
        else
          for
            _      <- checkAndIncrement(1)
            answer <- queryLm(prompt)
          yield DynamicValues.string(answer)
    )

    val llmQueryBatched = SandboxTool(
      name = "llm_query_batched",
      parameters = Vector(SandboxTool.Param("prompts", Some("list"))),
      invoke = kwargs =>
        val prompts = DynamicValues.sequenceAt(kwargs, "prompts").map(DynamicValues.renderText)
        if prompts.isEmpty then Right(DynamicValues.sequence(Vector.empty))
        else
          checkAndIncrement(prompts.size).flatMap { _ =>
            // The limit is above the size of the batch, so errors never stop the batch and each prompt gets its text.
            val executor = ParallelExecutor(
              numThreads = ThreadCount.applyUnsafe(math.min(8, prompts.size)),
              maxErrors = ErrorLimit.applyUnsafe(prompts.size + 1)
            )
            executor
              .execute(
                task = (p: String) => queryLm(p).fold(err => s"[ERROR] ${err.message}", identity),
                data = prompts
              )(using ctx)
              .map { outcome =>
                val answers = outcome.results.map(_.fold(error => s"[ERROR] ${error.message}", identity))
                DynamicValues.sequence(answers.map(DynamicValues.string))
              }
          }
    )

    Vector(llmQuery, llmQueryBatched)

  /** The user tools as host functions for the sandbox. Each function runs with the runtime context of the call, and
    * keeps the name of its tool and the parameter hints in the Python form.
    *
    *   - An argument is optional when the tool has an `inputSchema` and the `required` list of the schema does not name
    *     the argument. A tool with no `inputSchema` has only required arguments.
    *   - A tool with an argument name that Python code cannot bind (`from`, `max-results`), or a name that the sandbox
    *     keeps for itself (`_dspy...`), has no declared parameters, so it takes keyword arguments:
    *     `search(**{'from': 'a'})`. This is the rule for an unknown argument list ([[ToolFunction.argSchema]]).
    */
  def fromToolFunctions(tools: Vector[ToolFunction])(using ctx: RuntimeContext): Vector[SandboxTool] =
    tools.map { tool =>
      SandboxTool(
        name = tool.name,
        parameters = sandboxParameters(tool),
        invoke = kwargs => tool.invoke(kwargs)(using ctx)
      )
    }

  /** The declared parameters of `tool` in the sandbox. See [[fromToolFunctions]] for the two rules. */
  private def sandboxParameters(tool: ToolFunction): Vector[SandboxTool.Param] =
    if !tool.argSchema.forall((name, _) => PythonNames.isBindable(name) && !name.startsWith("_dspy")) then Vector.empty
    else
      val required =
        tool.inputSchema.map(schema => DynamicValues.sequenceAt(schema, "required").flatMap(DynamicValues.asString))
      tool.argSchema.map { (name, typeRef) =>
        SandboxTool.Param(name, typeRef.pythonTypeName, optional = required.exists(!_.contains(name)))
      }
