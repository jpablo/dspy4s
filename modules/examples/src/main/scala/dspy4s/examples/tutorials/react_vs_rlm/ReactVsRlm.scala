/**
 * ReAct vs RLM for Compositional Tool Calling
 *
 * Source:   DSPyWeekly issue 23 — https://github.com/RamXX/react2rlm (MIT). Ported and adapted for dspy4s.
 * Upstream task: a drug-safety checker that must perform every pairwise drug-drug interaction check
 *           (C(7,2) = 21 pairs) and every drug-condition contraindication check (7 × 2 = 14) over a
 *           medication list. The point of the demo: `ReAct` selects tools heuristically and tends to check
 *           only a handful of the required pairs, while `RLM` writes Python that ENUMERATES the combinations
 *           deterministically and so achieves full coverage — the "neuro-symbolic" advantage of having the
 *           model emit symbolic code rather than reason through every call in natural language.
 *
 * What this showcases (two dspy4s flagships, same task, same tools):
 *   - `ReAct`  — the text-protocol think→pick-tool→observe loop (re-reads a growing trajectory each turn).
 *   - `RLM`    — inputs become variables in a sandboxed Python REPL; the model writes code that calls the
 *                SAME tools in a loop and `SUBMIT`s the result. Tools are exposed to the sandbox verbatim.
 *
 * Both agents share one base signature and one set of [[ToolFunction]]s. The task instruction is deliberately
 * NEUTRAL about strategy (it asks for a thorough report, not "check every pair"), so each approach picks its own
 * method. We measure two things per run: COVERAGE (a [[CallRecorder]] over every tool call) and EFFORT (LM
 * round-trips + wall time). The durable, model-independent result is the EFFORT gap: even when a capable model
 * lets ReAct reach full coverage too, RLM gets there in a handful of LM calls (one code-gen step writes a loop
 * that does all the checks) versus ReAct's one-LM-call-per-tool-call, trajectory-re-reading turns.
 *
 * Observed in a sample run: ReAct covered 5/21 pairs + 5/14 contraindications in 12 LM round-trips (90s), while
 * RLM covered 21/21 + 14/14 in 3 round-trips (33s) — ReAct even reported "no issue identified" for drugs it
 * never checked. Numbers vary by model/run; the gap's direction is the point.
 *
 * Deltas from the original:
 *   - The interaction / contraindication / drug-class tables are small ILLUSTRATIVE fixtures (the original
 *     uses simulated databases too). This is not medical advice.
 *   - The original runs on Groq (`groq/openai/gpt-oss-120b`); dspy4s's OpenAI-compatible route is used here
 *     via [[Demo]] (`OPENAI_API_KEY` + optional `DSPY_MODEL`). Any OpenAI-compatible endpoint works.
 *   - `RLM` requires **Deno** on the PATH for its Pyodide sandbox. Without it, this demo runs the ReAct side
 *     only and prints how to enable the RLM side.
 *   - We don't assert exact coverage numbers (a live model varies run to run); we print what each achieved.
 *
 * Run with: OPENAI_API_KEY=sk-... sbt "examples/runMain dspy4s.examples.tutorials.react_vs_rlm.reactVsRlmMain"
 */
package dspy4s.examples.tutorials.react_vs_rlm

import dspy4s.core.contracts.{CallbackEvent, CallbackHandler, DspyError, DynamicValues, LmStartEvent, RuntimeContext, TypeRef}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.examples.Demo
import dspy4s.programs.{ReAct, RLM}
import dspy4s.programs.contracts.ToolFunction
import dspy4s.typed.Signature
import zio.blocks.schema.DynamicValue

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.util.control.NonFatal

