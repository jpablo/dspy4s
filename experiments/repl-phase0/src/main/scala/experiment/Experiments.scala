package experiment

import experiment.CellOutcome.*
import experiment.Report.*
import harness.guest.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files as JFiles, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration.*

def isCompileError(r: CellResult): Boolean = r.outcome.isInstanceOf[CompileError]
def isFailed(r      : CellResult): Boolean = r.outcome.isInstanceOf[Failed]

def stoppedAfterTimeout(r: CellResult): Boolean =
  r.outcome match
    case TimedOut(_, stopped, _) => stopped
    case _                       => false

def stopMillis(r: CellResult): Long =
  r.outcome match
    case TimedOut(_, _, ms) => ms
    case _                  => -1

def millis[A](body: => A): (A, Long) =
  val t0 = System.nanoTime()
  val a  = body
  (a, (System.nanoTime() - t0) / 1_000_000)

object StateExperiment:
  def run(): Unit =
    section("1. Persistent state")
    val s  = ReplSession("state", None)
    val r1 = s.run("val x = 21")
    val r2 = s.run("val y = x * 2\nprintln(s\"y = $y\")")
    show("cell 1", r1.output)
    show("cell 2", r2.output)
    check("a val of cell 1 is visible in cell 2", r1.isOk && r2.isOk && r2.output.contains("y = 42"))
    val r3 = s.run("case class P(a: Int)\ndef twice(n: Int): Int = n * 2")
    val r4 = s.run("println(twice(P(x).a))")
    check("a class and a def of cell 3 are visible in cell 4", r3.isOk && r4.isOk && r4.output.contains("42"))
    val r5 = s.run("val bad: Int = \"text\"")
    val r6 = s.run("println(x + y)")
    check("the state stays after a compile error", isCompileError(r5) && r6.isOk && r6.output.contains("63"))
    val r7 = s.run("val boom: Int = 1 / 0")
    val r8 = s.run("println(x)")
    check("the state stays after a runtime failure", isFailed(r7) && r8.isOk && r8.output.contains("21"))
    note(s"cell times (ms): ${List(r1, r2, r3, r4, r5, r6, r7, r8).map(_.millis).mkString(", ")}")

object TimeoutExperiment:
  def run(): Unit =
    section("2. Timeout")
    val s = ReplSession("timeout", None)
    s.run("val before = 7")
    val loop = """|println("start of cell")
         |var i = 0L
         |while true do
         |  i += 1
         |  if i == 1_000_000L then println("one million")""".stripMargin
    val r = s.run(loop, 2.seconds)
    show("output of the loop cell", r.output)
    check(
      "a top-level infinite loop stops after 2 s",
      stoppedAfterTimeout(r),
      s"outcome ${r.outcome}, cell time ${r.millis} ms, the stop took ${stopMillis(r)} ms"
    )
    check(
      "the output before the timeout comes back",
      r.output.contains("start of cell") && r.output.contains("one million")
    )
    val after = s.run("println(before + 1)")
    check("the REPL accepts a later cell", after.isOk && after.output.contains("8"), after.output.trim)

    val inDef = s.run("def spin(): Int =\n  var n = 0\n  while true do n += 1\n  n\nspin()", 1.second)
    check("a loop inside a def stops", stoppedAfterTimeout(inDef), s"the stop took ${stopMillis(inDef)} ms")
    val recursion =
      s.run("def fib(n: Int): Long = if n < 2 then n else fib(n - 1) + fib(n - 2)\nprintln(fib(90))", 1.second)
    check("a deep recursion stops", stoppedAfterTimeout(recursion), s"the stop took ${stopMillis(recursion)} ms")
    val lambda = s.run("Iterator.continually(1).foreach(_ => ())", 1.second)
    check(
      "a library loop that calls a guest lambda stops",
      stoppedAfterTimeout(lambda),
      s"the stop took ${stopMillis(lambda)} ms"
    )
    val sleep = s.run("println(\"sleeping\")\nThread.sleep(600_000)", 1.second)
    show("output of the sleep cell", sleep.output)
    check(
      "a blocking call stops (Thread.interrupt)",
      stoppedAfterTimeout(sleep),
      s"the stop took ${stopMillis(sleep)} ms"
    )
    val last = s.run("println(before * 2)")
    check("the REPL accepts a cell after five stops", last.isOk && last.output.contains("14"), last.output.trim)

