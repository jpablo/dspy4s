package dspy4s.harness

import dspy4s.harness.guest.*
import dspy4s.harness.kernel.CellOutcome
import dspy4s.harness.repl.{Bindings, ScalaRepl}
import zio.blocks.schema.json.Json

import scala.concurrent.duration.*

/** The laws of the API reference of a mode (CODE_HARNESS_DESIGN.md, 5.1 and 5.2).
  *
  * In the live sessions of 2026-11-10 the API reference was the whole guest API in each mode. So a model in a work
  * session said that it could use the network, other sessions and `rlm`, and a cell that called them did not compile.
  * The law ties the reference to the compiler: the reference of a mode names a function only when a call of it compiles
  * in a REPL of that mode.
  */
class ReferenceLaws extends munit.FunSuite:

  // Each mode starts a REPL, and each probe compiles a cell.
  override val munitTimeout: Duration = 10.minutes

  private val none = Mode(Access.None, Access.None, Access.None, Access.None, Access.None, Access.None, Access.None)

  /** The modes of 5.2, the mode of a work session of phase 7, and a mode that only reads files. */
  private val modes = Vector(
    "full"       -> Mode.full,
    "local"      -> Mode.local,
    "readOnly"   -> Mode.readOnly,
    "controller" -> Mode.controller,
    "closed"     -> Mode.closed,
    "work"       -> Mode.local.copy(sessions = Access.None, children = Access.None),
    "readFiles"  -> none.copy(files = Access.Read)
  )

  /** A call of each function of `api` that needs a capability. The handlers of the law throw, so a call that compiles
    * fails when it runs, and the law looks only at the compile. A name with two forms has a probe for each form, and
    * the name compiles when one of its forms compiles: `zoom(ref)` reads the memory of the session with no capability
    * (7.1), and `zoom(session, ref)` needs `Sessions`.
    */
  private val probes = Vector(
    "read"            -> """read("a.txt")""",
    "write"           -> """write("a.txt", "b")""",
    "edit"            -> """edit("a.txt", "a", "b")""",
    "httpGet"         -> """httpGet("http://fake.test")""",
    "exec"            -> """exec("true")""",
    "spawn"           -> """spawn("true")""",
    "jobOutput"       -> "jobOutput(JobId(0, 0))",
    "waitJob"         -> "waitJob(JobId(0, 0), 1)",
    "stopJob"         -> "stopJob(JobId(0, 0))",
    "llmQuery"        -> """llmQuery("p")""",
    "llmQueryBatched" -> """llmQueryBatched(Vector("p"))""",
    "keys"            -> "keys()",
    "load"            -> """load[Int]("k")""",
    "store"           -> """store("k", 1)""",
    "sessions"        -> "sessions()",
    // A cell can get a ref from a card of `sessions`. The `start` probe makes a `Scope` and a `StrategyName`, so it
    // compiles only because the session types are `@assumeSafe` (the live sessions of 2026-11-10).
    "tell"       -> """tell(sessions().head.session, "m")""",
    "observe"    -> """observe(sessions().head.session, "q")""",
    "zoom"       -> """zoom(sessions().head.session, "r")""",
    "zoom"       -> """zoom("0.0")""",
    "start"      -> """start("g", Scope.Directory("d"), StrategyName.Window)""",
    "rlm"        -> """rlm[Int]("t")""",
    "rlmBatched" -> """rlmBatched[Int](Vector(("t", Map.empty)))"""
  )

  /** A method of a capability with no function of `api` of the same name, and the function that calls it. */
  private val methods =
    Vector("list" -> "sessions", "get" -> "httpGet", "query" -> "llmQuery", "queryBatched" -> "llmQueryBatched")

  /** The functions that need no capability, so each reference names them. */
  private val always = Vector("println", "now", "input", "zoom")

  /** The words of the session API. A model reads a call in a comment as well as in a `def`. `zoom(ref)` reads the
    * memory of this session with no capability (7.1), so only the form for another session, `zoom(session`, counts. The
    * value comes before the tests of the modes, so the class has each field before a test closure takes it.
    */
  private val sessionWords = raw"\b(tell|observe|zoom\(session|SessionRef|SessionCard|StrategyName|Sessions)\b".r

  /** True when `reference` has a definition with the name `name`. */
  private def mentions(reference: String, name: String): Boolean =
    raw"\bdef\s+${java.util.regex.Pattern.quote(name)}\b".r.findFirstIn(reference).nonEmpty

  /** The compile error of each probe in a REPL of `mode`, or none when the call compiles, in the order of `probes`. */
  private def errors(mode: Mode): Vector[(String, String, Option[String])] =
    val repl = ScalaRepl(mode, ReferenceLaws.bindings, cellTimeout = 30.seconds)
    try probes.map { (name, code) =>
        (
          name,
          code,
          repl.run(code).outcome match
            case CellOutcome.CompileError(lines) => Some(lines.mkString("\n"))
            case _                               => None
        )
      }
    finally repl.close()

  /** The names of `probes` with a call that compiles in a REPL of `mode`. */
  private def compiling(mode: Mode): Set[String] = errors(mode).collect { case (name, _, None) => name }.toSet

  test("Reference: each function of api has a call that compiles in a REPL of the full mode") {
    val failed = errors(Mode.full).collect { case (_, code, Some(error)) => s"$code: $error" }
    assert(failed.isEmpty, failed.mkString("\n\n"))
  }

  modes.foreach { (label, mode) =>
    test(s"Reference: the reference of the mode $label names a function only when a call of it compiles in that mode") {
      val reference    = Harness.apiReference(mode)
      val compiles     = compiling(mode)
      val wrong        = probes.map(_._1).filter(name => mentions(reference, name) != compiles(name))
      val methodsWrong = methods.collect {
        case (method, function) if mentions(reference, method) != compiles(function) => method
      }
      assert(
        wrong.isEmpty && methodsWrong.isEmpty,
        s"compiles: ${compiles.toVector.sorted.mkString(", ")}; the reference is wrong for: " +
          (wrong ++ methodsWrong).mkString(", ")
      )
      always.foreach(name => assert(mentions(reference, name), s"the reference does not name $name"))
      // The form of zoom for another session needs Sessions, as each function of the trait.
      assertEquals(reference.contains("def zoom(session"), compiles("sessions"), "zoom(session, ref)")
    }
  }

  test("Reference: a mode with no Sessions gets no word of the session API, also in a comment or a type") {
    // In a live session of 2026-11-10, the work mode had no Sessions, and the model still named tell, observe, zoom
    // and Sessions.start, from the comments of SessionTypes.scala.
    modes.filter((_, mode) => mode.sessions == Access.None).foreach { (label, mode) =>
      val found = sessionWords.findAllIn(Harness.apiReference(mode)).toVector.distinct
      assert(found.isEmpty, s"the reference of the mode $label has: ${found.mkString(", ")}")
    }
  }

  test("Reference: no reference shows the Schema givens of the session types, which a cell cannot use") {
    modes.foreach { (label, mode) =>
      val reference = Harness.apiReference(mode)
      assert(!reference.contains("SessionSchemas"), s"the reference of the mode $label shows SessionSchemas")
    }
  }