// ── Illustrative drug-safety fixtures (NOT medical advice) ────────────────────────────────────────────────
object DrugSafetyData:

  /** Canonical normalization for drug and condition names so lookups and coverage keys are case/space-stable. */
  def norm(s: String): String = s.trim.toLowerCase.replaceAll("\\s+", " ")

  /** Order-independent key for a drug pair (so `(a, b)` and `(b, a)` collapse to one). */
  def pairKey(a: String, b: String): String = Vector(norm(a), norm(b)).sorted.mkString("|")

  val drugs: Vector[String] =
    Vector("warfarin", "aspirin", "ibuprofen", "lisinopril", "metformin", "atorvastatin", "omeprazole")

  val conditions: Vector[String] =
    Vector("peptic ulcer disease", "chronic kidney disease")

  /** The 21 unique drug pairs the agent is expected to check (`combinations(drugs, 2)`). */
  val allInteractionPairs: Vector[String] =
    (for case Seq(a, b) <- drugs.combinations(2).toVector yield pairKey(a, b))

  /** The 14 drug × condition contraindication checks expected. */
  val allContraindicationPairs: Vector[String] =
    for d <- drugs; c <- conditions yield s"${norm(d)}|${norm(c)}"

  private val rawInteractions: List[((String, String), String)] = List(
    ("warfarin", "aspirin")        -> "MAJOR: additive anticoagulant + antiplatelet effect — substantially increased bleeding risk.",
    ("warfarin", "ibuprofen")      -> "MAJOR: NSAID with an anticoagulant — increased GI bleeding risk; may raise INR.",
    ("warfarin", "omeprazole")     -> "MODERATE: omeprazole can inhibit warfarin metabolism — monitor INR.",
    ("warfarin", "atorvastatin")   -> "MODERATE: possible potentiation of anticoagulant effect — monitor INR.",
    ("aspirin", "ibuprofen")       -> "MODERATE: ibuprofen can blunt aspirin's antiplatelet effect; additive GI risk.",
    ("aspirin", "lisinopril")      -> "MODERATE: NSAIDs/aspirin may reduce the antihypertensive effect and renal perfusion.",
    ("ibuprofen", "lisinopril")    -> "MODERATE: NSAID + ACE inhibitor — reduced renal function (part of the 'triple whammy').",
    ("lisinopril", "metformin")    -> "MINOR: ACE inhibitors may modestly enhance the glucose-lowering effect.",
    ("atorvastatin", "omeprazole") -> "MINOR: minimal clinically significant interaction expected."
  )

  /** Pairwise interaction lookup; pairs without an entry are reported as not clinically significant. */
  val interactionDb: Map[String, String] =
    rawInteractions.map { case ((a, b), v) => pairKey(a, b) -> v }.toMap

  private val rawContraindications: List[((String, String), String)] = List(
    ("ibuprofen", "peptic ulcer disease")    -> "CONTRAINDICATED: NSAIDs can cause/worsen peptic ulcers and GI bleeding.",
    ("aspirin", "peptic ulcer disease")      -> "CAUTION: increased GI bleeding risk; avoid or co-prescribe gastroprotection.",
    ("warfarin", "peptic ulcer disease")     -> "CAUTION: heightened bleeding risk with an active ulcer.",
    ("ibuprofen", "chronic kidney disease")  -> "CONTRAINDICATED: NSAIDs are nephrotoxic and can worsen renal function.",
    ("metformin", "chronic kidney disease")  -> "CONTRAINDICATED below an eGFR threshold: lactic-acidosis risk.",
    ("lisinopril", "chronic kidney disease") -> "CAUTION: monitor renal function and potassium (hyperkalemia risk).",
    ("aspirin", "chronic kidney disease")    -> "CAUTION: may reduce renal perfusion; use with monitoring."
  )

  val contraindicationDb: Map[String, String] =
    rawContraindications.map { case ((d, c), v) => s"${norm(d)}|${norm(c)}" -> v }.toMap

  val drugClassDb: Map[String, String] = Map(
    "warfarin"     -> "vitamin-K antagonist anticoagulant",
    "aspirin"      -> "antiplatelet / NSAID (salicylate)",
    "ibuprofen"    -> "NSAID",
    "lisinopril"   -> "ACE inhibitor",
    "metformin"    -> "biguanide antihyperglycemic",
    "atorvastatin" -> "HMG-CoA reductase inhibitor (statin)",
    "omeprazole"   -> "proton-pump inhibitor"
  )

// ── Records which (drug,drug) and (drug,condition) checks an agent actually performed ─────────────────────
/** Thread-safe: `RLM`'s sandbox bridge may invoke tools off the main call stack, so all mutation is guarded. */
final class CallRecorder:
  private val interactions      = mutable.Set.empty[String]
  private val contraindications = mutable.Set.empty[String]

  def recordInteraction(a: String, b: String): Unit =
    synchronized { interactions += DrugSafetyData.pairKey(a, b); () }

  def recordContraindication(drug: String, condition: String): Unit =
    synchronized { contraindications += s"${DrugSafetyData.norm(drug)}|${DrugSafetyData.norm(condition)}"; () }

  /** (covered, total) over the 21 required pairwise interaction checks. */
  def interactionCoverage: (Int, Int) =
    synchronized { (DrugSafetyData.allInteractionPairs.count(interactions.contains), DrugSafetyData.allInteractionPairs.size) }

  /** (covered, total) over the 14 required drug-condition contraindication checks. */
  def contraindicationCoverage: (Int, Int) =
    synchronized { (DrugSafetyData.allContraindicationPairs.count(contraindications.contains), DrugSafetyData.allContraindicationPairs.size) }