object OutputExperiment:
  def run(): Unit =
    section("3. Output")
    val a                 = ReplSession("out-a", None)
    val b                 = ReplSession("out-b", None)
    def cell(tag: String) =
      """|val t0 = System.nanoTime()
         |for i <- 1 to 2000 do
         |  println(s"TAG line $i")
         |  if i % 100 == 0 then Thread.sleep(5)
         |println(s"window $t0 ${System.nanoTime()}")""".stripMargin.replace("TAG", tag)
    val Vector(ra, rb)        = inParallel(Seq(() => a.run(cell("A")), () => b.run(cell("B"))))
    def lines(r: CellResult)  = r.output.linesIterator.filter(_.contains(" line ")).toVector
    def window(r: CellResult) =
      r.output.linesIterator.find(_.startsWith("window")).map(_.split(" ")).map(p => (p(1).toLong, p(2).toLong))
    check(
      "REPL A has its 2000 lines and no line of B",
      lines(ra).size == 2000 && lines(ra).forall(_.startsWith("A line")),
      s"${lines(ra).size} lines"
    )
    check(
      "REPL B has its 2000 lines and no line of A",
      lines(rb).size == 2000 && lines(rb).forall(_.startsWith("B line")),
      s"${lines(rb).size} lines"
    )
    (window(ra), window(rb)) match
      case (Some((s1, e1)), Some((s2, e2))) =>
        val overlap = (math.min(e1, e2) - math.max(s1, s2)) / 1_000_000
        check("the two guest runs overlapped in time", overlap > 0, s"overlap $overlap ms")
      case _ => check("the two guest runs overlapped in time", false, "no window line")

    val c         = ReplSession("diagnostics", None)
    val typeError = c.run("val n: Int = \"text\"")
    show("a type error", typeError.output)
    check(
      "a type error comes back as text",
      isCompileError(typeError) && typeError.output.contains("Required: Int")
    )
    val syntaxError = c.run("val = 3")
    show("a syntax error", syntaxError.outcome.toString)
    check("a syntax error comes back as text", isCompileError(syntaxError))
    val failure = c.run("val q = 1 / 0")
    show("a runtime failure", failure.output)
    check("a runtime failure comes back as text", isFailed(failure) && failure.output.contains("ArithmeticException"))
    val warning = c.run("@deprecated(\"old\", \"1\") def old = 1\nval useOld = old")
    show("a warning", warning.output)
    check("a warning comes back as text", warning.isOk && warning.output.contains("deprecated"))

    val direct = c.run("System.out.println(\"via System.out\")")
    check(
      "without routing, a guest System.out.println does not reach the buffer (it goes to the host stdout)",
      direct.isOk && !direct.output.contains("via System.out")
    )
    StdRouting.install()
    val routed = c.run("System.out.println(\"via System.out\")\nSystem.err.println(\"via System.err\")")
    check(
      "with the one-time routing of System.out, the guest System.out reaches its own buffer",
      routed.output.contains("via System.out") && routed.output.contains("via System.err")
    )

    val fa                      = ReplSession("future-a", None)
    val fb                      = ReplSession("future-b", None)
    def futureCell(tag: String) =
      s"""|import scala.concurrent.*, duration.*, ExecutionContext.Implicits.global
          |Await.result(Future(println("$tag from a Future")), 5.seconds)
          |Await.result(Future.traverse(1 to 50)(i => Future(println("$tag future " + i))), 5.seconds)""".stripMargin
    val rfa                                    = fa.run(futureCell("A"))
    val rfb                                    = fb.run(futureCell("B"))
    def futureLines(text: String, tag: String) = text.linesIterator.count(_.startsWith(s"$tag future"))
    note(
      s"global ExecutionContext: buffer A has ${futureLines(fa.currentOutput, "A")} A lines and " +
        s"${futureLines(fa.currentOutput, "B")} B lines; buffer B has ${futureLines(rfb.output, "B")} B lines"
    )
    check(
      "a Future of REPL B prints to the buffer of B",
      rfb.output.contains("B from a Future") && futureLines(rfb.output, "B") == 50,
      "a thread of the global pool keeps the Console.out of the REPL that started it"
    )

    // The fix: the preamble binds an ExecutionContext for each REPL, and each task sets the output of its REPL.
    def quiet =
      Handlers(
        files = new Files:
          def read(path : String): String                = ""
          def write(path: String, content: String): Unit = ()
        ,
        shell = new Shell:
          def exec(command: String): ExecResult = ExecResult(0, "")
        ,
        llm = new Llm:
          def query(prompt: String): String = ""
      )
    val ec1                 = ReplSession("ec-c", Some(quiet))
    val ec2                 = ReplSession("ec-d", Some(quiet))
    def ecCell(tag: String) =
      s"""|import scala.concurrent.*, duration.*
          |Await.result(Future.traverse(1 to 50)(i => Future(println("$tag future " + i))), 5.seconds)""".stripMargin
    val Vector(rc, rd) = inParallel(Seq(() => ec1.run(ecCell("C")), () => ec2.run(ecCell("D"))))
    check(
      "with an ExecutionContext for each REPL (bound in the preamble), each buffer has its own 50 task lines",
      futureLines(rc.output, "C") == 50 && futureLines(rc.output, "D") == 0 &&
        futureLines(rd.output, "D") == 50 && futureLines(rd.output, "C") == 0,
      s"C buffer: ${futureLines(rc.output, "C")} C, ${futureLines(rc.output, "D")} D; " +
        s"D buffer: ${futureLines(rd.output, "D")} D, ${futureLines(rd.output, "C")} C"
    )

