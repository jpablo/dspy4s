package experiment

/** Runs the experiments of Phase 0. Give experiment names as arguments to run only those. The "stuck" experiment leaves
  * a thread that runs until the JVM ends, so it runs last.
  */
object Main:
  private val all =
    List("cost", "growth", "state", "timeout", "output", "parallel", "host", "replay", "fork", "oom", "stuck")

  def main(args: Array[String]): Unit =
    val selected = if args.isEmpty then all else all.filter(args.contains)
    Report.section("Environment")
    Report.note(
      s"java ${System.getProperty("java.version")}, ${Runtime.getRuntime.availableProcessors} cores, " +
        s"max heap ${Runtime.getRuntime.maxMemory / (1024 * 1024)} MiB"
    )
    Report.note(s"scala ${dotty.tools.dotc.config.Properties.versionNumberString}")
    Report.note(s"compiler arguments: ${ReplSession.compilerArgs.drop(2).mkString(" ")}")
    selected.foreach:
      case "cost"     => CostExperiment.run()
      case "state"    => StateExperiment.run()
      case "timeout"  => TimeoutExperiment.run()
      case "output"   => OutputExperiment.run()
      case "parallel" => ParallelExperiment.run()
      case "host"     => HostApiExperiment.run()
      case "replay"   => ReplayExperiment.run()
      case "growth"   => GrowthExperiment.run()
      case "oom"      => OomExperiment.run()
      case "fork"     => ForkExperiment.run()
      case "stuck"    => StuckExperiment.run()
      case other      => Report.note(s"unknown experiment $other")
    Report.summary()
    // The "stuck" experiment leaves a daemon thread; the JVM ends with the main thread.
    System.exit(0)

/** A child JVM for the forked variant: it starts one REPL, runs one cell, prints the result line, and then measures its
  * memory.
  */
object ForkProbe:
  def main(args: Array[String]): Unit =
    val t0    = System.nanoTime()
    val s     = ReplSession("fork", None)
    val r     = s.run("1 + 1")
    val ms    = (System.nanoTime() - t0) / 1_000_000
    val jvmMs = System.currentTimeMillis() - ProcessHandle.current().info().startInstant().get().toEpochMilli
    println(s"RESULT ok=${r.isOk} repl+cell=${ms}ms since-jvm-start=${jvmMs}ms")
    println(s"MEMORY heap-after-gc=${Jvm.heapAfterGc()}MiB rss=${Jvm.rss()}MiB")
    System.exit(0)
