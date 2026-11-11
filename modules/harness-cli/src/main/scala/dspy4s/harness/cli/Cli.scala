package dspy4s.harness.cli

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, Secrets}
import dspy4s.harness.host.{Behind, HostError, LocalHost, SessionHandle, WorkEnv, WorkRuntime}
import dspy4s.harness.kernel.{EffectResult, Entry, GuardRule, Logged, Position, RequestId, SessionId, TurnOutcome}
import dspy4s.harness.sandbox.{Backends, HostList}
import dspy4s.lm.contracts.{LanguageModel, LmCapabilities, LmMode, LmRequest, LmResponse}
import dspy4s.lm.providers.{OpenAiClient, OpenAiLanguageModel}
import zio.blocks.schema.DynamicValue

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.util.Try
import scala.util.control.NonFatal

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
  * of the client. `--script` runs the loop in the script mode, so a script or an agent can drive the session. `--list`
  * prints `listText` and starts no session. `--session` takes an id or the start of an id (`resolveSession`).
  *
  * The file `<home>/.dspy4s/harness/.env` can give each of these variables (`EnvFile`). A variable of the process wins
  * over the file, apart from the base URL and the key, which come both from the file or both from the process. A line
  * of the file that is not right gives an error and the exit code 2.
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
      case Right(cli) if cli.list =>
        listText(cli.root, ZoneId.systemDefault()) match
          case Left(error) =>
            System.err.println(error)
            1
          case Right(text) =>
            println(text)
            0
      case Right(cli) => EnvFile.read(EnvFile.pathOf(home)) match
          case Left(error) =>
            System.err.println(error)
            2
          case Right(file) => session(cli, home, EnvFile.merge(file, sys.env), secretsOf(sys.env, file))
    System.out.flush()
    sys.exit(code)

  /** Opens the session of `cli` with the variables `env` and the mask `secrets`, runs the loop, and gives the exit
    * code.
    */
  private def session(cli: CliArgs, home: Path, env: Map[String, String], secrets: Secrets): Int =
    WorkEnv.summaryOptions(env) match
      case Left(error) =>
        System.err.println(error)
        2
      case Right(options) => session(cli, home, env, secrets, options)

  /** Opens the session of `cli` with the variables `env`, the mask `secrets` and the summary options `options`, runs
    * the loop, and gives the exit code.
    *
    * The console comes before the host. JLine reads the input while it makes the terminal (`EditorConsole.system`), so
    * a line that the person types after the start of the host stays. Also, only one line of text comes between the open
    * and the loop, so the loop shows each entry that commits after the open.
    */
  private def session(
      cli    : CliArgs,
      home   : Path,
      env    : Map[String, String],
      secrets: Secrets,
      options: DynamicValue.Record
  ): Int =
    val name  = env.getOrElse("DSPY_MODEL", "none")
    val model = env.get("DSPY_TRACE").filter(_.nonEmpty).foldLeft(modelOf(name, env)) { (inner, file) =>
      TracedModel(inner, Path.of(file), secrets.maskText)
    }
    val console = consoleOf(cli, secrets)
    // A SIGTERM runs the shutdown hooks, so this hook gives the terminal its modes back. `close` runs only once.
    val hook = Thread(() => console.close())
    Try(Runtime.getRuntime.addShutdownHook(hook)): Unit
    try
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        val env              = WorkEnv(home, HostList.empty, Backends.detect(), secrets, cli.root, options)
        LocalHost.start(cli.root, WorkRuntime.forWork(env), secrets) match
          case Left(error) =>
            System.err.println(printable(s"The host did not start: $error"))
            1
          case Right(host) =>
            try
              val target = cli.session match
                case None => Right((
                    SessionId(UUID.randomUUID().toString),
                    Some(WorkRuntime.config(cli.scope, name, HarnessLimits()))
                  ))
                // The journal files, not the catalog, give the sessions, so a session with no row still opens.
                case Some(wanted) =>
                  LocalHost.journals(cli.root).left.map(errorText).flatMap(resolveSession(_, wanted)).map(id =>
                    (id, None)
                  )
              val opened =
                for
                  (id, config) <- target
                  handle       <- host.open(id, config).left.map(e => s"The session ${id.value} did not open: $e")
                yield handle
              opened match
                case Left(error) =>
                  System.err.println(printable(error))
                  1
                case Right(handle) =>
                  // The id can come from the name of a file, so it goes through `printable` as each other text.
                  console.print(
                    s"The session ${visible(handle.id.value)} is open. Type a message, or /help for the commands."
                  )
                  if loop(handle, console, cli.script) then 0 else 1
            finally
              // The person gets the terminal back first, because the close of the host can wait for the stop of jobs.
              console.close()
              host.close()
      }
    finally
      console.close()
      Try(Runtime.getRuntime.removeShutdownHook(hook)): Unit

  /** The console of the client: the line editor at a terminal, with its history at `<root>/history`. Plain streams in
    * the script mode, and for a pipe.
    */
  private def consoleOf(cli: CliArgs, secrets: Secrets): ClientConsole =
    val editor = if cli.script then None else EditorConsole.system(cli.root.resolve("history"), secrets.maskText)
    editor.getOrElse(PlainConsole(BufferedReader(InputStreamReader(System.in, UTF_8)), System.out))

  /** The text of `--list`: the sessions of the host at `root` (10.2), with `sessionsText`, and a line for each file of
    * the folder of the journals that is not a journal. `LocalHost.sessions` rebuilds the catalog as the start of a host
    * does, so a journal with no row is in the list too. It starts no session, and a root with no folder of journals
    * gets no file.
    */
  def listText(root: Path, zone: ZoneId): Either[String, String] =
    LocalHost.sessions(root).left.map(errorText).map { rebuilt =>
      val bad =
        rebuilt.failed.map((file, reason) => s"Not listed: ${visible(file.getFileName.toString)}: ${visible(reason)}")
      (sessionsText(rebuilt.rows, zone) +: bad).mkString("\n")
    }

  /** The text of an error of the host for the person. */
  private def errorText(error: HostError): String =
    error match
      case HostError.OpenFailed(message) => message
      case other                         => other.toString

  /** The mask of the client: each secret-like variable of the process and of the variables file (5.6), with the rules
    * of `Secrets.known`. A short value, as the placeholder key `unused`, is not a known value, so the mask never
    * changes a word of a cell. The process keeps its variables also when the file gives the connection, so the mask
    * covers both values of a name.
    */
  private[cli] def secretsOf(process: Map[String, String], file: Map[String, String] = Map.empty): Secrets =
    val fromProcess = Secrets.known(process, Map.empty).values
    // A name with another value in the process gets a second mark, so the mask keeps both values.
    val fromFile = Secrets.known(file, Map.empty).values.map { (name, value) =>
      if fromProcess.get(name).exists(_ != value) then s"$name (.env)" -> value else name -> value
    }
    Secrets(fromFile ++ fromProcess)

  /** The model of the variables `env`, or a model whose calls fail with the reason. */
  private[cli] def modelOf(name: String, env: Map[String, String]): LanguageModel =
    val base = env.getOrElse("OPENAI_BASE_URL", OpenAiClient.defaultBaseUrl)
    OpenAiLanguageModel.fromEnv(name, base, env = env) match
      case Right(lm) if env.get("DSPY_NATIVE_REASONING").contains("true") =>
        lm.copy(capabilities = Some(LmCapabilities(reasoning = true)))
      case Right(lm)    => lm
      case Left(reason) => NoModel(s"no model: $reason")

  /** The loop of the client on plain streams: `loop` with a `PlainConsole` of `in` and `out`. */
  def loop(handle: SessionHandle, in: BufferedReader, out: PrintStream, script: Boolean = false): Boolean =
    loop(handle, PlainConsole(in, out), script)

  /** The loop of the client: a thread prints `entryText` of each entry of the feed of `handle`, and another thread
    * reads the lines of `console` and runs the commands of `commandsFor`.
    *
    *   - Ctrl-C on an empty line during a turn is `/stop`. With no open turn, it prints a hint and sends nothing. A
    *     Ctrl-C while no read waits does the same (`ClientConsole.onInterrupt`).
    *   - Each text goes through `printable` before it reaches the console, so no text sends a control character.
    *   - `/help` prints `helpText`.
    *   - A message, `/stop` and `/stopall` go through `send`. `/close`, `/allow` and `/stopjob` go through `run`, so
    *     the person sees a refusal, for example `Close` during a turn.
    *   - `/jobs` and `/status` print `jobsText` and `statusText`, and `/reasoning` sets the view of the reasoning of
    *     the next steps. They change nothing in the session. `/allow N` grants the call that the entry N asked for.
    *   - After a turn that ends `Aborted`, the client prints `stopNotice`: each job of that turn that still runs, and
    *     how to stop it (10.3).
    *   - An error of `commandsFor` and a refused command print one line, and the loop goes on.
    *   - After `Behind`, the client reads the journal after its last `seq`, so it shows each entry once.
    *   - It ends at `/quit`, at the end of the input, or when the feed ends, for example after `Close` or a failed
    *     write. It gives false only when the feed ended and the session did not close, as after a failed write.
    *   - In the script mode (`script`), each line waits until the session has no open turn, and each command goes
    *     through `run`, so the commands commit in the order of the lines. The end of the input also waits for the open
    *     turn. So `printf 'm1\nm2\n' | cli --script` gives two turns, and the second message starts the second turn. A
    *     line waits also for a turn that started before it, for example after a reopen or a job message. `/quit` and
    *     `/stop` wait too, so a person stops a script with the end of the process.
    */
  def loop(handle: SessionHandle, console: ClientConsole, script: Boolean): Boolean =
    // The state before the feed, so an entry that commits between the two reads is in the feed or in the read after
    // it, and `Shown` shows it once (10.2: a client misses nothing).
    val before = handle.state
    val start  = before.nextSeq - 1
    val feed   = handle.subscribe(1024)
    val done   = CountDownLatch(1)
    val shown  = Shown(start)
    val view   = AtomicReference(ReasoningView.Short)
    // The `seq` of the `TurnStarted` of the last turn that the client saw: the turn of a stop notice.
    val turn                      = AtomicLong(before.lastTurn)
    def print(text: String): Unit = console.print(printable(text))
    // An entry that the client did not show yet, and the stop notice after a turn that a stop ended.
    def show(logged: Logged): Unit =
      shown.next(logged).foreach { entry =>
        entryText(entry, view.get).foreach(print)
        entry.entry match
          case Entry.TurnStarted                    => turn.set(entry.seq)
          case Entry.TurnEnded(TurnOutcome.Aborted) =>
            stopNotice(handle.state, turn.get, jobStarts(handle.read(turn.get)), handle.jobStatus).foreach(print)
          case _ => ()
      }
    // An entry of the feed, or the entries of the journal after the last one shown after `Behind`.
    def take(item: Either[Behind, Logged]): Unit =
      item match
        case Right(logged) => show(logged)
        case Left(_)       => handle.read(shown.last).foreach(show)
    handle.read(start).foreach(l => take(Right(l)))
    val printer = Thread.ofPlatform().daemon(true).name("harness-cli-printer").start { () =>
      while done.getCount > 0 && !feed.ended do feed.poll(200).foreach(take)
      done.countDown()
    }
    // The script mode: the reader waits until the session has no open turn, or until the loop ends.
    def idle(): Unit = while handle.state.position != Position.NoTurn && !done.await(50, TimeUnit.MILLISECONDS) do ()
    // The rule of the entry `seq`, when that entry asked for an approval (5.5).
    def asked(seq: Long): Option[GuardRule] =
      handle.read(seq - 1).headOption.filter(_.seq == seq).collect {
        case Logged(_, _, Entry.EffectDone(_, EffectResult.NeedsApproval(rule))) => rule
      }
    // One line of the person, or the `Stop` of a Ctrl-C. `Quit` ends the loop.
    def act(parsed: Line): Unit =
      parsed match
        case Line.Quit          => done.countDown()
        case Line.Wait(seconds) => done.await(seconds.toLong, TimeUnit.SECONDS): Unit
        case Line.Jobs          => print(jobsText(handle.state, jobStarts(handle.read(-1)), handle.jobStatus))
        case Line.Status        =>
          val now = handle.state
          print(statusText(now, handle.jobStatus, pendingCalls(now, handle.read(now.lastTurn))))
        case Line.Help            => print(helpText)
        case Line.Reasoning(next) =>
          view.set(next)
          print(s"The client now shows ${reasoningWords(next)} of each step.")
        case _ =>
          // The person must see a refusal of `Close`, `Allow` and `StopJob`, so these wait for the commit.
          val waits = script ||
            (parsed match
              case Line.Close | Line.Allow(_, _) | Line.StopJob(_) => true
              case _                                               => false)
          commandsFor(parsed, handle.state, RequestId(UUID.randomUUID().toString), asked) match
            case Left(error)     => print(error)
            case Right(commands) => commands.foreach { command =>
                val result = if waits then handle.run(command).map(_ => ()) else handle.send(command)
                result.left.foreach(error => print(s"The session refused the command: $error"))
              }
    def line(text: String): Unit =
      if script then idle()
      act(parseLine(text))
    // Ctrl-C: `/stop` during a turn, and a hint with no open turn.
    def interrupt(): Unit =
      if handle.state.position == Position.NoTurn then
        print("No turn is open. /quit ends the client, and the session stays open.")
      else act(Line.Stop)
    console.onInterrupt(() => interrupt())
    // The reader can wait for a line with no end, so it is a daemon thread, and the loop does not wait for it.
    Thread.ofPlatform().daemon(true).name("harness-cli-reader").start { () =>
      try
        var typed = console.read()
        while typed != Typed.End && done.getCount > 0 do
          typed match
            case Typed.Text(text) => line(text)
            case _                => interrupt()
          if done.getCount > 0 then typed = console.read()
        // The turn of the last line of a script ends before the loop.
        if script then idle()
      catch case NonFatal(e) => Try(print(s"The input of the client failed: $e")): Unit
      finally done.countDown()
    }: Unit
    done.await()
    printer.join(1_000)
    Iterator.continually(feed.poll()).takeWhile(_.isDefined).flatten.foreach(take)
    val failed = feed.ended && !handle.state.closed
    feed.close()
    if failed then print("The session stopped: its host can write no more. The journal keeps each committed entry.")
    !failed

/** The words of a view of the reasoning, for the answer to `/reasoning`. */
private def reasoningWords(view: ReasoningView): String =
  view match
    case ReasoningView.Off   => "no reasoning"
    case ReasoningView.Short => "the start of the reasoning"
    case ReasoningView.Full  => "all the reasoning"

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