object ParallelExperiment:
  def run(): Unit =
    section("4. Parallel REPLs")
    val p1   = ReplSession("par-1", None)
    val p2   = ReplSession("par-2", None)
    val host = classOf[dotty.tools.repl.StopRepl]
    check(
      "each REPL loader has its own StopRepl class",
      (p1.stopReplClass ne p2.stopReplClass) && (p1.stopReplClass ne host) && (p2.stopReplClass ne host)
    )
    val spin = "println(\"P1 start\")\nwhile true do ()"
    val work = """|val t0 = System.nanoTime()
         |var n = 0L
         |while System.nanoTime() - t0 < 4_000_000_000L do n += 1
         |println(s"P2 done after ${(System.nanoTime() - t0) / 1_000_000} ms")""".stripMargin
    val Vector(r1, r2) = inParallel(Seq(() => p1.run(spin, 2.seconds), () => p2.run(work, 30.seconds)))
    check("REPL 1 stops at its timeout of 2 s", stoppedAfterTimeout(r1), s"cell time ${r1.millis} ms")
    show("REPL 2 output", r2.output)
    check("REPL 2 ends normally, after the stop of REPL 1", r2.isOk && r2.output.contains("P2 done"))
    val later1 = p1.run("println(\"P1 again\")")
    val later2 = p2.run("println(n > 0)")
    check("both REPLs accept a later cell", later1.isOk && later2.isOk && later2.output.contains("true"))

    // Throughput: n REPLs on n threads, 40 cells each, with a compile in each cell. Rounds 1 and 2 warm the JIT,
    // rounds 3 and 4 measure the time, and round 5 samples the thread states (the sampler slows the run).
    def cells                             = (1 to 40).map(i => s"val v$i = (1 to $i).map(_ * 2).sum\nprintln(s\"v$i = $${v$i}\")")
    def correct(results: Seq[CellResult]) =
      results.zipWithIndex.forall((r, k) => r.isOk && r.output.contains(s"v${k + 1} = ${(k + 1) * (k + 2)}"))
    var allCorrect = true
    for round <- 1 to 5; n <- List(1, 4, 8) do
      val sessions = (1 to n).map(i => ReplSession(s"conc-$round-$n-$i", None))
      sessions.foreach(_.run("val warm = 1"))
      val gc0           = Jvm.gcMillis()
      val sampler       = Option.when(round == 5)(Contention.start(s"repl-conc-$round-$n-"))
      val (results, ms) = millis(inParallel(sessions.map(s => () => cells.map(s.run(_)))))
      val contention    = sampler.map(_.stop())
      val gcMs          = Jvm.gcMillis() - gc0
      allCorrect &&= results.forall(correct)
      sessions.foreach(_.close())
      if round == 3 || round == 4 then
        note(
          f"round $round: $n REPLs in parallel, 40 cells each: wall time $ms ms, ${n * 40 * 1000.0 / ms}%.0f cells per second, GC $gcMs ms"
        )
      contention.foreach(c =>
        note(s"$n REPLs, thread states of the cell threads in ${c.samples} samples: ${c.summary}")
      )
    check("40 cells in each of 1, 4 and 8 parallel REPLs give correct results", allCorrect)

