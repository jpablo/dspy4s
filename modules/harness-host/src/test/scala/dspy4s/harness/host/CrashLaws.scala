package dspy4s.harness.host

import dspy4s.harness.{HarnessLimits, Models, Place, ScriptLm, Secrets, Support}
import dspy4s.harness.Support.answer
import dspy4s.harness.guest.Mode
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A host in a child JVM for the crash laws (CODE_HARNESS_DESIGN.md, 10.3). It opens a work session at the root
  * `args(0)` for the folder `args(1)`. The policy starts the job `sleep 317` and waits for it in the same cell. When
  * the cell waits, the probe prints `READY`, and it waits until the test kills it.
  */
object CrashProbe:

  def main(args: Array[String]): Unit =
    val limits  = HarnessLimits(steps = 2)
    val model   = ScriptLm(Models.policy("start", Some("""val j = spawn("sleep 317"); waitJob(j, 300)""")))
    val env     = WorkEnv(Path.of(args(1)).resolveSibling("home"), HostList.empty, Backends.detect(), Secrets.none)
    val runtime = Models.withModel(model)(WorkRuntime.forWork(env, limits))
    val host    = LocalHost.start(Path.of(args(0)), runtime, Secrets.none).fold(e => sys.error(e.toString), identity)
    val handle  = opened(host, id(1), WorkRuntime.config(Path.of(args(1)), Mode.local, "fake-model", limits))
    handle.send(message("m1")).left.foreach(e => sys.error(e.toString))
    while !handle.state.open.values.exists(_.op == "waitJob") do Thread.sleep(20)
    println("READY")
    scala.Console.flush()
    Thread.sleep(600_000)

/** The laws of a crash of the host (10.2, 10.3, 15: "crash tests with SIGKILL that also check that no job process
  * stays").
  */
class CrashLaws extends munit.FunSuite:

  /** The processes whose command line holds `text`. */
  private def running(text: String): Vector[ProcessHandle] =
    ProcessHandle.allProcesses().iterator().asScala.filter(_.info().commandLine().toScala.exists(_.contains(text)))
      .toVector

  /** Waits at most `seconds` for the line `line` of `process`. */
  private def awaitLine(process: Process, line: String, seconds: Int): Boolean =
    val lines  = LinkedBlockingQueue[String]()
    val reader = BufferedReader(InputStreamReader(process.getInputStream, UTF_8))
    Thread.ofVirtual().start { () =>
      Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(l => lines.add(l): Unit)
    }: Unit
    val end   = System.nanoTime() + seconds * 1_000_000_000L
    var found = false
    while !found && System.nanoTime() < end do
      found = Option(lines.poll(100, TimeUnit.MILLISECONDS)).contains(line)
    found

  test(
    "Crash: after a SIGKILL of the host during a cell, no job process stays, the next start gives the catalog rows " +
      "of the journals, and the open gives TurnEnded(Crashed)"
  ) {
    assume(Backends.detect().nonEmpty, "no OS sandbox works on this host")
    val place = Place()
    try withRoot { root =>
        val java  = ProcessHandle.current().info().command().toScala.getOrElse("java")
        val probe = ProcessBuilder(
          java,
          "-cp",
          System.getProperty("java.class.path"),
          "dspy4s.harness.host.CrashProbe",
          root.toString,
          place.root.toString
        ).redirectErrorStream(true).start()
        try
          assert(awaitLine(probe, "READY", 120), "the probe did not reach its cell")
          assert(running("sleep 317").nonEmpty, "the job did not start")
        finally
          probe.destroyForcibly(): Unit
          probe.waitFor(30, TimeUnit.SECONDS): Unit
        assert(eventually(15)(running("sleep 317").isEmpty), "a process of the job stays after the crash")
        withHost(root, ScriptRuntime(() => Support.Script(Vector(answer("after"))))) { host =>
          val file = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
          val log  =
            try file.readFrom(0).fold(e => fail(e), identity)
            finally file.close()
          assertEquals(host.list, Vector(catalogRow(id(1))(log)))
          val handle = opened(host, id(1))
          assert(outcomes(handle).contains(TurnOutcome.Crashed), entries(handle).toString)
        }
      }
    finally place.close()
  }
