package dspy4s.harness.host

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{ClosableIterator, DspyError, RuntimeContext}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{
  Budget,
  Harness,
  HarnessLimits,
  Models,
  Place,
  PolicyInput,
  PolicyStep,
  Secrets,
  SubAnswer,
  SubQuery
}
import dspy4s.harness.Models.policy
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.{ExchangeEnd, ExchangeRequest}
import dspy4s.lm.providers.{HttpResponse, HttpStreamResponse, HttpTransport, OpenAiClient, OpenAiLanguageModel}
import dspy4s.programs.strategies.Predict
import dspy4s.signatures.Signature
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import zio.blocks.schema.json.Json

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

import ExchangeFileLaws.*

/** The laws of the exchange file of a session (docs/code-harness/OBSERVABILITY.md, 4 and 6: L6 to L9). The work
  * sessions of these laws use `OpenAiLanguageModel` on a scripted chat endpoint, so each exchange goes through
  * `RecordedTransport` to the file, as in a live session.
  */
class ExchangeFileLaws extends ScalaCheckSuite:

  // A work session starts a real REPL, and a turn compiles cells.
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(3, "min")

  override def beforeEach(context: BeforeEach): Unit = RuntimeEnvironment.resetForTests()
  override def afterEach(context : AfterEach): Unit  = RuntimeEnvironment.resetForTests()

  private val limits = HarnessLimits(steps = 3)

  /** Runs a work session with `endpoint` in a new place, and gives the root of the host and the handle to `check`. */
  private def work(endpoint: ChatEndpoint, exchanges: Boolean = true)(check: (Path, SessionHandle) => Unit): Unit =
    val place = Place()
    try withRoot { root =>
        val env     = WorkEnv(place.home, HostList.empty, Backends.detect(), Secrets.none, root, exchanges = exchanges)
        val runtime = Models.withModel(modelOf(endpoint))(WorkRuntime.forWork(env, limits))
        val base    = WorkRuntime.config(place.root, "fake-model", limits)
        withHost(root, runtime)(host => check(root, opened(host, id(1), Some(base))))
      }
    finally place.close()

  private def sandboxed(): Unit = assume(Backends.detect().nonEmpty, "no OS sandbox works on this host")

  private def ended(handle: SessionHandle, n: Int): Unit =
    assert(eventually(60)(outcomes(handle).size == n), entries(handle).toString)

  // ── The file ──

  test("Exchange file: it is next to the journal, and the host does not take it as a journal") {
    withRoot { root =>
      given RuntimeContext = RuntimeContext()
      val file             = ExchangeFile(root, id(1), () => 0L, Secrets.none)
      file.start(plain("{}")).end(ExchangeEnd.Done)
      assertEquals(file.path, SessionFile.folderOf(root).resolve(s"${id(1).value}.http.jsonl"))
      assert(Files.exists(file.path), file.path.toString)
      assertEquals(Catalog.journalFiles(SessionFile.folderOf(root)), Right(Vector.empty))
    }
  }

  test("Exchange file: DSPY_EXCHANGES=off turns the file off, and any other value keeps it on") {
    assert(ExchangeFile.enabled(Map.empty))
    assert(!ExchangeFile.enabled(Map(ExchangeFile.Variable -> "off")))
    assert(!ExchangeFile.enabled(Map(ExchangeFile.Variable -> " OFF ")))
    assert(ExchangeFile.enabled(Map(ExchangeFile.Variable -> "on")))
  }

  test("Exchange file: the lines of a plain exchange and of a stream have the fields of 4.3") {
    withRoot { root =>
      given RuntimeContext = RuntimeContext()
      val file             = ExchangeFile(root, id(1), () => 41L, Secrets.none)
      val first            = file.start(plain("""{"model":"m"}"""))
      first.replied(200, Map("content-type" -> "application/json"))
      first.body("""{"choices":[]}""")
      first.end(ExchangeEnd.Done)
      val second = file.start(ExchangeRequest(Url, Map.empty, """{"stream":true}""", stream = true))
      second.replied(200, Map.empty)
      second.line("data: {}")
      second.line("")
      second.line("data: [DONE]")
      second.end(ExchangeEnd.Closed)
      val third = file.start(plain("{}"))
      third.end(ExchangeEnd.Failed("lm_transport: refused"))
      val lines = linesOf(file.path)
      assertEquals(
        lines.map(text(_, "kind")),
        Vector("request", "reply", "request", "reply", "request", "reply").map(Some(_))
      )
      val Vector(r1, p1, r2, p2, r3, p3) = lines: @unchecked
      assertEquals(Vector(r1, r2, r3).map(text(_, "session")), Vector.fill(3)(Some(id(1).value)))
      assertEquals(Vector(r1, r2, r3).map(number(_, "after")), Vector.fill(3)(Some(41L)))
      assertEquals(
        (text(r1, "url"), text(r1, "body"), bool(r1, "stream")),
        (Some(Url), Some("""{"model":"m"}"""), Some(false))
      )
      assertEquals(Vector(p1, p2, p3).map(text(_, "id")), Vector(r1, r2, r3).map(text(_, "id")))
      assertEquals(text(r1, "id").toSet.size + text(r2, "id").toSet.size, 2)
      assert(text(r1, "id") != text(r2, "id"))
      assertEquals(
        (number(p1, "status"), text(p1, "body"), text(p1, "end")),
        (Some(200L), Some("""{"choices":[]}"""), Some("done"))
      )
      assertEquals(field(p1, "first"), None)
      assertEquals(strings(p2, "lines"), Vector("data: {}", "", "data: [DONE]"))
      assertEquals(text(p2, "end"), Some("closed"))
      assert(number(p2, "first").exists(f => number(r2, "at").exists(_ <= f)), p2.print)
      assertEquals(
        (text(p3, "end"), text(p3, "error"), field(p3, "status")),
        (Some("failed"), Some("lm_transport: refused"), None)
      )
    }
  }

  // ── L6: the mask ──

  property("Exchange L6: no line of the file holds a known secret value or the value of a secret header") {
    forAllNoShrink(secrets) { values =>
      withRoot { root =>
        given RuntimeContext = RuntimeContext()
        val file             = ExchangeFile(root, id(1), () => 0L, Secrets(values))
        val all              = values.values.mkString(" | ")
        val record           = file.start(
          ExchangeRequest(s"$Url?q=$all", Map("Authorization" -> s"Bearer $Unknown", "x-trace" -> all), s"[$all]", true)
        )
        record.replied(200, Map("x-echo" -> all, "x-api-key" -> Unknown))
        record.line(s"data: $all")
        record.end(ExchangeEnd.Failed(s"boom $all"))
        val text = Files.readString(file.path, UTF_8)
        values.values.foreach(value => assert(!text.contains(value), s"$value is in the file: $text"))
        assert(!text.contains(Unknown), text)
        values.keys.foreach(name => assert(text.contains(Secrets.mark(name)), s"the mark of $name is not in $text"))
        assert(text.contains(Secrets.mark("Authorization")), text)
      }
    }
  }

  // ── L7: the labels ──

  test("Exchange L7: each exchange of a work session has its session, its predictor and the seq before it") {
    sandboxed()
    val endpoint = ChatEndpoint(answers(policy("FAKE-ONE", None), policy("FAKE-TWO", None)))
    work(endpoint) { (root, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      ended(handle, 1)
      assertEquals(handle.send(message("m2", "a second fake message")), Right(()))
      ended(handle, 2)
      val lines    = linesOf(ExchangeFile.pathOf(root, id(1)))
      val requests = lines.filter(text(_, "kind").contains("request"))
      assertEquals(lines.filter(text(_, "kind").contains("reply")).map(text(_, "id")), requests.map(text(_, "id")))
      assertEquals(requests.size, endpoint.calls.get)
      assert(requests.forall(text(_, "session").contains(id(1).value)), requests.map(_.print).toString)
      val steps   = handle.read(-1).collect { case Logged(seq, _, Entry.StepStarted(_)) => seq }
      val asked   = requests.filter(text(_, "predictor").contains("policy"))
      val summary = requests.filter(r => isSummary(promptOf(text(r, "body").getOrElse(""))))
      assertEquals(asked.flatMap(number(_, "after")), steps)
      assert(asked.forall(bool(_, "stream").contains(true)), asked.map(_.print).toString)
      assert(summary.nonEmpty, requests.map(_.print).toString)
      assert(
        summary.forall(r => text(r, "predictor").exists(Set("compress", "merge"))),
        summary.map(_.print).toString
      )
      assertEquals(asked.size + summary.size, requests.size)
    }
  }

  test("Exchange L7: an llmQuery of a cell has the predictor subQuery, after its EffectStarted") {
    sandboxed()
    val endpoint =
      ChatEndpoint(answers(policy("ask", Some("println(llmQuery(\"FAKE-QUESTION\"))")), policy("FAKE-DONE", None)))
    work(endpoint) { (root, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      ended(handle, 1)
      val started = handle.read(-1).collect { case Logged(seq, _, Entry.EffectStarted(_, "llmQuery", _)) => seq }
      val leaf    = linesOf(ExchangeFile.pathOf(root, id(1)))
        .filter(r => text(r, "kind").contains("request") && text(r, "predictor").contains("subQuery"))
      assertEquals(started.size, 1, entries(handle).toString)
      assertEquals(leaf.flatMap(number(_, "after")), started)
      assert(promptOf(text(leaf.head, "body").getOrElse("")).contains("FAKE-QUESTION"), leaf.head.print)
      assert(
        entries(handle).exists { case Entry.Observed(output, _, _) => output.contains(Answer); case _ => false },
        entries(handle).toString
      )
    }
  }

  test("Exchange L7: a probe adds its link to the lines of its exchanges") {
    val place = Place()
    try withRoot { root =>
        val endpoint = ChatEndpoint(_ => Right(policy("answer", Some("submit(\"the fake answer\")"))))
        val file     = ExchangeFile(root, id(1), () => 7L, Secrets.none)
        val link     = ProbeLink(id(1), EffectId(3L, 1))
        val context  =
          RuntimeContext(lm = Some(modelOf(endpoint)), adapter = Some(ChatAdapter()), exchangeSink = Some(file))
        val answer =
          probeAt(root, place).ask(id(1), journal, "what did the person ask?", link, Budget(5))(using context)
        assertEquals(answer, Right("the fake answer"))
        val requests = linesOf(file.path).filter(text(_, "kind").contains("request"))
        assert(requests.nonEmpty)
        assert(requests.forall(text(_, "probe").contains(s"${id(1).value}.3.1")), requests.map(_.print).toString)
        assert(requests.forall(number(_, "after").contains(7L)), requests.map(_.print).toString)
        assert(requests.forall(text(_, "predictor").contains("policy")), requests.map(_.print).toString)
      }
    finally place.close()
  }

  test("Exchange L7: with exchanges off, a work session writes no exchange file") {
    sandboxed()
    work(ChatEndpoint(answers(policy("FAKE-ONE", None))), exchanges = false) { (root, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      ended(handle, 1)
      assert(!Files.exists(ExchangeFile.pathOf(root, id(1))))
    }
  }

  // ── L8: many threads ──

  test("Exchange L8: the lines of exchanges on many threads do not mix, and each line is valid JSON") {
    withRoot { root =>
      val file    = ExchangeFile(root, id(1), () => 0L, Secrets.none)
      val threads = (0 until 8).map { t =>
        Thread.ofPlatform().start { () =>
          given RuntimeContext = RuntimeContext()
          (0 until 25).foreach { n =>
            val record = file.start(ExchangeRequest(Url, Map.empty, s"${"x" * 4000} $t.$n", stream = true))
            record.replied(200, Map.empty)
            (0 until 20).foreach(i => record.line(s"data: $t.$n.$i ${"y" * 200}"))
            record.end(ExchangeEnd.Closed)
          }
        }
      }
      threads.foreach(_.join())
      val raw = Files.readAllLines(file.path, UTF_8).asScala.toVector
      assertEquals(raw.size, 8 * 25 * 2)
      val lines = raw.map(line => Json.parse(line).fold(e => fail(s"a line is not JSON: $e"), identity))
      val byId  = lines.groupBy(text(_, "id"))
      assertEquals(byId.size, 8 * 25)
      byId.values.foreach { group =>
        assertEquals(group.flatMap(text(_, "kind")).sorted, Vector("reply", "request"))
        val name = group.flatMap(text(_, "body")).head.split(' ').last
        assertEquals(group.flatMap(strings(_, "lines")).map(_.split(' ')(1)), Vector.tabulate(20)(i => s"$name.$i"))
      }
    }
  }

  // ── L9: a late thread ──

  test("Exchange L9: the lines reach the file from a thread with the interrupt flag") {
    withRoot { root =>
      given RuntimeContext = RuntimeContext()
      val file             = ExchangeFile(root, id(1), () => 0L, Secrets.none)
      try
        Thread.currentThread().interrupt()
        val record = file.start(plain("{}"))
        record.end(ExchangeEnd.Closed)
        assert(Thread.currentThread().isInterrupted, "the write cleared the interrupt flag")
      finally Thread.interrupted(): Unit
      assertEquals(linesOf(file.path).flatMap(text(_, "kind")), Vector("request", "reply"))
    }
  }

  test("Exchange L9: an exchange that a stop cut still writes its reply line") {
    sandboxed()
    val reached  = CountDownLatch(1)
    val endpoint = ChatEndpoint(
      answers(policy("FAKE-LATE", None)),
      pause = text =>
        if !isSummary(text) then
          reached.countDown()
          // The thread of the call ignores the stop for a while.
          try Thread.sleep(2_000)
          catch case _: InterruptedException => Thread.sleep(2_000)
    )
    work(endpoint) { (root, handle) =>
      assertEquals(handle.send(message("m1")), Right(()))
      assert(reached.await(60, TimeUnit.SECONDS), entries(handle).toString)
      assertEquals(handle.send(Command.Abort("FAKE-STOP")), Right(()))
      ended(handle, 1)
      assertEquals(outcomes(handle), Vector(TurnOutcome.Aborted))
      val path = ExchangeFile.pathOf(root, id(1))
      def late = linesOf(path).filter(r => text(r, "predictor").contains("policy")).flatMap(text(_, "id")).toSet
      def done = linesOf(path).filter(text(_, "kind").contains("reply")).flatMap(text(_, "id")).toSet
      assert(late.nonEmpty, linesOf(path).map(_.print).toString)
      assert(eventually(20)(late.subsetOf(done)), linesOf(path).map(_.print).toString)
    }
  }

  // ── Helpers ──

  /** A probe at `root` with the leaves of a work runtime, as in `ProbeLaws`. */
  private def probeAt(root: Path, place: Place): HarnessProbe =
    val policyOf = Signature.derived[PolicyInput, PolicyStep]("policy", WorkRuntime.instructions)
    val subOf    = Signature.derived[SubQuery, SubAnswer]("subQuery", Harness.subQueryInstructions)
    HarnessProbe(
      root,
      place.home,
      Secrets.none,
      limits,
      Predict(policyOf, name = Some("policy")),
      Predict(subOf, name = Some("subQuery"))
    )

  private def meta(source: Source): Meta = Meta(0L, source, Taint.Trusted, required = true)

  private def journal: Vector[Logged] =
    Vector(
      Logged(0L, meta(Source.User), Entry.Input(RequestId("m1"), "the fake question of the person", Map.empty)),
      Logged(1L, meta(Source.Harness), Entry.TurnStarted),
      Logged(2L, meta(Source.Harness), Entry.TurnEnded(TurnOutcome.Replied("the fake reply")))
    )

object ExchangeFileLaws:

  val Url = "http://test.invalid/v1/chat/completions"

  /** A token that no mask knows. Only the rule of the secret headers keeps it out of the file. */
  val Unknown = "UNKNOWN-TOKEN-FOR-THE-LAWS-1234"

  /** The answer of the `subQuery` leaf. */
  val Answer = "FAKE-ANSWER-OF-THE-LEAF"

  def plain(body: String): ExchangeRequest = ExchangeRequest(Url, Map.empty, body, stream = false)

  /** The known secrets of a law: 1 to 3 values of 12 to 20 characters, with names that the mask marks. */
  val secrets: Gen[Map[String, String]] =
    for
      n      <- Gen.choose(1, 3)
      values <- Gen.listOfN(n, Gen.choose(12, 20).flatMap(k => Gen.listOfN(k, Gen.alphaNumChar).map(_.mkString)))
    yield values.zipWithIndex.map((value, i) => s"FAKE_API_KEY_$i" -> s"S$i$value").toMap

  /** The replies of a work session: a summary request gets `summaryReply`, a request of the `subQuery` leaf gets
    * `Answer`, and the policy gets `steps` in order.
    */
  def answers(steps: String*): String => Either[String, String] =
    val next = AtomicInteger(0)
    text =>
      if isSummary(text) then Right(summaryReply(text))
      else if text.contains(Harness.subQueryInstructions) then
        Right(s"[[ ## answer ## ]]\n$Answer\n\n[[ ## completed ## ]]")
      else steps.lift(next.getAndIncrement()).toRight("the script has no more policy replies")

  /** A chat endpoint for the laws: the reply to a request is `answer` of the texts of its messages. A stream gives the
    * reply in one line, then a line with the finish reason and the usage, then `data: [DONE]`. `pause` runs before each
    * reply.
    */
  final class ChatEndpoint(answer: String => Either[String, String], pause: String => Unit = _ => ())
      extends HttpTransport:
    val calls = AtomicInteger(0)

    override def sendJson(url: String, headers: Map[String, String], body: String): Either[DspyError, HttpResponse] =
      calls.incrementAndGet(): Unit
      val prompt = promptOf(body)
      pause(prompt)
      answer(prompt) match
        case Right(reply) => Right(HttpResponse(200, Map("content-type" -> "application/json"), replyOf(reply)))
        case Left(error)  => Right(HttpResponse(500, Map.empty, errorOf(error)))

    override def streamSse(url: String, headers: Map[String, String], body: String)
        : Either[DspyError, HttpStreamResponse] =
      calls.incrementAndGet(): Unit
      val prompt = promptOf(body)
      pause(prompt)
      answer(prompt) match
        case Right(reply) =>
          val lines = Vector(s"data: ${deltaOf(reply)}", "", s"data: $Stop", "", "data: [DONE]", "")
          Right(HttpStreamResponse(200, Map("content-type" -> "text/event-stream"), VectorLines(lines)))
        case Left(error) => Right(HttpStreamResponse(500, Map.empty, VectorLines(Vector(errorOf(error)))))

  /** The lines of a stream of the endpoint. */
  final class VectorLines(items: Vector[String]) extends ClosableIterator[String]:
    private var index             = 0
    override def hasNext: Boolean = index < items.size
    override def next(): String   =
      val item = items(index)
      index += 1
      item
    override def close(): Unit = index = items.size

  def modelOf(endpoint: ChatEndpoint): OpenAiLanguageModel =
    OpenAiLanguageModel(
      model = "fake-model",
      client = OpenAiClient(apiKey = "FAKE-KEY", baseUrl = "http://test.invalid/v1", transport = endpoint)
    )

  /** The texts of the messages of a chat body, one after the other. */
  def promptOf(body: String): String =
    Json.parse(body).toOption.flatMap(field(_, "messages")).toVector.flatMap(_.elements.toVector).flatMap { message =>
      field(message, "content").toVector.flatMap {
        case Json.String(text) => Vector(text)
        case parts             => parts.elements.toVector.flatMap(field(_, "text")).collect { case Json.String(text) => text }
      }
    }.mkString("\n")

  private def replyOf(reply: String): String =
    Json.Object(
      "id"      -> Json.String("c1"),
      "model"   -> Json.String("fake-model"),
      "choices" -> Json.Array(Json.Object(
        "index"         -> Json.Number(0),
        "message"       -> Json.Object("role" -> Json.String("assistant"), "content" -> Json.String(reply)),
        "finish_reason" -> Json.String("stop")
      )),
      "usage" -> Json.Object(
        "prompt_tokens"     -> Json.Number(3),
        "completion_tokens" -> Json.Number(1),
        "total_tokens"      -> Json.Number(4)
      )
    ).print

  private def deltaOf(reply: String): String =
    Json.Object("choices" -> Json.Array(Json.Object(
      "index"         -> Json.Number(0),
      "delta"         -> Json.Object("content" -> Json.String(reply)),
      "finish_reason" -> Json.Null
    ))).print

  private val Stop: String = """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
    """"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""

  private def errorOf(error: String): String =
    Json.Object("error" -> Json.Object("message" -> Json.String(error), "type" -> Json.String("fake"))).print

  // ── The lines of a file ──

  def linesOf(path: Path): Vector[Json] =
    if !Files.exists(path) then Vector.empty
    else
      Files.readAllLines(path, UTF_8).asScala.toVector.filter(_.nonEmpty).map { line =>
        Json.parse(line).fold(e => throw AssertionError(s"a line is not JSON: $e: $line"), identity)
      }

  def field(json: Json, key: String): Option[Json] = json.fields.collectFirst { case (k, v) if k == key => v }

  def text(json: Json, key: String): Option[String] = field(json, key).collect { case Json.String(value) => value }

  def number(json: Json, key: String): Option[Long] =
    field(json, key).collect { case Json.Number(value) => BigDecimal(value.toString).toLong }

  def bool(json: Json, key: String): Option[Boolean] = field(json, key).collect { case Json.Boolean(value) => value }

  def strings(json: Json, key: String): Vector[String] =
    field(json, key).toVector.flatMap(_.elements).collect { case Json.String(value) => value }
