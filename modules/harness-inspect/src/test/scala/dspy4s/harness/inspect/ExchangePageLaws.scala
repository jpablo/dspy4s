package dspy4s.harness.inspect

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.host.{ExchangeFile, SessionFile}
import dspy4s.harness.host.HostSupport.withRoot
import dspy4s.harness.kernel.SessionId
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import ExchangePageLaws.*

/** The laws of the page of one exchange (docs/code-harness/OBSERVABILITY.md, 5.4 and 6: L16 and the views). */
class ExchangePageLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val client = HttpClient.newHttpClient()

  private def get(inspector: Inspector, path: String): (Int, String) =
    val response = client.send(
      HttpRequest.newBuilder(URI.create(inspector.url + path.stripPrefix("/"))).GET().build(),
      BodyHandlers.ofString()
    )
    (response.statusCode(), response.body())

  private def serving[A](root: Path)(body: Inspector => A): A =
    val inspector = Inspector.start(root, 0).fold(e => fail(e), identity)
    try body(inspector)
    finally inspector.close()

  // ── L16: the diff ──

  property("Inspect L16: the diff of a and b, applied to a, gives b") {
    forAllNoShrink(lines, lines)((a, b) => assertEquals(LineDiff.patch(a, LineDiff.diff(a, b)), Some(b)))
  }

  property("Inspect L16: the diff of a with a has no change") {
    forAllNoShrink(lines)(a => assertEquals(LineDiff.diff(a, a), a.map(Edit.Keep(_))))
  }

  property("Inspect L16: the kept and removed lines are a in order, the kept and added lines are b in order") {
    forAllNoShrink(lines, lines) { (a, b) =>
      val edits = LineDiff.diff(a, b)
      assertEquals(edits.collect { case Edit.Keep(l) => l; case Edit.Remove(l) => l }, a)
      assertEquals(edits.collect { case Edit.Keep(l) => l; case Edit.Add(l) => l }, b)
    }
  }

  property("Inspect L16: the diff keeps as many lines as the longest common subsequence") {
    forAllNoShrink(lines, lines) { (a, b) =>
      assertEquals(LineDiff.diff(a, b).count { case Edit.Keep(_) => true; case _ => false }, lcs(a, b))
    }
  }

  test("Inspect L16: two texts past the limit of the table still give a diff that gives b") {
    val a = Vector.tabulate(3000)(i => s"a $i")
    val b = Vector.tabulate(3000)(i => if i % 7 == 0 then s"b $i" else s"a $i")
    assertEquals(LineDiff.patch(a, LineDiff.diff(a, b)), Some(b))
  }

  property("Inspect L16: each change is in one hunk, with at most the context of kept lines around it") {
    forAllNoShrink(lines, lines, Gen.choose(0, 4)) { (a, b, context) =>
      val edits  = LineDiff.diff(a, b)
      val hunks  = LineDiff.hunks(edits, context)
      val change = (e: Edit) =>
        e match
          case Edit.Keep(_) => false;
          case _            => true
      assertEquals(hunks.flatMap(_.edits).count(change), edits.count(change))
      hunks.foreach { hunk =>
        assert(hunk.edits.exists(change), hunk.toString)
        assert(hunk.edits.takeWhile(e => !change(e)).size <= context, hunk.toString)
        assert(hunk.edits.reverse.takeWhile(e => !change(e)).size <= context, hunk.toString)
      }
      // The numbers of the lines of each hunk point at its lines in a and in b.
      hunks.foreach { hunk =>
        val fromA = hunk.edits.collect { case Edit.Keep(l) => l; case Edit.Remove(l) => l }
        val fromB = hunk.edits.collect { case Edit.Keep(l) => l; case Edit.Add(l) => l }
        assertEquals(a.slice(hunk.fromA - 1, hunk.fromA - 1 + fromA.size), fromA)
        assertEquals(b.slice(hunk.fromB - 1, hunk.fromB - 1 + fromB.size), fromB)
      }
    }
  }

  // ── The chat view ──

  test("Inspect: the text of a request has a line with the role of each message, then its content") {
    assertEquals(
      Chat.requestLines(chatBody("SYSTEM", "line 1\nline 2")),
      Vector("── system ──", "SYSTEM", "── user ──", "line 1", "line 2")
    )
    assertEquals(Chat.requestLines("""{"prompt":"x"}"""), Vector("{", """  "prompt": "x"""", "}"))
  }

  test("Inspect: the chat view reads a stream as the model does: the text, the reasoning, the finish and the usage") {
    val reading = Chat.read(Exchanges.read(fixtureFile()).head.head)
    assertEquals(reading.output.map(o => (o.text, o.reasoning)), Some(("Hello", "think")))
    assertEquals(reading.finish, Some("stop"))
    assertEquals(reading.usage.map(u => (u.promptTokens, u.completionTokens)), Some((11L, 2L)))
    assertEquals(reading.error, None)
  }

  test("Inspect: the chat view reads the tool calls of a stream, and a plain reply") {
    val exchanges = Exchanges.read(fixtureFile()).head
    val call      = Chat.read(exchanges(2))
    assertEquals(call.output.toVector.flatMap(_.toolCalls.map(_.name)), Vector("read"))
    assertEquals(call.finish, Some("tool_calls"))
    val plain = Chat.read(exchanges(1))
    assertEquals(plain.output.map(_.text), Some("S1"))
    assertEquals(plain.usage.map(_.totalTokens), Some(5L))
    assertEquals(Chat.read(exchanges(3)), Reading(None, None, None, Some("The exchange has no reply yet.")))
  }

  // ── The page ──

  test("Inspect: the page of an exchange has its four views, the exact body, and the numbered lines of its reply") {
    withRoot { root =>
      val session = fixture(root)
      serving(root) { inspector =>
        val (status, page) = get(inspector, s"/s/${session.value}/x/x1")
        assertEquals(status, 200)
        Vector("x-chat", "x-request", "x-reply", "x-diff").foreach(view =>
          assert(page.contains(s"""class="x-view $view"""), view)
        )
        assert(page.contains(Html.escape(chatBody("POLICY", "U1"))), page)
        assert(page.contains("""<span class="n">1</span>"""), page)
        assert(page.contains("Hello"), page)
        // The request view and the lines of the reply have the colors of JSON (5.3).
        val request =
          """(?s)<section class="x-view x-request">(.*?)</section>""".r.findFirstMatchIn(page).map(_.group(1))
        assert(request.exists(_.contains("""<span class="t-key">&quot;model&quot;</span>""")), request)
        // The request view has the layout of JsonText: each field on a line of its own, with an indent.
        assert(
          request.exists(_.contains("""{
  <span class="t-key">&quot;model&quot;</span>: """)),
          request
        )
        val reply = """(?s)<section class="x-view x-reply">(.*?)</section>""".r.findFirstMatchIn(page).map(_.group(1))
        assert(reply.exists(_.contains("""<span class="t-key">&quot;choices&quot;</span>""")), reply)
        assertEquals(get(inspector, s"/s/${session.value}/x/fake-none")._1, 404)
      }
    }
  }

  test(
    "Inspect: the diff of an exchange is against the last earlier exchange of its predictor, or the one of against"
  ) {
    withRoot { root =>
      val session = fixture(root)
      serving(root) { inspector =>
        val (_, third) = get(inspector, s"/s/${session.value}/x/x3")
        assert(third.contains("""<span class="d add">+ ── assistant ──</span>"""), third)
        assert(third.contains("""<span class="d add">+ U2</span>"""), third)
        assert(third.contains("""<option value="x1" selected>"""), third)
        val (_, first) = get(inspector, s"/s/${session.value}/x/x1")
        assert(first.contains("No earlier exchange of this predictor."), first)
        val (_, against) = get(inspector, s"/s/${session.value}/x/x3?against=x2")
        assert(against.contains("""<option value="x2" selected>"""), against)
        assert(against.contains("""<span class="d del">- C</span>"""), against)
      }
    }
  }

  test("Inspect: the row of an exchange in the tree links to its page") {
    withRoot { root =>
      val session = fixture(root)
      serving(root) { inspector =>
        val (_, page) = get(inspector, s"/s/${session.value}")
        assert(page.contains(s"""href="/s/${session.value}/x/x1""""), page)
      }
    }
  }

object ExchangePageLaws:

  /** Short texts of a few lines from a small set of words, so two texts share many lines. */
  val lines: Gen[Vector[String]] =
    Gen.choose(0, 14).flatMap(n => Gen.listOfN(n, Gen.oneOf("a", "b", "c", "d", "e", ""))).map(_.toVector)

  /** The length of the longest common subsequence of `a` and `b`, by the plain table. */
  def lcs(a: Vector[String], b: Vector[String]): Int =
    val table = Array.ofDim[Int](a.size + 1, b.size + 1)
    for i <- a.indices.reverse; j <- b.indices.reverse do
      table(i)(j) = if a(i) == b(j) then table(i + 1)(j + 1) + 1 else table(i + 1)(j).max(table(i)(j + 1))
    table(0)(0)

  def chatBody(system: String, user: String): String =
    Json.Object(
      "model"       -> Json.String("fake-model"),
      "temperature" -> Json.Number(0),
      "messages"    -> Json.Array(
        Json.Object("role" -> Json.String("system"), "content" -> Json.String(system)),
        Json.Object("role" -> Json.String("user"), "content"   -> Json.String(user))
      ),
      "stream" -> Json.Boolean(true)
    ).print

  val laterBody: String = Json.Object(
    "model"    -> Json.String("fake-model"),
    "messages" -> Json.Array(
      Json.Object("role" -> Json.String("system"), "content"    -> Json.String("POLICY")),
      Json.Object("role" -> Json.String("user"), "content"      -> Json.String("U1")),
      Json.Object("role" -> Json.String("assistant"), "content" -> Json.String("A")),
      Json.Object("role" -> Json.String("user"), "content"      -> Json.String("U2"))
    ),
    "stream" -> Json.Boolean(true)
  ).print

  private def delta(fields: (String, Json)*): String =
    "data: " + Json.Object("choices" -> Json.Array(Json.Object(
      "index"         -> Json.Number(0),
      "delta"         -> Json.Object(fields*),
      "finish_reason" -> Json.Null
    ))).print

  private def finish(reason: String): String =
    "data: " + Json.Object(
      "choices" -> Json.Array(Json.Object(
        "index"         -> Json.Number(0),
        "delta"         -> Json.Object(),
        "finish_reason" -> Json.String(reason)
      )),
      "usage" -> Json.Object(
        "prompt_tokens"     -> Json.Number(11),
        "completion_tokens" -> Json.Number(2),
        "total_tokens"      -> Json.Number(13)
      )
    ).print

  private val toolCall: String = delta(
    "tool_calls" -> Json.Array(Json.Object(
      "index"    -> Json.Number(0),
      "id"       -> Json.String("c1"),
      "type"     -> Json.String("function"),
      "function" -> Json.Object("name" -> Json.String("read"), "arguments" -> Json.String("""{"path":"a"}"""))
    ))
  )

  private def request(id: String, after: Long, predictor: String, stream: Boolean, body: String): Json =
    Json.Object(
      "kind"      -> Json.String("request"),
      "id"        -> Json.String(id),
      "session"   -> Json.String("fake-session-1"),
      "after"     -> Json.Number(after),
      "predictor" -> Json.String(predictor),
      "at"        -> Json.Number(1_762_950_000_000L),
      "url"       -> Json.String("http://test.invalid/v1/chat/completions"),
      "headers"   -> Json.Object("Authorization" -> Json.String("«secret:Authorization»")),
      "stream"    -> Json.Boolean(stream),
      "body"      -> Json.String(body)
    )

  private def streamed(id: String, lines: Vector[String]): Json =
    Json.Object(
      "kind"   -> Json.String("reply"),
      "id"     -> Json.String(id),
      "at"     -> Json.Number(1_762_950_001_000L),
      "first"  -> Json.Number(1_762_950_000_300L),
      "status" -> Json.Number(200),
      "lines"  -> Json.Array(lines.map(Json.String(_))*),
      "end"    -> Json.String("closed")
    )

  private def plain(id: String, body: String): Json =
    Json.Object(
      "kind"   -> Json.String("reply"),
      "id"     -> Json.String(id),
      "at"     -> Json.Number(1_762_950_001_000L),
      "status" -> Json.Number(200),
      "body"   -> Json.String(body),
      "end"    -> Json.String("done")
    )

  /** The lines of the exchange file of the fixture: x1 (policy, a stream with reasoning and text), x2 (compress,
    * plain), x3 (policy, a stream with a tool call), and x4 (policy, open).
    */
  val fileLines: Vector[Json] = Vector(
    request("x1", 3L, "policy", stream = true, chatBody("POLICY", "U1")),
    streamed(
      "x1",
      Vector(
        delta("reasoning_content" -> Json.String("think")),
        "",
        delta("content" -> Json.String("Hel")),
        "",
        delta("content" -> Json.String("lo")),
        "",
        finish("stop"),
        "",
        "data: [DONE]"
      )
    ),
    request("x2", 3L, "compress", stream = false, chatBody("C", "M")),
    plain(
      "x2",
      """{"choices":[{"index":0,"message":{"role":"assistant","content":"S1"},"finish_reason":"stop"}],""" +
        """"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}"""
    ),
    request("x3", 8L, "policy", stream = true, laterBody),
    streamed("x3", Vector(toolCall, "", finish("tool_calls"), "", "data: [DONE]")),
    request("x4", 10L, "policy", stream = false, laterBody)
  )

  /** Writes the exchange file of the fixture in a new folder, and gives its path. */
  def fixtureFile(): Path =
    val folder = Files.createTempDirectory("dspy4s-exchange-page")
    val path   = folder.resolve("x.http.jsonl")
    Files.writeString(path, fileLines.map(_.print + "\n").mkString, UTF_8)
    path

  /** The root of the laws of the page: the journal of `InspectorLaws.turn` and the exchange file of `fileLines`. */
  def fixture(root: Path): SessionId =
    val session = SessionId("fake-session-1")
    InspectorLaws.journal(root, session, InspectorLaws.turn): Unit
    Files.createDirectories(SessionFile.folderOf(root))
    Files.writeString(ExchangeFile.pathOf(root, session), fileLines.map(_.print + "\n").mkString, UTF_8)
    session
