package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Files as GuestFiles, Mode}
import munit.FunSuite

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

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

  /** A backend that counts its calls and keeps the profile of each call. It runs the command as it is. */
  private final class Counting extends Backend:
    val calls                                                                                           = AtomicInteger(0)
    val profiles                                                                                        = ConcurrentLinkedQueue[Profile]()
    val name                                                                                            = "counting"
    def works(): Boolean                                                                                = true
    def wrap(profile: Profile, cwd: Path, argv: Vector[String], proxyPort: Option[Int]): Vector[String] =
      calls.incrementAndGet(): Unit
      profiles.add(profile): Unit
      argv

  test("Exec: with no backend, exec fails and the command does not run") {
    withPlace { place =>
      val marker = place.scope.resolve("marker")
      val none   = shell(place, backend = None)
      val error  = intercept[SandboxError](none.exec(s"touch '$marker'"))
      assert(error.getMessage.contains("No OS sandbox works on this host"), error.getMessage)
      assert(!Files.exists(marker))
      // No command runs, so the shell needs no walk for the secret files.
      assertEquals(none.profile, Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty))
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

  test("Exec: the secret paths of the home folder and the journals stay closed to each command") {
    val backend = Backends.detect()
    assume(backend.isDefined, "no OS sandbox works on this host")
    withPlace { place =>
      val sh = shell(place, backend = backend)
      assert(!sh.exec(s"cat '${place.secrets.resolve("id_fake")}'").contains("FAKE-KEY"))
      assert(!sh.exec(s"cat '${place.journals.resolve("j.txt")}'").contains("FAKE-JOURNAL"))
    }
  }

  test("Exec: on a backend that closes names, a command reaches no file and no folder with a secret name") {
    // Seatbelt closes the names with one rule, with no walk of the scope (5.4). A walk before each command made each
    // command wait in a large scope, and the commit that removed the walk left these files open (2026-11-12).
    val backend = Backends.detect()
    assume(backend.exists(_.closesNames), "the sandbox of this host does not close a name")
    withPlace { place =>
      val sh = shell(place, backend = backend)
      Files.createDirectories(place.scope.resolve("nested/.env.d")): Unit
      Files.writeString(place.scope.resolve("nested/.env.local"), "FAKE-NESTED"): Unit
      Files.writeString(place.scope.resolve("nested/.env.d/x"), "FAKE-FOLDER"): Unit
      Files.writeString(place.scope.resolve(".envrc"), "FAKE-RC"): Unit
      Files.writeString(place.scope.resolve("notes.env.txt"), "FAKE-NOTES"): Unit
      Vector(
        "cat .env"                                -> "FAKE-ENV",
        "cat nested/.env.local"                   -> "FAKE-NESTED",
        "cat nested/.env.d/x"                     -> "FAKE-FOLDER",
        "ln -s .env link; cat link"               -> "FAKE-ENV",
        "cp nested/.env.local copied; cat copied" -> "FAKE-NESTED"
      ).foreach((command, value) => assert(!sh.exec(command).contains(value), command))
      sh.exec("echo FAKE-NEW > .env.made; mv .env moved"): Unit
      assert(!Files.exists(place.scope.resolve(".env.made")), "a command made a file with a secret name")
      assert(Files.exists(place.scope.resolve(".env")), "a command moved a file with a secret name")
      assertEquals(sh.exec("cat .envrc notes.env.txt"), "FAKE-RCFAKE-NOTES")
    }
  }

  test(
    "Exec: on a backend that cannot close a name, a command reads no file with a secret name that was there when the " +
      "shell started, also in a sub folder"
  ) {
    // bubblewrap cannot close a name, so before this rule a command read .env on Linux (the gap of 5.4, 2026-11-12).
    // The shell of an activation walks the scope once, and the backend covers each file that the walk finds.
    val backend = Backends.detect()
    assume(backend.exists(!_.closesNames), "the sandbox of this host closes the names")
    withPlace { place =>
      Files.createDirectories(place.scope.resolve("app/conf"))
      Files.writeString(place.scope.resolve("app/conf/.env.local"), "FAKE-DEEP"): Unit
      Files.writeString(place.scope.resolve(".envrc"), "FAKE-RC"): Unit
      Files.writeString(place.scope.resolve("notes.env.txt"), "FAKE-NOTES"): Unit
      val sh  = shell(place, backend = backend)
      val top = sh.exec("cat .env 2>&1; echo FAKE-DONE")
      assert(!top.contains("FAKE-ENV") && top.contains("FAKE-DONE"), top)
      val deep = sh.exec("cat app/conf/.env.local 2>&1; echo FAKE-DONE")
      assert(!deep.contains("FAKE-DEEP") && deep.contains("FAKE-DONE"), deep)
      assert(sh.profile.isHidden(place.scope.resolve(".env")), sh.profile.toString)
      assert(sh.profile.isHidden(place.scope.resolve("app/conf/.env.local")), sh.profile.toString)
      // Only a secret name is closed.
      assertEquals(sh.exec("cat .envrc notes.env.txt"), "FAKE-RCFAKE-NOTES")
    }
  }

  test(
    "Exec: on a backend that cannot close a name, the shell walks once at its start, and each command gets that profile"
  ) {
    // A walk before each command made each command wait in a large scope (5.4), so a later file is not hidden.
    withPlace { place =>
      val backend = Counting()
      val sh      = shell(place, backend = Some(backend))
      Files.writeString(place.scope.resolve(".env.later"), "FAKE-LATER"): Unit
      sh.exec("true"): Unit
      sh.exec("true"): Unit
      assertEquals(backend.profiles.asScala.toVector, Vector(sh.profile, sh.profile))
      assert(sh.profile.isHidden(place.scope.resolve(".env")), sh.profile.toString)
      assert(!sh.profile.isHidden(place.scope.resolve(".env.later")), sh.profile.toString)
    }
  }

  test(
    "Exec: on a backend that cannot close a name, a file with a secret name that is made after the shell started stays " +
      "readable, and the shell of the next activation covers it"
  ) {
    // The rule of 5.4 for such a backend: one walk at the start of each activation, and no walk before a command.
    val backend = Backends.detect()
    assume(backend.exists(!_.closesNames), "the sandbox of this host closes the names")
    withPlace { place =>
      val sh = shell(place, backend = backend)
      assertEquals(sh.exec("echo FAKE-MADE > .env.made && cat .env.made"), "FAKE-MADE\n")
      Files.writeString(place.scope.resolve(".env.person"), "FAKE-PERSON"): Unit
      assertEquals(sh.exec("cat .env.made .env.person"), "FAKE-MADE\nFAKE-PERSON")
      val next = shell(place, backend = backend).exec("cat .env.made .env.person 2>&1; echo FAKE-DONE")
      assert(!next.contains("FAKE-MADE") && !next.contains("FAKE-PERSON") && next.contains("FAKE-DONE"), next)
    }
  }

  test(
    "Exec: on a backend that cannot close a name, a command reads no target of a symbolic link with a secret name, and " +
      "the command runs"
  ) {
    val backend = Backends.detect()
    assume(backend.exists(!_.closesNames), "the sandbox of this host closes the names")
    withPlace { place =>
      val real = Files.createDirectories(place.scope.resolve("conf")).resolve("real.txt")
      Files.writeString(real, "FAKE-REAL"): Unit
      Files.createSymbolicLink(place.scope.resolve(".env.outside"), place.outside.resolve("r.txt")): Unit
      Files.createSymbolicLink(place.scope.resolve(".env.inside"), real): Unit
      Files.createSymbolicLink(place.scope.resolve(".env.relative"), Path.of("conf/real.txt")): Unit
      val sh  = shell(place, backend = backend)
      val out = sh.exec("cat .env.outside .env.inside .env.relative conf/real.txt 2>&1; echo FAKE-DONE")
      assert(!out.contains("FAKE-OUTSIDE") && !out.contains("FAKE-REAL") && out.contains("FAKE-DONE"), out)
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
