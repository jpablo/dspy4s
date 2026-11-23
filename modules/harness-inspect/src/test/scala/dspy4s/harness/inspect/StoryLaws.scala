package dspy4s.harness.inspect

import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.host.HostSupport.withRoot
import dspy4s.harness.host.SessionFile
import dspy4s.harness.kernel.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}

import InspectorLaws.*

/** The laws of the chat and the trajectory of a session (docs/code-harness/OBSERVABILITY.md, 5.7 and L23). */
class StoryLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── Helpers ──

  /** The rows of `entries`, one second apart. An `Input` comes from the person, and each other entry from the harness.
    */
  private def rowsOf(entries: Vector[Entry]): Vector[SessionFile.Row] =
    entries.zipWithIndex.map { (entry, i) =>
      val source = entry match
        case Entry.Input(_, _, _) => Source.User
        case _                    => Source.Harness
      val logged = Logged(i.toLong, Meta(Start + i * 1000L, source, Taint.Trusted, required = true), entry)
      SessionFile.Row(i.toLong, true, "", Right(logged))
    }

  private def n(x: Long): Json = Json.Number(BigDecimal(x))

  /** An exchange with a plain reply. `usage` is the prompt tokens, the completion tokens and the cached tokens. */
  private def exchange(
      id       : String,
      after    : Long,
      predictor: String,
      at       : Long                       = Start,
      ended    : Long                       = Start + 700L,
      first    : Option[Long]               = None,
      status   : Long                       = 200L,
      usage    : Option[(Long, Long, Long)] = None,
      error    : Option[String]             = None
  ): Exchange =
    val request = Json.Object(
      "kind"      -> Json.String("request"),
      "id"        -> Json.String(id),
      "after"     -> n(after),
      "predictor" -> Json.String(predictor),
      "at"        -> n(at),
      "stream"    -> Json.Boolean(false),
      "body"      -> Json.String("{}"),
      "url"       -> Json.String("http://test.invalid/v1/chat/completions")
    )
    val body = usage.fold("{}") { (p, c, cached) =>
      s"""{"usage":{"prompt_tokens":$p,"completion_tokens":$c,"prompt_tokens_details":{"cached_tokens":$cached}}}"""
    }
    val fields = Vector(
      "kind"   -> Json.String("reply"),
      "id"     -> Json.String(id),
      "at"     -> n(ended),
      "status" -> n(status),
      "body"   -> Json.String(body)
    ) ++ first.map("first" -> n(_)) ++ error.map("error" -> Json.String(_))
    Exchange(0, request, Some(Json.Object(fields*)))

  /** The turn of the fixture, with a policy call in each step and a summary call after the output of the cell. */
  private val calls = Vector(
    exchange("x1", 3L, "policy", Start + 3000L, Start + 4200L, Some(Start + 3200L), usage = Some((1000L, 100L, 920L))),
    exchange("x2", 7L, "compress", Start + 7000L, Start + 7500L, usage = Some((300L, 50L, 0L))),
    exchange("x3", 8L, "policy", Start + 8000L, Start + 8600L, usage = Some((1200L, 30L, 1100L)))
  )

  private def turnOf(items: Vector[Item]): Turn =
    items.collectFirst { case Item.OfTurn(turn) => turn }.getOrElse(fail(s"no turn in $items"))

  private def seqs(rows: Vector[SessionFile.Row]): Vector[Long] = rows.map(_.seq)

  // ── L23 ──

  property("Story L23: each entry and each exchange is in exactly one item, and the items keep the order") {
    val journal   = Gen.frequency(3 -> Gen.listOf(entry(text)).map(_.toVector), 1 -> Gen.const(turn))
    val predictor = Gen.oneOf("policy", "compress", "merge", "shorten", "subQuery", "none")
    forAllNoShrink(journal, Gen.listOf(Gen.zip(Gen.choose(-1L, 40L), predictor))) { (entries, raw) =>
      val rows      = rowsOf(entries)
      val exchanges = raw.toVector.zipWithIndex.map { case ((after, p), i) => exchange(s"x$i", after, p) }
      val items     = Story.of(rows, exchanges)
      assertEquals(items.flatMap(_.seqs), seqs(rows))
      assertEquals(items.flatMap(_.exchangeIds).sorted, exchanges.map(_.id).sorted)
      // The key of each item is its place in the journal, so the follow can replace the items from a `seq`.
      assertEquals(items.map(_.key), items.map(_.key).sorted)
    }
  }

  property("Story L24: a new prompt comes right before the item that holds its call, with the key of that item") {
    val journal   = Gen.frequency(3 -> Gen.listOf(entry(text)).map(_.toVector), 1 -> Gen.const(turn))
    val predictor = Gen.oneOf("policy", "compress", "subQuery", "none")
    val call      = Gen.zip(Gen.choose(-1L, 40L), predictor, Gen.prob(0.5))
    val prompt    = Prompt("FAKE-INSTRUCTIONS", Vector.empty, Vector.empty, "", inSystem = true)
    forAllNoShrink(journal, Gen.listOf(call)) { (entries, raw) =>
      val rows      = rowsOf(entries)
      val exchanges = raw.toVector.zipWithIndex.map { case ((after, p, _), i) => exchange(s"x$i", after, p) }
      val marked    = raw.toVector.zip(exchanges).collect { case ((_, _, true), x) => x }
      val items     = Story.of(rows, exchanges, marked.map(x => x.id -> PromptMark(x, prompt, None, None)).toMap)
      // The new prompts hold no entry and no exchange, and the keys still go up.
      assertEquals(items.flatMap(_.seqs), seqs(rows))
      assertEquals(items.flatMap(_.exchangeIds).sorted, exchanges.map(_.id).sorted)
      assertEquals(items.map(_.key), items.map(_.key).sorted)
      val news = items.zipWithIndex.collect { case (Item.NewPrompt(mark, at), i) => (i, mark, at) }
      assertEquals(news.map(_._2.call.id).sorted, marked.map(_.id).sorted)
      news.foreach { (i, mark, at) =>
        val holder = items.drop(i + 1).find { case Item.NewPrompt(_, _) => false; case _ => true }
        assert(holder.exists(h => h.key == at && h.exchangeIds.contains(mark.call.id)), items.toString)
      }
    }
  }

  test("Story L23: a turn holds its message, and a step its policy calls, its Acted, its calls, its output") {
    val items = Story.of(rowsOf(turn), calls)
    assertEquals(items.size, 2, items.toString)
    assert(
      items.head match
        case Item.Note(row) => row.seq == 0L;
        case _              => false
      ,
      items.head.toString
    )
    val t = turnOf(items)
    assertEquals(t.message.map(_.seq), Some(1L))
    assertEquals(t.start.seq, 2L)
    assertEquals(t.end.map(_.seq), Some(10L))
    assertEquals(t.steps.map(_.start.seq), Vector(3L, 8L))
    t.steps.head.parts match
      case Vector(
            Part.Model(Vector(x1), Some(acted)),
            Part.Cell(code),
            Part.Call(started, Some(done)),
            Part.Output(output),
            Part.Memory(Vector(x2))
          ) => assertEquals(
          (x1.id, acted.seq, code.seq, started.seq, done.seq, output.seq, x2.id),
          ("x1", 4L, 4L, 5L, 6L, 7L, "x2")
        )
      case other => fail(s"the parts of step 1: $other")
    t.steps(1).parts match
      case Vector(Part.Model(Vector(x3), Some(acted))) => assertEquals((x3.id, acted.seq), ("x3", 9L))
      case other                                       => fail(s"the parts of step 2: $other")
  }

  test("Story L23: the numbers of a turn are the counts and the sums of its parts") {
    val plain = Story.summary(turnOf(Story.of(rowsOf(turn), calls)))
    assertEquals(
      plain,
      Summary(Some(8000L), steps = 2, cells = 1, calls = 1, prompt = 2200L, completion = 130L, errors = 0)
    )
    // A cell that fails, a call that gives an error, and an exchange with the status 500 are three errors.
    val bad = turn.map {
      case Entry.Observed(o, _, w) => Entry.Observed(o, CellOutcome.Failed("FAKE-BOOM"), w)
      case Entry.EffectDone(id, _) => Entry.EffectDone(id, EffectResult.Error("FAKE-NO-FILE"))
      case other                   => other
    }
    val failing = calls.updated(2, exchange("x3", 8L, "policy", status = 500L))
    assertEquals(Story.summary(turnOf(Story.of(rowsOf(bad), failing))).errors, 3)
  }

  test("Story L23: the row of a model call gives its time, its tokens, its cached part and its speed") {
    val html = Story.html(SessionId("fake-session-1"), Story.of(rowsOf(turn), calls), Start)
    assert(html.contains("1.2 s"), html)
    assert(html.contains("1,000 → 100"), html)
    assert(html.contains("92%"), html)
    assert(html.contains("100 tok/s"), html)
    assert(html.contains("""href="/s/fake-session-1/x/x1""""), html)
  }

  test("Story L23: an end that is not a reply has its words, and a turn that runs shows Working") {
    def endOf(outcome: Option[TurnOutcome]): String =
      val entries = turn.dropRight(1) ++ outcome.map(Entry.TurnEnded(_)).toVector
      Story.endText(turnOf(Story.of(rowsOf(entries), Vector.empty)))
    assertEquals(endOf(Some(TurnOutcome.Replied("FAKE-REPLY"))), "FAKE-REPLY")
    assert(endOf(Some(TurnOutcome.Aborted)).contains("stopped"))
    assert(endOf(Some(TurnOutcome.Crashed)).contains("crashed"))
    assert(endOf(Some(TurnOutcome.Exhausted)).contains("steps"))
    assert(endOf(Some(TurnOutcome.Failed("FAKE-WHY"))).contains("FAKE-WHY"))
    assert(endOf(Some(TurnOutcome.Submitted(Json.String("FAKE-VALUE")))).contains("FAKE-VALUE"))
    assert(endOf(None).contains("Working"))
  }

  test("Story L23: the views escape each text of the journal") {
    val entries = Vector(
      Entry.Input(RequestId("m1"), Markup, Map.empty),
      Entry.TurnStarted,
      Entry.StepStarted(1),
      Entry.Acted(Markup, Markup, Some(Markup)),
      Entry.EffectStarted(EffectId(1L, 0), "read", Json.Object("path" -> Json.String(Markup))),
      Entry.EffectDone(EffectId(1L, 0), EffectResult.Error(Markup)),
      Entry.Observed(Markup, CellOutcome.Failed(Markup), Map.empty),
      Entry.TurnEnded(TurnOutcome.Failed(Markup)),
      Entry.StopRequested(Markup)
    )
    val html = Story.html(SessionId("fake-session-1"), Story.of(rowsOf(entries), Vector.empty), Start)
    assert(!html.contains(Markup), html)
    assert(html.contains(Html.escape(Markup)), html)
  }

  // ── The chat view of design A ──

  private def sectionOf(html: String, key: Long): String =
    val start = html.indexOf(s"""data-key="$key"""")
    if start < 0 then fail(s"no item $key in $html")
    else html.substring(html.lastIndexOf("<section", start), html.indexOf("</section>", start))

  test(
    "Story L23: the chat gives an entry outside a turn in words, and only the trajectory has a record of the harness"
  ) {
    val effort  = Map("reasoning_effort" -> Json.String("high"))
    val entries = turn ++
      Vector(Entry.ScopeRecorded("FAKE-HEAD", clean = true), Entry.ModelChanged(ModelChoice("FAKE-MODEL", effort)))
    val html    = Story.html(SessionId("fake-session-1"), Story.of(rowsOf(entries), calls), Start)
    val started = sectionOf(html, 0L)
    assert(started.contains("""<div class="ev c-only"><span><b>Session started</b> """), started)
    assert(started.contains(" · fake-model · custom mode · 3 steps a turn"), started)
    assert(started.contains("""<div class="t-only">"""), started)
    // ScopeRecorded has no words, so only the trajectory shows it.
    val scope = sectionOf(html, 11L)
    assert(scope.startsWith("""<section class="item other quiet""""), scope)
    assert(!scope.contains("c-only"), scope)
    assert(sectionOf(html, 12L).contains("<b>Model</b> ") && html.contains(" · FAKE-MODEL · effort high</span>"), html)
  }

  test("Story L23: the work line of a turn opens a short list of its steps, and the turn holds its numbers") {
    val html = Story.html(SessionId("fake-session-1"), Story.of(rowsOf(turn), calls), Start)
    assert(
      html.contains("""<section class="item turn" data-key="1" data-ms="8000" data-in="2200" data-out="130">"""),
      html
    )
    assert(html.contains("""<summary><span class="mk ok">✓</span>Worked 8.0 s · 2 steps · 1 cell · 1 call"""), html)
    assert(html.contains("""<div class="brief c-only">"""), html)
    assert(
      html.contains(
        """<span class="t">Reading notes.txt.<span class="c"><span class="ok">✓</span> val n = """ +
          """api.read(&quot;notes.txt&quot;).linesIterator.size → val n: Int = 42</span></span>"""
      ),
      html
    )
    assert(html.contains("""<span class="t">The reply</span>"""), html)
    assert(html.contains("""<div class="bfoot">1 memory summary · 500 ms · <button data-goto>"""), html)
    assert(html.contains("""<div class="traj t-only">"""), html)
    // A cell that failed has a ✗.
    val failed = turn.map {
      case Entry.Observed(o, _, w) => Entry.Observed(o, CellOutcome.Failed("FAKE-BOOM"), w)
      case other                   => other
    }
    val bad = Story.html(SessionId("fake-session-1"), Story.of(rowsOf(failed), calls), Start)
    assert(bad.contains("""<span class="c"><span class="bad">✗</span> val n = """), bad)
  }

  test("Story L23: a reply shows its Markdown, a submitted value is a good end, and a failure is a bad end") {
    def endOf(outcome: TurnOutcome): String =
      Story.html(
        SessionId("fake-session-1"),
        Story.of(rowsOf(turn.dropRight(1) :+ Entry.TurnEnded(outcome)), Vector.empty),
        Start
      )
    val reply = endOf(TurnOutcome.Replied("FAKE **bold** `code`"))
    assert(reply.contains("""<div class="text md"><p>FAKE <strong>bold</strong> <code>code</code></p></div>"""), reply)
    val submitted = endOf(TurnOutcome.Submitted(Json.String("FAKE-VALUE")))
    assert(submitted.contains("""<span class="mk ok">✓</span>"""), submitted)
    assert(!submitted.contains("reply bad"), submitted)
    val failed = endOf(TurnOutcome.Failed("FAKE-WHY"))
    assert(
      failed.contains("""<span class="mk bad">✗</span>""") && failed.contains("""<div class="say reply bad">"""),
      failed
    )
  }

  test("Story L23: the page of a session opens on the chat view, has the toggle, and still holds the entries") {
    withRoot { root =>
      val (session, _) = fixture(root)
      val inspector    = Inspector.start(root, 0).fold(e => fail(e), identity)
      try
        val client            = HttpClient.newHttpClient()
        def get(path: String) =
          client.send(HttpRequest.newBuilder(URI.create(inspector.url + path)).GET().build(), BodyHandlers.ofString())
        val page = get(s"s/${session.value}").body()
        assert(page.contains("""<body class="mode-chat"""), page.take(2000))
        Vector("chat", "trajectory", "entries").foreach(m => assert(page.contains(s"""data-mode="$m""""), m))
        assert(page.contains("""id="story""""), page.take(2000))
        // The bar has a place for the totals of the turns, and a menu of the raw paths.
        assert(page.contains("""<span id="totals"></span>"""), page.take(2000))
        assert(page.contains("""<details class="files"><summary>Files</summary><div class="menu">"""), page.take(2000))
        assert(page.contains(Html.escape(FirstMessage)), page.take(2000))
        assertEquals(seqsOf(page), turn.indices.map(_.toLong).toVector)
        val from1 = get(s"s/${session.value}/story?from=1")
        assertEquals(from1.statusCode(), 200)
        assert(from1.body().contains("""data-key="1""""), from1.body())
        assert(!from1.body().contains("""data-key="0""""), from1.body())
        assert(get(s"s/${session.value}/story?from=0").body().contains("""data-key="0""""))
        assertEquals(get(s"s/${session.value}/story?from=fake").statusCode(), 404)
      finally inspector.close()
    }
  }
