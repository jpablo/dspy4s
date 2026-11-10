package dspy4s.harness.sandbox

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The laws of the wrapper of a process (CODE_HARNESS_DESIGN.md, 10.3, phase 7). A law asks `ps` if a process runs,
  * because a stopped process stays a zombie in a container with no init.
  */
class WrapperLaws extends munit.FunSuite:

  /** True when `condition` holds within 10 seconds. */
  private def eventually(condition: => Boolean): Boolean =
    val end = System.nanoTime() + 10_000_000_000L
    var met = condition
    while !met && System.nanoTime() < end do
      Thread.sleep(20)
      met = condition
    met

  /** The processes of this machine that run and whose command line holds `text`. */
  private def running(text: String): Vector[ProcessHandle] =
    ProcessHandle.allProcesses().iterator().asScala
      .filter(p => p.info().commandLine().toScala.exists(_.contains(text)) && Support.running(p.pid)).toVector

  test("Wrapper: the wrapper gives the exit code of its command, and the command gets an empty standard input") {
    val wrapper = ProcessBuilder(ProcessWrapper.command("read x; echo got-$x-; exit 4", 1)*).redirectErrorStream(true)
      .start()
    // Text on the pipe of the wrapper does not reach the command, and the pipe stays open.
    wrapper.getOutputStream.write("FAKE-INPUT\n".getBytes(UTF_8))
    wrapper.getOutputStream.flush()
    assert(wrapper.waitFor(10, TimeUnit.SECONDS), "the wrapper did not end")
    assertEquals(wrapper.exitValue(), 4)
    val output = String(wrapper.getInputStream.readAllBytes(), UTF_8)
    assert(output.contains("got--"), output)
  }

  test("Wrapper: the wrapper stops the process group of its command when its standard input closes") {
    val wrapper = ProcessBuilder(ProcessWrapper.command("sleep 30 & sleep 30; wait", 1)*).start()
    assert(eventually(wrapper.descendants().count() >= 2), "the command did not start")
    val inside = wrapper.descendants().iterator().asScala.toVector
    wrapper.getOutputStream.close()
    assert(
      eventually(inside.forall(p => !Support.running(p.pid)) && !wrapper.isAlive),
      "a process of the command stays"
    )
  }

  test("Wrapper: a command that ignores TERM gets KILL after the grace period") {
    val wrapper = ProcessBuilder(ProcessWrapper.command("trap '' TERM; sleep 30", 1)*).start()
    assert(eventually(wrapper.descendants().count() >= 1), "the command did not start")
    val inside = wrapper.descendants().iterator().asScala.toVector
    val start  = System.nanoTime()
    wrapper.getOutputStream.close()
    assert(eventually(inside.forall(p => !Support.running(p.pid))), "a process of the command stays")
    assert((System.nanoTime() - start) / 1_000_000_000L < 8)
  }

  test(
    "Wrapper: when the first process of the group ends on TERM, a process of the group that ignores TERM gets KILL"
  ) {
    val wrapper = ProcessBuilder(ProcessWrapper.command("/bin/sh -c \"trap '' TERM; sleep 344\" & wait", 1)*).start()
    assert(eventually(running("sleep 344").size >= 3), "the command did not start")
    wrapper.getOutputStream.close()
    // The wrapper ends after its stop, so it waits for the KILL.
    assert(wrapper.waitFor(10, TimeUnit.SECONDS), "the wrapper did not end")
    assert(eventually(running("sleep 344").isEmpty), s"a process of the command stays: ${running("sleep 344")}")
  }

  test("Wrapper: the stop also reaches a process of the command in another session") {
    assume(Host.isExecutable("/usr/bin/perl"), "no perl on this host")
    val wrapper =
      ProcessBuilder(ProcessWrapper.command("perl -MPOSIX -e 'POSIX::setsid(); exec \"sleep\", \"345\"' & wait", 1)*)
        .start()
    assert(eventually(running("sleep 345").nonEmpty), "the command did not start")
    wrapper.getOutputStream.close()
    assert(wrapper.waitFor(10, TimeUnit.SECONDS), "the wrapper did not end")
    assert(eventually(running("sleep 345").isEmpty), "the process in another session stays")
  }
