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
import dspy4s.programs.runtime.ParallelExecutor
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger

/** Builds the sandbox functions that let generated RLM code call a subordinate language model. */
private[programs] object RLMSandboxTools:
  def build(
      maxLlmCalls: LlmCallLimit,
      subLm: Option[LanguageModel],
      ctx: RuntimeContext
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
        case Some(model) =>
          model
            .call(LmRequest(
              model = model.id,
              messages = Vector(Message(role = MessageRole.User, text = Some(prompt)))
            ))(using ctx)
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
          yield DynamicValues.fromAny(answer)
    )

    val llmQueryBatched = SandboxTool(
      name = "llm_query_batched",
      parameters = Vector(SandboxTool.Param("prompts", Some("list"))),
      invoke = kwargs =>
        val prompts = DynamicValues.recordGet(kwargs, "prompts") match
          case Some(seq: DynamicValue.Sequence) => seq.elements.iterator.map(DynamicValues.renderText).toVector
          case _                                => Vector.empty
        if prompts.isEmpty then Right(DynamicValues.fromAny(List.empty[String]))
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
                DynamicValues.fromAny(answers.toList)
              }
          }
    )

    Vector(llmQuery, llmQueryBatched)