// ── The three shared tools (typed argSchema so BOTH the ReAct prompt and the RLM Python sandbox agree) ─────
object DrugSafetyTools:

  private def arg(args: DynamicValue.Record, name: String): String =
    DynamicValues.recordGet(args, name).map(DynamicValues.renderText).getOrElse("")

  /** Hand-built so we control the recording side effect AND populate `argSchema` (which drives the Python
    * parameter names in RLM's sandbox and the tool docs in ReAct's prompt — see `CodeAct.sandboxTools`). */
  private def tool(toolName: String, desc: String, params: (String, TypeRef)*)(
      body: DynamicValue.Record => String
  ): ToolFunction =
    new ToolFunction:
      override val name: String                              = toolName
      override val description: String                       = desc
      override val argSchema: Vector[(String, TypeRef)]      = params.toVector
      override def invoke(args: DynamicValue.Record)(using RuntimeContext): Either[DspyError, DynamicValue] =
        Right(DynamicValues.fromAny(body(args)))

  def build(recorder: CallRecorder): Vector[ToolFunction] = Vector(
    tool(
      "check_drug_interaction",
      "Check the interaction between two drugs. Returns the severity and clinical detail.",
      "drug_a" -> TypeRef.string,
      "drug_b" -> TypeRef.string
    ) { args =>
      val (a, b) = (arg(args, "drug_a"), arg(args, "drug_b"))
      recorder.recordInteraction(a, b)
      DrugSafetyData.interactionDb.getOrElse(
        DrugSafetyData.pairKey(a, b),
        s"NONE: no clinically significant interaction documented between $a and $b."
      )
    },
    tool(
      "check_contraindication",
      "Check whether a drug is contraindicated for a patient condition.",
      "drug"      -> TypeRef.string,
      "condition" -> TypeRef.string
    ) { args =>
      val (d, c) = (arg(args, "drug"), arg(args, "condition"))
      recorder.recordContraindication(d, c)
      DrugSafetyData.contraindicationDb.getOrElse(
        s"${DrugSafetyData.norm(d)}|${DrugSafetyData.norm(c)}",
        s"NONE: no specific contraindication documented for $d in $c."
      )
    },
    tool(
      "get_drug_class",
      "Return the pharmacological class of a drug.",
      "drug" -> TypeRef.string
    ) { args =>
      val d = arg(args, "drug")
      DrugSafetyData.drugClassDb.getOrElse(DrugSafetyData.norm(d), s"unknown class for $d")
    }
  )