object HostApiExperiment:
  private def tagged(tag: String, calls: AtomicInteger): Handlers =
    Handlers(
      files = new Files:
        def read(path: String): String =
          calls.incrementAndGet()
          s"[$tag] $path"
        def write(path: String, content: String): Unit = ()
      ,
      shell = new Shell:
        def exec(command: String): ExecResult = ExecResult(0, s"$tag ran $command")
      ,
      llm = new Llm:
        def query(prompt: String): String = s"$tag answers $prompt"
    )

  def run(): Unit =
    section("5. Host API")
    val (c1, c2) = (AtomicInteger(), AtomicInteger())
    val h1       = ReplSession("host-1", Some(tagged("H1", c1)))
    val h2       = ReplSession("host-2", Some(tagged("H2", c2)))
    check("the registry has no entry after the two preambles", Registry.size == 0, s"size ${Registry.size}")
    val code = """|val tags = (1 to 5000).map(i => read(s"f$i").takeWhile(_ != ']')).distinct
         |println(s"tags: ${tags.mkString(",")}")
         |println(exec("ls").stdout)
         |println(llmQuery("hello"))""".stripMargin
    val Vector(o1, o2) = inParallel(Seq(() => h1.run(code), () => h2.run(code)))
    show("REPL 1 output", o1.output)
    check("REPL 1 sees only its own handler", o1.isOk && o1.output.contains("tags: [H1") && !o1.output.contains("H2"))
    check("REPL 2 sees only its own handler", o2.isOk && o2.output.contains("tags: [H2") && !o2.output.contains("H1"))
    check("each host handler got its own 5000 calls", c1.get == 5000 && c2.get == 5000, s"${c1.get}, ${c2.get}")
    val perf = h1.run(
      "val t0 = System.nanoTime()\nvar k = 0\nwhile k < 1_000_000 do\n  read(\"x\")\n  k += 1\n" +
        "println(s\"ns per call: ${(System.nanoTime() - t0) / 1_000_000}\")"
    )
    note(
      s"1,000,000 direct guest calls of read: ${perf.output.linesIterator.find(_.startsWith("ns per call")).getOrElse(perf.output)}"
    )
    val identity = h1.run("println(System.identityHashCode(classOf[harness.guest.Files]))")
    check(
      "the guest trait is the same class as in the host (no second copy)",
      identity.output.trim == System.identityHashCode(classOf[harness.guest.Files]).toString
    )
    val wrong = h1.run("read(42)")
    show("a wrong argument type", wrong.output)
    check("a wrong argument type is a compile error", isCompileError(wrong))

