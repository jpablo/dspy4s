package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Files as GuestFiles, Mode}

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

/** The laws of the stop of an `exec` (CODE_HARNESS_DESIGN.md, 10.3, phase 7): step 3 of the hard stop, and an interrupt
  * of the thread of an `exec`. The backend `Direct` runs the command as it is, so the laws run on each host.
  */
class ShellStopLaws extends munit.FunSuite:

  /** `exec` takes `Files^`, and the handler does not use it. */
  private given GuestFiles =
    new GuestFiles:
      def read(path : String): String                                 = ""
      def write(path: String, content: String): Unit                  = ()
      def edit(path : String, oldText: String, newText: String): Unit = ()

  private def shell(place: Place): SandboxedShell =
    SandboxedShell(
      Support.config(Mode.local, place.scope),
      place.layout,
      HostList.empty,
      Some(Support.Direct),
      60,
      200_000,
      Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"), "HOME" -> sys.env.getOrElse("HOME", "/tmp"))
    )

  /** The processes of this machine whose command line holds `text`. */
  private def running(text: String): Vector[ProcessHandle] =
    ProcessHandle.allProcesses().iterator().asScala.filter(_.info().commandLine().toScala.exists(_.contains(text)))
      .toVector

  /** The processes of the program `sleep` with the one argument `seconds`. The wrapper and the shells of a command also
    * hold the text of the command in their command lines, so `running` finds them too.
    */
  private def sleeping(seconds: Int): Vector[ProcessHandle] =
    running(s"sleep $seconds").filter { process =>
      val info = process.info()
      info.command().toScala.exists(_.endsWith("/sleep")) &&
      info.arguments().toScala.exists(_.toVector == Vector(seconds.toString))
    }

  private def eventually(condition: => Boolean): Boolean =
    val end = System.nanoTime() + 15_000_000_000L
    var met = condition
    while !met && System.nanoTime() < end do
      Thread.sleep(20)
      met = condition
    met

  test("Shell stop: stopAll ends an exec that runs, its process tree stops, and the exec gives a note of the stop") {
    val place = Place()
    try
      val sh     = shell(place)
      val result = AtomicReference[Try[String]]()
      val thread = Thread.ofPlatform().start(() => result.set(Try(sh.exec("sleep 341"))))
      assert(eventually(running("sleep 341").nonEmpty), "the command did not start")
      sh.stopAll()
      thread.join(15_000)
      assert(!thread.isAlive, "the exec did not end after stopAll")
      assert(eventually(running("sleep 341").isEmpty), "a process of the exec stays")
      assert(result.get.toOption.exists(_.toLowerCase.contains("stop")), String.valueOf(result.get))
    finally place.close()
  }

  test("Shell stop: an interrupt of the thread of an exec stops its process tree") {
    val place = Place()
    try
      val sh     = shell(place)
      val ended  = AtomicReference[Option[Throwable]](None)
      val thread = Thread.ofPlatform().start { () =>
        try sh.exec("sleep 342"): Unit
        catch case e: InterruptedException => ended.set(Some(e))
      }
      assert(eventually(running("sleep 342").nonEmpty), "the command did not start")
      thread.interrupt()
      thread.join(15_000)
      assert(eventually(running("sleep 342").isEmpty), "a process of the exec stays after the interrupt")
      assert(ended.get.nonEmpty, "the exec did not give the interrupt back to its thread")
    finally place.close()
  }

  // The hard stop of a turn interrupts the thread of a cell two times: `ScalaRepl.stopCell`, then the wait of the cell
  // (10.3). A live host law left a `sleep` with no parent after such a stop. A signal of the handler to the wrapper
  // during its stop ends the watcher, and only the watcher stops a process that left the tree of the command.
  test("Shell stop: a second interrupt during the stop of an exec does not cut the stop of its wrapper") {
    val place = Place()
    try
      val sh     = shell(place)
      val thread = Thread.ofPlatform().start { () =>
        try sh.exec("( sleep 347 >/dev/null 2>&1 & ); sleep 348"): Unit
        catch case _: InterruptedException => ()
      }
      assert(eventually(sleeping(347).nonEmpty && sleeping(348).nonEmpty), "the command did not start")
      thread.interrupt()
      // The exec clears the flag when it sees the interrupt. The second interrupt then comes during the stop.
      while thread.isInterrupted do Thread.onSpinWait()
      thread.interrupt()
      thread.join(15_000)
      assert(eventually(sleeping(347).isEmpty), "a process that left the tree of the command stays after the stop")
      assert(eventually(sleeping(348).isEmpty), "a process of the exec stays after the stop")
    finally
      (sleeping(347) ++ sleeping(348)).foreach(_.destroyForcibly(): Unit)
      place.close()
  }

  // After the end of the wrapper, the handler stops what it saw and what is left: TERM, the grace period, then KILL.
  // `sleep 349` ignores TERM, and it left the tree and the group of the command, so only the KILL of the handler stops
  // it. An interrupt during the grace period must not skip the KILL.
  test("Shell stop: an interrupt during the grace period of the handler does not skip its KILL") {
    val place = Place()
    try
      val sh     = shell(place)
      val ended  = AtomicReference[Option[Throwable]](None)
      val thread = Thread.ofPlatform().start { () =>
        // With `exit 0` at the end, bash cannot replace itself with its last command.
        try sh.exec("""bash -c 'set -m; trap "" TERM; sleep 349 & sleep 0.5; exit 0'; sleep 350"""): Unit
        catch case e: InterruptedException => ended.set(Some(e))
      }
      // `sleep 349` left the tree when its bash ended. On Linux, an orphan can go to a subreaper and not to process 1.
      def left = sleeping(349).exists(_.parent.toScala.forall(!_.info().command().toScala.exists(_.endsWith("bash"))))
      assert(eventually(left && sleeping(350).nonEmpty), "the command did not start")
      thread.interrupt()
      // The command line of the wrapper holds the command, so this waits for the end of the wrapper. Then the handler
      // sends TERM to `sleep 349`, and waits 2 s.
      assert(eventually(running("sleep 350").isEmpty), "the wrapper did not end")
      Thread.sleep(500)
      thread.interrupt()
      thread.join(15_000)
      assert(eventually(sleeping(349).isEmpty), "a process that ignores TERM stays after the stop")
      assert(ended.get.nonEmpty, "the exec did not give the interrupt back to its thread")
    finally
      (sleeping(349) ++ sleeping(350)).foreach(_.destroyForcibly(): Unit)
      place.close()
  }
