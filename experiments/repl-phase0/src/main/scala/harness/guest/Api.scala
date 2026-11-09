package harness.guest

// The guest API (F2 of docs/code-harness/CODE_HARNESS.md). The REPL class loader shares this package with the host,
// so a handler that the host makes is the same object in the guest code. Guest code calls the functions at the end
// of this file directly; a `using` parameter selects the handler.

/** The result of one shell command. */
final case class ExecResult(exitCode: Int, stdout: String)

trait Files:
  /** The full content of `path`. */
  def read(path: String): String

  /** Writes `content` to `path`. */
  def write(path: String, content: String): Unit

trait Shell:
  /** Runs `command` in the workspace. */
  def exec(command: String): ExecResult

trait Llm:
  /** The answer of the LM to `prompt`. */
  def query(prompt: String): String

/** The handlers of one REPL. The host sets `ec` to a context that runs each task with the output of that REPL. */
final case class Handlers(
    files: Files,
    shell: Shell,
    llm  : Llm,
    ec   : scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
)

/** The host puts the handlers of one REPL here, under a key for that REPL. The preamble of that REPL takes them out one
  * time and keeps them in a `val`. After the preamble, the registry has no entry for that REPL, so no global slot
  * selects a handler at call time.
  */
object Registry:
  private val entries = java.util.concurrent.ConcurrentHashMap[String, Handlers]()

  def register(key: String, handlers: Handlers): Unit = entries.put(key, handlers): Unit

  def take(key: String): Handlers =
    val handlers = entries.remove(key)
    if handlers == null then throw IllegalStateException(s"No handlers for the key $key") else handlers

  def size: Int = entries.size

def read(path      : String)(using files: Files): String                    = files.read(path)
def write(path     : String, content    : String)(using files: Files): Unit = files.write(path, content)
def exec(command   : String)(using shell: Shell): ExecResult                = shell.exec(command)
def llmQuery(prompt: String)(using llm  : Llm): String                      = llm.query(prompt)
