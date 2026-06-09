package dspy4s.optimize

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.:=
import dspy4s.core.contracts.{DspyError, DynamicValues, Example, RuntimeContext, SignatureLayout}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.lm.contracts.{Embedder, LanguageModel, LmMode, LmOutput, LmRequest, LmResponse}
import dspy4s.programs.DynamicPredict
import dspy4s.programs.contracts.ProgramCall
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer

/** Offline KNNFewShot suite: a trainset with two clusters (x-axis questions / y-axis questions) and a deterministic
  * embedder. For a query near cluster A, the compiled program must bootstrap ONLY cluster-A neighbors as demos —
  * asserted from the final task prompt (which carries demo answers a1/a2 but not a3/a4). */
class KNNFewShotSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context: AfterEach):  Unit = RuntimeEnvironment.resetForTests()

  private val gold = Map("q1" -> "a1", "q2" -> "a2", "q3" -> "a3", "q4" -> "a4")

  /** Cluster A (q1, q2) on the x-axis; cluster B (q3, q4) on the y-axis; the query sits near A. */
  private val embedder: Embedder = Embedder.fromFunction("clusters") { texts =>
    texts.map {
      case "question: q1"    => Vector(1.0f, 0.0f)
      case "question: q2"    => Vector(0.9f, 0.1f)
      case "question: q3"    => Vector(0.0f, 1.0f)
      case "question: q4"    => Vector(0.1f, 0.9f)
      case "question: query" => Vector(1.0f, 0.05f)
      case other             => throw new IllegalArgumentException(s"unmapped text: '$other'")
    }
  }

  /** Answers gold questions (the bootstrap-teacher calls); for the final query it answers "guided" iff its prompt
    * carries a cluster-A demo answer. Records every prompt so the test can inspect what the demos rendered. */
  private final class ScriptedLm extends LanguageModel:
    val prompts: ArrayBuffer[String] = ArrayBuffer.empty
    override val id: String          = "scripted-knn"
    override val mode: LmMode        = LmMode.Chat
    override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
      val full = request.messages.flatMap(_.text).mkString("\n")
      prompts += full
      val question = extractLastQuestion(full)
      val answer   = gold.getOrElse(question, if full.contains("a1") then "guided" else "unguided")
      Right(LmResponse(outputs = Vector(LmOutput(text = s"[[ ## answer ## ]]\n$answer\n\n[[ ## completed ## ]]"))))

  /** The CURRENT question is the last `[[ ## question ## ]]` block in the rendered prompt (demos come first). */
  private def extractLastQuestion(prompt: String): String =
    val marker = "[[ ## question ## ]]"
    val at     = prompt.lastIndexOf(marker)
    if at < 0 then ""
    else prompt.drop(at + marker.length).linesIterator.map(_.trim).find(_.nonEmpty).getOrElse("")

  private def ex(q: String): Example =
    Example(DynamicValues.record("question" := q, "answer" := gold(q)), inputKeys = Set("question"))

  private val trainset = Vector(ex("q1"), ex("q2"), ex("q3"), ex("q4"))
  private val student  = DynamicPredict(layout = SignatureLayout.parse("question -> answer").toOption.get)

  test("each call bootstraps the query's OWN nearest neighbors as demos (cluster A in, cluster B out)") {
    val lm = new ScriptedLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current

      val compiled = new KNNFewShot[DynamicPredict](k = 2, trainset, embedder).compile(student).toOption.get
      val result   = compiled.apply(ProgramCall(inputs = DynamicValues.record("question" := "query")))

      // The final answer proves the demos steered the call (the LM answers "guided" only when a1 is in its prompt).
      assertEquals(result.toOption.flatMap(_.asString("answer").toOption), Some("guided"))

      // The final task prompt carries the cluster-A demos (q1/q2 with the teacher's answers) and no cluster-B ones.
      val finalPrompt = lm.prompts.last
      assert(finalPrompt.contains("a1") && finalPrompt.contains("a2"), s"cluster-A demos expected:\n$finalPrompt")
      assert(!finalPrompt.contains("a3") && !finalPrompt.contains("a4"), s"cluster-B demos must be absent:\n$finalPrompt")

      // Bootstrap ran the teacher on exactly the 2 neighbors before the final call.
      assertEquals(lm.prompts.size, 3)
    }
  }

  test("a different query draws demos from the other cluster (the demo set is per-call, not fixed)") {
    val embedderB = Embedder.fromFunction("clusters-b") { texts =>
      texts.map {
        case "question: q1"    => Vector(1.0f, 0.0f)
        case "question: q2"    => Vector(0.9f, 0.1f)
        case "question: q3"    => Vector(0.0f, 1.0f)
        case "question: q4"    => Vector(0.1f, 0.9f)
        case "question: query" => Vector(0.05f, 1.0f) // near cluster B this time
        case other             => throw new IllegalArgumentException(s"unmapped text: '$other'")
      }
    }
    val lm = new ScriptedLm
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(lm), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val compiled = new KNNFewShot[DynamicPredict](k = 2, trainset, embedderB).compile(student).toOption.get
      val _        = compiled.apply(ProgramCall(inputs = DynamicValues.record("question" := "query")))

      val finalPrompt = lm.prompts.last
      assert(finalPrompt.contains("a3") && finalPrompt.contains("a4"), s"cluster-B demos expected:\n$finalPrompt")
      assert(!finalPrompt.contains("a1") && !finalPrompt.contains("a2"), s"cluster-A demos must be absent:\n$finalPrompt")
    }
  }
