package dspy4s.harness.sandbox

import dspy4s.harness.guest.Mode

import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The escape tests on Linux (5.4). They skip on a host with no working bwrap. The network stays closed in each mode,
  * so the proxy probe expects no answer.
  */
class BubblewrapLaws extends EscapeProbes(Bubblewrap, "Bubblewrap:", proxies = false):

  test("Bubblewrap: the flags mount each control path that exists read-only, and a rule on a name gets no flags") {
    // bubblewrap mounts only a path that exists, so it keeps all of .git read-only (5.4, choice 19).
    val place = Place()
    try
      JFiles.createDirectories(place.scope.resolve(".vscode")): Unit
      JFiles.writeString(place.scope.resolve(".vscode/settings.json"), "{}"): Unit
      val profile             = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      val flags               = Bubblewrap.flags(profile, place.scope)
      def mounted(path: Path) = flags.sliding(3).contains(Vector("--ro-bind", path.toString, path.toString))
      assert(mounted(place.scope.resolve(".git")), flags)
      assert(mounted(place.scope.resolve(".vscode/settings.json")), flags)
      assert(!flags.contains(place.scope.resolve(".idea").toString), flags)
      val named = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty, closesNames = true)
      intercept[IllegalArgumentException](Bubblewrap.flags(named, place.scope)): Unit
    finally place.close()
  }

  test(
    "Bubblewrap: the flags cover each secret file of the walk, mount each folder above it on itself before the control " +
      "paths, and mount no path through a symbolic link"
  ) {
    // A command can move a folder above a mount, and the mount goes with it (the escape probes, 2026-11-15).
    val place = Place()
    try
      val s    = place.scope
      val deep = JFiles.createDirectories(s.resolve("app/conf")).resolve(".env.local")
      JFiles.writeString(deep, "FAKE-DEEP"): Unit
      JFiles.createDirectories(s.resolve("app/hooks")): Unit
      JFiles.createSymbolicLink(s.resolve(".env.outside"), place.outside.resolve("r.txt")): Unit
      val layout                 = place.layout.copy(hooks = Vector(s.resolve("app/hooks")))
      val profile                = Profiles.forShell(Mode.local, s, layout, HostList.empty, Bubblewrap.closesNames)
      val flags                  = Bubblewrap.flags(profile, s)
      def at(args: String*): Int =
        val i = flags.indexOfSlice(args)
        assert(i >= 0, s"${args.mkString(" ")}: $flags")
        i
      val scope = at("--bind", s.toString, s.toString)
      val app   = at("--bind", s.resolve("app").toString, s.resolve("app").toString)
      val conf  = at("--bind", s.resolve("app/conf").toString, s.resolve("app/conf").toString)
      val hooks = at("--ro-bind", s.resolve("app/hooks").toString, s.resolve("app/hooks").toString)
      val cover = at("--ro-bind", "/dev/null", deep.toString)
      assert(scope < app && app < conf && conf < hooks && hooks < cover, flags)
      at("--ro-bind", "/dev/null", s.resolve(".env").toString): Unit
      at("--ro-bind", "/dev/null", place.outside.resolve("r.txt").toString): Unit
      assert(!flags.contains(s.resolve(".env.outside").toString), flags)
      // A folder above a secret outside the writable roots needs no mount: a command cannot move it.
      assert(!flags.contains(place.outside.toString), flags)
    finally place.close()
  }

  test("Bubblewrap: a profile that can write the seccomp filter gets no command line") {
    // A command could change the filter of a later command.
    assume(Bubblewrap.works(), "bubblewrap does not work on this host")
    val filter  = Seccomp.file().getOrElse(fail("no seccomp filter on this host"))
    val folder  = filter.getParent
    val profile = Profile(
      canRun = true,
      writable = Vector(folder),
      controls = Vector.empty,
      hidden = Vector.empty,
      net = Net.Closed
    )
    intercept[IllegalStateException](Bubblewrap.wrap(profile, folder, Vector("/bin/sh", "-c", "true"), None)): Unit
  }

  test("Bubblewrap: a stop of the wrapper during the start of bwrap leaves no process of the command") {
    // A stop can kill the first bwrap before `--die-with-parent` of the second one works, and `--new-session` puts the
    // second one out of the process group. The start of bwrap takes some milliseconds, so the stops come at each point.
    assume(Bubblewrap.works(), "bubblewrap does not work on this host")
    val folder = JFiles.createTempDirectory("dspy4s-bwrap-stop")
    try
      val profile = Profile(
        canRun = true,
        writable = Vector(folder),
        controls = Vector.empty,
        hidden = Vector.empty,
        net = Net.Closed
      )
      val argv = ProcessWrapper.command(
        ProcessWrapper.quoted(Bubblewrap.wrap(profile, folder, Vector("/bin/sh", "-c", "sleep 346"), None)),
        1
      )
      for delay <- Vector(0L, 2L, 3L, 4L, 5L, 6L, 8L, 10L, 20L); _ <- 1 to 2 do
        val wrapper = ProcessBuilder(argv*).start()
        Thread.sleep(delay)
        wrapper.getOutputStream.close()
        assert(wrapper.waitFor(10, TimeUnit.SECONDS), s"the wrapper did not end, stop after $delay ms")
      val end = System.nanoTime() + 5_000_000_000L
      while running("sleep 346").nonEmpty && System.nanoTime() < end do Thread.sleep(20)
      assert(running("sleep 346").isEmpty, s"a process of the command stays: ${running("sleep 346")}")
    finally JFiles.deleteIfExists(folder): Unit
  }

  /** The processes of this machine that run and whose command line holds `text`. */
  private def running(text: String): Vector[ProcessHandle] =
    ProcessHandle.allProcesses().iterator().asScala
      .filter(p => p.info().commandLine().toScala.exists(_.contains(text)) && Support.running(p.pid)).toVector
