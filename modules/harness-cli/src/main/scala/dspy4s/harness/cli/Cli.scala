package dspy4s.harness.cli

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, Secrets}
import dspy4s.harness.host.{Behind, LocalHost, SessionHandle, WorkEnv, WorkRuntime}
import dspy4s.harness.kernel.{Logged, Position, RequestId, SessionId}
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.{LanguageModel, LmCapabilities, LmMode, LmRequest, LmResponse}
import dspy4s.lm.providers.{OpenAiClient, OpenAiLanguageModel}
import zio.blocks.schema.DynamicValue

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** The terminal client of a work session (CODE_HARNESS_DESIGN.md, 15, phase 7).
  *
  * `main` reads the arguments, makes the model from `OPENAI_BASE_URL`, `DSPY_MODEL` and `OPENAI_API_KEY`, starts a
  * `LocalHost` with a `WorkRuntime`, and opens the session: a new one in the folder, or the session of `--session` with
  * its own configuration. It prints the id of the session, then runs `loop`. The key goes only to the model, and the
  * mask of the host hides it in each entry (5.6). With none of these variables, the client still starts, and the first
  * policy call fails its turn with the error of the model. A session that another host has open gives an error and the
  * exit code 1. `DSPY_NATIVE_REASONING=true` declares that the model gives its reasoning apart from its text.
  * `DSPY_SUMMARY_OPTIONS` is a JSON object, the option bag of each summary request of the memory tree (7.1), for
  * example `{"chat_template_kwargs": {"enable_thinking": false}}`. A value that is not a JSON object gives an error and
  * the exit code 2. `DSPY_TRACE=FILE` writes each model call to `FILE` as one JSON line (`TracedModel`), with the mask
  * of the client. `--script` runs the loop in the script mode, so a script or an agent can drive the session.
  *
  * A proposal for the review: the CLI runs on JDK 21. Its start time is a check of this phase, and an AOT cache on JDK
  * 25 comes later only if the start is too slow (choice 8).
  */