object ReactVsRlm:

  /** Deliberately NEUTRAL about strategy: asks for a thorough report, NOT "check every unique pair". That lets
    * each approach choose how to be thorough — the whole point of the comparison. (An over-directive "enumerate
    * every pair" instruction hands ReAct the answer and erases the difference.) */
  private val taskInstructions =
    """You are a clinical drug-safety checker. Given a medication list and the patient's conditions, produce a
      |thorough risk_report: identify the clinically significant drug-drug interactions and any drug-condition
      |contraindications, then summarize the overall risk and flag the MAJOR and MODERATE concerns. Use the
      |available tools to ground your findings.""".stripMargin

  /** One base signature, shared by both approaches. Inputs are comma-separated lists. */
  val signature = Signature.fromString("medications, conditions -> risk_report", taskInstructions)

  /** A generous ceiling, not a target: ReAct *could* check all 21 pairs + 14 contraindications within this budget,
    * so any under-coverage reflects its own heuristic tool selection — not an artificial cap. Each turn re-reads
    * the growing trajectory (O(N²) tokens), which is exactly the cost the EFFORT metric below surfaces. */
  val ReActMaxIterations: Int = 40

  final case class RunResult(
      label: String,
      report: String,
      recorder: CallRecorder,
      lmCalls: Int,
      elapsedMs: Long,
      trajectory: Option[String]
  )

  private def coverageLine(label: String, covered: Int, total: Int): String =
    val pct = if total == 0 then 0 else math.round(covered.toDouble / total * 100).toInt
    f"$label%-34s $covered%2d / $total%-2d ($pct%3d%%)"

  private def metricLine(label: String, value: String): String = f"$label%-34s $value"

  /** Run `body` with a fresh LM-call counter installed on the context's callbacks, returning the body's result
    * plus the effort it took: LM round-trips (counted via [[LmStartEvent]]) and wall time. */
  private def measured[A](body: RuntimeContext ?=> A)(using base: RuntimeContext): (A, Int, Long) =
    val counter = new AtomicInteger(0)
    val countingCallback = new CallbackHandler:
      override def onEvent(event: CallbackEvent)(using RuntimeContext): Unit = event match
        case _: LmStartEvent => val _ = counter.incrementAndGet()
        case _               => ()
    val started = System.nanoTime()
    val result =
      RuntimeEnvironment.withSettings(base.withCallbacks(base.callbacks :+ countingCallback)) {
        body(using RuntimeEnvironment.current)
      }
    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
    (result, counter.get(), elapsedMs)

  def runReAct(medications: String, conditions: String)(using RuntimeContext): RunResult =
    val recorder = new CallRecorder
    val agent = ReAct(
      baseSignature = signature,
      tools = DrugSafetyTools.build(recorder),
      maxIterations = ReActMaxIterations
    )
    val (report, lmCalls, ms) = measured {
      agent
        .apply((medications = medications, conditions = conditions))
        .map(_.output.risk_report)
        .fold(err => s"[error] ${err.message}", identity)
    }
    RunResult("ReAct", report, recorder, lmCalls, ms, trajectory = None)

  def runRlm(medications: String, conditions: String)(using RuntimeContext): RunResult =
    val recorder = new CallRecorder
    val agent = RLM(
      baseSignature = signature,
      tools = DrugSafetyTools.build(recorder),
      maxIterations = 12
    )
    val (prediction, lmCalls, ms) = measured {
      agent.apply((medications = medications, conditions = conditions))
    }
    val report     = prediction.map(_.output.risk_report).fold(err => s"[error] ${err.message}", identity)
    val trajectory = prediction.toOption.flatMap(_.raw.asString("trajectory").toOption)
    RunResult("RLM (direct)", report, recorder, lmCalls, ms, trajectory)

  def printResult(r: RunResult): Unit =
    val (ic, it) = r.recorder.interactionCoverage
    val (cc, ct) = r.recorder.contraindicationCoverage
    println(s"=== ${r.label} ===")
    println(s"risk_report:\n${r.report}\n")
    println(coverageLine("pairwise interaction coverage:", ic, it))
    println(coverageLine("drug-condition coverage:", cc, ct))
    println(metricLine("LM round-trips:", r.lmCalls.toString))
    println(metricLine("wall time:", f"${r.elapsedMs / 1000.0}%.1fs"))
    r.trajectory.foreach { t =>
      println("\n--- RLM generated and ran this code (trajectory excerpt) ---")
      println(if t.length > 1600 then t.take(1600) + "\n... (truncated)" else t)
    }
    println("-" * 70)

  /** Side-by-side summary — the model-independent point: similar coverage is reachable, but the EFFORT differs. */
  def printComparison(react: RunResult, rlm: RunResult): Unit =
    def inter(r: RunResult): String =
      val (c, t) = r.recorder.interactionCoverage
      s"$c/$t"
    def contra(r: RunResult): String =
      val (c, t) = r.recorder.contraindicationCoverage
      s"$c/$t"
    println("=== ReAct vs RLM — same task, same tools ===")
    println(f"${""}%-26s ${react.label}%-14s ${rlm.label}%-14s")
    println(f"${"LM round-trips"}%-26s ${react.lmCalls}%-14d ${rlm.lmCalls}%-14d")
    println(f"${"wall time (s)"}%-26s ${react.elapsedMs / 1000.0}%-14.1f ${rlm.elapsedMs / 1000.0}%-14.1f")
    println(f"${"pairwise coverage"}%-26s ${inter(react)}%-14s ${inter(rlm)}%-14s")
    println(f"${"contraindication coverage"}%-26s ${contra(react)}%-14s ${contra(rlm)}%-14s")
    println("-" * 70)

  val demoMedications: String = DrugSafetyData.drugs.mkString(", ")
  val demoConditions: String  = DrugSafetyData.conditions.mkString(", ")

  private[react_vs_rlm] def denoAvailable: Boolean =
    try new ProcessBuilder("deno", "--version").start().waitFor() == 0
    catch case NonFatal(_) => false

@main def reactVsRlmMain(): Unit = Demo.withLm {
  import ReactVsRlm.*

  println(s"Medications: $demoMedications")
  println(s"Conditions:  $demoConditions")
  println(s"Goal: a thorough risk report. There are ${DrugSafetyData.allInteractionPairs.size} unique drug pairs " +
    s"and ${DrugSafetyData.allContraindicationPairs.size} drug-condition combinations to consider.\n")

  val react = runReAct(demoMedications, demoConditions)
  printResult(react)

  if denoAvailable then
    val rlm = runRlm(demoMedications, demoConditions)
    printResult(rlm)
    printComparison(react, rlm)
  else
    println("=== RLM (direct) — SKIPPED ===")
    println("RLM needs Deno on the PATH for its Pyodide sandbox. Install from https://deno.com and re-run to")
    println("see the RLM side write Python that enumerates every pair — typically far fewer LM round-trips.")
}
