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