object ReplayExperiment:
  private val cells = Vector(
    """|val build = read("build.sbt")
       |val modules = "lazy val (\\w+)".r.findAllMatchIn(build).map(_.group(1)).toVector""".stripMargin,
    """|def testsOf(m: String): Vector[String] =
       |  exec(s"find modules/$m/src/test -type f").stdout.linesIterator.filter(_.nonEmpty).toVector
       |val counts = modules.map(m => m -> testsOf(m).size).toMap""".stripMargin,
    """|val summary = llmQuery(s"Summarize: ${counts.toList.sorted.mkString(", ")}")
       |write("summary.txt", summary)
       |println(summary)""".stripMargin
  )
  private val cell4 = "println(counts.filter(_._2 == 0).keys.toList.sorted)"

  private def workspace(): Path =
    val root                               = JFiles.createTempDirectory("replay-ws")
    def put(path: String, content: String) =
      val p = root.resolve(path)
      JFiles.createDirectories(p.getParent)
      JFiles.writeString(p, content, UTF_8)
    put("build.sbt", "lazy val core = project\nlazy val programs = project\nlazy val docs = project\n")
    put("modules/core/src/test/A.scala", "class A")
    put("modules/core/src/test/B.scala", "class B")
    put("modules/programs/src/test/C.scala", "class C")
    root

  def run(): Unit =
    section("6. Replay (section 8.4)")
    val root        = workspace()
    val liveWorld   = RealWorld(root)
    val liveGate    = Gate(liveWorld)
    val live        = ReplSession("replay-live", Some(liveGate.handlers))
    val journal     = mutable.ArrayBuffer[CellJournal]()
    val (_, liveMs) = millis:
      for code <- cells do
        liveGate.startLiveCell()
        val r = live.run(code)
        journal += CellJournal(code, liveGate.liveRecords, r.output, r.isOk)
    journal.zipWithIndex.foreach((j, i) =>
      note(s"cell ${i + 1}: ok ${j.ok}, ${j.effects.size} effects: ${j.effects.map(_.op).mkString(", ")}")
    )
    note(
      s"live run of cells 1-3: $liveMs ms; real effects: ${liveWorld.reads.get} reads, ${liveWorld.processes.get} " +
        s"processes, ${liveWorld.lmCalls.get} LM calls, ${liveWorld.writes.get} writes"
    )
    val journalBytes = journal.map(_.effects.map(e => e.op.length + e.args.map(_.length).sum + e.result.length).sum).sum
    note(
      s"the journal holds ${journal.map(_.effects.size).sum} effects, $journalBytes characters of arguments and results"
    )
    liveGate.startLiveCell()
    val liveCell4 = live.run(cell4)
    show("cell 4 in the live REPL", liveCell4.output)
    live.close()

    // The restart. The files change after the session, so a live run of the cells now gives other values.
    JFiles.writeString(root.resolve("build.sbt"), "lazy val core = project\nlazy val extra = project\n", UTF_8)
    JFiles.delete(root.resolve("summary.txt"))

    val replayWorld               = RealWorld(root)
    val replayGate                = Gate(replayWorld)
    val replayed                  = ReplSession("replay", Some(replayGate.handlers))
    val (replayResults, replayMs) = millis:
      journal.filter(_.ok).map: j =>
        replayGate.startReplayCell(j.effects)
        val r = replayed.run(j.code)
        (r, replayGate.unusedReplayRecords, j)
    replayResults.zipWithIndex.foreach { case ((r, unused, j), i) =>
      check(
        s"replay of cell ${i + 1} is ok, uses all its records, and gives the recorded output",
        r.isOk && unused.isEmpty && r.output == j.output,
        s"${r.millis} ms"
      )
    }
    note(s"replay of cells 1-3: $replayMs ms (the live run took $liveMs ms)")
    check(
      "the replay did no real effect",
      replayWorld.reads.get + replayWorld.writes.get + replayWorld.processes.get + replayWorld.lmCalls.get == 0,
      s"${replayWorld.reads.get} reads, ${replayWorld.writes.get} writes, ${replayWorld.processes.get} processes, " +
        s"${replayWorld.lmCalls.get} LM calls"
    )
    check("the replay did not write summary.txt again", !JFiles.exists(root.resolve("summary.txt")))
    replayGate.startLiveCell()
    val after4 = replayed.run(cell4)
    show("cell 4 after the replay", after4.output)
    check("cell 4 works after the replay, with the same output", after4.isOk && after4.output == liveCell4.output)
    val modules = replayed.run("println(modules)")
    check(
      "the definitions come from the journal, not from the files now",
      modules.output.trim == "Vector(core, programs, docs)",
      s"modules = ${modules.output.trim}; the disk now has core and extra"
    )

    // Divergence: the code of cell 1 changed, so its call has other arguments than the record.
    val divergedWorld = RealWorld(root)
    val divergedGate  = Gate(divergedWorld)
    val diverged      = ReplSession("diverged", Some(divergedGate.handlers))
    divergedGate.startReplayCell(journal.head.effects)
    val d = diverged.run(journal.head.code.replace("build.sbt", "other.sbt"))
    show("a divergent cell", d.output)
    check(
      "a divergence fails the cell with a clear message and no real effect",
      isFailed(d) && d.output.contains("No recorded effect for read(other.sbt)") && divergedWorld.reads.get == 0
    )

