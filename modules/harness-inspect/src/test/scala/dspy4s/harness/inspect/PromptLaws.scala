package dspy4s.harness.inspect

import dspy4s.core.contracts.{DspyError, DynamicValues, History, LmTransportError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{Harness, HarnessEnv, Models, PolicyAdapter, PolicyInput}
import dspy4s.harness.host.HostSupport.withRoot
import dspy4s.harness.host.{ExchangeFile, SessionFile}
import dspy4s.harness.kernel.*
import dspy4s.lm.contracts.LmMode
import dspy4s.lm.providers.{HttpResponse, HttpStreamResponse, HttpTransport, OpenAiClient, OpenAiLanguageModel}
import dspy4s.programs.strategies.Predict
import zio.blocks.schema.json.Json

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import InspectorLaws.{Markup, Start}

/** The laws of the prompt of a session (docs/code-harness/OBSERVABILITY.md, 5.8 and L24). */
class PromptLaws extends munit.FunSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── Helpers ──

  private val api = Harness.apiReference(HarnessEnv.taskMode)

  private val input =
    PolicyInput("FAKE-TASK\nFAKE-TASK-LINE-2", api, "FAKE-MEMORY", "FAKE-STATUS", "FAKE-OBSERVATION", History.empty)

  /** A transport that keeps the body of each request, and gives a reply of the policy with no code. */
  private final class Kept extends HttpTransport:
    var bodies: Vector[String] = Vector.empty

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      bodies = bodies :+ body
      val reply = Json.Object("choices" -> Json.Array(Json.Object(
        "index"   -> Json.Number(0),
        "message" -> Json.Object(
          "role"    -> Json.String("assistant"),
          "content" -> Json.String(Models.policy("FAKE-DONE", None))
        ),
        "finish_reason" -> Json.String("stop")
      )))
      Right(HttpResponse(200, Map.empty, reply.print))

    override def streamSse(url: String, headers: Map[String, String], body: String)
        : Either[DspyError, HttpStreamResponse] = Left(LmTransportError("FAKE: no stream"))

  /** The exact body of one call to the policy of the harness with `input`, as the OpenAI client sends it. */
  private def bodyOf(input: PolicyInput, effort: Option[String] = None): String =
    val kept    = Kept()
    val client  = OpenAiClient(apiKey = "", baseUrl = "http://test.invalid/v1", transport = kept)
    val options = DynamicValues.record(effort.toVector.map(e => "reasoning_effort" -> DynamicValues.string(e))*)
    val model   = OpenAiLanguageModel("fake-model", LmMode.Chat, client, defaultOptions = options)
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model))) {
      given RuntimeContext = PolicyAdapter.around(RuntimeEnvironment.current)
      val leaf             = Predict(Harness.policySignature(Harness.policyInstructions), name = Some("policy"))
      leaf(input).fold(e => fail(e.message), identity): Unit
    }
    kept.bodies.headOption.getOrElse(fail("no request"))

  private def exchange(id: String, after: Long, body: String, predictor: String = "policy"): Exchange =
    Exchange(
      0,
      Json.Object(
        "kind"      -> Json.String("request"),
        "id"        -> Json.String(id),
        "after"     -> Json.Number(after),
        "predictor" -> Json.String(predictor),
        "at"        -> Json.Number(Start),
        "url"       -> Json.String("http://test.invalid/v1/chat/completions"),
        "stream"    -> Json.Boolean(false),
        "body"      -> Json.String(body)
      ),
      None
    )

  private def promptOf(body: String): Prompt = Prompt.of(exchange("x", 3L, body)).getOrElse(fail(s"no prompt: $body"))

  // ── The parts ──

  test("Prompt L24: a request of the policy has its instructions, its task and its API, and no memory") {
    val prompt = promptOf(bodyOf(input))
    assertEquals(prompt.fields, Vector("task" -> input.task, "apiReference" -> api))
    assert(prompt.inSystem, prompt.toString)
    assert(prompt.instructions.startsWith("Your input fields are:"), prompt.instructions)
    assert(prompt.instructions.contains(Harness.policyInstructions), prompt.instructions)
    assert(prompt.instructions.endsWith(PolicyAdapter.FixedHeader), prompt.instructions)
    Vector("FAKE-TASK", "FAKE-MEMORY", "FAKE-STATUS").foreach(fake =>
      assert(!prompt.instructions.contains(fake), fake)
    )
    assertEquals(prompt.option("model"), Some(Json.String("fake-model")))
    assertEquals(prompt.url, "http://test.invalid/v1/chat/completions")
  }

  test("Prompt L24: only the instructions, the task, the API, the URL and the options make a change") {
    val prompt = promptOf(bodyOf(input))
    // The memory, the status and the observation change at each turn or step, so they make no change.
    val turn = input.copy(memory = "FAKE-OTHER-MEMORY", status = "FAKE-OTHER-STATUS", observation = "FAKE-OTHER")
    assertEquals(promptOf(bodyOf(turn)).changes(prompt), Vector.empty)
    assertEquals(promptOf(bodyOf(input.copy(memory = ""))).changes(prompt), Vector.empty)
    assertEquals(promptOf(bodyOf(input.copy(task = "FAKE-NEW-TASK"))).changes(prompt), Vector("task"))
    val effort = promptOf(bodyOf(input, effort = Some("low")))
    assertEquals(effort.changes(prompt), Vector("reasoning_effort"))
    assertEquals(prompt.changes(effort), Vector("reasoning_effort"))
  }

  test("Prompt L24: fixed fields in the last user message come from it, and the instructions are the system message") {
    val system = "Your input fields are:\n[[ ## task ## ]]\n{task}\n\n[[ ## completed ## ]]\nFAKE-OBJECTIVE"
    val user   = "[[ ## task ## ]]\nFAKE-TASK\n\n[[ ## apiReference ## ]]\nFAKE-API\n\n[[ ## memory ## ]]\nFAKE-MEMORY" +
      "\n\n[[ ## status ## ]]\nFAKE-STATUS"
    val body = Json.Object(
      "model"    -> Json.String("fake-model"),
      "messages" -> Json.Array(
        Json.Object("role" -> Json.String("system"), "content"    -> Json.String(system)),
        Json.Object("role" -> Json.String("user"), "content"      -> Json.String("FAKE-EARLIER")),
        Json.Object("role" -> Json.String("assistant"), "content" -> Json.String("FAKE-STEP")),
        Json.Object("role" -> Json.String("user"), "content"      -> Json.String(user))
      )
    ).print
    val prompt = promptOf(body)
    assertEquals(prompt.fields, Vector("task" -> "FAKE-TASK", "apiReference" -> "FAKE-API"))
    assertEquals(prompt.instructions, system)
    assert(!prompt.inSystem, prompt.toString)
    assertEquals(Prompt.of(exchange("x", 3L, "FAKE-NOT-JSON")), None)
    assertEquals(Prompt.of(exchange("x", 3L, """{"model":"fake-model"}""")), None)
  }

  // ── The API ──

  test("Prompt L24: the calls of the API reference of a mode have their signatures and their comments") {
    val calls = promptOf(bodyOf(input)).calls
    assert(calls.size >= 10, calls.toString)
    val flat = api.replaceAll("\\s+", " ")
    calls.foreach { c =>
      assert(c.signature.startsWith(s"def ${c.name}"), c.toString)
      assert(flat.contains(c.signature + " ="), c.toString)
      assert(c.doc.nonEmpty && !c.doc.contains("/**") && !c.doc.contains("*/"), c.toString)
    }
    Vector("println", "read", "store", "zoom").foreach(name => assert(calls.exists(_.name == name), name))
  }

  test("Prompt L24: a signature ends at the = of its body, also over lines and after a default value") {
    val source = """trait Files:
        |  def read(path: String): String
        |
        |@assumeSafe object api:
        |  /** Prints `value`. A second sentence.
        |    */
        |  def println(value: Any): Unit = scala.Console.println(value)
        |
        |  def spawn(command: String, report: Boolean = false)(using f: Files^): String = f.spawn(command, report)
        |
        |  /** Many lines. */
        |  def input[A](name: String)(
        |      using d: Data[A]
        |  ): A =
        |    d.decode(name) match
        |      case Right(value) => value
        |      case Left(error)  => throw error
        |
        |  def pick(f: Int => Int, s: String = "a=b"): Int => Int = f
        |
        |  private def hidden(): Unit = ()
        |
        |object other:
        |  def outside(): Unit = ()
        |""".stripMargin
    assertEquals(
      Prompt.calls(source),
      Vector(
        ApiCall("println", "def println(value: Any): Unit", "Prints `value`. A second sentence."),
        ApiCall("spawn", "def spawn(command: String, report: Boolean = false)(using f: Files^): String", ""),
        ApiCall("input", "def input[A](name: String)( using d: Data[A] ): A", "Many lines."),
        ApiCall("pick", """def pick(f: Int => Int, s: String = "a=b"): Int => Int""", "")
      )
    )
    assertEquals(Prompt.calls(source).head.about, "Prints `value`.")
    assertEquals(Prompt.calls("trait Files:\n  def read(path: String): String"), Vector.empty)
  }

  // ── The new prompts ──

  test("Prompt L24: a call has a new prompt when it is the first, or when its prompt differs from the call before") {
    val calls = Vector(
      exchange("x1", 3L, bodyOf(input)),
      exchange("x2", 8L, bodyOf(input.copy(memory = "FAKE-TURN-2"))),
      exchange("x3", 12L, "FAKE-NOT-JSON"),
      exchange("x4", 15L, bodyOf(input.copy(task = "FAKE-TASK-2"))),
      exchange("x5", 19L, bodyOf(input.copy(task = "FAKE-TASK-2", memory = "FAKE-TURN-4"))),
      exchange("x6", 23L, bodyOf(input.copy(task = "FAKE-TASK-2"), effort = Some("low")))
    )
    def ids(found: Vector[(Exchange, Prompt, Option[(Exchange, Prompt)])]) =
      found.map((x, _, before) => (x.id, before.map(_._1.id)))
    assertEquals(
      ids(Prompt.starts(calls, None)),
      Vector(("x1", None), ("x4", Some("x2")), ("x6", Some("x5")))
    )
    // With the call before them, the first calls have a new prompt only when their prompt changed.
    assertEquals(ids(Prompt.starts(calls.drop(1), Some(calls.head))), Vector(("x4", Some("x2")), ("x6", Some("x5"))))
    assertEquals(ids(Prompt.starts(calls.drop(3), Some(calls(1)))), Vector(("x4", Some("x2")), ("x6", Some("x5"))))
    assertEquals(ids(Prompt.starts(calls.drop(4), Some(calls(3)))), Vector(("x6", Some("x5"))))
    assert(!Prompt.policy(exchange("x7", 3L, "{}", predictor = "compress")))
  }

  // ── The view ──

  test("Prompt L24: the row of a prompt has the tabs of its parts, opens on the instructions, and escapes each text") {
    val prompt = promptOf(bodyOf(input.copy(task = s"FAKE-TASK $Markup")))
    val html   = PromptView.html(SessionId("fake-session-1"), PromptMark(exchange("x1", 3L, ""), prompt, None, None))
    assert(html.contains("SYSTEM") && html.contains("<b>The prompt</b>"), html.take(600))
    Vector("instructions", "field-task", "field-apiReference", "settings").foreach(tab =>
      assert(html.contains(s"""data-ptab="$tab""""), tab)
    )
    assert(!html.contains("data-ptab=\"changes\""), html.take(600))
    assert(html.contains("""data-pview="instructions">"""), html.take(600))
    assert(html.contains("""data-pview="settings" hidden>"""), html.take(600))
    assert(html.contains(s"API (${prompt.calls.size})"), html.take(600))
    // The row of the prompt links to no request: the HTTP row of the call links to it (L29).
    val head = html.take(html.indexOf("</summary>"))
    assert(!head.contains("href="), head)
    assert(!html.contains(Markup), html)
    assert(html.contains(Html.escape(Markup)), html)
    // A field of another name shows its text, escaped.
    val other = Prompt("FAKE-INSTRUCTIONS", Vector("other" -> Markup), Vector.empty, "http://test.invalid", true)
    val box   = PromptView.html(SessionId("fake-session-1"), PromptMark(exchange("x1", 3L, ""), other, None, None))
    assert(!box.contains(Markup) && box.contains(Html.escape(Markup)), box)
  }

  test("Prompt L24: the Task tab shows the task as Markdown, and a button shows its raw text") {
    val prompt = promptOf(bodyOf(input.copy(task = "## Why\n\n- **A** `x`\n\n```scala\nval y = 1\n```")))
    val task   = prompt.field("task").getOrElse(fail(s"no task in $prompt"))
    val html   = PromptView.html(SessionId("fake-session-1"), PromptMark(exchange("x1", 3L, ""), prompt, None, None))
    val view   = """(?s)<div class="pview" data-pview="field-task" hidden>(.*?)</div><div class="pview" """.r
      .findFirstMatchIn(html).map(_.group(1)).getOrElse(fail(s"no task view in ${html.take(600)}"))
    assert(
      view.contains(
        """<button data-form="markdown" class="on">Markdown</button><button data-form="raw">Raw</button>"""
      ),
      view
    )
    assert(view.contains(s"""<div class="md ptask" data-form-view="markdown">${Markdown.html(task)}</div>"""), view)
    assert(view.contains(s"""<pre class="ptext" data-form-view="raw" hidden>${Html.escape(task)}</pre>"""), view)
    assert(view.contains("<h4>Why</h4>") && view.contains("""<span class="t-keyword">val</span>"""), view)
    // The page has the handler of the two buttons.
    assert(Html.page("FAKE-TITLE", "FAKE-NONCE", "").contains("closest('button[data-form]')"))
  }

  test("Prompt L27: the Instructions tab shows the parts of a real request, and a button shows its raw text") {
    val prompt = promptOf(bodyOf(input))
    val parts  = Instructions.parse(prompt.instructions).getOrElse(fail(s"no parts in ${prompt.instructions}"))
    assertEquals(parts.inputs.map(_.name), Vector("task", "apiReference", "memory", "status", "observation", "history"))
    assertEquals(parts.inputs.last.kind, "History")
    // With no effort of the model, the signature has a reasoning field too.
    assertEquals(parts.outputs.map(_.name).takeRight(2), Vector("text", "code"))
    assertEquals(parts.markers.map(_._1), (parts.inputs ++ parts.outputs).map(_.name) :+ "completed")
    assert(parts.objective.startsWith(Harness.policyInstructions.trim.linesIterator.next().trim), parts.objective)
    assertEquals(parts.rest, PolicyAdapter.FixedHeader)
    val html = PromptView.html(SessionId("fake-session-1"), PromptMark(exchange("x1", 3L, ""), prompt, None, None))
    val view = """(?s)<div class="pview" data-pview="instructions">(.*?)</div><div class="pview" """.r
      .findFirstMatchIn(html).map(_.group(1)).getOrElse(fail(s"no instructions in ${html.take(600)}"))
    assert(view.contains("""<button data-form="parts" class="on">Parts</button><button data-form="raw">Raw</button>"""))
    assert(view.contains(s"""<div class="pinstr" data-form-view="parts">${Instructions.html(parts)}</div>"""), view)
    assert(view.contains("""<pre class="ptext" data-form-view="raw" hidden>"""), view)
    assert(view.contains("""<span class="mk">[[ ## task ## ]]</span>"""), view)
    // Instructions with no parts show their text only.
    val other = Prompt("FAKE-INSTRUCTIONS", Vector.empty, Vector.empty, "http://test.invalid", true)
    val plain = PromptView.html(SessionId("fake-session-1"), PromptMark(exchange("x1", 3L, ""), other, None, None))
    assert(plain.contains("""line.</p><pre class="ptext">FAKE-INSTRUCTIONS</pre>"""), plain)
    assert(!plain.contains("data-form="), plain)
  }

  test("Prompt L27: the system message on the page of an exchange shows its parts and its fixed fields") {
    val body   = bodyOf(input.copy(task = s"FAKE-TASK **$Markup**"))
    val system = Json.parse(body).toOption.flatMap(Exchanges.field(_, "messages")).flatMap(_.elements.headOption)
      .map(m => Chat.contentOf(m).mkString("\n")).getOrElse(fail(s"no system message in $body"))
    val (head, fixed) = Instructions.split(system)
    val parts         = Instructions.parse(head).getOrElse(fail(s"no parts in $head"))
    assertEquals(fixed.map(_._1), Vector("task", "apiReference", "memory"))
    val html = Chat.html(exchange("x1", 3L, body))
    // The head of the card is the head of a user card: the role with its dot, the count of the lines, and the buttons.
    val lines = Story.plural(system.linesIterator.size.toLong, "line")
    assert(
      html.contains(
        s"""<details class="msg role-system" data-forms><summary><span class="role">system</span>""" +
          s"""<span class="lines"> · $lines</span>${Html.forms("parts" -> "Parts", "raw" -> "Raw")}</summary>""" +
          """<div class="parts" data-form-view="parts">"""
      ),
      html.take(600)
    )
    assert(html.contains(Instructions.html(parts)), html.take(600))
    assert(html.contains("<h3>The fixed fields</h3>"), html.take(600))
    fixed.foreach((name, value) => assert(html.contains(Instructions.fieldHtml(name, value)), name))
    assert(html.contains(s"""<div class="text" data-form-view="raw" hidden>${Html.escape(system)}</div>"""), html)
    assert(!html.contains(Markup), html)
    // The buttons of a box with data-forms switch its views, and a button in the head of a card does not close it.
    val page = Html.page("FAKE-TITLE", "FAKE-NONCE", "")
    assert(page.contains("form.closest('[data-forms], .pview')") &&
      page.contains("if (form) {\n    ev.preventDefault();"))
    // A system message with no parts keeps its text.
    val bare = """{"messages":[{"role":"system","content":"FAKE-SYSTEM"}]}"""
    assert(
      Chat.html(exchange("x2", 3L, bare)).contains(
        """<details class="msg role-system"><summary><span class="role">system</span><span class="lines"> · 1 line</span>""" +
          """</summary><div class="text">FAKE-SYSTEM</div>"""
      )
    )
  }

  test("Prompt L28: the user message and the reply on the page of an exchange show their fields") {
    val body = bodyOf(input.copy(observation = s"FAKE-OBSERVATION $Markup"))
    val last = Json.parse(body).toOption.flatMap(Exchanges.field(_, "messages")).flatMap(_.elements.lastOption)
      .getOrElse(fail(s"no message in $body"))
    val user   = Chat.contentOf(last).mkString("\n")
    val fields = Framed.parse(user).getOrElse(fail(s"no fields in $user"))
    assertEquals(fields.fields.map(_._1), Vector("status", "observation"))
    assert(fields.rest.startsWith("Respond with the corresponding output fields"), fields.rest)
    val answer     = Models.policy("FAKE-DONE **ok**", Some("val n = 1"))
    val completion = Json.Object("choices" -> Json.Array(Json.Object(
      "index"         -> Json.Number(0),
      "message"       -> Json.Object("role" -> Json.String("assistant"), "content" -> Json.String(answer)),
      "finish_reason" -> Json.String("stop")
    )))
    val reply = Json.Object(
      "kind"   -> Json.String("reply"),
      "id"     -> Json.String("x1"),
      "at"     -> Json.Number(Start + 1000L),
      "status" -> Json.Number(200),
      "body"   -> Json.String(completion.print),
      "end"    -> Json.String("done")
    )
    val html                                              = Chat.html(exchange("x1", 3L, body).copy(reply = Some(reply)))
    val buttons                                           = Html.forms("fields" -> "Fields", "raw" -> "Raw")
    def framed(role: String, text: String, parts: Framed) =
      s"""<div class="msg role-$role" data-forms><div class="mhead"><div class="role">$role</div>$buttons</div>""" +
        s"""<div class="framed" data-form-view="fields">${Framed.html(parts)}</div>""" +
        s"""<div class="text" data-form-view="raw" hidden>${Html.escape(text)}</div></div>"""
    assert(html.contains(framed("user", user, fields)), html)
    val parts = Framed.parse(answer).getOrElse(fail(s"no fields in $answer"))
    assertEquals(parts.fields.map(_._1), Vector("reasoning", "text", "code"))
    assert(parts.completed, parts.toString)
    assert(html.contains(framed("assistant", answer, parts)), html)
    assert(html.contains("<strong>ok</strong>") && html.contains("""<span class="t-keyword">val</span>"""), html)
    assert(!html.contains(Markup), html)
  }

  test("Prompt L26: the API tab gives the Scala source and each signature with the colors of Scala") {
    val tokens = Highlight.source(api)
    val prompt = promptOf(bodyOf(input))
    prompt.calls.foreach(c => assert(tokens.contains(Token(Tint.Def, c.name)), s"${c.name} is not a definition"))
    assert(tokens.exists(t => t.tint == Tint.Comment && t.text.startsWith("/**")), tokens.take(40).toString)
    val html   = PromptView.html(SessionId("fake-session-1"), PromptMark(exchange("x1", 3L, ""), prompt, None, None))
    val source = """(?s)<summary>The Scala source</summary><pre class="ptext">(.*?)</pre></details>""".r
      .findFirstMatchIn(html).map(_.group(1)).getOrElse(fail(s"no source in ${html.take(600)}"))
    assertEquals(source, Highlight.sourceHtml(api))
    assert(source.contains("""<span class="t-keyword">def</span> <span class="t-def">read</span>("""), source)
    assert(html.contains("""<pre><span class="t-keyword">def</span> <span class="t-def">read</span>("""), html)
  }

  test("Prompt L24: the row of a changed prompt names the parts, opens on their diff, and links to the two calls") {
    val before = promptOf(bodyOf(input))
    val after  = promptOf(bodyOf(input.copy(task = "FAKE-TASK\nFAKE-NEW-LINE")))
    val mark   = PromptMark(exchange("x4", 15L, ""), after, Some((exchange("x2", 8L, ""), before)), None)
    val html   = PromptView.html(SessionId("fake-session-1"), mark)
    assert(html.contains("<b>The prompt changed:</b> task"), html.take(600))
    assert(html.contains("""data-pview="changes">"""), html)
    assert(html.contains("""data-pview="instructions" hidden>"""), html)
    assert(html.contains("""<span class="d del">- FAKE-TASK-LINE-2</span>"""), html)
    assert(html.contains("""<span class="d add">+ FAKE-NEW-LINE</span>"""), html)
    assert(html.contains("""href="/s/fake-session-1/x/x4?against=x2""""), html)
    // The text names the earlier call by its number, as its HTTP row does (L29).
    assert(html.contains("""Against the prompt of <a href="/s/fake-session-1/x/x2">exchange 1</a>."""), html)
  }

  // ── The page ──

  test("Prompt L24: the page puts the prompt before the first turn, with the Activated of the call in its settings") {
    withRoot { root =>
      val session = SessionId("fake-session-1")
      InspectorLaws.journal(root, session, InspectorLaws.turn): Unit
      Files.createDirectories(SessionFile.folderOf(root))
      // The second call has the same prompt, with another memory.
      val lines = Vector(
        exchange("x1", 3L, bodyOf(input)),
        exchange("x2", 8L, bodyOf(input.copy(memory = "FAKE-STEP-2")))
      ).map(_.request.print + "\n")
      Files.writeString(ExchangeFile.pathOf(root, session), lines.mkString, UTF_8)
      val store = Store(root)
      assertEquals(store.activation(session, 3L).map(_.seq), Some(0L))
      assertEquals(store.activation(session, -1L), None)
      val inspector = Inspector.start(root, 0).fold(e => fail(e), identity)
      try
        val client            = HttpClient.newHttpClient()
        def get(path: String) =
          client.send(HttpRequest.newBuilder(URI.create(inspector.url + path)).GET().build(), BodyHandlers.ofString())
            .body()
        val page = get(s"s/${session.value}")
        // The prompt comes after the note of `Activated` and before the turn, with the key of the turn.
        val note   = page.indexOf("""<section class="item other" data-key="0">""")
        val prompt = page.indexOf("""<section class="item prompt" data-key="1" data-prompt="x1">""")
        val turn   = page.indexOf("""<section class="item turn" data-key="1" """)
        assert(note >= 0 && note < prompt && prompt < turn, page)
        assert(page.contains("The activation: #0"), page)
        assert(!page.contains("data-prompt=\"x2\""), page)
        // The follow gets the same item. After the first call, the follow compares with the call before.
        assert(get(s"s/${session.value}/story?from=1&skipped=0").contains("data-prompt=\"x1\""))
        assert(!get(s"s/${session.value}/story?from=4&skipped=0").contains("data-prompt="))
        // A page that shows the entries from 4 on starts with the prompt of its first call.
        assert(get(s"s/${session.value}/story?from=4&skipped=4").contains("data-prompt=\"x2\""))
      finally inspector.close()
    }
  }
