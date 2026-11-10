package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Mode, Scope, StrategyName}
import dspy4s.harness.kernel.{Guard, ModelChoice, Owner, SessionConfig, TurnKind}

import java.io.{ByteArrayOutputStream, IOException}
import java.net.{InetAddress, ServerSocket, Socket, StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A plain HTTP server on the loopback address for the tests. It counts its connections, keeps the head of the last
  * request, and answers each request with the body `FAKE-SERVER`.
  */
final class TestServer extends AutoCloseable:
  private val server      = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
  private val connections = AtomicInteger(0)

  @volatile private var head = ""

  val port: Int = server.getLocalPort

  def count: Int = connections.get

  /** The head of the last request, with no empty line at its end. */
  def lastHead: String = head

  def close(): Unit = server.close()

  private def answer(client: Socket): Unit =
    try
      // The head of the request ends with an empty line.
      val in   = client.getInputStream
      val seen = ByteArrayOutputStream()
      var tail = 0
      var open = true
      while open && tail != 0x0d0a0d0a && seen.size < 65536 do
        val b = in.read()
        if b < 0 then open = false
        else
          tail = (tail << 8) | b
          seen.write(b)
      head = seen.toString(ISO_8859_1).stripSuffix("\r\n\r\n")
      val body   = "FAKE-SERVER".getBytes(UTF_8)
      val status = s"HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
      client.getOutputStream.write(status.getBytes(ISO_8859_1) ++ body)
    catch case _: IOException => ()
    finally client.close()

  private val acceptor = Thread(
    () =>
      try
        while !server.isClosed do
          val client = server.accept()
          connections.incrementAndGet(): Unit
          answer(client)
      catch case _: IOException => (),
    "test-server"
  )
  acceptor.setDaemon(true)
  acceptor.start()

/** A server on a UNIX socket of the host, as the D-Bus bus or docker.sock are. It answers each connection with an HTTP
  * answer with the body `FAKE-UNIX`. The socket is in a short folder under /tmp, because a socket path has a limit of
  * about 100 bytes.
  */
final class UnixServer extends AutoCloseable:
  private val folder = Files.createTempDirectory(Path.of("/tmp"), "dspy4s-u")
  val path: Path     = folder.resolve("s")
  private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
  server.bind(UnixDomainSocketAddress.of(path)): Unit

  private val acceptor = Thread(
    () =>
      try
        while server.isOpen do
          val client = server.accept()
          try
            val body = "FAKE-UNIX"
            val text = s"HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            client.write(ByteBuffer.wrap(text.getBytes(ISO_8859_1))): Unit
          finally client.close()
      catch case _: IOException => (),
    "test-unix-server"
  )
  acceptor.setDaemon(true)
  acceptor.start()

  def close(): Unit =
    server.close()
    Files.deleteIfExists(path): Unit
    Files.deleteIfExists(folder): Unit

/** The places of one test, under a new real temporary folder: a scope with `.git` and two `.env` files, a folder
  * outside the scope, a fake secret, a journal and the private temporary directory. Each file holds a fake value.
  */
final class Place extends AutoCloseable:
  val base: Path     = Files.createTempDirectory("dspy4s-sandbox-test-").toRealPath()
  val scope: Path    = Files.createDirectories(base.resolve("scope"))
  val outside: Path  = Files.createDirectories(base.resolve("outside"))
  val secrets: Path  = Files.createDirectories(base.resolve("home/.ssh"))
  val journals: Path = Files.createDirectories(base.resolve("journals"))
  val temp: Path     = Files.createDirectories(base.resolve("tmp"))
  Files.createDirectories(scope.resolve(".git")): Unit
  Files.writeString(scope.resolve(".env"), "FAKE-ENV"): Unit

  /** A secret file with a quote, a backslash and a tab in its name, which SBPL must escape. */
  val odd: Path = Files.writeString(scope.resolve(".env.q\"b\\c\td"), "FAKE-ODD")
  Files.writeString(outside.resolve("r.txt"), "FAKE-OUTSIDE"): Unit
  Files.writeString(secrets.resolve("id_fake"), "FAKE-KEY"): Unit
  Files.writeString(journals.resolve("j.txt"), "FAKE-JOURNAL"): Unit

  val layout: Layout = Layout(temp, journals, Vector(secrets), Layout.secretNames)

  def close(): Unit =
    val paths = Files.walk(base)
    try paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p): Unit)
    finally paths.close()

/** The helpers of the tests. */
object Support:

  /** A session with the mode `mode` and the scope `scope`. The other fields do not matter for the sandbox. */
  def config(mode: Mode, scope: Path, env: Set[String] = Set.empty): SessionConfig =
    SessionConfig(
      kind = TurnKind.Program,
      steps = 10,
      mode = mode,
      ceiling = mode,
      strategy = StrategyName.Window,
      scope = Scope.Directory(scope.toString),
      owner = Owner.User,
      guard = Guard.allowAll,
      preamble = "",
      model = ModelChoice("fake-model", Map.empty),
      goal = None,
      env = env
    )

  /** Runs a command line with only `PATH` and `HOME`, and gives its output. */
  def run(argv: Vector[String], cwd: Path): String =
    val builder = ProcessBuilder(argv*).directory(cwd.toFile).redirectErrorStream(true)
    val env     = builder.environment()
    env.clear()
    Vector("PATH", "HOME").foreach(name => sys.env.get(name).foreach(value => env.put(name, value): Unit))
    val process = builder.start()
    val output  = String(process.getInputStream.readAllBytes(), UTF_8)
    process.waitFor(30, TimeUnit.SECONDS): Unit
    output

  /** True when the process `pid` runs, from `ps`. A zombie does not run. `ProcessHandle.isAlive` is true for a zombie
    * until its parent reaps it, and a JVM that is PID 1 in a container with no init does not reap its orphans.
    */
  def running(pid: Long): Boolean =
    val state = run(Vector("ps", "-o", "stat=", "-p", pid.toString), Path.of("/")).trim
    state.nonEmpty && !state.startsWith("Z")

  /** A backend with no sandbox. It runs the command as it is, so the tests of the handler run on each host. */
  object Direct extends Backend:
    val name                                                                                            = "direct"
    def works(): Boolean                                                                                = true
    def wrap(profile: Profile, cwd: Path, argv: Vector[String], proxyPort: Option[Int]): Vector[String] = argv
