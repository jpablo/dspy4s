package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Files, JobId, JobStatus, Scope, Shell}
import dspy4s.harness.kernel.SessionConfig

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path, Paths}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A command that did not run, because the mode gives no child process or no OS sandbox works (5.4). */
final class SandboxError(message: String) extends RuntimeException(message)

/** The handler `Shell.sandboxed(root, profile)` of CODE_HARNESS_DESIGN.md, 5.3.
  *
  * `exec` runs the command with `/bin/sh -c` in the backend, under the profile of the mode of the session, in the
  * folder of the scope. The command gets the allowlist of variables (5.6), the private temporary directory as `TMPDIR`,
  * and the proxy of the host list in a mode with `Network^`. Its standard input is empty. With no backend, in a mode
  * with no child process, or when the folder of the scope is not there, `exec` fails, and the command does not run.
  *
  * A proposal for the review: `exec` gives the standard output and the standard error as one text. A last line tells
  * about a cut of the output, the end of the time limit, and an exit code that is not 0. `spawn` and the jobs come with
  * the session host of phase 7. The host deletes the temporary directory of the layout at the end of the session.
  */
final class SandboxedShell(
    config        : SessionConfig,
    layout        : Layout,
    hosts         : HostList,
    backend       : Option[Backend],
    timeoutSeconds: Int                 = 600,
    maxOutput     : Int                 = 200_000,
    parentEnv     : Map[String, String] = sys.env
) extends Shell,
      AutoCloseable:

  /** The scope of the session, as a real path. Each command starts in it. */
  val root: Path = Layout.real(Paths.get(SandboxedShell.pathOf(config.scope)))

  /** The profile of the mode. Each command also gets the secret files that exist when it starts. */
  val profile: Profile = Profiles.forMode(config.mode, root, layout, hosts)

  /** The secret files of the writable roots. The shell keeps one index for its life, so a command lists again only the
    * folders that changed (`SecretIndex`).
    */
  private val index = SecretIndex(layout.secretNames)

  private var proxy: Option[HostProxy] = None

  /** The processes of the `exec` calls that run, and the ones that `stopAll` stopped. */
  private val running = ConcurrentHashMap.newKeySet[Process]()
  private val stopped = ConcurrentHashMap.newKeySet[Process]()

  def exec(command: String)(using Files): String = run(start(command))

  /** The process of `command` under the profile of the mode, in the backend, in the folder of the scope, and under
    * `ProcessWrapper`. Its standard input is the pipe of the wrapper, and the command itself gets an empty one.
    *
    * A person can delete the folder of the scope after the open. Then the error of the JDK names the program and not
    * the folder, so the shell checks the folder first, and its error names the folder (5.4).
    */
  private def start(command: String): Process =
    if !profile.canRun then
      throw SandboxError("The mode of this session gives no child process, so exec did not run the command.")
    val sandbox = backend.getOrElse(throw SandboxError(
      "No OS sandbox works on this host, so exec did not run the command. macOS needs /usr/bin/sandbox-exec, and " +
        "Linux needs bwrap."
    ))
    if !JFiles.isDirectory(root) then
      val fault =
        if JFiles.exists(root) then s"The path $root of the session is not a folder"
        else s"The folder $root of the session does not exist"
      throw SandboxError(s"$fault, so the command did not run.")
    val port = profile.net match
      case Net.Proxy(_) => Some(proxyPort())
      case Net.Closed   => None
    val now       = profile.hide(index.find(profile))
    val sandboxed = sandbox.wrap(now, root, Vector("/bin/sh", "-c", command), port)
    val wrapped   = ProcessWrapper.command(ProcessWrapper.quoted(sandboxed), (SandboxedShell.GraceMillis / 1000).toInt)
    val builder   = ProcessBuilder(wrapped*).directory(root.toFile).redirectErrorStream(true)
    val env       = builder.environment()
    env.clear()
    childEnv(port).foreach((name, value) => env.put(name, value): Unit)
    builder.start()

  def spawn(command: String, report   : Boolean)(using Files): JobId = later("spawn")
  def jobOutput(job: JobId): String                                  = later("jobOutput")
  def waitJob(job  : JobId, maxSeconds: Int): JobStatus              = later("waitJob")
  def stopJob(job  : JobId): Unit                                    = later("stopJob")

  /** Starts `command` as `exec` does, under the profile of the mode and in the folder of the scope, and gives the
    * process with no wait (phase 7, for the jobs of 10.3). The command runs under `ProcessWrapper`: its own standard
    * input is empty, and the standard input of the process is the pipe of the wrapper, which the caller holds.
    */
  def launch(command: String): Process = start(command)

  /** Step 3 of the hard stop (10.3, phase 7): it closes the pipe of the wrapper of each `exec` that runs, and returns
    * at once. Each wrapper then stops the group and the tree of its command with TERM, a grace period and KILL
    * (`ProcessWrapper`), and the `exec` calls give their output with a note of the stop.
    *
    * Since phase 7, `exec` runs under `ProcessWrapper` as a job does, so a crash of the host also stops it. An
    * interrupt of the thread of an `exec` stops its process tree before the interrupt goes on.
    */
  def stopAll(): Unit =
    running.forEach { process =>
      stopped.add(process): Unit
      Try(process.getOutputStream.close()): Unit
    }

  /** Stops the proxy. */
  def close(): Unit =
    synchronized {
      proxy.foreach(_.close())
      proxy = None
    }

  /** The variables of a command: the allowlist, the temporary directory, and the proxy. */
  private def childEnv(port: Option[Int]): Map[String, String] =
    val temp      = Vector("TMPDIR", "TMP", "TEMP").map(_ -> layout.temp.toString)
    val proxyVars = port.toVector.flatMap { p =>
      Vector("http_proxy", "https_proxy", "all_proxy", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")
        .map(_ -> s"http://127.0.0.1:$p")
    }
    Environment.forChild(parentEnv, config.env) ++ temp ++ proxyVars

  private def proxyPort(): Int =
    synchronized {
      val started = proxy.getOrElse(HostProxy.start(hosts))
      proxy = Some(started)
      started.port
    }

  /** Waits for the command, with the time limit. A child that the command leaves behind stops at the end. After an
    * interrupt of the thread, the process tree stops first, and then the interrupt goes on.
    */
  private def run(process: Process): String =
    running.add(process): Unit
    val output   = collect(process.getInputStream)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong)
    var seen     = Vector.empty[ProcessHandle]
    var ended    = false
    try
      while !ended && System.nanoTime() < deadline do
        // A child can leave its parent, so the handler keeps each process that it sees.
        seen = (seen.filter(Host.running) ++ process.descendants().iterator.asScala).distinct
        ended = process.waitFor(SandboxedShell.PollMillis, TimeUnit.MILLISECONDS)
    finally
      seen = (seen ++ process.descendants().iterator.asScala).distinct
      // The end of the pipe makes the wrapper stop the command, and the wrapper ends after its stop. A signal from the
      // handler before that end can stop the watcher of the wrapper, so the handler stops only what is left after it.
      Try(process.getOutputStream.close()): Unit
      // The stop of the wrapper takes at most the grace period and one second more.
      val again = SandboxedShell.awaitEnd(process, SandboxedShell.GraceMillis + 2_000L)
      running.remove(process): Unit
      SandboxedShell.stop(seen :+ process.toHandle)
      if again then Thread.currentThread().interrupt()
    // A child that holds the output open and that the handler did not see can block the reader.
    val (kept, cut) = Try(output.get(SandboxedShell.GraceMillis, TimeUnit.MILLISECONDS)).getOrElse {
      Try(process.getInputStream.close()): Unit
      output.join()
    }
    val notes = Vector(
      Option.when(stopped.remove(process))("[the command stopped, because the turn stops]"),
      Option.when(cut > 0)(s"[$cut more bytes of output were cut]"),
      Option.when(!ended)(s"[the command stopped after $timeoutSeconds s]"),
      Option.when(ended && process.exitValue() != 0)(s"[exit code ${process.exitValue()}]")
    ).flatten
    val text = String(kept, UTF_8)
    if notes.isEmpty then text
    else text + (if text.isEmpty || text.endsWith("\n") then "" else "\n") + notes.mkString("\n")

  /** Reads all the output on its own thread, and keeps the first `maxOutput` bytes. */
  private def collect(in: InputStream): CompletableFuture[(Array[Byte], Long)] =
    val result = CompletableFuture[(Array[Byte], Long)]()
    val reader = Thread(
      () =>
        val kept   = ByteArrayOutputStream()
        val buffer = Array.ofDim[Byte](8192)
        var total  = 0L
        try
          var n = in.read(buffer)
          while n >= 0 do
            kept.write(buffer, 0, (maxOutput - kept.size).max(0).min(n))
            total += n
            n = in.read(buffer)
        catch case _: java.io.IOException => () // the handler closed the stream
        result.complete((kept.toByteArray, total - kept.size)): Unit
      ,
      "dspy4s-exec-output"
    )
    reader.setDaemon(true)
    reader.start()
    result

  private def later(op: String): Nothing =
    throw UnsupportedOperationException(s"$op: a program call has no jobs. A work session of the host has them (10.3)")

object SandboxedShell:
  private val PollMillis           = 50L
  private[sandbox] val GraceMillis = 2_000L

  /** The folder of a scope. */
  def pathOf(scope: Scope): String =
    scope match
      case Scope.Home(path)        => path
      case Scope.Directory(path)   => path
      case Scope.Worktree(path, _) => path
      case Scope.Scratch(path)     => path
      case Scope.Fork(path, _)     => path

  /** Waits at most `millis` for the end of `process`. True when an interrupt came during the wait: the wait then ends,
    * and the caller sets the interrupt again after its stop.
    */
  private def awaitEnd(process: Process, millis: Long): Boolean =
    try
      process.waitFor(millis, TimeUnit.MILLISECONDS): Unit
      false
    catch case _: InterruptedException => true

  /** TERM to each process that runs, a grace period, then KILL. A zombie does not run, so the grace period does not
    * wait for it.
    */
  private def stop(processes: Vector[ProcessHandle]): Unit =
    val alive = processes.filter(Host.running)
    alive.foreach(_.destroy(): Unit)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GraceMillis)
    while alive.exists(Host.running) && System.nanoTime() < deadline do Thread.sleep(PollMillis)
    alive.filter(Host.running).foreach(_.destroyForcibly(): Unit)