object CostExperiment:
  def run(): Unit =
    section("7. Cost")
    val baseHeap = Jvm.heapAfterGc()
    val baseMeta = Jvm.metaspace()
    val baseRss  = Jvm.rss()
    note(
      s"before any REPL: heap $baseHeap MiB, metaspace $baseMeta MiB, rss $baseRss MiB, ${Jvm.loadedClasses()} classes"
    )
    val keptHeap = withRepls(baseHeap, baseMeta, baseRss)
    // The REPLs are out of scope now.
    val released = Jvm.heapAfterGc()
    note(
      s"after all REPLs are closed and out of scope: heap $released MiB (start $baseHeap, with the REPLs $keptHeap), " +
        s"metaspace ${Jvm.metaspace()} MiB, ${Jvm.loadedClasses()} classes, rss ${Jvm.rss()} MiB"
    )
    check(
      "the heap returns near the start value after the REPLs are dropped",
      released - baseHeap < 20,
      s"+${released - baseHeap} MiB"
    )

  private def start(name: String): (ReplSession, Map[String, Long]) =
    val t0    = System.nanoTime()
    val s     = ReplSession(name, None)
    val first = s.run("1 + 1")
    val total = (System.nanoTime() - t0) / 1_000_000
    (s, s.startup.toMap + ("first cell" -> first.millis) + ("total" -> total))

  private def show(times: Map[String, Long]): String =
    List("driver", "initialState", "first cell", "total").map(k => s"$k ${times(k)} ms").mkString(", ")

  /** Starts and measures the REPLs, and closes them. Gives the heap with all REPLs. */
  private def withRepls(baseHeap: Long, baseMeta: Long, baseRss: Long): Long =
    val (cold, coldTimes) = start("cold")
    note(s"first REPL in the JVM (cold): ${show(coldTimes)}")
    val heap1 = Jvm.heapAfterGc()
    val meta1 = Jvm.metaspace()
    val rss1  = Jvm.rss()
    note(s"with 1 REPL: heap $heap1 MiB (+${heap1 - baseHeap}), metaspace $meta1 MiB (+${meta1 -
        baseMeta}), rss $rss1 MiB (+${rss1 - baseRss})")

    val (warm, warmTimes) = start("warm")
    note(s"second REPL (warm): ${show(warmTimes)}")
    val heap2 = Jvm.heapAfterGc()
    val rss2  = Jvm.rss()
    note(s"with 2 REPLs: heap $heap2 MiB (+${heap2 - heap1} for the second), rss $rss2 MiB (+${rss2 - rss1})")

    val small  = (1 to 30).map(i => warm.run(s"val a$i = $i * 2").millis)
    val larger = (1 to 30).map: i =>
      warm.run(
        s"""|case class Row$i(name: String, size: Int)
            |val rows$i = (1 to 100).map(k => Row$i(s"r$$k", k % 7)).groupBy(_.size).view.mapValues(_.size).toMap
            |println(rows$i.size)""".stripMargin
      ).millis
    note(s"warm cell time, small cell: median ${median(small)} ms, p90 ${percentile(small, 0.9)} ms")
    note(
      s"warm cell time, cell with a case class and collections: median ${median(larger)} ms, p90 ${percentile(larger, 0.9)} ms"
    )
    val heap3 = Jvm.heapAfterGc()
    note(
      f"heap growth of one REPL after 60 more cells: +${heap3 - heap2} MiB (${(heap3 - heap2) * 1024.0 / 60}%.0f KiB for each cell)"
    )

    val (four, fourMs) = millis(inParallel((1 to 4).map(i => () => start(s"four-$i"))))
    note(
      s"4 REPLs started at the same time on 4 threads: wall time $fourMs ms; each: ${four.map(_._2("total")).mkString(", ")} ms"
    )
    val heap4 = Jvm.heapAfterGc()
    val rss4  = Jvm.rss()
    note(s"with 2 + 4 REPLs: heap $heap4 MiB (+${heap4 -
        heap3} for the 4), metaspace ${Jvm.metaspace()} MiB, rss $rss4 MiB (+${rss4 - rss2} for the 4)")

    val (eight, eightMs) = millis(inParallel((1 to 8).map(i => () => start(s"eight-$i"))))
    val heap8            = Jvm.heapAfterGc()
    val rss8             = Jvm.rss()
    note(s"8 more REPLs started at the same time: wall time $eightMs ms; heap $heap8 MiB (+${heap8 -
        heap4} for the 8), rss $rss8 MiB (+${rss8 - rss4})")

    (cold :: warm :: (four ++ eight).map(_._1).toList).foreach(_.close())
    heap8

