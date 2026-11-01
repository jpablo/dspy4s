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
import dspy4s.lm.contracts.{LanguageModel, LmRequest, Message, MessageRole}
import dspy4s.programs.LlmCallLimit
import dspy4s.programs.contracts.ToolFunction
import dspy4s.programs.runtime.ParallelExecutor

import java.util.concurrent.atomic.AtomicInteger

/** Builds the sandbox functions of generated RLM code: the calls of a subordinate language model and the user tools. */
private[programs] object RLMSandboxTools:

  /** The tools `llm_query` and `llm_query_batched`. The usage of each sub-LM call goes to the usage scope of the RLM
    * call ([[LanguageModel.callAndRecordUsage]]). The batched calls run on worker threads, and the scope travels to
    * them. Upstream counts these calls in the usage of the RLM call, because they run in the context of the call
    * (`dspy/predict/rlm.py`, `_make_llm_tools`, with `contextvars.copy_context` for the batched calls).
    */
  def build(
      maxLlmCalls: LlmCallLimit,
      subLm      : Option[LanguageModel],
      ctx        : RuntimeContext
  ): Vector[SandboxTool] =
    val counter = new AtomicInteger(0)

    def checkAndIncrement(n: Int): Either[DspyError, Unit] =
      if counter.get() + n > maxLlmCalls then
        Left(RuntimeError(
          "rlm",
          s"LLM call limit exceeded: ${counter.get()} + $n > $maxLlmCalls. " +
            "Use Python code for aggregation instead of making more LLM calls."
        ))
      else
        val _ = counter.addAndGet(n)
        Right(())

    def queryLm(prompt: String): Either[DspyError, String] =
      val lm = subLm.orElse(ctx.lm.collect { case m: LanguageModel => m })
      lm match
        case None        => Left(RuntimeError("rlm", "No LM configured. Configure an ambient LM or pass subLm to RLM."))
        case Some(model) => LanguageModel
            .callAndRecordUsage(
              model,
              LmRequest(model = model.id, messages = Vector(Message(role = MessageRole.User, text = Some(prompt))))
            )(using ctx)
            .map(_.outputs.headOption.map(_.text).getOrElse(""))

    val llmQuery = SandboxTool(
      name = "llm_query",
      parameters = Vector(SandboxTool.Param("prompt", Some("str"))),
      invoke = kwargs =>
        val prompt = DynamicValues.recordGet(kwargs, "prompt").map(DynamicValues.renderText).getOrElse("")
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
            val executor = ParallelExecutor(
              numThreads = ThreadCount.applyUnsafe(math.min(8, prompts.size)),
              maxErrors = ErrorLimit.applyUnsafe(prompts.size)
            )
            executor
              .execute(
                task = (p: String) => queryLm(p).fold(err => s"[ERROR] ${err.message}", identity),
                data = prompts
              )(using ctx)
              .map { outcome =>
                val answers = prompts.indices.map { i =>
                  outcome.results(i).getOrElse(
                    s"[ERROR] ${outcome.errors.get(i).map(_.message).getOrElse("prompt was not executed")}"
                  )
                }
                DynamicValues.sequence(answers.map(DynamicValues.string))
              }
          }
    )

    Vector(llmQuery, llmQueryBatched)

  /** The user tools as host functions for the sandbox. Each function runs with the runtime context of the call, and
    * keeps the name of its tool and the parameter hints in the Python form.
    */
  def fromToolFunctions(tools: Vector[ToolFunction])(using ctx: RuntimeContext): Vector[SandboxTool] =
    tools.map { tool =>
      SandboxTool(
        name = tool.name,
        parameters = tool.argSchema.map { case (name, typeRef) => SandboxTool.Param(name, typeRef.pythonTypeName) },
        invoke = kwargs => tool.invoke(kwargs)(using ctx)
      )
    }