object ReferenceLaws:

  /** Handlers for each capability. Each call throws, because a probe only compiles. */
  val bindings: Bindings = Bindings(
    turn = new Turn:
      def now(): Long                       = 0L
      def input(name: String): Option[Json] = None
      def zoom(ref  : String): String       = ???
    ,
    files = Some(new Files:
      def read(path : String): String                                 = ???
      def write(path: String, content: String): Unit                  = ???
      def edit(path : String, oldText: String, newText: String): Unit = ???),
    shell = Some(new Shell:
      def exec(command : String)(using Files): String                            = ???
      def spawn(command: String, report   : Boolean = false)(using Files): JobId = ???
      def jobOutput(job: JobId): String                                          = ???
      def waitJob(job  : JobId, maxSeconds: Int): JobStatus                      = ???
      def stopJob(job  : JobId): Unit                                            = ???),
    network = Some(new Network:
      def get(url: String): String = ???),
    llm = Some(new Llm:
      def query(prompt        : String): String                 = ???
      def queryBatched(prompts: Vector[String]): Vector[String] = ???),
    store = Some(new Store:
      def keys: Vector[String]                        = ???
      def load[A: Data](key : String): Option[A]      = ???
      def store[A: Data](key: String, value: A): Unit = ???),
    sessions = Some(new Sessions:
      def list(): Vector[SessionCard]                                                       = ???
      def tell(session   : SessionRef, message : String): Unit                              = ???
      def start(goal     : String, scope       : Scope, strategy: StrategyName): SessionRef = ???
      def observe(session: SessionRef, question: String)(using Llm): String                 = ???
      def zoom(session   : SessionRef, ref     : String): String                            = ???),
    children = Some(new Children:
      def rlm[A: Data](task        : String, inputs: Map[String, String]): A           = ???
      def rlmBatched[A: Data](tasks: Vector[(String, Map[String, String])]): Vector[A] = ???)
  )