object GrowthExperiment:
  def run(): Unit =
    section("7c. Heap growth of a long session")
    val base = Jvm.heapAfterGc()
    val s    = ReplSession("long", None)
    var last = Jvm.heapAfterGc()
    note(s"after the start: heap $last MiB (+${last - base})")
    for block <- 1 to 12 do
      val times = (1 to 50).map: i =>
        val k = block * 100 + i
        s.run(
          s"""|case class Row$k(name: String, size: Int)
              |val rows$k = (1 to 100).map(j => Row$k(s"r$$j", j % 7)).groupBy(_.size).view.mapValues(_.size).toMap
              |println(rows$k.size)""".stripMargin
        ).millis
      val heap = Jvm.heapAfterGc()
      note(
        s"after ${block * 50} cells: heap $heap MiB (+${heap - last} for 50 cells), median cell time ${median(times)} ms"
      )
      last = heap
    if sys.props.contains("exp.histogram") then
      val pid = ProcessHandle.current().pid().toString
      val p   = ProcessBuilder("jcmd", pid, "GC.class_histogram").start()
      String(p.getInputStream.readAllBytes()).linesIterator.take(25).foreach(l => note(l))
    s.close()

/** A guest that fills the heap can stop the host, so this experiment runs in a child JVM (`OomProbe`). */
object OomExperiment:
  def run(): Unit =
    section("9. Memory isolation (in a child JVM, 4 runs with -Xmx1g and 4 runs with -Xmx4g)")
    val javaCmd = ProcessHandle.current().info().command().orElse("java")
    val cp      = System.getProperty("java.class.path")
    val runs    = List("1g", "1g", "1g", "1g", "4g", "4g", "4g", "4g").zipWithIndex.map: (heap, k) =>
      val i = k + 1
      val p = ProcessBuilder(
        javaCmd,
        s"-Xmx$heap",
        "-Xss4m",
        "-cp",
        cp,
        "experiment.OomProbe"
      ).redirectErrorStream(true).start()
      val out      = String(p.getInputStream.readAllBytes(), UTF_8)
      val finished = p.waitFor(180, _root_.java.util.concurrent.TimeUnit.SECONDS)
      if i == 1 then show("child output of run 1", out)
      val survived    = out.contains("STEP after") && out.contains("ok true\n") && finished
      val otherFailed = out.linesIterator.exists(l => l.startsWith("STEP during") && l.contains("ok false"))
      val recovered   = out.linesIterator.exists(l => l.startsWith("STEP after") && l.contains("ok true"))
      note(
        s"run $i (-Xmx$heap): host survived ${out.contains("STEP after")}, REPL 2 failed during the hog $otherFailed, " +
          s"REPL 2 recovered $recovered, exit ${if finished then p.exitValue().toString else "none"}" +
          (if out.contains("STEP after") then ""
           else s"; last lines: ${out.linesIterator.toVector.takeRight(2).mkString(" / ")}")
      )
      out.contains("STEP after")
    check(
      "the host JVM survives a guest that fills the heap, in all runs",
      runs.forall(identity),
      s"${runs.count(identity)} of ${runs.size}"
    )