object Cli:

  def main(args: Array[String]): Unit =
    val home = Path.of(sys.props.getOrElse("user.home", "."))
    val cwd  = Path.of("").toAbsolutePath
    val code = CliArgs.parse(args.toVector, home, cwd) match
      case Left(usage) =>
        System.err.println(usage)
        2
      case Right(cli) => session(cli, home)
    System.out.flush()
    sys.exit(code)

  /** Opens the session of `cli`, runs the loop, and gives the exit code. */
  private def session(cli: CliArgs, home: Path): Int =
    WorkEnv.summaryOptions(sys.env) match
      case Left(error) =>
        System.err.println(error)
        2
      case Right(options) => session(cli, home, options)

  /** Opens the session of `cli` with the summary options `options`, runs the loop, and gives the exit code. */
  private def session(cli: CliArgs, home: Path, options: DynamicValue.Record): Int =
    val name    = sys.env.getOrElse("DSPY_MODEL", "none")
    val secrets = secretsOf(sys.env)
    val model   = sys.env.get("DSPY_TRACE").filter(_.nonEmpty).foldLeft(modelOf(name)) { (inner, file) =>
      TracedModel(inner, Path.of(file), secrets.maskText)
    }
    RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
      given RuntimeContext = RuntimeEnvironment.current
      val env              = WorkEnv(home, HostList.empty, Backends.detect(), secrets, cli.root, options)
      LocalHost.start(cli.root, WorkRuntime.forWork(env), secrets) match
        case Left(error) =>
          System.err.println(s"The host did not start: $error")
          1
        case Right(host) =>
          try
            val (id, config) = cli.session.fold(
              (SessionId(UUID.randomUUID().toString), Some(WorkRuntime.config(cli.scope, name, HarnessLimits())))
            )(id => (id, None))
            host.open(id, config) match
              case Left(error) =>
                System.err.println(s"The session ${id.value} did not open: $error")
                1
              case Right(handle) =>
                println(s"The session ${id.value} is open. Type a message, or one of $commandList.")
                val in = BufferedReader(InputStreamReader(System.in, UTF_8))
                if loop(handle, in, System.out, cli.script) then 0 else 1
          finally host.close()
    }

  /** The mask of the client: each secret-like variable of the environment (5.6), with the rules of `Secrets.known`. A
    * short value, as the placeholder key `unused`, is not a known value, so the mask never changes a word of a cell.
    */
  private[cli] def secretsOf(env: Map[String, String]): Secrets = Secrets.known(env, Map.empty)

  /** The model of the environment, or a model whose calls fail with the reason. */
  private def modelOf(name: String): LanguageModel =
    val base = sys.env.getOrElse("OPENAI_BASE_URL", OpenAiClient.defaultBaseUrl)
    OpenAiLanguageModel.fromEnv(name, base) match
      case Right(lm) if sys.env.get("DSPY_NATIVE_REASONING").contains("true") =>
        lm.copy(capabilities = Some(LmCapabilities(reasoning = true)))
      case Right(lm)    => lm
      case Left(reason) => NoModel(s"no model: $reason")

  /** The loop of the client: a thread prints `entryText` of each entry of the feed of `handle`, and this thread reads
    * the lines of `in` and runs the commands of `commandsFor`.
    *
    *   - A message, `/stop` and `/stopall` go through `send`. `/close` and `/allow` go through `run`, so the person
    *     sees a refusal, for example `Close` during a turn.
    *   - An error of `commandsFor` and a refused command print one line, and the loop goes on.
    *   - After `Behind`, the client reads the journal after its last `seq`, so it shows each entry once.
    *   - It ends at `/quit`, at the end of `in`, or when the feed ends, for example after `Close` or a failed write. It
    *     gives false only when the feed ended and the session did not close, as after a failed write.
    *   - In the script mode (`script`), each line waits until the session has no open turn, and each command goes
    *     through `run`, so the commands commit in the order of the lines. The end of `in` also waits for the open turn.
    *     So `printf 'm1\nm2\n' | cli --script` gives two turns, and the second message starts the second turn. A line
    *     waits also for a turn that started before it, for example after a reopen or a job message. `/quit` and `/stop`
    *     wait too, so a person stops a script with the end of the process.
    */
  def loop(handle: SessionHandle, in: BufferedReader, out: PrintStream, script: Boolean = false): Boolean =
    // The state before the feed, so an entry that commits between the two reads is in the feed or in the read after
    // it, and `Shown` shows it once (10.2: a client misses nothing).
    val start                     = handle.state.nextSeq - 1
    val feed                      = handle.subscribe(1024)
    val done                      = CountDownLatch(1)
    val shown                     = Shown(start)
    def print(text: String): Unit = out.synchronized(out.println(text))
    // An entry of the feed, or the entries of the journal after the last one shown after `Behind`.
    def take(item: Either[Behind, Logged]): Unit =
      item match
        case Right(logged) => shown.next(logged).flatMap(entryText).foreach(print)
        case Left(_)       => handle.read(shown.last).foreach(l => shown.next(l).flatMap(entryText).foreach(print))
    handle.read(start).foreach(l => take(Right(l)))
    val printer = Thread.ofPlatform().daemon(true).name("harness-cli-printer").start { () =>
      while done.getCount > 0 && !feed.ended do feed.poll(200).foreach(take)
      done.countDown()
    }
    // The script mode: the reader waits until the session has no open turn, or until the loop ends.
    def idle(): Unit = while handle.state.position != Position.NoTurn && !done.await(50, TimeUnit.MILLISECONDS) do ()
    // The reader can wait for a line with no end, so it is a daemon thread, and the loop does not wait for it.
    Thread.ofPlatform().daemon(true).name("harness-cli-reader").start { () =>
      try
        var line = in.readLine()
        while line != null && done.getCount > 0 do
          if script then idle()
          val parsed = parseLine(line)
          if parsed == Line.Quit then done.countDown()
          else
            parsed match
              case Line.Wait(seconds) => done.await(seconds.toLong, TimeUnit.SECONDS): Unit
              case _                  => ()
            commandsFor(parsed, handle.state, RequestId(UUID.randomUUID().toString)) match
              case Left(error)     => print(error)
              case Right(commands) => commands.foreach { command =>
                  val result = parsed match
                    case _ if script                => handle.run(command).map(_ => ())
                    case Line.Close | Line.Allow(_) => handle.run(command).map(_ => ())
                    case _                          => handle.send(command)
                  result.left.foreach(error => print(s"The session refused the command: $error"))
                }
            line = in.readLine()
        // The turn of the last line of a script ends before the loop.
        if script then idle()
      catch case _: java.io.IOException => ()
      finally done.countDown()
    }: Unit
    done.await()
    printer.join(1_000)
    Iterator.continually(feed.poll()).takeWhile(_.isDefined).flatten.foreach(take)
    val failed = feed.ended && !handle.state.closed
    feed.close()
    if failed then print("The session stopped: its host can write no more. The journal keeps each committed entry.")
    !failed

/** The last `seq` that a client showed, so it shows each entry once. */
private final class Shown(start: Long):
  @volatile private var seen = start

  def last: Long = seen

  /** The entry when it is newer than the last one, and it becomes the last one. */
  def next(logged: Logged): Option[Logged] =
    synchronized {
      if logged.seq > seen then
        seen = logged.seq
        Some(logged)
      else None
    }

/** A model for a client with no model in its environment: each call fails with `reason`. */
private final class NoModel(reason: String) extends LanguageModel:
  override val id: String                                                                    = "none"
  override val mode: LmMode                                                                  = LmMode.Chat
  override def call(request: LmRequest)(using RuntimeContext): Either[DspyError, LmResponse] =
    Left(RuntimeError("cli", reason))
