package dspy4s.harness.cli

import dspy4s.harness.Support
import dspy4s.harness.host.{Behind, Feed, HostError, SessionHandle}
import dspy4s.harness.kernel.*
import zio.blocks.chunk.Chunk
import zio.blocks.schema.json.Json

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The laws of the terminal client (CODE_HARNESS_DESIGN.md, 10.3, 5.5 and 15, phase 7). */
class CliLaws extends munit.FunSuite:

  private val home    = Path.of("/fake/home")
  private val cwd     = Path.of("/fake/work")
  private val request = RequestId("fake-request")

  private val pushRule = GuardRule("exec", ArgMatch.Exact(Json.Object(Chunk(("command", Json.String("git push"))))))
  private val pending  = SessionState.initial.copy(pending = Set(pushRule))

  test("CLI: parse gives the folder, the root and the session, with the defaults") {
    assertEquals(CliArgs.parse(Vector.empty, home, cwd), Right(CliArgs(cwd, home.resolve(".dspy4s/harness"), None)))
    assertEquals(
      CliArgs.parse(Vector("--root", "/fake/root", "--session", "fake-id", "/fake/other"), home, cwd),
      Right(CliArgs(Path.of("/fake/other"), Path.of("/fake/root"), Some(SessionId("fake-id"))))
    )
  }

  test("CLI: parse gives an error with the usage for an unknown flag or a flag with no value") {
    Vector(Vector("--fake"), Vector("--full"), Vector("--root"), Vector("--session")).foreach { args =>
      assert(CliArgs.parse(args, home, cwd).left.exists(_.contains("--root")), args.toString)
    }
  }

  test("CLI: parseLine reads the commands, and a text that does not start with / is a message") {
    assertEquals(parseLine("/stop"), Line.Stop)
    assertEquals(parseLine("/stopall"), Line.StopAll)
    assertEquals(parseLine("/allow once"), Line.Allow(GrantLifetime.Once))
    assertEquals(parseLine("/allow turn"), Line.Allow(GrantLifetime.Turn))
    assertEquals(parseLine("/allow session"), Line.Allow(GrantLifetime.Session))
    assertEquals(parseLine("/close"), Line.Close)
    assertEquals(parseLine("/quit"), Line.Quit)
    assertEquals(parseLine("/fake"), Line.Unknown("/fake"))
    assertEquals(parseLine("fix the build"), Line.Message("fix the build"))
  }

  test("CLI: Stop gives Abort, and Stop all gives Abort and then StopJobs(OfLastTurn)") {
    commandsFor(Line.Stop, SessionState.initial, request) match
      case Right(Vector(Command.Abort(_))) => ()
      case other                           => fail(other.toString)
    commandsFor(Line.StopAll, SessionState.initial, request) match
      case Right(Vector(Command.Abort(_), Command.StopJobs(JobSelector.OfLastTurn, _))) => ()
      case other                                                                        => fail(other.toString)
  }

  test(
    "CLI: Allow gives one Grant for each pending call with the lifetime of the line, and with no pending call an error"
  ) {
    commandsFor(Line.Allow(GrantLifetime.Turn), pending, request) match
      case Right(Vector(Command.Grant(_, rule, GrantLifetime.Turn))) => assertEquals(rule, pushRule)
      case other                                                     => fail(other.toString)
    assert(commandsFor(Line.Allow(GrantLifetime.Once), SessionState.initial, request).isLeft)
  }

  test("CLI: a message gives one Send with the request id, Close gives Close, and Quit gives no command") {
    assertEquals(
      commandsFor(Line.Message("hello"), SessionState.initial, request),
      Right(Vector(Command.Send(request, "hello", Map.empty)))
    )
    commandsFor(Line.Close, SessionState.initial, request) match
      case Right(Vector(Command.Close(SessionOutcome.Parked, _))) => ()
      case other                                                  => fail(other.toString)
    assertEquals(commandsFor(Line.Quit, SessionState.initial, request), Right(Vector.empty))
    assert(commandsFor(Line.Unknown("/fake"), SessionState.initial, request).left.exists(_.contains("/stop")))
  }

  test(
    "CLI: entryText shows the text and the code of a step, the output of a cell and a pending call, and hides a step start"
  ) {
    val log = Support.logOf(
      1000L,
      Entry.StepStarted(0),
      Entry.Acted("", "FAKE-TEXT", Some("FAKE-CODE")),
      Entry.Observed("FAKE-OUTPUT", CellOutcome.Ok, Map.empty),
      Entry.EffectDone(EffectId(1L, 0), EffectResult.NeedsApproval(pushRule))
    )
    assertEquals(entryText(log(0)), None)
    assert(entryText(log(1)).exists(t => t.contains("FAKE-TEXT") && t.contains("FAKE-CODE")))
    assert(entryText(log(2)).exists(_.contains("FAKE-OUTPUT")))
    assert(entryText(log(3)).exists(t => t.contains("git push") && t.contains("/allow")))
  }

  test("CLI: loop sends a message, runs /close and shows its refusal, and ends when the feed ends") {
    val handle = FakeHandle()
    val out    = ByteArrayOutputStream()
    val lines  = "hello\n/close\n"
    val loop   = Thread.ofPlatform().start { () =>
      Cli.loop(handle, BufferedReader(StringReader(lines)), PrintStream(out, true, UTF_8))
    }
    loop.join(10_000)
    assert(!loop.isAlive, "the loop did not end at the end of its input")
    handle.sent.asScala.toVector match
      case Vector(Command.Send(_, "hello", _)) => ()
      case other                               => fail(s"sent: $other")
    handle.ran.asScala.toVector match
      case Vector(Command.Close(SessionOutcome.Parked, _)) => ()
      case other                                           => fail(s"ran: $other")
    assert(out.toString(UTF_8).contains("TurnOpen"), out.toString(UTF_8))
    assert(out.toString(UTF_8).contains("FAKE-REPLY"), out.toString(UTF_8))
  }

  test("CLI: loop ends with no error when the feed ends, also with no end of its input") {
    val handle = FakeHandle(endAfterFirst = true)
    val ended  = java.util.concurrent.atomic.AtomicReference[scala.util.Try[Unit]]()
    // An input that never ends: only the end of the feed can end the loop.
    val input = java.io.PipedReader(java.io.PipedWriter())
    val loop  = Thread.ofPlatform().start { () =>
      ended.set(scala.util.Try(Cli.loop(
        handle,
        BufferedReader(input),
        PrintStream(ByteArrayOutputStream(), true, UTF_8)
      )))
    }
    loop.join(10_000)
    assert(!loop.isAlive, "the loop did not end at the end of the feed")
    assert(Option(ended.get).exists(_.isSuccess), String.valueOf(ended.get))
  }

  test("CLI start: the time from the start of a JVM to the first prompt, and the end at /quit") {
    val root  = Files.createTempDirectory("dspy4s-cli-test-")
    val scope = Files.createDirectories(root.resolve("work"))
    val java  = ProcessHandle.current().info().command().toScala.getOrElse("java")
    val start = System.nanoTime()
    val cli   = ProcessBuilder(
      java,
      "-cp",
      System.getProperty("java.class.path"),
      "dspy4s.harness.cli.Cli",
      "--root",
      root.resolve("host").toString,
      scope.toString
    ).redirectErrorStream(true).start()
    cli.getOutputStream.write("/quit\n".getBytes(UTF_8))
    cli.getOutputStream.close()
    assert(cli.waitFor(120, TimeUnit.SECONDS), "the client did not end")
    val seconds = (System.nanoTime() - start) / 1e9
    val output  = String(cli.getInputStream.readAllBytes(), UTF_8)
    println(f"CLI start: $seconds%.1f s from the start of the JVM to the end at /quit")
    assertEquals(cli.exitValue(), 0, output)
    assert(output.contains("session"), output)
    Support.remove(root)
  }

