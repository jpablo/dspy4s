package dspy4s.harness.cli

import dspy4s.adapters.ChatAdapter
import dspy4s.core.contracts.{DspyError, RuntimeContext, RuntimeError}
import dspy4s.core.runtime.RuntimeEnvironment
import dspy4s.harness.{HarnessLimits, Secrets}
import dspy4s.harness.guest.JobStatus
import dspy4s.harness.inspect.Inspector
import dspy4s.harness.host.{
  Behind,
  Controller,
  ExchangeFile,
  Feed,
  HostError,
  LocalHost,
  SessionHandle,
  WorkEnv,
  WorkRuntime
}
import dspy4s.harness.kernel.{EffectResult, Entry, GuardRule, Logged, Position, ReleaseItem, RequestId, SessionId}
import dspy4s.harness.kernel.{SessionOutcome, TurnOutcome}
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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
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
  * the exit code 2. Each HTTP exchange with the model endpoint goes to the exchange file of the session
  * (`ExchangeFile`), with the mask of the client, and `DSPY_EXCHANGES=off` turns the file off. `--script` runs the loop
  * in the script mode, so a script or an agent can drive the session. `--list` prints `listText` and starts no session.
  * `--session` takes an id or the start of an id (`resolveSession`).
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
      case Right(cli) if cli.inspect => inspect(cli.root, cli.port)
      case Right(cli) if cli.list    =>
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
    * the loop, and gives the exit code. A host that does not start and a session that does not open print the error of
    * the host in words (`HostError.words`), and give the exit code 1.
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
    val name    = env.getOrElse("DSPY_MODEL", "none")
    val model   = modelOf(name, env)
    val console = consoleOf(cli, secrets, env)
    // A SIGTERM runs the shutdown hooks, so this hook gives the terminal its modes back. `close` runs only once.
    val hook = Thread(() => console.close())
    Try(Runtime.getRuntime.addShutdownHook(hook)): Unit
    try
      RuntimeEnvironment.withSettings(RuntimeContext(lm = Some(model), adapter = Some(ChatAdapter()))) {
        given RuntimeContext = RuntimeEnvironment.current
        val work             =
          WorkEnv(home, HostList.empty, Backends.detect(), secrets, cli.root, options, ExchangeFile.enabled(env))
        LocalHost.startWith(cli.root, peers => WorkRuntime.forHost(work)(peers), secrets) match
          case Left(error) =>
            System.err.println(printable(s"The host did not start: ${error.words}"))
            1
          case Right(host) =>
            try
              val desk   = HostDesk(host, Controller.config(home, name, HarnessLimits()))
              val opened =
                if cli.controller then desk.controller.left.map(e => s"The controller did not open: ${e.words}")
                else
                  val target = cli.session match
                    case None => Right((
                        SessionId(UUID.randomUUID().toString),
                        Some(WorkRuntime.config(cli.scope, name, HarnessLimits()))
                      ))
                    // The journal files, not the catalog, give the sessions, so a session with no row still opens. A
                    // session whose owner is a session opens only with its owner (`openRefusal`).
                    case Some(wanted) =>
                      LocalHost.journals(cli.root).left.map(_.words).flatMap(resolveSession(_, wanted)).flatMap {
                        id =>
                          openRefusal(host.list.find(_.session == id)).toLeft((id, None))
                      }
                  for
                    (id, config) <- target
                    handle       <- host.open(id, config).left.map(e => s"The session ${id.value} did not open: ${e.words}")
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
                  if loop(handle, console, cli.script, Some(desk), home.toString) then 0 else 1
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
  private def consoleOf(cli: CliArgs, secrets: Secrets, env: Map[String, String]): ClientConsole =
    // NO_COLOR with any value turns the colors off (no-color.org).
    val color  = !env.get("NO_COLOR").exists(_.nonEmpty)
    val editor = if cli.script then None else EditorConsole.system(cli.root.resolve("history"), secrets.maskText, color)
    editor.getOrElse(PlainConsole(BufferedReader(InputStreamReader(System.in, UTF_8)), System.out))

  /** The text of `--list`: the sessions of the host at `root` (10.2), with `sessionsText`, and a line for each file of
    * the folder of the journals that is not a journal. `LocalHost.sessions` rebuilds the catalog as the start of a host
    * does, so a journal with no row is in the list too. It starts no session, and a root with no folder of journals
    * gets no file. An error of the host gives its words (`HostError.words`).
    */
  def listText(root: Path, zone: ZoneId): Either[String, String] =
    LocalHost.sessions(root).left.map(_.words).map { rebuilt =>
      val bad =
        rebuilt.failed.map((file, reason) => s"Not listed: ${visible(file.getFileName.toString)}: ${visible(reason)}")
      (sessionsText(rebuilt.rows, zone) +: bad).mkString("\n")
    }

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

  /** Serves the inspector of `root` on `port` until the process ends (docs/code-harness/OBSERVABILITY.md, 5.1). The
    * server runs on its own threads, so this thread only waits, and Ctrl-C ends the process.
    */
  private def inspect(root: Path, port: Int): Int =
    Inspector.start(root, port) match
      case Left(error) =>
        System.err.println(error)
        1
      case Right(inspector) =>
        Try(Runtime.getRuntime.addShutdownHook(Thread(() => inspector.close()))): Unit
        println(s"The inspector of $root runs at ${inspector.url} . Ctrl-C ends it.")
        Try(Thread.currentThread().join()): Unit
        0

  /** The model of the variables `env`, or a model whose calls fail with the reason. */
  private[cli] def modelOf(name: String, env: Map[String, String]): LanguageModel =
    val base = env.getOrElse("OPENAI_BASE_URL", OpenAiClient.defaultBaseUrl)
    OpenAiLanguageModel.fromEnv(name, base, env = env) match
      case Right(lm) if env.get("DSPY_NATIVE_REASONING").contains("true") =>
        lm.copy(capabilities = Some(LmCapabilities(reasoning = true)))
      case Right(lm)    => lm
      case Left(reason) => NoModel(s"no model: $reason")

  /** The loop of the client on plain streams: `loop` with a `PlainConsole` of `in` and `out`. */
  def loop(
      handle: SessionHandle,
      in    : BufferedReader,
      out   : PrintStream,
      script: Boolean      = false,
      desk  : Option[Desk] = None
  ): Boolean = loop(handle, PlainConsole(in, out), script, desk)

  /** The loop of the client with no desk: one session, as in phase 7. */
  def loop(handle: SessionHandle, console: ClientConsole, script: Boolean): Boolean =
    loop(handle, console, script, None, "")

  /** The loop of the client with `desk`, and with no home folder for the footer of a rich console. */
  def loop(handle: SessionHandle, console: ClientConsole, script: Boolean, desk: Option[Desk]): Boolean =
    loop(handle, console, script, desk, "")

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
    *   - A console with the screen of CLI_SCREEN.md (`ClientConsole.rich`) gets the rows of `Screen.after` for each
    *     entry, in place of `entryText`, and it reads the live block (`Screen.live`) and the footer (`Screen.footer`)
    *     of the session that the client shows. Ctrl-O switches the detail of a cell, and `/show ID` prints a cell or a
    *     call of that session in full. `home` is the home folder, which the footer shows as `~`. A plain console keeps
    *     the lines of `entryText`.
    *   - An error of `commandsFor` and a refused command print one line, and the loop goes on. A refused command prints
    *     the error of the host in words (`HostError.words`), for example `The session refused the command: a turn is
    *     open`.
    *   - After `Behind`, the client reads the journal after its last `seq`, so it shows each entry once.
    *   - With no desk, it ends at `/quit`, at the end of the input, or when the feed ends, for example after `Close` or
    *     a failed write. It gives false only when the feed ended and the session did not close, as after a failed
    *     write.
    *   - With a desk, only `/quit` and the end of the input end it. The end of the feed of the session that the client
    *     shows does not end it. When that session ends in another way than a `/close` of this client, for example after
    *     a failed write, the client prints one line that names `/controller` and `/open ID`. Then it waits for the next
    *     line.
    *   - In the script mode (`script`), each line waits until the session has no open turn, and each command goes
    *     through `run`, so the commands commit in the order of the lines. The end of the input also waits for the open
    *     turn. So `printf 'm1\nm2\n' | cli --script` gives two turns, and the second message starts the second turn. A
    *     line waits also for a turn that started before it, for example after a reopen or a job message. `/quit` and
    *     `/stop` wait too, so a person stops a script with the end of the process.
    *   - With a desk (8.7), `/sessions` prints the card of each session, `/open ID` shows the session `ID`, `/reopen
    *     ID` opens an ended session again and shows it, and `/controller` shows the controller: the client then
    *     subscribes to the feed of that session, prints its entries, and sends the commands to it. `/close OUTCOME`
    *     prints the release plan, asks the person to confirm each irreversible item with a line `yes`, and runs the
    *     release through the desk. After a release that ran, the client shows the controller. With no desk, these lines
    *     print that the client has one session, and `/close OUTCOME` sends `Close` with no release.
    */
  def loop(handle: SessionHandle, console: ClientConsole, script: Boolean, desk: Option[Desk], home: String): Boolean =
    val done                      = CountDownLatch(1)
    val rich                      = console.rich
    val start                     = if rich then ReasoningView.Line else ReasoningView.Short
    val look                      = AtomicReference(Look(Detail.Compact, start, console.glyphs))
    def print(text: String): Unit = console.print(printable(text))
    // True while the reader runs a desk line. A release ends the session that the client shows, and then the client
    // shows the controller, so the printer prints no end notice for that session.
    val atDesk = AtomicBoolean(false)
    // The state before the feed, so an entry that commits between the two reads is in the feed or in the read after
    // it, and `Shown` shows it once (10.2: a client misses nothing).
    val view = View(handle, handle.state.nextSeq - 1, () => look.get, print, Option.when(rich)(console.printRows))
    if rich then
      console.watch(
        () =>
          Screen.live(
            view.screen,
            view.handle.state,
            System.currentTimeMillis(),
            look.get,
            view.handle.activity,
            view.handle.draft
          ),
        () =>
          val h = view.handle
          Some(Screen.footer(h.state, h.memoryUse, runningJobs(h), home, console.glyphs))
      )
      console.onDetail(() =>
        look.updateAndGet(l => l.copy(detail = if l.detail == Detail.Full then Detail.Compact else Detail.Full)): Unit
      )
    // With a desk, the end of the feed does not end the loop. The printer prints the end notice once, and it waits
    // until the reader shows another session or the loop ends.
    val printer = Thread.ofPlatform().daemon(true).name("harness-cli-printer").start { () =>
      while done.getCount > 0 && (desk.nonEmpty || !view.ended) do
        if !view.ended then view.poll(200)
        else
          if desk.nonEmpty && !atDesk.get then view.endOnce().foreach(id => print(endedText(id)))
          done.await(200, TimeUnit.MILLISECONDS): Unit
      done.countDown()
    }
    // The script mode: the reader waits until the session has no open turn, until its feed ends, or until the loop
    // ends.
    def idle(): Unit =
      while !view.ended && view.handle.state.position != Position.NoTurn && !done.await(50, TimeUnit.MILLISECONDS) do ()
    // The rule of the entry `seq` of the session that the client shows, when that entry asked for an approval (5.5).
    def asked(seq: Long): Option[GuardRule] =
      view.handle.read(seq - 1).headOption.filter(_.seq == seq).collect {
        case Logged(_, _, Entry.EffectDone(_, EffectResult.NeedsApproval(rule))) => rule
      }
    // One line of the person, or the `Stop` of a Ctrl-C, for the session that the client shows. `Quit` ends the loop.
    def act(parsed: Line): Unit =
      val current = view.handle
      parsed match
        case Line.Quit          => done.countDown()
        case Line.Wait(seconds) => done.await(seconds.toLong, TimeUnit.SECONDS): Unit
        case Line.Jobs          => print(jobsText(current.state, jobStarts(current.read(-1)), current.jobStatus))
        case Line.Status        =>
          val now = current.state
          print(statusText(now, current.jobStatus, pendingCalls(now, current.read(now.lastTurn))))
        case Line.Help                            => print(helpText)
        case _ if deskLine(parsed, desk.nonEmpty) =>
          atDesk.set(true)
          try
            val next = deskStep(desk, parsed, current, console, print)
            if next ne current then view.show(next)
          finally atDesk.set(false)
        case Line.Reasoning(next) =>
          look.updateAndGet(_.copy(reasoning = next)): Unit
          print(s"The client now shows ${reasoningWords(next)} of each step.")
        case Line.Show(id) => Screen.show(Screen.of(current.read(-1)), id, look.get) match
            case Left(error) => print(error)
            case Right(rows) => console.printRows(rows)
        case _ =>
          // The person must see a refusal of `Close`, `Allow` and `StopJob`, so these wait for the commit.
          val waits = script ||
            (parsed match
              case Line.Close(_, _) | Line.Allow(_, _) | Line.StopJob(_) => true
              case _                                                     => false)
          commandsFor(parsed, current.state, RequestId(UUID.randomUUID().toString), asked) match
            case Left(error)     => print(error)
            case Right(commands) => commands.foreach { command =>
                val result = if waits then current.run(command).map(_ => ()) else current.send(command)
                result.left.foreach(error => print(s"The session refused the command: ${error.words}"))
              }
    def line(text: String): Unit =
      if script then idle()
      act(parseLine(text))
    // Ctrl-C: `/stop` during a turn, and a hint with no open turn.
    def interrupt(): Unit =
      if view.handle.state.position == Position.NoTurn then
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
    view.drain()
    val failed = view.ended && !view.handle.state.closed
    view.close()
    if failed then print("The session stopped: its host can write no more. The journal keeps each committed entry.")
    !failed

  /** True for a line that goes through the desk (8.7): `/sessions`, `/open`, `/reopen` and `/controller`, and `/close`
    * when the client has a desk.
    */
  private def deskLine(line: Line, desk: Boolean): Boolean =
    line match
      case Line.Sessions | Line.Open(_) | Line.Reopen(_) | Line.Controller => true
      case Line.Close(_, _)                                                => desk
      case _                                                               => false

  /** Runs a desk line of the loop (8.7), and gives the session that the client shows after it. The confirmation of an
    * irreversible item of a release is the next line of `console`. A release ends the session, so after a release that
    * ran, it gives the controller. A `/close` of the controller prints that the controller never ends. A session that
    * does not open prints the error of the host in words (`HostError.words`).
    */
  private def deskStep(
      desk   : Option[Desk],
      line   : Line,
      handle : SessionHandle,
      console: ClientConsole,
      print  : String => Unit
  ): SessionHandle =
    def shown(opened: Either[HostError, SessionHandle], id: SessionId): SessionHandle =
      opened match
        case Right(next) =>
          print(s"The client shows the session ${next.id.value}.")
          next
        case Left(HostError.Ended) =>
          print(s"The session ${id.value} has ended. Type /reopen ${id.value} to open it again.")
          handle
        case Left(error) =>
          print(s"The session ${id.value} did not open: ${error.words}")
          handle
    desk match
      case None =>
        print("This client shows one session. Start the client with --controller to reach the other sessions.")
        handle
      case Some(d) => line match
          case Line.Sessions =>
            val cards = d.cards
            if cards.isEmpty then print("The host has no session.") else cards.foreach(card => print(cardText(card)))
            handle
          case Line.Open(id)               => shown(d.open(id), id)
          case Line.Reopen(id)             => shown(d.reopen(id), id)
          case Line.Controller             => shown(d.controller, Controller.id)
          case Line.Close(outcome, reason) =>
            if release(d, handle, outcome, reason, console, print) then shown(d.controller, Controller.id) else handle
          case _ => handle

  /** `/close OUTCOME` with a desk (8.7): the plan, a question for each irreversible item, the release, and its steps.
    * It gives true when the release ran, so the session ended. A plan or a release that fails prints the error of the
    * host in words (`HostError.words`).
    */
  private def release(
      desk   : Desk,
      handle : SessionHandle,
      outcome: SessionOutcome,
      reason : String,
      console: ClientConsole,
      print  : String => Unit
  ): Boolean =
    if handle.id == Controller.id then
      print("The controller never ends. Type /open ID to close a work session.")
      false
    else
      desk.planOf(handle.id, outcome) match
        case Left(error) =>
          print(s"The session has no release plan: ${error.words}")
          false
        case Right((state, plan)) =>
          print(s"The release plan of the session ${handle.id.value}:")
          print(planText(state, plan))
          val confirm = (item: ReleaseItem) =>
            print(s"The step \"${itemText(item)}\" cannot be undone. Type yes to run it.")
            console.read() match
              case Typed.Text(text) => text.trim == "yes"
              case _                => false
          desk.release(handle.id, outcome, reason, confirm) match
            case Left(error) =>
              print(s"The release did not run: ${error.words}")
              false
            case Right(steps) =>
              steps.foreach(step => print(stepText(step)))
              true

  /** The end notice of the session `id` with a desk: the session ended, and the commands that show another session. */
  private def endedText(id: SessionId): String =
    s"The session ${id.value} ended. Type /controller or /open ID to show another session."

/** The session that a client shows (8.7): its handle, its feed, the last `seq` that the client showed, and the
  * `TurnStarted` of the last turn that the client saw, for the stop notice. `show` changes the session, and it shows
  * the entries of the last turn of the new one. `look` gives the view of the reasoning of each step, and the detail and
  * the glyphs of a rich console. With `rows`, the console is rich: each session keeps its `Screen`, and the rows of
  * `Screen.after` go to `rows` in place of `entryText`.
  */
private final class View(
    first: SessionHandle,
    start: Long,
    look : () => Look,
    print: String => Unit,
    rows : Option[Vector[Row] => Unit]
):
  private var current: Watched = opened(first, start)
  // True after `endOnce` gave the id of `current`.
  private var told = false

  def handle: SessionHandle = synchronized(current.handle)

  def ended: Boolean = synchronized(current.feed.ended)

  /** The screen of the session that the client shows. */
  def screen: Screen = synchronized(current.screen.get)

  /** The id of the session that the client shows, after the end of its feed. It gives the id once for each session. */
  def endOnce(): Option[SessionId] =
    synchronized {
      val untold = current.feed.ended && !told
      if untold then told = true
      Option.when(untold)(current.handle.id)
    }

  /** One poll of the feed of the session that the client shows. */
  def poll(waitMillis: Long): Unit =
    val now = synchronized(current)
    now.feed.poll(waitMillis).foreach(take(now, _))

  /** The entries that are in the feed now. */
  def drain(): Unit =
    val now = synchronized(current)
    Iterator.continually(now.feed.poll()).takeWhile(_.isDefined).flatten.foreach(take(now, _))

  /** The client shows `next` from now on. */
  def show(next: SessionHandle): Unit =
    synchronized {
      current.feed.close()
      // The entries of the last turn: its message comes just before its `TurnStarted`.
      current = opened(next, next.state.lastTurn - 2)
      told = false
    }

  def close(): Unit = synchronized(current.feed.close())

  /** The view of `h` after the `seq` `from`. The turn of the stop notice comes from the state before the feed. */
  private def opened(h: SessionHandle, from: Long): Watched =
    val turn = AtomicLong(h.state.lastTurn)
    // A rich console starts the screen from the journal up to `from`, so the next entries continue the turn.
    val screen = AtomicReference(if rows.isDefined then Screen.of(h.read(-1).filter(_.seq <= from)) else Screen.empty)
    val now    = Watched(h, h.subscribe(1024), Shown(from), turn, screen)
    h.read(from).foreach(logged => take(now, Right(logged)))
    now

  // An entry of the feed, or the entries of the journal after the last one shown after `Behind`.
  private def take(now: Watched, item: Either[Behind, Logged]): Unit =
    item match
      case Right(logged) => printEntry(now, logged)
      case Left(_)       => now.handle.read(now.shown.last).foreach(printEntry(now, _))

  // An entry that the client did not show yet, and the stop notice after a turn that a stop ended.
  private def printEntry(now: Watched, logged: Logged): Unit =
    now.shown.next(logged).foreach { fresh =>
      rows match
        case Some(printRows) =>
          val (next, out) = Screen.after(now.screen.get, fresh, look())
          now.screen.set(next)
          printRows(out)
        case None => entryText(fresh, look().reasoning).foreach(print)
      fresh.entry match
        case Entry.TurnStarted                    => now.turn.set(fresh.seq)
        case Entry.TurnEnded(TurnOutcome.Aborted) =>
          val h = now.handle
          stopNotice(h.state, now.turn.get, jobStarts(h.read(now.turn.get)), h.jobStatus).foreach(print)
        case _ => ()
    }

/** One session of a `View`: its handle, its feed, what the client showed, the turn of a stop notice, and its screen. */
private final case class Watched(
    handle: SessionHandle,
    feed  : Feed,
    shown : Shown,
    turn  : AtomicLong,
    screen: AtomicReference[Screen]
)

/** The jobs of `handle` that run now, for the footer. */
private def runningJobs(handle: SessionHandle): Int =
  handle.jobStatus.values.count {
    case JobStatus.Running => true
    case _                 => false
  }

/** The words of a view of the reasoning, for the answer to `/reasoning`. */
private def reasoningWords(view: ReasoningView): String =
  view match
    case ReasoningView.Off   => "no reasoning"
    case ReasoningView.Line  => "one line with the size of the reasoning"
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
