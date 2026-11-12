package dspy4s.harness.inspect

import dspy4s.core.contracts.RuntimeContext
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{Secrets, Support}
import dspy4s.harness.host.{Catalog, ExchangeFile, HarnessProbe, ProbeLink, SessionFile, catalogRow}
import dspy4s.harness.host.HostSupport.withRoot
import dspy4s.harness.kernel.*
import dspy4s.lm.contracts.{ExchangeEnd, ExchangeRequest}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.Schema
import zio.blocks.schema.json.Json

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.net.{Socket, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import scala.compiletime.constValueTuple
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*

import InspectorLaws.*

/** The laws of the inspector (docs/code-harness/OBSERVABILITY.md, 5 and 6: L10 to L15). A root with a journal, its
  * catalog row, an exchange file and a probe journal is the fixture of the laws of the server.
  */
class InspectorLaws extends ScalaCheckSuite:

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  // ── Helpers ──

  private val client = HttpClient.newHttpClient()

  private def send(inspector: Inspector, path: String) =
    client.send(
      HttpRequest.newBuilder(URI.create(inspector.url + path.stripPrefix("/"))).GET().build(),
      BodyHandlers.ofString()
    )

  private def get(inspector: Inspector, path: String): (Int, String) =
    val response = send(inspector, path)
    (response.statusCode(), response.body())

  /** Runs `body` with an inspector of `root` on a free port. */
  private def serving[A](root: Path)(body: Inspector => A): A =
    val inspector = Inspector.start(root, 0).fold(e => fail(e), identity)
    try body(inspector)
    finally inspector.close()

  // ── L10: only reads ──

  test("Inspect L10: after any request, the files of the root have the same bytes, and no file comes") {
    withRoot { root =>
      val (session, xid) = fixture(root)
      val before         = snapshot(root)
      serving(root) { inspector =>
        val paths = Vector(
          "/",
          s"/s/${session.value}",
          s"/s/${session.value}/after/3?size=0",
          s"/raw/${session.value}/journal.jsonl",
          s"/raw/${session.value}/http.jsonl",
          s"/raw/${session.value}/x/$xid/request",
          s"/raw/${session.value}/x/$xid/reply",
          s"/p/${HarnessProbe.nameOf(probe(session))}",
          "/s/fake-no-session",
          "/raw/..%2F..%2Fetc/journal.jsonl",
          "/nothing"
        )
        paths.foreach(path => get(inspector, path): Unit)
        rawRequest(inspector.port, "POST", s"127.0.0.1:${inspector.port}", "/"): Unit
      }
      assertEquals(snapshot(root).keySet, before.keySet)
      before.foreach((path, bytes) => assert(snapshot(root)(path).sameElements(bytes), s"$path changed"))
    }
  }

  test("Inspect L10: the inspector reads a root that it cannot write, also the rows of its catalog") {
    withRoot { root =>
      val (session, _) = fixture(root)
      // A row of the catalog that the fold of the journal does not give, so the page shows where its row came from.
      val catalog = Catalog.open(Catalog.pathOf(root)).fold(e => fail(e), identity)
      try
        val row = catalog.row(session).getOrElse(fail("the catalog has no row"))
        assertEquals(catalog.put(row.copy(firstMessage = "FAKE-FROM-THE-CATALOG")), Right(()))
      finally catalog.close()
      val all                                    = Files.walk(root).iterator().asScala.toVector
      def mode(path: Path, write: Boolean): Unit =
        val text = (Files.isDirectory(path), write) match
          case (true, true)   => "rwx------"
          case (true, false)  => "r-x------"
          case (false, true)  => "rw-------"
          case (false, false) => "r--------"
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(text)): Unit
      try
        all.foreach(mode(_, write = false))
        serving(root) { inspector =>
          val (_, list) = get(inspector, "/")
          assert(list.contains("FAKE-FROM-THE-CATALOG"), list)
          val (status, page) = get(inspector, s"/s/${session.value}")
          assertEquals(status, 200, page)
          assertEquals(seqsOf(page), turn.indices.map(_.toLong).toVector)
        }
      finally all.foreach(mode(_, write = true))
    }
  }

  // ── L11: the host check ──

  test("Inspect L11: a request with another Host header gets 403 and no data, and a request that is not GET gets 405") {
    withRoot { root =>
      val (session, _) = fixture(root)
      serving(root) { inspector =>
        val port = inspector.port
        val evil = rawRequest(port, "GET", "evil.example", s"/raw/${session.value}/journal.jsonl")
        assert(evil.startsWith("HTTP/1.1 403"), evil)
        assert(!evil.contains(FirstMessage), evil)
        assert(rawRequest(port, "GET", s"localhost:$port", "/").startsWith("HTTP/1.1 200"))
        assert(rawRequest(port, "GET", s"127.0.0.1:$port", "/").startsWith("HTTP/1.1 200"))
        assert(rawRequest(port, "POST", s"127.0.0.1:$port", "/").startsWith("HTTP/1.1 405"))
      }
    }
  }

  test("Inspect L11: a page has a policy of its scripts, with the nonce of its script, and no CORS header") {
    withRoot { root =>
      val (session, _) = fixture(root)
      serving(root) { inspector =>
        val response = send(inspector, s"/s/${session.value}")
        val policy   = response.headers().firstValue("Content-Security-Policy").orElse("")
        val nonce    = "'nonce-([A-Za-z0-9+/=_-]+)'".r.findFirstMatchIn(policy).map(_.group(1))
        assert(nonce.nonEmpty, policy)
        assert(response.body().contains(s"""<script nonce="${nonce.get}">"""), response.body().take(300))
        assert(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
      }
    }
  }

  // ── L12 to L14: the views of an entry ──

  property("Inspect L12: the JSON view of a row decodes to the same Logged as the row") {
    forAllNoShrink(Gen.listOfN(12, entry(text))) { entries =>
      withRoot { root =>
        val session = SessionId("fake-session-1")
        journal(root, session, entries.toVector): Unit
        rowsOf(root, session).foreach { row =>
          assertEquals(LoggedCodec.decode(EntryViews.json(row)).toOption, row.logged.toOption)
          assert(row.logged.isRight, row.toString)
        }
      }
    }
  }

  property("Inspect L13: the Scala view names the case of each entry and each of its fields") {
    forAllNoShrink(entry(text)) { value =>
      val shown = ScalaText.of(value)
      assert(shown.contains(value.productPrefix), shown)
      value.productElementNames.foreach(name => assert(shown.contains(s"$name = "), s"$name is not in $shown"))
    }
  }

  test("Inspect L13: the generator of the laws gives each case of Entry") {
    val seen = Iterator.continually(entry(text).sample).flatten.take(3000).map(_.productPrefix).toSet
    assertEquals(seen, caseNames[Entry].toSet)
  }

  test("Inspect L13: a string prints as a Scala literal, and a text with a line end as a block") {
    assertEquals(ScalaText.of("a\"b\\c\td"), "\"a\\\"b\\\\c\\td\"")
    assertEquals(ScalaText.of(Some(41L)), "Some(41L)")
    assertEquals(ScalaText.of(Entry.StepStarted(2)), "StepStarted(step = 2)")
    assertEquals(ScalaText.of(Entry.TurnStarted), "TurnStarted")
    assert(ScalaText.of(Entry.StopRequested("line 1\nline 2")).contains("\"\"\"line 1\nline 2\"\"\""))
  }

  test("Inspect L14: the escape gives an entity for each of the five marks of HTML") {
    assertEquals(Html.escape("""<a href="x">'&"""), "&lt;a href=&quot;x&quot;&gt;&#39;&amp;")
  }

  property("Inspect L14: for any text in an entry, the card and the row hold no markup from that text") {
    forAllNoShrink(entry(marked)) { value =>
      val logged = Logged(7L, Meta(Start, Source.Harness, Taint.External, required = true), value)
      val row    = SessionFile.Row(7L, true, LoggedCodec.encodeToString(logged), Right(logged))
      val card   = EntryViews.card(logged)
      val html   = EntryViews.html(row)
      assert(!card.contains(Markup) && !card.contains("<i data-m"), card)
      assert(!html.contains(Markup) && !html.contains("<i data-m"), html)
      // The JSON and Scala views escape a quote as \", so only a card that shows the text as it is has its escape.
      val shown = value match
        case Entry.Acted(_, text, _)      => Some(text)
        case Entry.Input(_, text, _)      => Some(text)
        case Entry.StopRequested(reason)  => Some(reason)
        case Entry.Observed(output, _, _) => Some(output)
        case _                            => None
      shown.foreach(text => assert(card.contains(Html.escape(text)), card))
    }
  }

  // ── L15: follow ──

  test("Inspect L15: after/{seq} gives the rows with a greater seq, all of them, in their order") {
    withRoot { root =>
      val (session, _) = fixture(root)
      val count        = rowsOf(root, session).size
      serving(root) { inspector =>
        (-1 to count).foreach { seq =>
          val (status, body) = get(inspector, s"/s/${session.value}/after/$seq?size=0")
          assertEquals(status, 200)
          assertEquals(seqsOf(body), ((seq + 1) until count).map(_.toLong).toVector)
          assert(body.contains("""data-status="Idle""""), body.take(300))
        }
      }
    }
  }

  test("Inspect L15: a reader that follows a journal while a writer appends gets each row once, in order") {
    withRoot { root =>
      val session = SessionId("fake-session-1")
      val path    = SessionFile.pathOf(root, session)
      val writer  = SessionFile.open(path, session).fold(e => fail(e), identity)
      try
        writer.append(
          Vector(Logged(0L, Meta(Start, Source.User, Taint.Trusted, true), Entry.TurnStarted)),
          flush = true
        ).fold(e => fail(e), identity)
        serving(root) { inspector =>
          val thread = Thread.ofPlatform().start { () =>
            (1 until 200).foreach { n =>
              writer.append(
                Vector(Logged(n.toLong, Meta(Start + n, Source.Harness, Taint.Trusted, true), Entry.StepStarted(n))),
                flush = false
              ): Unit
            }
          }
          var seen = Vector(0L)
          // A synchronous test of munit has no time limit, so the loop has its own.
          val deadline = System.nanoTime() + 60_000_000_000L
          while (thread.isAlive || seen.last < 199L) && System.nanoTime() < deadline do
            val (_, body) = get(inspector, s"/s/${session.value}/after/${seen.last}?size=0")
            seen = seen ++ seqsOf(body)
          thread.join()
          assertEquals(seen, (0L until 200L).toVector)
        }
      finally writer.close()
    }
  }

  // ── The pages ──

  test("Inspect: the page of a session shows each exchange after its entry, and an exchange with no reply as open") {
    withRoot { root =>
      val (session, xid) = fixture(root)
      serving(root) { inspector =>
        val (_, page) = get(inspector, s"/s/${session.value}")
        assertEquals(seqsOf(page), rowsOf(root, session).map(_.seq))
        val at   = page.indexOf(s"""id="x$xid"""")
        val step = page.indexOf("""data-seq="3"""")
        val next = page.indexOf("""data-seq="4"""")
        assert(step >= 0 && step < at && at < next, s"$step $at $next")
        assert(raw"""class="row exchange[^"]*\bopen\b[^"]*"""".r.findFirstIn(page).nonEmpty, page)
      }
    }
  }

  test("Inspect: the list of the sessions comes from the catalog, and from the journals with no catalog") {
    withRoot { root =>
      val (session, _) = fixture(root)
      serving(root) { inspector =>
        val (_, list) = get(inspector, "/")
        assert(list.contains(s"""href="/s/${session.value}""""), list)
        assert(list.contains(FirstMessage), list)
        assert(list.contains(s"""href="/p/${HarnessProbe.nameOf(probe(session))}""""), list)
      }
      Files.delete(Catalog.pathOf(root))
      serving(root) { inspector =>
        val (_, list) = get(inspector, "/")
        assert(list.contains(s"""href="/s/${session.value}""""), list)
        assert(list.contains(FirstMessage), list)
      }
    }
  }

  test("Inspect: the raw paths give the rows of the journal, the exchange file, and the bodies of an exchange") {
    withRoot { root =>
      val (session, xid) = fixture(root)
      serving(root) { inspector =>
        val (_, journalText) = get(inspector, s"/raw/${session.value}/journal.jsonl")
        val rows             = journalText.linesIterator.filter(_.nonEmpty).toVector
        assertEquals(rows.size, rowsOf(root, session).size)
        rows.zip(rowsOf(root, session)).foreach { (line, row) =>
          val json = Json.parse(line).fold(e => fail(s"$e: $line"), identity)
          assertEquals(json.fields.collectFirst { case ("seq", Json.Number(n)) => n.toLong }, Some(row.seq))
          val entryText = json.fields.collectFirst { case ("entry", value) => value.print }.getOrElse("")
          assertEquals(LoggedCodec.decode(entryText).toOption, row.logged.toOption)
        }
        val (_, file) = get(inspector, s"/raw/${session.value}/http.jsonl")
        assertEquals(file, Files.readString(ExchangeFile.pathOf(root, session), UTF_8))
        assertEquals(get(inspector, s"/raw/${session.value}/x/$xid/request"), (200, RequestBody))
        assertEquals(get(inspector, s"/raw/${session.value}/x/$xid/reply")._2, "data: {\"a\":1}\n\ndata: [DONE]")
        assertEquals(get(inspector, s"/raw/${session.value}/x/fake-none/request")._1, 404)
        assertEquals(get(inspector, "/raw/..%2F..%2Fetc/journal.jsonl")._1, 404)
      }
    }
  }

  test("Inspect: the page of a probe shows its journal") {
    withRoot { root =>
      val (session, _) = fixture(root)
      serving(root) { inspector =>
        val (status, page) = get(inspector, s"/p/${HarnessProbe.nameOf(probe(session))}")
        assertEquals(status, 200)
        assert(page.contains("the fake question of the probe"), page)
      }
    }
  }

object InspectorLaws:

  val Start: Long = 1_762_950_000_000L

  val FirstMessage = "How many lines does notes.txt have?"

  val RequestBody = """{"model":"fake-model","messages":[{"role":"user","content":"x"}]}"""

  /** A text with markup, for the law of the escape. */
  val Markup = """<i data-m="1">'&"""

  val LoggedCodec = summon[Schema[Logged]].jsonCodec

  inline def caseNames[T](using m: Mirror.SumOf[T]): List[String] =
    constValueTuple[m.MirroredElemLabels].toList.map(_.toString)

  def probe(session: SessionId): ProbeLink = ProbeLink(session, EffectId(5L, 0))

  /** The entries of a short turn: a cell that reads a file, and a reply. */
  val turn: Vector[Entry] = Vector(
    Entry.Activated(Versions("3.10.0", "1"), Support.config(TurnKind.Chat, 3)),
    Entry.Input(RequestId("m1"), FirstMessage, Map.empty),
    Entry.TurnStarted,
    Entry.StepStarted(1),
    Entry.Acted("I read the file.", "Reading notes.txt.", Some("""val n = api.read("notes.txt").linesIterator.size""")),
    Entry.EffectStarted(EffectId(1L, 0), "read", Json.Object("path" -> Json.String("notes.txt"))),
    Entry.EffectDone(EffectId(1L, 0), EffectResult.Value(Json.String("note 1\nnote 2"), "2 lines")),
    Entry.Observed("val n: Int = 42", CellOutcome.Ok, Map.empty),
    Entry.StepStarted(2),
    Entry.Acted("Done.", "notes.txt has 42 lines.", None),
    Entry.TurnEnded(TurnOutcome.Replied("notes.txt has 42 lines."))
  )

  /** Writes a journal of `entries` for `session` under `root`, and gives its rows. */
  def journal(root: Path, session: SessionId, entries: Vector[Entry]): Vector[Logged] =
    val logged = entries.zipWithIndex.map { (value, i) =>
      Logged(i.toLong, Meta(Start + i * 1000L, Source.Harness, Taint.Trusted, required = true), value)
    }
    val file = SessionFile.open(SessionFile.pathOf(root, session), session).fold(e => throw AssertionError(e), identity)
    try file.append(logged, flush = true).fold(e => throw AssertionError(e), identity)
    finally file.close()
    logged

  /** The root of the laws of the server: the journal of `turn` with its catalog row, an exchange file with a complete
    * exchange after the `StepStarted` of `seq` 3 and an open one after the last entry, and the journal of a probe. It
    * gives the session and the id of the complete exchange.
    */
  def fixture(root: Path): (SessionId, String) =
    val session = SessionId("fake-session-1")
    val logged  = journal(root, session, turn)
    val catalog = Catalog.open(Catalog.pathOf(root)).fold(e => throw AssertionError(e), identity)
    try catalog.put(catalogRow(session)(logged)).fold(e => throw AssertionError(e), identity)
    finally catalog.close()
    given RuntimeContext = RuntimeContext()
    val first            = ExchangeFile(root, session, () => 3L, Secrets.none)
    val record           =
      first.start(ExchangeRequest("http://test.invalid/v1/chat/completions", Map.empty, RequestBody, stream = true))
    record.replied(200, Map("content-type" -> "text/event-stream"))
    Vector("data: {\"a\":1}", "", "data: [DONE]").foreach(record.line)
    record.end(ExchangeEnd.Closed)
    ExchangeFile(root, session, () => 10L, Secrets.none)
      .start(ExchangeRequest("http://test.invalid/v1/chat/completions", Map.empty, "{}", stream = false)): Unit
    val link   = probe(session)
    val probed = SessionFile.open(HarnessProbe.pathOf(root, link), SessionId("fake-probe-1"))
      .fold(e => throw AssertionError(e), identity)
    try probed.append(
        Vector(Logged(
          0L,
          Meta(Start, Source.User, Taint.Trusted, true),
          Entry.Input(RequestId("p1"), "the fake question of the probe", Map.empty)
        )),
        flush = true
      ).fold(e => throw AssertionError(e), identity)
    finally probed.close()
    val xid = linesOf(ExchangeFile.pathOf(root, session)).head.fields.collectFirst { case ("id", Json.String(id)) =>
      id
    }
    (session, xid.getOrElse(throw AssertionError("the exchange file has no id")))

  def linesOf(path: Path): Vector[Json] =
    Files.readAllLines(path, UTF_8).asScala.toVector.filter(_.nonEmpty).map(l =>
      Json.parse(l).fold(e => throw AssertionError(e), identity)
    )

  def rowsOf(root: Path, session: SessionId): Vector[SessionFile.Row] =
    val file =
      SessionFile.readOnly(SessionFile.pathOf(root, session), session).fold(e => throw AssertionError(e), identity)
    try file.rows(0L).fold(e => throw AssertionError(e), identity)
    finally file.close()

  /** The bytes of each file under `root`, by its path. */
  def snapshot(root: Path): Map[Path, Array[Byte]] =
    Files.walk(root).iterator().asScala.filter(Files.isRegularFile(_)).map(p =>
      root.relativize(p) -> Files.readAllBytes(p)
    ).toMap

  /** The `seq` of each entry row of a page or a fragment, in their order. */
  def seqsOf(html: String): Vector[Long] = """data-seq="(\d+)"""".r.findAllMatchIn(html).map(_.group(1).toLong).toVector

  /** The status line and the rest of the reply to a request with the Host header `host`, on a socket. */
  def rawRequest(port: Int, method: String, host: String, path: String): String =
    val socket = Socket("127.0.0.1", port)
    try
      val out = PrintStream(socket.getOutputStream, true, UTF_8)
      out.print(s"$method $path HTTP/1.1\r\nHost: $host\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
      out.flush()
      val in = BufferedReader(InputStreamReader(socket.getInputStream, UTF_8))
      Iterator.continually(in.readLine()).takeWhile(_ != null).mkString("\n")
    finally socket.close()

  // ── Generators ──

  val text: Gen[String] = Gen.frequency(
    3 -> Gen.alphaNumStr.map(_.take(20)),
    1 -> Gen.const("line 1\nline 2 \"quoted\""),
    1 -> Gen.const("with \"\"\" three quotes\n"),
    1 -> Gen.const(Markup),
    2 -> Gen.asciiPrintableStr.map(_.take(30))
  )

  /** A text that holds `Markup`. */
  val marked: Gen[String] =
    for a <- Gen.alphaNumStr.map(_.take(8)); b <- Gen.alphaNumStr.map(_.take(8)) yield s"$a$Markup$b"

  def json(text: Gen[String]): Gen[Json] = text.map(t => Json.Object("k" -> Json.String(t)))

  /** An entry of each case of `Entry`, with the texts of `text`. */
  def entry(text: Gen[String]): Gen[Entry] =
    val id   = EffectId(1L, 0)
    val rule = GuardRule("exec", ArgMatch.AnyArgs)
    Gen.oneOf(
      Gen.const(Entry.Activated(Versions("3.10.0", "1"), Support.config(TurnKind.Chat, 3))),
      for t <- text; v <- json(text) yield Entry.Input(RequestId("m1"), t, Map("value" -> v)),
      Gen.const(Entry.TurnStarted),
      Gen.choose(1, 9).map(Entry.StepStarted(_)),
      for r <- text; t <- text; c <- Gen.option(text) yield Entry.Acted(r, t, c),
      for op <- text; a <- json(text) yield Entry.EffectStarted(id, op, a),
      Gen.oneOf(
        for j <- json(text); t <- text yield EffectResult.Value(j, t),
        text.map(EffectResult.Error(_)),
        Gen.const(EffectResult.NeedsApproval(rule)),
        Gen.const(EffectResult.Unknown)
      ).map(Entry.EffectDone(id, _)),
      for
        o       <- text
        outcome <- Gen.oneOf(
                     Gen.const(CellOutcome.Ok),
                     text.map(t => CellOutcome.CompileError(Vector(t))),
                     text.map(CellOutcome.Failed(_)),
                     Gen.const(CellOutcome.TimedOut(30_000L)),
                     json(text).map(CellOutcome.Submitted(_))
                   )
        w <- json(text)
      yield Entry.Observed(o, outcome, Map("w" -> w)),
      text.map(Entry.StopRequested(_)),
      for
        which <- Gen.oneOf(JobSelector.One(id), JobSelector.OfLastTurn, JobSelector.All)
        t     <- text
      yield Entry.JobsStopRequested(which, t),
      Gen.oneOf(GrantLifetime.values.toIndexedSeq).map(Entry.Granted(RequestId("g1"), rule, _)),
      for h <- text; c <- Gen.oneOf(true, false) yield Entry.ScopeRecorded(h, c),
      for m <- text; v <- json(text) yield Entry.ModelChanged(ModelChoice(m, Map("reasoning_effort" -> v))),
      text.map(t => Entry.Releasing(Vector(ReleaseItem.Keep(t, "fake")))),
      text.map(t => Entry.Released(ReleaseStep(ReleaseItem.Keep(t, "fake"), ReleaseResult.Failed(t)))),
      Gen.oneOf(
        text.map(TurnOutcome.Replied(_)),
        json(text).map(TurnOutcome.Submitted(_)),
        Gen.const(TurnOutcome.Exhausted),
        Gen.const(TurnOutcome.Aborted),
        Gen.const(TurnOutcome.Crashed),
        text.map(TurnOutcome.Failed(_))
      ).map(Entry.TurnEnded(_)),
      for
        o <- Gen.oneOf(
               Gen.const(SessionOutcome.Merged),
               Gen.const(SessionOutcome.Parked),
               text.map(SessionOutcome.Abandoned(_)),
               Gen.const(SessionOutcome.Replaced(SessionId("fake-session-2")))
             )
        r <- text
      yield Entry.Ended(o, r)
    )
