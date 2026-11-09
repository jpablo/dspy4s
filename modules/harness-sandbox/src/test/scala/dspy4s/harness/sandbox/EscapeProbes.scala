package dspy4s.harness.sandbox

import dspy4s.harness.guest.Mode
import munit.FunSuite

import java.nio.file.{Files as JFiles, Path}

/** The escape tests of one backend (5.4): real child processes try each effect, and the test compares what they did
  * with what the profile allows. Both ways count. A child must not do more than the profile allows, and it must do what
  * the profile allows, so that a probe that fails for another reason cannot pass.
  *
  * @param law
  *   the start of the test names, for example `Seatbelt:`
  * @param proxies
  *   false when the backend keeps the network closed also in a mode with `Network^`
  */
abstract class EscapeProbes(backend: Backend, law: String, proxies: Boolean) extends FunSuite:

  private lazy val works = backend.works()

  /** The value of a variable of the test JVM (build.sbt). A child must not see it. */
  private val fakeSecret = "fake-value-for-the-escape-tests"

  private val listed = HostList(Set("localhost"))

  /** What a real child did, and what the profile allows. */
  private final case class Probe(name: String, observed: Boolean, allowed: Boolean)

  /** Runs each probe under `wrap`. With the wrap of the backend, the probes are the escape tests. With no sandbox, they
    * are the control, which shows that each probe sees its effect.
    */
  private def probes(mode: Mode, sandboxed: Boolean): Vector[Probe] =
    val place  = Place()
    val server = TestServer()
    val proxy  = HostProxy.start(listed)
    try
      // The profile of one command: the profile of the mode, with the secret files that exist when the command starts,
      // as in `exec`. A probe takes the profile before its command.
      def profile(): Profile          = Profiles.forCommand(mode, place.scope, place.layout, listed)
      def sh(command: String): String =
        val argv = Vector("/bin/sh", "-c", command)
        Support.run(
          if sandboxed then backend.wrap(profile(), place.scope, argv, Some(proxy.port)) else argv,
          place.scope
        )
      // With no sandbox, a plain child can write and read each file.
      def allows(byProfile: Boolean): Boolean = !sandboxed || byProfile
      def write(path: Path): Probe            =
        val allowed = allows(profile().canWrite(path))
        sh(s"echo x > '$path'"): Unit
        Probe(s"write $path", JFiles.exists(path), allowed)
      def read(path: Path, value: String): Probe =
        val allowed = allows(profile().canRead(path))
        Probe(s"read $path", sh(s"cat '$path'").contains(value), allowed)
      val secret = place.secrets.resolve("id_fake")
      val env    = place.scope.resolve(".env")
      val pid    = ProcessHandle.current().pid()
      Vector(
        write(place.scope.resolve("w1")),
        write(place.temp.resolve("w2")),
        write(place.outside.resolve("w3")),
        write(place.scope.resolve(".git/w4")),
        write(place.scope.resolve(".env.new")), // a new file with a secret name holds only data of the command
        read(secret, "FAKE-KEY"),
        read(env, "FAKE-ENV"),
        read(place.odd, "FAKE-ODD"),
        read(place.journals.resolve("j.txt"), "FAKE-JOURNAL"),
        read(place.outside.resolve("r.txt"), "FAKE-OUTSIDE"),
        Probe(
          "a hard link to a secret",
          sh(s"ln '$secret' '${place.scope}/hl' 2>&1; cat '${place.scope}/hl'").contains("FAKE-KEY"),
          allows(profile().canRead(secret))
        ), {
          val allowed = allows(profile().canRead(env))
          Probe(
            "a hard link to a file with a secret name",
            sh(s"ln '$env' '${place.scope}/hl-env' 2>&1; cat '${place.scope}/hl-env'").contains("FAKE-ENV"),
            allowed
          )
        }, {
          val allowed = allows(profile().canWrite(env))
          Probe(
            "a move of a file with a secret name",
            sh(s"mv '$env' '${place.scope}/moved-env' 2>&1; cat '${place.scope}/moved-env'").contains("FAKE-ENV"),
            allowed
          )
        }, {
          val deep = place.outside.resolve("deep")
          sh(s"""sh -c "sh -c 'echo x > $deep'""""): Unit
          Probe("a write outside from a child of a child", JFiles.exists(deep), allows(profile().canWrite(deep)))
        }, {
          val target = place.outside.resolve("hard")
          JFiles.writeString(target, "original"): Unit
          sh(s"ln '$target' '${place.scope}/hard' 2>&1; echo appended >> '${place.scope}/hard'"): Unit
          Probe("a write outside through a hard link", JFiles.readString(target).contains("appended"), allows(false))
        }, {
          val target = place.outside.resolve("soft")
          JFiles.writeString(target, "original"): Unit
          sh(s"ln -s '$target' '${place.scope}/soft' 2>&1; echo appended >> '${place.scope}/soft'"): Unit
          Probe(
            "a write outside through a symbolic link",
            JFiles.readString(target).contains("appended"),
            allows(false)
          )
        }, {
          val target = place.outside.resolve("moved")
          sh(s"echo x > '${place.scope}/moved' && mv '${place.scope}/moved' '$target'"): Unit
          Probe("a move out of the scope", JFiles.exists(target), allows(profile().canWrite(target)))
        },
        Probe(
          "the network with no proxy",
          sh(s"curl -s -m 3 --noproxy '*' http://127.0.0.1:${server.port}/").contains("FAKE-SERVER"),
          !sandboxed
        ),
        Probe(
          "a host of the list through the proxy",
          sh(
            s"curl -s -m 5 -x http://127.0.0.1:${proxy.port} http://localhost:${server.port}/"
          ).contains("FAKE-SERVER"),
          !sandboxed || (proxies && profile().canConnect("localhost"))
        ),
        Probe(
          "another host through the proxy",
          sh(
            s"curl -s -m 5 -x http://127.0.0.1:${proxy.port} http://127.0.0.1:${server.port}/"
          ).contains("FAKE-SERVER"),
          false // the proxy refuses it, also with no sandbox
        ),
        Probe(
          "the variables of the harness process",
          sh(s"ps eww -p $pid 2>&1; cat /proc/$pid/environ 2>&1").contains(fakeSecret),
          !sandboxed
        ),
        Probe("launchd", sh("launchctl list 2>&1").contains("com.apple"), !sandboxed && Host.os.contains("mac"))
      )
    finally
      proxy.close()
      server.close()
      place.close()

  private def check(found: Vector[Probe]): Unit =
    val wrong = found.filter(p => p.observed != p.allowed)
    assert(
      wrong.isEmpty,
      wrong.map(p => s"${p.name}: the child ${if p.observed then "did" else "did not"} do it").mkString("\n")
    )

  test(s"$law in local mode, a child writes only the scope and the temporary directory, with no network") {
    assume(works, s"${backend.name} does not work on this host")
    check(probes(Mode.local, sandboxed = true))
  }

  test(s"$law in full mode, a child also reaches the hosts of the list through the proxy") {
    assume(works, s"${backend.name} does not work on this host")
    check(probes(Mode.full, sandboxed = true))
  }

  test(s"$law a child of a local session has the profile of local mode") {
    assume(works, s"${backend.name} does not work on this host")
    check(probes(Mode.local.meet(Mode.closed), sandboxed = true))
  }

  test(s"$law when the backend works, a command runs under the profile of each mode with a child process") {
    // `Backends.detect` trusts `works()`. A probe that passes where the real command line fails gives a backend that
    // runs no command.
    assume(works, s"${backend.name} does not work on this host")
    val place = Place()
    try Vector(Mode.local, Mode.full, Mode.local.meet(Mode.closed)).foreach { mode =>
        val profile = Profiles.forMode(mode, place.scope, place.layout, listed)
        val argv    = backend.wrap(profile, place.scope, Vector("/bin/sh", "-c", "echo ran"), Some(1))
        assertEquals(Support.run(argv, place.scope), "ran\n", s"mode $mode")
      }
    finally place.close()
  }

  test(s"$law a profile with no child process gets no command line") {
    val place = Place()
    try Vector(Mode.readOnly, Mode.controller).foreach { mode =>
        val profile = Profiles.forMode(mode, place.scope, place.layout, listed)
        intercept[IllegalArgumentException](backend.wrap(profile, place.scope, Vector("/bin/echo", "hi"), None))
      }
    finally place.close()
  }

  test(s"$law with no sandbox, each probe sees its effect") {
    // The control: the same probes as a plain child, which can do each thing. A probe with no effect here tests nothing.
    assume(works, s"${backend.name} does not work on this host")
    check(probes(Mode.full, sandboxed = false))
  }
