package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Files as GuestFiles, Mode}
import munit.FunSuite

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** The laws of the handler of `Shell` (5.3): `exec` runs only in a sandbox. The tests with the `Direct` backend check
  * the handler on each host, and the tests with a real backend skip on a host with none.
  */
class SandboxedShellLaws extends FunSuite:

  /** `exec` takes `Files^`, and the handler does not use it. */
  private given GuestFiles =
    new GuestFiles:
      def read(path : String): String                                 = ""
      def write(path: String, content: String): Unit                  = ()
      def edit(path : String, oldText: String, newText: String): Unit = ()

  private val parentEnv = Map(
    "PATH"          -> sys.env.getOrElse("PATH", "/usr/bin:/bin"),
    "HOME"          -> sys.env.getOrElse("HOME", "/tmp"),
    "FAKE_API_KEY"  -> "fake-key-1",
    "EDITOR"        -> "fake-editor",
    "PROJECT_NAME"  -> "fake-project",
    "PROJECT_TOKEN" -> "fake-token-2"
  )

  private def shell(
      place  : Place,
      mode   : Mode            = Mode.local,
      backend: Option[Backend] = Some(Support.Direct),
      hosts  : HostList        = HostList.empty,
      timeout: Int             = 30,
      limit  : Int             = 200_000
  ): SandboxedShell =
    val config = Support.config(mode, place.scope, env = Set("PROJECT_NAME", "PROJECT_TOKEN"))
    SandboxedShell(config, place.layout, hosts, backend, timeout, limit, parentEnv)

  private def withPlace(body: Place => Unit): Unit =
    val place = Place()
    try body(place)
    finally place.close()

  /** A backend that counts its calls. It runs the command as it is. */
  private final class Counting extends Backend:
    val calls                                                                                                         = AtomicInteger(0)
    val name                                                                                                          = "counting"
    def works(): Boolean                                                                                              = true
    def wrap(profile: Profile, cwd: java.nio.file.Path, argv: Vector[String], proxyPort: Option[Int]): Vector[String] =
      calls.incrementAndGet(): Unit
      argv

  test("Shell: warm walks the writable roots before the first command, so that command lists only changed folders") {
    // The first exec of a live session in a scope of 1.75 million entries waited 34 s for this walk (2026-11-11).
    withPlace { place =>
      (1 to 200).foreach(i => Files.createDirectories(place.scope.resolve(s"tree/d$i/e")))
      // The index lists a folder again when it changed less than 2 s before a walk, so the tree must be older.
      Thread.sleep(2_100)
      val commands = shell(place)
      assertEquals(commands.listings, 0L)
      commands.warm()
      assert(commands.listings >= 400L, s"warm listed ${commands.listings} folders")
      val warmed = commands.listings
      commands.exec("true"): Unit
      assert(commands.listings - warmed < 5, s"the first command listed ${commands.listings - warmed} folders again")
    }
  }

  test("Shell: warm does nothing in a mode with no child process, with no backend, or after close") {
    withPlace { place =>
      Files.createDirectories(place.scope.resolve("a/b"))
      val noChild = shell(place, Mode.readOnly)
      noChild.warm()
      assertEquals(noChild.listings, 0L, "a mode with no child process")
      val noBackend = shell(place, backend = None)
      noBackend.warm()
      assertEquals(noBackend.listings, 0L, "no backend")
      val closed = shell(place)
      closed.close()
      closed.warm()
      assertEquals(closed.listings, 0L, "after close")
    }
  }

  test("Exec: with no backend, exec fails and the command does not run") {
    withPlace { place =>
      val marker = place.scope.resolve("marker")
      val error  = intercept[SandboxError](shell(place, backend = None).exec(s"touch '$marker'"))
      assert(error.getMessage.contains("No OS sandbox works on this host"), error.getMessage)
      assert(!Files.exists(marker))
    }
  }

  test("Exec: in a mode with no child process, exec fails and the command does not run") {
    withPlace { place =>
      Vector(Mode.readOnly, Mode.controller, Mode.local.copy(files = dspy4s.harness.guest.Access.Read)).foreach {
        mode =>
          val backend = Counting()
          intercept[SandboxError](shell(place, mode, Some(backend)).exec("echo hi")): Unit
          assertEquals(backend.calls.get, 0, mode)
      }
    }
  }

  test("Exec: the command sees only the variables of the allowlist and of the session") {
    withPlace { place =>
      val out   = shell(place).exec("env")
      val names = out.linesIterator.map(_.takeWhile(_ != '=')).toSet
      assert(names.contains("PATH") && names.contains("HOME") && names.contains("PROJECT_NAME"), names)
      assert(!names.contains("FAKE_API_KEY") && !names.contains("EDITOR") && !names.contains("PROJECT_TOKEN"), names)
      assert(out.contains(s"TMPDIR=${place.temp}"), "TMPDIR is the private temporary directory")
      assert(!names.exists(_.toLowerCase.endsWith("_proxy")), "local mode has no proxy")
    }
  }

  test("Exec: the output, a cut, and an exit code that is not 0") {
    withPlace { place =>
      assertEquals(shell(place).exec("echo hi; echo err >&2"), "hi\nerr\n")
      assertEquals(shell(place).exec("echo hi; exit 3"), "hi\n[exit code 3]")
      assertEquals(shell(place, limit = 10).exec("printf '%0100d' 0"), "0000000000\n[90 more bytes of output were cut]")
    }
  }

  test("Exec: the time limit stops the command and its children") {
    withPlace { place =>
      val pids  = place.scope.resolve("pids")
      val start = System.nanoTime()
      val out   = shell(place, timeout = 1).exec(s"sleep 30 & echo $$! > '$pids'; sleep 30")
      val took  = (System.nanoTime() - start) / 1_000_000
      // The limit is 1 s, and the processes stop at TERM. The grace period of 2 s before KILL is not necessary.
      assert(took < 2_500, s"exec took $took ms")
      assert(out.contains("[the command stopped after 1 s]"), out)
      val child = Files.readString(pids).trim.toLong
      Thread.sleep(500)
      assert(!Support.running(child), "the child of the command runs")
    }
  }

  test("Exec: a command can make a file with a secret name, and the next command cannot read it") {
    val backend = Backends.detect()
    assume(backend.isDefined, "no OS sandbox works on this host")
    withPlace { place =>
      val sh = shell(place, backend = backend)
      assertEquals(sh.exec("echo FAKE-NEW > .env.made && cat .env.made"), "FAKE-NEW\n")
      val out = sh.exec("cat .env.made")
      assert(!out.contains("FAKE-NEW"), out)
    }
  }

  test("Exec: a write outside the scope fails, also from a child of the command") {
    val backend = Backends.detect()
    assume(backend.isDefined, "no OS sandbox works on this host")
    withPlace { place =>
      val target = place.outside.resolve("escape")
      val out    = shell(place, backend = backend).exec(s"""sh -c "echo x > '$target'"; echo done""")
      assert(!Files.exists(target), out)
      assert(out.contains("done"), out)
      assertEquals(shell(place, backend = backend).exec("echo x > inside && cat inside"), "x\n")
    }
  }

  test("Exec: in full mode, the command reaches a host of the list through the proxy, and no other host") {
    assume(Seatbelt.works(), "only Seatbelt lets a child reach the proxy")
    withPlace { place =>
      val server = TestServer()
      val sh     = shell(place, Mode.full, Some(Seatbelt), HostList(Set("localhost:*")))
      try
        assertEquals(sh.exec(s"curl -s -m 5 http://localhost:${server.port}/"), "FAKE-SERVER")
        assert(sh.exec(s"curl -s -m 5 http://127.0.0.1:${server.port}/").contains("not on the host list"))
        assert(!sh.exec(s"curl -s -m 3 --noproxy '*' http://localhost:${server.port}/").contains("FAKE-SERVER"))
        assertEquals(server.count, 1)
      finally
        sh.close()
        server.close()
    }
  }
