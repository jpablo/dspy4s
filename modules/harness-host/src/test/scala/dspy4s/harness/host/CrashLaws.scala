package dspy4s.harness.host

import dspy4s.harness.{HarnessLimits, Models, Place, Secrets, Support}
import dspy4s.harness.Support.answer
import dspy4s.harness.kernel.*
import dspy4s.harness.host.HostSupport.*
import dspy4s.harness.sandbox.{Backends, HostList}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.jdk.OptionConverters.*

/** A host in a child JVM for the crash laws (CODE_HARNESS_DESIGN.md, 10.3). It opens a work session at the root
  * `args(0)` for the folder `args(1)`. The policy starts the job `sleep 317`, and then runs `exec("sleep 318")` in the
  * same cell. When the exec runs, the probe prints `READY`, and it waits until the test kills it.
  */
object CrashProbe:

  def main(args: Array[String]): Unit =
    val limits = HarnessLimits(steps = 2)
    val model  = sessionModel(Models.policy("start", Some("""val j = spawn("sleep 317"); exec("sleep 318")""")))
    val env    = WorkEnv(
      Path.of(args(1)).resolveSibling("home"),
      HostList.empty,
      Backends.detect(),
      Secrets.none,
      Path.of(args(0))
    )
    val runtime = Models.withModel(model)(WorkRuntime.forWork(env, limits))
    val host    = LocalHost.start(Path.of(args(0)), runtime, Secrets.none).fold(e => sys.error(e.toString), identity)
    val handle  = opened(host, id(1), Some(WorkRuntime.config(Path.of(args(1)), "fake-model", limits)))
    handle.send(message("m1")).left.foreach(e => sys.error(e.toString))
    while !handle.state.open.values.exists(_.op == "exec") do Thread.sleep(20)
    println("READY")
    scala.Console.flush()
    Thread.sleep(600_000)

/** A host in a child JVM for the crash law of the report pass (CODE_HARNESS_DESIGN.md, 8.6 and 8.7). It opens the owner
  * `id(1)` and the session `id(2)` of that owner at the root `args(0)`, and sends a message of the owner to the
  * session. When the turn of the session ends, the hook before the report prints `READY`, and it waits until the test
  * kills the probe. So the session has its `TurnEnded`, and the owner has no report.
  */
object ReportCrashProbe:

  def main(args: Array[String]): Unit =
    val root    = Path.of(args(0))
    val runtime = ScriptRuntime(_ => Support.Script(Vector.fill(4)(answer("done"))))
    val hooks   = HostHooks(beforeReport = (_, _) =>
      println("READY")
      scala.Console.flush()
      Thread.sleep(600_000))
    val host = LocalHost.startWith(root, _ => runtime, Secrets.none, hooks = hooks)
      .fold(e => sys.error(e.toString), identity)
    opened(host, id(1)): Unit
    opened(host, id(2), Some(config().copy(owner = Owner.Session(id(1))))): Unit
    host.runAs(id(2), Command.Send(RequestId("m1"), "fake work", Map.empty), Controller.messageSource(id(1)))
      .left.foreach(e => sys.error(e.toString))
    Thread.sleep(600_000)

/** The laws of a crash of the host (10.2, 10.3, 15: "crash tests with SIGKILL that also check that no job process
  * stays"). Since the review of the plan, an `exec` runs under the wrapper too, so the law also checks it.
  */
class CrashLaws extends munit.FunSuite:

  /** Waits at most `seconds` for the line `line` of `process`. */
  private def awaitLine(process: Process, line: String, seconds: Int): Boolean =
    val lines  = LinkedBlockingQueue[String]()
    val reader = BufferedReader(InputStreamReader(process.getInputStream, UTF_8))
    Thread.ofVirtual().start { () =>
      Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(l => lines.add(l): Unit)
    }: Unit
    val end   = System.nanoTime() + seconds * 1_000_000_000L
    var found = false
    while !found && System.nanoTime() < end do found = Option(lines.poll(100, TimeUnit.MILLISECONDS)).contains(line)
    found

  test(
    "Crash: after a SIGKILL of the host during a cell, no process of a job or of an exec stays, the next start gives " +
      "the catalog rows of the journals, and the open gives TurnEnded(Crashed)"
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
          assert(running("sleep 318").nonEmpty, "the exec did not start")
        finally
          probe.destroyForcibly(): Unit
          probe.waitFor(30, TimeUnit.SECONDS): Unit
        assert(eventually(15)(running("sleep 317").isEmpty), "a process of the job stays after the crash")
        assert(eventually(15)(running("sleep 318").isEmpty), "a process of the exec stays after the crash")
        withHost(root, ScriptRuntime(_ => Support.Script(Vector(answer("after"))))) { host =>
          val file = SessionFile.open(SessionFile.pathOf(root, id(1)), id(1)).fold(e => fail(e), identity)
          val log  =
            try file.readFrom(0).fold(e => fail(e), identity)
            finally file.close()
          assertEquals(host.list, Vector(catalogRow(id(1))(log)))
          val handle = opened(host, id(1), None)
          assert(outcomes(handle).contains(TurnOutcome.Crashed), entries(handle).toString)
        }
      }
    finally place.close()
  }

  test(
    "Crash: after a SIGKILL of the host between the TurnEnded of a session and the report to its owner, the open of " +
      "the owner in the next host commits the report once"
  ) {
    withRoot { root =>
      val java  = ProcessHandle.current().info().command().toScala.getOrElse("java")
      val probe = ProcessBuilder(
        java,
        "-cp",
        System.getProperty("java.class.path"),
        "dspy4s.harness.host.ReportCrashProbe",
        root.toString
      ).redirectErrorStream(true).start()
      try assert(awaitLine(probe, "READY", 120), "the probe did not reach the report")
      finally
        probe.destroyForcibly(): Unit
        probe.waitFor(30, TimeUnit.SECONDS): Unit
      withHost(root, ScriptRuntime(_ => Support.Script(Vector.fill(4)(answer("after"))))) { host =>
        val child = host.journalOf(id(2)).fold(e => fail(e), identity).map(_.entry)
        assert(
          child.exists {
            case Entry.TurnEnded(TurnOutcome.Replied(_)) => true
            case _                                       => false
          },
          child.toString
        )
        val owner = opened(host, id(1), None)
        // The `TurnStarted` of a report has the source of the report too, so the law counts only the `Input`.
        def reports =
          owner.read(-1).count {
            case Logged(_, meta, Entry.Input(_, _, _)) => meta.source == Source.Child(id(2))
            case _                                     => false
          }
        assert(eventually()(reports == 1), entries(owner).toString)
        assertEquals(host.reportTo(id(1)), Right(0))
        assertEquals(reports, 1)
      }
    }
  }
