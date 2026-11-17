package dspy4s.harness.cli

import dspy4s.harness.HarnessLimits
import dspy4s.harness.host.{Controller, WorkRuntime}
import dspy4s.harness.kernel.*
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.nio.file.Path

/** The laws of `/config` and of the grants of `/status`: a person sees each setting that acts on the session and on its
  * turns (CODE_HARNESS_DESIGN.md, 5.5, 10.6).
  */
class ConfigLaws extends munit.FunSuite:

  private val start = GuardRule("start", ArgMatch.Exact(Json.Object(Chunk(("goal", Json.String("Count the lines."))))))

  private val facts = HostFacts(
    Limits(
      cellMillis = 30_000L,
      cellWallMillis = Some(900_000L),
      callsPerCell = 10_000,
      cellsPerRepl = 300,
      commandSeconds = 600,
      observationChars = 4_000,
      memoryChars = 40_000,
      modelCalls = 60,
      summaryCalls = 64,
      batch = 4,
      depth = 2
    ),
    SandboxFacts(
      backend = Some("Seatbelt"),
      hosts = Vector.empty,
      secretPaths = Vector("/home/u/.ssh", "/home/u/.aws"),
      secretNames = Vector(".env"),
      env = Vector("HOME", "PATH")
    )
  )

  private val client = ClientFacts(Path.of("/home/u/.dspy4s/harness"), "http://endpoint.example/v1/", exchanges = true)

  private def work(host: Option[HostFacts] = Some(facts)): SessionState =
    val config = WorkRuntime
      .config(Path.of("/home/u/shop"), "example-model", HarnessLimits(), Map("reasoning_effort" -> Json.String("low")))
      .copy(host = host)
    SessionState.initial.copy(config = config, closed = false)

  private def lines(text: String): Vector[String] = text.linesIterator.toVector

  test("CLI: /status lists the active grants with their lifetimes, and no grant gives no part") {
    val turn  = GuardRule("exec", ArgMatch.Exact(Json.Object(Chunk(("command", Json.String("git push"))))))
    val state =
      work().copy(grants = Set(ActiveGrant(start, GrantLifetime.Session), ActiveGrant(turn, GrantLifetime.Turn)))
    val text = lines(statusText(state, Map.empty, Vector.empty))
    val from = text.indexOf("Active grants:")
    assert(from >= 0, text.mkString("\n"))
    assertEquals(
      text.slice(from + 1, from + 3),
      Vector("  for this turn: exec \"git push\"", s"  for this session: ${callText(start)}")
    )
    assert(!statusText(work(), Map.empty, Vector.empty).contains("grant"))
  }

  test(
    "CLI: /config gives the session, the model, the mode, the guard, the grants, the limits, the sandbox and the files"
  ) {
    val state = work().copy(grants = Set(ActiveGrant(start, GrantLifetime.Session)))
    val text  = configText(SessionId("abc"), state, client, ModelFacts(Some(128_000L), Vector.empty), "/home/u")
    val all   = lines(text)
    Vector("Session", "Model", "Mode", "Guard", "Grants", "Limits", "Sandbox", "Files")
      .foreach(head => assert(all.contains(head), s"no part $head in\n$text"))
    Vector(
      "  id: abc",
      "  owner: the person",
      "  scope: Directory ~/shop",
      "  strategy: MemoryTree",
      "  name: example-model",
      "  endpoint: http://endpoint.example/v1/",
      "  option reasoning_effort: \"low\"",
      "  context window: 128,000 tokens",
      "  this session: Files^, Shell^, Llm^ and Store^",
      "  rules: none, so each call runs",
      s"  for this session: ${callText(start)}",
      "  steps of a turn: 10",
      "  turns with no person: 8",
      "  time of the guest code of a cell: 30 s",
      "  wall time of a cell: 15 min",
      "  guest API calls of a cell: 10,000",
      "  summary calls of a turn: 64",
      "  backend: Seatbelt",
      "  closed paths: ~/.ssh, ~/.aws",
      "  closed names: .env, and each .env.NAME",
      "  variables of a command: HOME, PATH",
      "  root: ~/.dspy4s/harness",
      "  journal: ~/.dspy4s/harness/sessions/abc.db",
      "  exchange file: ~/.dspy4s/harness/sessions/abc.http.jsonl"
    ).foreach(line => assert(all.contains(line), s"no line [$line] in\n$text"))
    val off = configText(SessionId("abc"), state, client.copy(exchanges = false), ModelFacts.unknown, "/home/u")
    assert(lines(off).contains("  exchange file: off (DSPY_EXCHANGES=off)"), off)
    assert(lines(off).contains("  context window: not known"), off)
  }

  test("CLI: /config gives the rules of the guard with their verdicts, and the ceiling of the controller") {
    val controller = SessionState.initial.copy(config = Controller.config(Path.of("/home/u"), "m", HarnessLimits()))
    val text       = configText(SessionId("c"), controller, client, ModelFacts.unknown, "/home/u")
    val rules      = lines(text).filter(_.startsWith("  Ask: "))
    assert(rules.nonEmpty && rules.forall(_.contains("start")), text)
    assert(lines(text).exists(_.startsWith("  the most for a session that it starts: ")), text)
  }

  test("CLI: /config of an activation with no host facts says so in place of the limits and the sandbox") {
    val text = configText(SessionId("abc"), work(host = None), client, ModelFacts.unknown, "/home/u")
    assert(text.contains("This activation recorded no limits and no sandbox."), text)
    assert(lines(text).contains("  steps of a turn: 10"), text)
  }

  test("CLI: /config parses, and the help lists it") {
    assertEquals(parseLine("/config"), Line.Config)
    assertEquals(parseLine("/config x"), Line.Unknown("/config x"))
    assert(helpText.linesIterator.exists(_.contains("/config")), helpText)
  }
