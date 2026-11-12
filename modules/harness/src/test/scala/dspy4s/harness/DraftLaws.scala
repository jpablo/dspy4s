package dspy4s.harness

import dspy4s.core.contracts.{History, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.guest.{Access, Mode}
import dspy4s.harness.kernel.Entry
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import dspy4s.lm.contracts.LanguageModel
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

import Models.*

/** The laws of the drafts of the policy: the text that the model writes now, for the live block of a client
  * (CLI_SCREEN.md, 13).
  */
class DraftLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val leaf  = Signature.derived[PolicyInput, PolicyStep]("policy", Harness.policyInstructions)
  private val input = PolicyInput("FAKE-TASK", "FAKE-API", "FAKE-STATUS", "FAKE-OBSERVATION", History.empty)
  private val none  = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)

  /** The step that the policy parses from the reply of `model`: the oracle of `Draft.text`. */
  private def stepOf(model: LanguageModel): PolicyStep =
    withModel(model) {
      Predict(leaf, name = Some("policy"))(input)(using PolicyAdapter.around(summon[RuntimeContext]))
        .fold(error => fail(error.message), _.output)
    }

  // ── Replies ──

  private val word = Gen.oneOf("the", "file", "has", "42", "lines.", "Done", "FAKE", "a", "test", "passes:", "x")
  private val line = Gen.choose(1, 6).flatMap(n => Gen.listOfN(n, word)).map(_.mkString(" "))
  private val text = Gen.choose(1, 4).flatMap(n => Gen.listOfN(n, line)).map(_.mkString("\n"))
  private val code = Gen.oneOf("val y = 41", "println(y + 1)", "exec(\"ls\")", "val z = 1\nprintln(z)")

  /** A reply of a model with no native reasoning, in each form that the policy parses (`PolicyAdapter.values`). */
  private val reply: Gen[String] =
    for
      t     <- text
      c     <- code
      after <- Gen.oneOf(Gen.const(""), text)
      form  <- Gen.choose(0, 4)
    yield form match
      case 0 => policy(t, Some(c))
      case 1 => policy(t, None)
      case 2 => s"$t\n\n[[ ## code ## ]]\n$c\n\n[[ ## completed ## ]]"
      case 3 => t
      case _ => s"$t\n\n```scala\n$c\n```\n\n$after".strip

  /** True when `shown` has a part of a marker, of a fence or of a code of `code`. */
  private def leaks(shown: String): Boolean =
    shown.contains("[") || shown.contains("#") || shown.contains("`") ||
      Vector("val ", "println", "exec(").exists(shown.contains)

  // ── Draft ──

  property("Draft: the text of a whole reply is the text of the step that the policy parses from it") {
    forAllNoShrink(reply) { whole =>
      assertEquals(Draft("", whole).text, stepOf(ScriptLm(whole)).text, whole)
    }
  }

  test("Draft: the text of a whole reply of a model with native reasoning is the text of its step") {
    val whole = thinking("The file has 42 lines.\nDone.", Some("println(1)"))
    assertEquals(Draft("a native fake thought", whole).text, stepOf(ThinkingLm("a native fake thought", whole)).text)
    assertEquals(Draft("a native fake thought", whole).text, "The file has 42 lines.\nDone.")
  }

  property("Draft: no part of a reply shows a part of a marker, of a fence or of the code") {
    val prefixes = reply.flatMap(whole => Gen.choose(0, whole.length).map(whole.take))
    forAllNoShrink(prefixes) { part =>
      val shown = Draft("", part).text
      assert(!leaks(shown), s"$part\n---\n$shown")
    }
  }

  property("Draft: the text of a part of a reply is a part of the text of the whole reply") {
    // The live block grows: a text that shows does not change later, apart from the white space at its end.
    val pairs = reply.flatMap(whole => Gen.choose(0, whole.length).map(i => (whole, whole.take(i))))
    forAllNoShrink(pairs) { (whole, part) =>
      val shown = Draft("", part).text
      assert(Draft("", whole).text.startsWith(shown), s"$part\n---\n$shown")
    }
  }

  test("Draft: the reasoning is the native reasoning, else the section of reasoning of the reply") {
    assertEquals(
      Draft("a native fake thought", policy("FAKE", None, "a fake thought")).thought,
      "a native fake thought"
    )
    assertEquals(Draft("", policy("FAKE", None, "a fake thought")).thought, "a fake thought")
    assertEquals(Draft("", "[[ ## reasoning ## ]]\nthe first part of a fa").thought, "the first part of a fa")
    assertEquals(Draft("", "FAKE").thought, "")
  }

  test("Draft: the reply ends at the marker of the end, and a fence that does not end hides the rest") {
    assertEquals(Draft("", "[[ ## text ## ]]\nDone.\n\n[[ ## completed ## ]]\nmore text").text, "Done.")
    assertEquals(Draft("", "Here is the cell:\n\n```scala\nval y = 4").text, "Here is the cell:")
    assertEquals(
      Draft("", "A shell example:\n\n```\nls -la\n```\n\nDone.").text,
      "A shell example:\n\n```\nls -la\n```\n\nDone."
    )
  }

  // ── Drafts ──

  test("Draft: no draft before a call to the policy, and none in a call before its first model call") {
    val drafts = Drafts()
    assertEquals(drafts.current, None)
    drafts.begin(): Unit
    assertEquals(drafts.current, None)
  }

  test("Draft: a model call starts an empty draft, and add puts each part at the end of its reasoning or reply") {
    val drafts = Drafts()
    val call   = drafts.begin()
    drafts.restart(call)
    assertEquals(drafts.current, Some(Draft.empty))
    drafts.add(call, "a fake ", "")
    drafts.add(call, "thought", "The file")
    drafts.add(call, "", " has 42 lines.")
    assertEquals(drafts.current, Some(Draft("a fake thought", "The file has 42 lines.")))
  }

  test("Draft: a new model call of the same call drops the draft of the model call before it") {
    val drafts = Drafts()
    val call   = drafts.begin()
    drafts.restart(call)
    drafts.add(call, "FAKE-FIRST", "FAKE-FIRST")
    drafts.restart(call)
    drafts.add(call, "", "FAKE-SECOND")
    assertEquals(drafts.current, Some(Draft("", "FAKE-SECOND")))
  }

  test("Draft: end removes the draft, and a late model call or token of the ended call shows nothing") {
    val drafts = Drafts()
    val call   = drafts.begin()
    drafts.restart(call)
    drafts.add(call, "", "FAKE")
    drafts.end(call)
    assertEquals(drafts.current, None)
    drafts.restart(call)
    drafts.add(call, "", "FAKE-LATE")
    assertEquals(drafts.current, None)
  }

  test("Draft: a call that a later call replaced changes nothing, also before the end of the later call") {
    val drafts = Drafts()
    val first  = drafts.begin()
    drafts.restart(first)
    val second = drafts.begin()
    assertEquals(drafts.current, None)
    drafts.restart(second)
    drafts.add(first, "", "FAKE-LATE")
    drafts.restart(first)
    drafts.add(second, "", "FAKE-SECOND")
    assertEquals(drafts.current, Some(Draft("", "FAKE-SECOND")))
    drafts.end(first)
    assertEquals(drafts.current, Some(Draft("", "FAKE-SECOND")))
  }

  // ── The call to the policy ──

  /** The reply of `callPolicy` of new effects with `model` and `drafts`, after one `StepStarted`. */
  private def policyReply(model: LanguageModel, drafts: Option[Drafts]): Either[String, PolicyReply] =
    val journal = Support.journal()
    Support.open(journal)
    journal.commit(Entry.StepStarted(0)): Unit
    val zooms = ZoomSource()
    withModel(model) {
      val effects = HarnessEffects(
        "FAKE-TASK",
        Harness.apiReference(none),
        Predict(leaf, name = Some("policy")),
        Window,
        journal,
        () => ScalaRepl(none, Bindings(JournalTurn(journal, () => 0L, zooms))),
        Recorder(journal),
        SnapshotStore(),
        HarnessLimits(),
        () => Budget(5),
        None,
        PolicyRunner.Direct,
        zooms,
        MemoryProgress.none,
        drafts
      )
      try effects.callPolicy(mustAnswer = false)
      finally effects.close()
    }

  test("Draft: callPolicy with drafts shows the reasoning and the text while the model writes, and none after") {
    val drafts = Drafts()
    val seen   = ConcurrentLinkedQueue[Option[Draft]]()
    val step   = thinking("The file has 42 lines.", Some("println(1)"))
    val model  = StreamLm(_ => Right(step), "a native fake thought", 5, _ => seen.add(drafts.current): Unit)
    val result = policyReply(model, Some(drafts))
    assertEquals(result.map(_.text), Right("The file has 42 lines."))
    assertEquals(drafts.current, None)
    val shown = seen.asScala.toVector.flatten
    assert(shown.exists(_.thought == "a native fake thought"), shown.toString)
    assert(shown.exists(d => d.text.nonEmpty && d.text != "The file has 42 lines."), shown.toString)
    assert(shown.forall(d => "The file has 42 lines.".startsWith(d.text)), shown.toString)
    assertEquals(model.streams.get, 1)
  }

  test(
    "Draft: callPolicy gives the same step with drafts and with none, also with the call of the fallback adapter"
  ) {
    // Two `scala` fences fit no rule of `PolicyAdapter.values`, so the JSON fallback makes a second model call.
    val first                                        = "FAKE-FIRST\n\n```scala\nval y = 1\n```\n\n```scala\nval z = 2\n```"
    val second                                       = """{"reasoning": "a fake thought", "text": "FAKE-SECOND", "code": null}"""
    def model(gate: Int => Unit = _ => ()): StreamLm =
      val n = AtomicInteger(0)
      StreamLm(_ => Right(if n.getAndIncrement() == 0 then first else second), size = 6, gate = gate)
    val plain  = model()
    val alone  = policyReply(plain, None)
    val drafts = Drafts()
    val seen   = ConcurrentLinkedQueue[Option[Draft]]()
    val shown  = model(_ => seen.add(drafts.current): Unit)
    assertEquals(policyReply(shown, Some(drafts)), alone)
    assertEquals(alone.map(_.text), Right("FAKE-SECOND"))
    assertEquals((plain.calls.get, plain.streams.get), (2, 0))
    assertEquals((shown.calls.get, shown.streams.get), (0, 2))
    // The draft of the second model call has no text of the first one.
    val last = seen.asScala.toVector.flatten.dropWhile(d => !d.reply.startsWith("{")).drop(1)
    assert(last.nonEmpty && last.forall(!_.reply.contains("FAKE-FIRST")), seen.toString)
  }

  test("Draft: callPolicy with drafts and a model that does not stream gives the same step and no draft") {
    val drafts = Drafts()
    val step   = policy("FAKE-DONE", None)
    assertEquals(policyReply(ScriptLm(step), Some(drafts)), policyReply(ScriptLm(step), None))
    assertEquals(drafts.current, None)
  }