object OomProbe:
  def main(args: Array[String]): Unit =
    val other = ReplSession("oom-other", None)
    val alloc = "val made = Vector.fill(500_000)(\"x\" * 10).size\nprintln(made)"
    println(s"STEP before: REPL 2 allocates, ok ${other.run(alloc).isOk}")
    val (hogResult, during) = hogTheHeap(other, alloc)
    println(s"STEP hog: REPL 1 outcome ${hogResult.outcome.toString.take(120)}")
    println(
      s"STEP during: REPL 2 allocates while REPL 1 holds the heap, ok ${during.isOk}, ${during.outcome.toString.take(120)}"
    )
    Jvm.heapAfterGc()
    println(s"STEP after: REPL 2 allocates after REPL 1 is closed and out of scope, ok ${other.run(alloc).isOk}")
    System.exit(0)

  /** REPL 1 keeps arrays in an object until the heap is full. Gives its result and the result of `alloc` in REPL 2. */
  private def hogTheHeap(other: ReplSession, alloc: String): (CellResult, CellResult) =
    val hog = ReplSession("oom-hog", None)
    hog.run("object Hog:\n  val buf = scala.collection.mutable.ArrayBuffer[Array[Byte]]()")
    val r      = hog.run("while true do Hog.buf += new Array[Byte](8 << 20)", 60.seconds)
    val during = other.run(alloc)
    hog.close()
    (r, during)

object ForkExperiment:
  def run(): Unit =
    section("7b. Cost of a forked JVM")
    val javaCmd = ProcessHandle.current().info().command().orElse("java")
    val cp      = System.getProperty("java.class.path")
    val times   = (1 to 3).map: _ =>
      val t0 = System.nanoTime()
      val p  =
        ProcessBuilder(javaCmd, "-Xmx1g", "-Xss4m", "-cp", cp, "experiment.ForkProbe").redirectErrorStream(true).start()
      val lines    = scala.io.Source.fromInputStream(p.getInputStream, "UTF-8").getLines()
      val ready    = lines.find(_.startsWith("RESULT"))
      val toResult = (System.nanoTime() - t0) / 1_000_000
      val rest     = lines.toList
      p.waitFor()
      val toExit = (System.nanoTime() - t0) / 1_000_000
      note(
        s"forked JVM: process start to the result line $toResult ms, to the process end $toExit ms; " +
          s"child says: ${ready.getOrElse("no RESULT line")} ${rest.mkString(" ")}"
      )
      toResult
    note(s"forked JVM, process start to the result line, median of 3: ${median(times)} ms")

object StuckExperiment:
  def run(): Unit =
    section("8. A loop that the stop flag cannot reach")
    val s = ReplSession("stuck", None)
    s.run("val kept = 5")
    val r = s.run("Iterator.from(0).size", 1.second)
    check("a loop inside library code with no guest call stops", stoppedAfterTimeout(r), r.outcome.toString)
    val after = s.run("println(kept * 3)")
    check("the REPL accepts a later cell while the old thread runs", after.isOk && after.output.contains("15"))
    ReplSession.leakedThreads.synchronized(ReplSession.leakedThreads.toList).foreach: t =>
      val cpu0 = Jvm.threadCpuMillis(t)
      Thread.sleep(2000)
      val cpu1 = Jvm.threadCpuMillis(t)
      note(s"the thread ${t.getName} still runs (alive ${t.isAlive}): ${cpu1 - cpu0} ms of CPU in 2000 ms")
