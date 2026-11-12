package dspy4s.harness.host

import dspy4s.core.contracts.{DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{Budget, FinishInput, Harness, HarnessEnv, HarnessLimits, PolicyInput, PolicyStep}
import dspy4s.harness.{Secrets, SubAnswer, SubQuery, Window}
import dspy4s.harness.guest.{Access, Data, DataError, DataShape, Mode}
import dspy4s.harness.kernel.{EffectId, Logged, SessionId}
import dspy4s.harness.sandbox.HostList
import dspy4s.lm.contracts.{ForwardingLanguageModel, LanguageModel, LmMode, LmRequest, LmResponse}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.{Shape, Signature}
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.atomic.AtomicInteger

/** The call that a probe answers: the `observe` effect `effect` of the session `caller` (8.7). */
final case class ProbeLink(caller: SessionId, effect: EffectId) derives CanEqual

/** A probe (CODE_HARNESS.md, 7.4; CODE_HARNESS_DESIGN.md, 8.2 and 8.7): the answer to a question about the journal of a
  * session. It changes nothing in that session.
  */
trait Probe:

  /** The answer to `question` about `journal`, the journal of `session`, for the call `link`. Each model call of the
    * probe takes one call from `budget`, and a spent budget gives an error.
    */
  def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
      RuntimeContext
  ): Either[String, String]

object Probe:

  /** A probe that gives an error: a host with no model. */
  val none: Probe = new Probe:
    def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
        RuntimeContext
    ): Either[String, String] = Left("this host has no probe")

/** The probe of a work runtime (8.7): a program call of a `Harness` with the leaves `policy` and `subQuery`. Its input
  * is the text of the journal (`journalText`) and the question, and its output is the text of `submit`. Its mode is
  * `mode`: only `Llm^`, so the code of the probe reads its input and asks the LM, and it can neither read files nor run
  * commands. The harness of the probe has a limit of model calls of the rest of `budget`, at most `limits.modelCalls`,
  * and after the call `budget` spends the calls that the probe made. `home` is the home of the secret paths, and
  * `secrets` the mask of its journal, which goes to `pathOf(root, link)`.
  */
final class HarnessProbe(
    root    : Path,
    home    : Path,
    secrets : Secrets,
    limits  : HarnessLimits,
    policy  : Predict[PolicyInput, PolicyStep],
    subQuery: Predict[SubQuery, SubAnswer]
) extends Probe:

  def ask(session: SessionId, journal: Vector[Logged], question: String, link: ProbeLink, budget: Budget)(using
      context: RuntimeContext
  ): Either[String, String] =
    import HarnessProbe.*
    for
      _      <- budget.check()
      model  <- context.lm.collect { case model: LanguageModel => model }.toRight("the probe has no model")
      answer <-
        val counted = Counted(model)
        // The exchanges of the probe go to the exchange file of the caller, with the link of the probe
        // (docs/code-harness/OBSERVABILITY.md, 4.2).
        val sink = context.exchangeSink.map {
          case file: ExchangeFile => file.forProbe(link)
          case other              => other
        }
        val inner = context.copy(lm = Some(counted), exchangeSink = sink)
        val left  = (budget.limit - budget.spent).max(0).min(limits.modelCalls)
        val env   = HarnessEnv(
          workspace = None,
          home = home,
          hosts = HostList.empty,
          backend = None,
          secrets = secrets,
          mode = mode,
          journals = (id, log) => keep(pathOf(root, link), id, log)
        )
        val harness = Harness(signature, policy, finisher, subQuery, Window, env, limits.copy(modelCalls = left))(
          using
          summon[Data[ProbeInput]],
          ProbeAnswer.data
        )
        val result =
          try RuntimeEnvironment.withContext(inner)(harness(ProbeInput(
              session.value,
              journalText(journal),
              question
            ))(using inner))
          finally (0 until counted.calls).foreach(_ => budget.spend(): Unit)
        result.map(_.output.answer).left.map((error: DspyError) => error.message)
    yield answer

object HarnessProbe:

  /** The input of the program call of a probe: the id of the session, the text of its journal and the question. */
  final case class ProbeInput(session: String, journal: String, question: String) derives Schema

  /** The output of a probe: the text of `submit`. */
  final case class ProbeAnswer(answer: String) derives Schema

  object ProbeAnswer:

    /** The code of the probe calls `submit` with the text of the answer, so the value is a string and not a record. */
    val data: Data[ProbeAnswer] = new Data[ProbeAnswer]:
      private val text                                                      = summon[Data[String]]
      def encode(value: ProbeAnswer): Json                                  = text.encode(value.answer)
      def decode(value: Json, path: String): Either[DataError, ProbeAnswer] =
        text.decode(value, path).map(ProbeAnswer(_))
      def shape: DataShape = text.shape

  /** The instructions of the program call of a probe. */
  val instructions: String =
    "Answer the question about the journal of a session. The input journal has one line for each entry of the " +
      "journal: its seq, its source and the entry. Read the journal in the code, and call submit with the answer " +
      "as a text."

  private val signature: Signature[ProbeInput, ProbeAnswer] =
    Signature.derived[ProbeInput, ProbeAnswer]("probe", instructions)

  /** The finisher of a probe: the answer from the steps of the work, as `Harness.forTask` makes it. */
  private val finisher: Predict[FinishInput, ProbeAnswer] = Predict(
    Signature.fromShapes("finisher", Harness.finisherInstructions, Shape.derived[FinishInput], signature.outputShape),
    name = Some("finisher")
  )

  /** Writes the journal `log` of the session `id` of a probe to `path` once: a second probe of one call keeps the first
    * journal. A failed write does not change the answer of the probe.
    */
  private def keep(path: Path, id: SessionId, log: Vector[Logged]): Unit =
    if !JFiles.exists(path) then
      SessionFile.open(path, id).foreach { file =>
        try file.append(log, flush = true): Unit
        finally file.close()
      }

  /** A model that counts the calls of `delegate`, so the probe spends them from the budget of its caller. The probe
    * does not own the model, so `close` does nothing.
    */
  private final class Counted(val delegate: LanguageModel) extends ForwardingLanguageModel:
    private val made                                                                  = AtomicInteger(0)
    def id: String                                                                    = delegate.id
    def mode: LmMode                                                                  = delegate.mode
    def calls: Int                                                                    = made.get()
    override def close(): Unit                                                        = ()
    def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      made.incrementAndGet(): Unit
      delegate.call(request)

  /** The mode of a probe: only `Llm^`. */
  val mode: Mode = Mode(Access.None, Access.None, Access.None, Access.Full, Access.None, Access.None, Access.None)

  /** The text of a journal for a probe: one line for each entry, with its `seq`, its source and its entry. The mask of
    * the host already holds the secrets of the journal.
    */
  def journalText(journal: Vector[Logged]): String =
    journal.map(logged => s"${logged.seq} ${logged.meta.source} ${logged.entry}").mkString("\n")

  /** The file of the journal of the probe of `link`: `<root>/probes/<caller>.<cell>.<n>.db`, so `list` does not show
    * it, and its name links it to the `observe` call.
    */
  def pathOf(root: Path, link: ProbeLink): Path =
    root.resolve("probes").resolve(s"${nameOf(link)}.db")

  /** The name of the probe of `link`: `<caller>.<cell>.<n>`. Its journal file and the lines of its exchanges have it
    * (docs/code-harness/OBSERVABILITY.md, 4.2).
    */
  def nameOf(link: ProbeLink): String = s"${link.caller.value}.${link.effect.cell}.${link.effect.n}"