/** A session for the laws of `Cli.loop`: `send` keeps the commands, `run` refuses each command with `TurnOpen`, and the
  * feed gives one `Acted` with the text `FAKE-REPLY`. With `endAfterFirst`, the feed ends after that entry.
  */
private final class FakeHandle(endAfterFirst: Boolean = false) extends SessionHandle:
  val sent: ConcurrentLinkedQueue[Command] = ConcurrentLinkedQueue()
  val ran: ConcurrentLinkedQueue[Command]  = ConcurrentLinkedQueue()
  private val reply                        = Support.logOf(1000L, Entry.Acted("", "FAKE-REPLY", None)).head

  def id: SessionId                                   = SessionId("fake-session")
  def send(command: Command): Either[HostError, Unit] =
    sent.add(command): Unit
    Right(())
  def run(command: Command): Either[HostError, SessionState] =
    ran.add(command): Unit
    Left(HostError.Rejected(Rejection.TurnOpen))
  def state: SessionState               = SessionState.initial
  def read(after: Long): Vector[Logged] = Vector(reply).filter(_.seq > after)
  def subscribe(capacity: Int): Feed    =
    new Feed:
      private val queue                                          = ConcurrentLinkedQueue[Either[Behind, Logged]](java.util.List.of(Right(reply)))
      def poll(waitMillis: Long): Option[Either[Behind, Logged]] =
        Option(queue.poll()).orElse { Thread.sleep(waitMillis.min(50)); None }
      def ended: Boolean = endAfterFirst && queue.isEmpty
      def close(): Unit  = ()
