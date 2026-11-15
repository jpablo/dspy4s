package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Access, Mode}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.nio.file.{DirectoryIteratorException, Files as JFiles, LinkOption, NoSuchFileException, Path}
import java.time.Instant
import java.util.Locale
import java.util.concurrent.{CancellationException, CompletableFuture, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

/** The places of the host that a profile names, apart from the scope (CODE_HARNESS_DESIGN.md, 5.4).
  *
  * Each path is absolute and normalized. Seatbelt compares the real path of a file, so a path that exists must also be
  * real: `Layout.forSession` resolves the symbolic links.
  */
final case class Layout(
    temp       : Path, // the private temporary directory of the session
    journals   : Path, // the folder of the journals: closed to each profile (5.4)
    secrets    : Vector[Path], // the secret paths, for example ~/.ssh (5.6)
    secretNames: Set[String], // the names of secret files: see `Profiles.forShell`
    hooks      : Vector[Path] = Vector.empty // the folders of `core.hooksPath` of the scope (`hooksFolder`, choice 19)
)

object Layout:

  /** The secret paths under a home folder. A proposal: the design names ~/.ssh, ~/.aws and .env. */
  def secretsUnder(home: Path): Vector[Path] =
    Vector(
      ".ssh",
      ".aws",
      ".azure",
      ".gnupg",
      ".kube",
      ".docker",
      ".netrc",
      ".npmrc",
      ".pypirc",
      ".git-credentials",
      ".password-store",
      ".config/gcloud",
      ".config/gh",
      ".config/git/credentials",
      ".config/op",
      ".codex/auth.json",
      ".claude/.credentials.json",
      ".m2/settings.xml",
      ".gradle/gradle.properties",
      ".sbt/.credentials",
      ".ivy2/.credentials",
      ".cargo/credentials",
      ".cargo/credentials.toml",
      ".local/share/keyrings",
      ".pgpass",
      ".vault-token",
      ".terraform.d/credentials.tfrc.json",
      "Library/Keychains"
    ).map(home.resolve)

  /** `.env` names `.env`, `.env.local` and `.env.production`. */
  val secretNames: Set[String] = Set(".env")

  /** True when `part`, one name of a path, is a secret name of `names`: the name, or the name and a dot. The walk of
    * `Profiles.secretFiles` and the files of the MCP server use this one rule.
    */
  def isSecretName(part: String, names: Set[String]): Boolean =
    names.exists(name => part == name || part.startsWith(name + "."))

  /** The layout of a session: a new private temporary directory, the secrets of the home folder `home`, and the hooks
    * folders `hooks` of the scope (`hooksFolder`). For each secret path that is a symbolic link, the layout holds the
    * link and its target.
    */
  def forSession(home: Path, journals: Path, hooks: Vector[Path] = Vector.empty): Layout =
    val temp = JFiles.createTempDirectory("dspy4s-session-").toRealPath()
    Layout(temp, real(journals), secretsUnder(real(home)).flatMap(p => Vector(p, real(p)).distinct), secretNames, hooks)

  /** The real path of `path` when it exists, else the absolute and normalized path. */
  def real(path: Path): Path =
    val absolute = path.toAbsolutePath.normalize
    if JFiles.exists(absolute) then absolute.toRealPath() else absolute

  /** The folder that `core.hooksPath` of the repository of `scope` names, or none: no git, no repository, no setting,
    * or an error (5.4, choice 19). A relative path is relative to the top of the work tree, as git reads it. The host
    * reads it once for each activation, so a change of the setting applies after a reopen.
    *
    * git reads the setting with no fsmonitor and no optional lock, with only `PATH`, `HOME` and `LANG`, and with a time
    * limit. A read of the configuration runs no hook. The repository configuration is a control path, so a session
    * cannot change the answer.
    */
  def hooksFolder(scope: Path): Option[Path] =
    def git(args: String*): Option[String] =
      Try {
        val builder = ProcessBuilder(
          (Vector("git", "-C", scope.toString, "--no-optional-locks", "-c", "core.fsmonitor=false") ++ args)*
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
        val env = builder.environment()
        env.clear()
        Vector("PATH", "HOME", "LANG").foreach(name => sys.env.get(name).foreach(value => env.put(name, value): Unit))
        val process = builder.start()
        process.getOutputStream.close()
        // The output comes on another thread, so a command that does not end cannot hold this thread past the limit.
        val output = CompletableFuture.supplyAsync(() => String(process.getInputStream.readAllBytes(), UTF_8).trim)
        if !process.waitFor(10, TimeUnit.SECONDS) then
          process.destroyForcibly(): Unit
          None
        else Option(output.get(5, TimeUnit.SECONDS)).filter(text => process.exitValue() == 0 && text.nonEmpty)
      }.toOption.flatten
    for
      top    <- git("rev-parse", "--show-toplevel")
      folder <- git("config", "--type=path", "--get", "core.hooksPath")
    yield real(Path.of(top).resolve(folder))

/** The hosts and the ports that the proxy lets through (5.4). A pattern is `name`, `name:port` or `name:*`. A pattern
  * with no port allows only the ports 80 and 443, `name:port` allows only that port, and `name:*` allows each port. The
  * match of the name ignores the case. A name `*.d` matches each subdomain of `d` and not `d`, and another name matches
  * only the same host. An IPv6 address with a port is in brackets, as in a URL, and an IPv6 address with no brackets
  * has no port. A pattern that the list cannot read allows nothing.
  *
  * A proposal for the review: the design names the host list of the `guarded` handler and gives no type.
  */
final case class HostList(patterns: Set[String]) derives CanEqual:

  /** True when the list allows `host` at the port 80 or 443. */
  def allows(host: String): Boolean = allows(host, 80) || allows(host, 443)

  /** True when the list allows `host` at `port`. */
  def allows(host: String, port: Int): Boolean =
    val name = HostList.normal(host)
    name.nonEmpty && patterns.exists { pattern =>
      val (patternName, ports) = HostList.split(pattern)
      val p                    = HostList.normal(patternName)
      val nameMatches          = if p.startsWith("*.") then name.endsWith(p.drop(1)) && name.length > p.length - 1 else name == p
      nameMatches && HostList.portMatches(ports, port)
    }

object HostList:
  val empty: HostList = HostList(Set.empty)

  private def normal(host: String): String = host.toLowerCase(Locale.ROOT).stripSuffix(".")

  /** The name of a pattern and its port part, which is empty or starts with a colon. */
  private def split(pattern: String): (String, String) =
    val close = pattern.indexOf(']')
    if pattern.startsWith("[") && close > 0 then (pattern.slice(1, close), pattern.drop(close + 1))
    else if pattern.count(_ == ':') == 1 then pattern.splitAt(pattern.indexOf(':'))
    else (pattern, "")

  private def portMatches(ports: String, port: Int): Boolean =
    ports match
      case ""   => port == 80 || port == 443
      case ":*" => true
      case _    =>
        val digits = ports.drop(1)
        ports.startsWith(":") && digits.nonEmpty && digits.forall(_.isDigit) && digits.toIntOption.contains(port)

/** The network of a child process (5.4). */
enum Net derives CanEqual:

  /** No network. */
  case Closed

  /** Only the proxy of the session, which lets a request through when its host and port are on the list. */
  case Proxy(hosts: HostList)

  /** True when the network lets the child reach `host` at the port 80 or 443. */
  def allows(host: String): Boolean = allows(host, 80) || allows(host, 443)

  def allows(host: String, port: Int): Boolean =
    this match
      case Closed         => false
      case Proxy(allowed) => allowed.allows(host, port)

/** A path of the scope that stays read-only in each mode (5.4, choice 19). A control path is a path that a program
  * outside the sandbox runs as code, or reads as settings that start a program, with no step of the person first. On a
  * backend that cannot close a name, all of `.git` takes the place of the parts of git.
  *
  * @param shape
  *   the paths that it covers
  * @param label
  *   its name for the model, relative to the scope
  * @param note
  *   what the model needs to know apart from the path, for example the git commands that fail, or empty
  */
final case class ControlPath(shape: ControlPath.Shape, label: String, note: String = "") derives CanEqual:

  /** True when a write to `path` changes this control path. */
  def covers(path: Path): Boolean = shape.covers(path)

object ControlPath:

  /** The paths of a control path. */
  enum Shape derives CanEqual:

    /** The path and each path under it. */
    case Under(path: Path)

    /** Only the path, for example `.git` itself, which is a file in a linked worktree. */
    case Exactly(path: Path)

    /** Each path under `under` and not under `except` with a part in `names`, and each path under it. The rule reads
      * each part of the path, as the regex of Seatbelt does. Only a backend that closes a name can apply it.
      */
    case Named(under: Path, names: Set[String], except: Option[Path])

    def covers(path: Path): Boolean =
      this match
        case Under(p)                    => path.startsWith(p)
        case Exactly(p)                  => path.equals(p)
        case Named(under, names, except) => path.startsWith(under) && !except.exists(path.startsWith) &&
          path.iterator.asScala.exists(part => names.contains(part.toString))

/** What a child process can do (5.4). The observations `canRun`, `canRead`, `canWrite` and `canConnect` are the meaning
  * of a profile: a backend must allow at most what they allow. Each path is absolute and normalized.
  *
  * `reads` is the read access of the file calls of the session, which run in the JVM and not in a child (8.7). So a
  * mode with `Files` and no `Shell`, as the controller has, still reads its files. A child process reads only with
  * `canRun`.
  *
  * `foundNames` are the secret names of the walk at the start of a shell (`Profiles.forShell`). `hidden` holds the
  * files with these names that the walk found. A file with such a name that is made after the walk is not hidden, so a
  * child can read it. Only the words of the profile read `foundNames`.
  */
final case class Profile(
    canRun     : Boolean, // a child process can start
    writable   : Vector[Path], // a child can write under each of these roots
    controls   : Vector[ControlPath], // the parts of the scope that stay read-only in each mode (5.4, choice 19)
    hidden     : Vector[Path], // a child can neither read nor write under these: the secrets, the journals, the secret files
    net        : Net,
    reads      : Boolean     = false,     // the file calls of the session can read, with no child process (8.7)
    hiddenNames: Set[String] = Set.empty, // a child can neither read nor write a part with one of these names (5.4)
    foundNames : Set[String] = Set.empty  // the secret names of the walk at the start of the shell (5.4)
):

  def isHidden(path: Path): Boolean =
    hidden.exists(path.startsWith) ||
      (hiddenNames.nonEmpty && path.iterator.asScala.exists(part => Layout.isSecretName(part.toString, hiddenNames)))

  def canRead(path: Path): Boolean = (canRun || reads) && !isHidden(path)

  def canWrite(path: Path): Boolean =
    canRun && writable.exists(path.startsWith) && !controls.exists(_.covers(path)) && !isHidden(path)

  /** True when a child can reach `host` at the port 80 or 443. */
  def canConnect(host: String): Boolean = canRun && net.allows(host)

  def canConnect(host: String, port: Int): Boolean = canRun && net.allows(host, port)

  /** The same profile, with `paths` hidden too. It allows less: nothing under `paths`, and the rest as before. */
  def hide(paths: Vector[Path]): Profile = copy(hidden = (hidden ++ paths).distinct)

/** The profiles of the modes. */
object Profiles:

  /** The profile of the table of 5.4, for a session with the mode `mode` and the scope `scope`. A child process needs
    * `Shell^` and `Files^`, because `exec` takes both. The network needs `Network^` too. With `closesNames`, for a
    * backend that closes a name in each folder, the profile also closes the secret names of `layout` (`hiddenNames`),
    * so `canRead` and `canWrite` give what that backend does. The control paths (`controls`) do not depend on the mode.
    */
  def forMode(mode: Mode, scope: Path, layout: Layout, hosts: HostList, closesNames: Boolean = false): Profile =
    val run = mode.shell == Access.Full && mode.files == Access.Full
    Profile(
      canRun = run,
      writable = if run then Vector(scope, layout.temp) else Vector.empty,
      controls = controls(scope, layout, closesNames),
      hidden = layout.secrets :+ layout.journals,
      net = if run && mode.network == Access.Full then Net.Proxy(hosts) else Net.Closed,
      reads = mode.files != Access.None,
      hiddenNames = if closesNames then layout.secretNames else Set.empty
    )

  /** The files of the tools that a program of the person runs or reads as settings with no step of the person (5.4): VS
    * Code (a setting that names a program, a task that runs when the folder opens), IntelliJ (a startup task, the
    * launcher of an sbt reload) and Claude Code (hooks).
    */
  val ToolFiles: Vector[String] = Vector(
    ".vscode/settings.json",
    ".vscode/tasks.json",
    ".idea",
    ".claude/settings.json",
    ".claude/settings.local.json"
  )

  /** The note of the git configuration. A probe with Seatbelt on 2026-11-13 showed that these commands change the
    * branch and then fail to write `.git/config`.
    */
  val ConfigNote: String =
    "Git cannot change its configuration. Three commands print an error about .git/config: a new branch that tracks " +
      "a remote branch, a rename of a branch and a removal of a branch. The change of the branch still happens."

  /** The note of a nested `.git`. */
  val NestedNote: String =
    "So git init, git clone and git submodule add fail in the folder of the session. A command can make a " +
      "repository in the temporary folder."

  /** The note of `.git` on a backend that cannot close a name. */
  val AllOfGitNote: String =
    "The sandbox of this host cannot close only the parts of git that run code, so all of .git stays read-only. A " +
      "git command that changes the repository fails, for example commit, branch, switch, stash or worktree add."

  /** The control paths of a session in `scope` (5.4, choice 19): the parts of git, the hooks folders of `layout` that
    * are in the scope, and the files of the tools (`ToolFiles`). The class holds only the scope, so a command can make
    * a repository in the temporary folder. A backend that cannot close a name (`closesNames` false) gets all of `.git`
    * in place of the parts of git, and only the control paths that exist now, so its observations stay true. With
    * `closesNames`, the list does not read the file system.
    */
  def controls(scope: Path, layout: Layout, closesNames: Boolean): Vector[ControlPath] =
    import ControlPath.Shape.*
    val git   = scope.resolve(".git")
    val ofGit =
      if !closesNames then Vector(ControlPath(Under(git), ".git", AllOfGitNote))
      else
        Vector(
          ControlPath(Exactly(git), ".git itself"),
          ControlPath(Under(git.resolve("hooks")), ".git/hooks"),
          ControlPath(Under(git.resolve("config")), ".git/config", ConfigNote),
          ControlPath(Under(git.resolve("config.worktree")), ".git/config.worktree"),
          ControlPath(Under(git.resolve("commondir")), ".git/commondir"),
          ControlPath(
            Named(git.resolve("modules"), Set("hooks", "config", "config.worktree", "commondir"), None),
            "the hooks and the configuration of each submodule in .git/modules"
          ),
          ControlPath(
            Named(git.resolve("worktrees"), Set("commondir", "gitdir", "config.worktree"), None),
            "commondir, gitdir and config.worktree in each folder of .git/worktrees"
          ),
          ControlPath(
            Named(scope, Set(".git"), Some(git)),
            "a file or a folder with the name .git below the top of the folder",
            NestedNote
          )
        )
    // A hooks folder outside the scope is outside the writable roots, so it needs no control path.
    val hooks = layout.hooks.distinct.filter(_.startsWith(scope)).map { folder =>
      val label = scope.relativize(folder).toString
      ControlPath(Under(folder), if label.isEmpty then "." else label)
    }
    val tools = ToolFiles.map(path => ControlPath(Under(scope.resolve(path)), path))
    val all   = ofGit ++ hooks ++ tools
    // A mount covers only a path that exists, and it cannot close a name. So the list of such a backend holds the
    // control paths that exist when the profile is made, and its observations stay true: a control path that does not
    // exist yet stays writable, the gap of Linux (5.4). This part of the profile reads the file system.
    if closesNames then all
    else
      all.filter(_.shape match
        case Under(path)    => JFiles.exists(path)
        case Exactly(path)  => JFiles.exists(path)
        case Named(_, _, _) => false)

  /** The profile of each command of a shell (5.4). `SandboxedShell` makes it once, at its start, and the host makes one
    * shell for each activation.
    *
    *   - With `closesNames`, for a backend that closes a name, it is the profile of the mode with the secret names
    *     (`hiddenNames`), and there is no walk.
    *   - Else, one walk of the writable roots finds each file and folder with a secret name (`secretFiles`), and the
    *     profile hides them, so the backend covers each one. The profile also gets the secret names as `foundNames`, so
    *     its words give this rule. A file with a secret name that is made after the walk is not hidden until the next
    *     activation. One walk of a scope with 61,000 folders took 5 s on macOS (2026-11-15), so a walk before each
    *     command costs too much.
    */
  def forShell(mode: Mode, scope: Path, layout: Layout, hosts: HostList, closesNames: Boolean): Profile =
    val profile = forMode(mode, scope, layout, hosts, closesNames)
    if closesNames then profile
    else profile.hide(secretFiles(profile, layout.secretNames)).copy(foundNames = layout.secretNames)

  /** What a command and a file call of `profile` can do, in words for the model of a session with the scope `scope` and
    * the layout `layout` (5.4). The text comes from the profile and the layout, so it gives the same rules as the
    * sandbox: the places that a command can write, the control paths with their notes, the closed paths, and the
    * network. The notes are data of the control paths, so this text names no tool.
    */
  def words(profile: Profile, scope: Path, layout: Layout): String =
    if !profile.canRun then "This session runs no command."
    else
      val places = profile.writable.map { root =>
        if root.equals(scope) then "the folder of the session"
        else if root.equals(layout.temp) then "the temporary folder in TMPDIR"
        else root.toString
      }
      val controls = Option.when(profile.controls.nonEmpty)(
        "In the folder of the session, these paths stay read-only, because a program outside the sandbox runs them " +
          s"as code or reads them as settings: ${list(profile.controls.map(_.label), "and")}."
      ) ++ profile.controls.map(_.note).filter(_.nonEmpty).distinct
      val examples = layout.secrets.filter(profile.isHidden).flatMap(p => Option(p.getFileName)).map(_.toString)
        .distinct.take(3)
      // A file call refuses a secret name in each case. A command cannot reach it when the backend closes the name,
      // and else only when the walk at the start of the shell found it (`foundNames`).
      val names = layout.secretNames.toVector.sorted.map(name =>
        s"a file or a folder whose name is $name or starts with \"$name.\" (for example $name.local)"
      )
      val (both, fileOnly) = if profile.hiddenNames.nonEmpty then (names, Vector.empty) else (Vector.empty, names)
      val found            = Option.when(fileOnly.nonEmpty && profile.foundNames.nonEmpty)(
        "A command can neither read nor write such a file or folder when it was there at the open of the session. A " +
          "command can read and write such a file or folder that is made after the open, until the session opens again."
      )
      val closed =
        Option.when(examples.nonEmpty)(s"the secret paths of the home folder (for example ${list(examples, "and")})") ++
          Option.when(profile.isHidden(layout.journals))("the journals of the harness") ++ both
      val network = profile.net match
        case Net.Closed                                 => "A command has no network."
        case Net.Proxy(hosts) if hosts.patterns.isEmpty => "A command reaches no host."
        case Net.Proxy(hosts)                           =>
          s"A command reaches only these hosts: ${hosts.patterns.toVector.sorted.mkString(", ")}."
      (Vector(s"A command and the calls write and edit can change files only under ${list(places, "and")}.") ++
        controls ++
        Option.when(closed.nonEmpty)(s"A command and a file call can neither read nor write ${list(closed, "or")}.") ++
        Option.when(fileOnly.nonEmpty)(s"A file call can neither read nor write ${list(fileOnly, "or")}.") ++
        found :+
        network).mkString(" ")

  /** The items of `items` in one text, with `word` before the last one: "a", "a and b", or "a, b and c". */
  private def list(items: Iterable[String], word: String): String =
    val all = items.toVector
    if all.size <= 1 then all.mkString else s"${all.init.mkString(", ")} $word ${all.last}"

  /** The files and folders in the writable roots of `profile` with a name of `names`, or with the name and a dot, and
    * the target of each one that is a symbolic link, and each folder that the walk cannot read. The walk does not
    * follow a link, and it does not enter a folder of `SecretIndex.Skipped` (`.git`, `node_modules` and `target`), a
    * control path, a hidden part, or a folder that it found. A new index does the walk, so the walk and `SecretIndex`
    * cannot give different files.
    */
  def secretFiles(profile: Profile, names: Set[String]): Vector[Path] = SecretIndex(names).find(profile)

/** The secret files of the writable roots of a profile, with a cache of the folders (CODE_HARNESS_DESIGN.md, 5.4).
  *
  * `find` gives the files of `Profiles.secretFiles`: each file and folder with a secret name of `names`, and the real
  * target of each one that is a symbolic link. The walk does not enter a folder of `Skipped` below a writable root, a
  * control path, a hidden part, or a folder with a secret name, and it does not follow a link. It also gives each
  * folder that it cannot read, apart from a writable root: the command can change the mode of its own folder, and then
  * it could read a secret file that the walk did not see. So bubblewrap covers the folder, and Seatbelt denies it. A
  * folder that is gone is skipped.
  *
  * The index keeps the listing of each folder between two calls, so a find of a large scope does not list each folder
  * again. A find still reads the attributes of each kept folder. It lists a folder again when its last-modified time,
  * its change time or its file key differs from the kept one. A command can set the last-modified time of a folder
  * back, but not its change time. It also lists a folder again when the folder changed less than `settle` before the
  * start of the find that listed it. The file time on Linux can be coarse, so two changes in one tick can give one
  * time. This is the rule of git for a racy file. `find` holds a lock, so two finds do not run at the same time.
  */
final class SecretIndex(names: Set[String], settle: FiniteDuration = 2.seconds):

  private var kept  = Map.empty[Path, SecretIndex.Listing]
  private var count = 0L

  // The progress of the find that runs. A find sets these with its lock, and `progress` reads them with no lock.
  private val walking             = java.util.concurrent.atomic.AtomicBoolean(false)
  private val read                = java.util.concurrent.atomic.AtomicLong(0L)
  @volatile private var lastTotal = Option.empty[Long]

  /** The number of folders that the index listed since its start. A find lists only the folders that changed. */
  def listings: Long = synchronized(count)

  /** The progress of the find that runs now, or none when no find runs. A command waits for a find that runs, so a
    * client can show why the command waits (CODE_HARNESS_DESIGN.md, 10.6). It does not wait for the lock of `find`.
    */
  def progress: Option[SecretIndex.Progress] =
    Option.when(walking.get)(SecretIndex.Progress(read.get, lastTotal))

  /** The secret files of the writable roots of `profile`. The find calls `stop` before each folder. When `stop` gives
    * true, the find throws `CancellationException`, and the index keeps the listings of the find before. A part of the
    * list would let a command read a secret file, so a stopped find gives no list. The default never stops.
    */
  def find(profile: Profile, stop: () => Boolean = () => false): Vector[Path] =
    synchronized {
      // The total is the folders that the last find read. The first find has none.
      lastTotal = Option.when(kept.nonEmpty)(kept.size.toLong)
      read.set(0L)
      walking.set(true)
      try
        val start = Instant.now()
        val found = Vector.newBuilder[Path]
        val next  = mutable.Map.empty[Path, SecretIndex.Listing]
        // A stack and not a recursion, because a command can make a deep tree of folders.
        val todo = mutable.Stack.from(profile.writable.filter(JFiles.isDirectory(_)))
        while todo.nonEmpty do
          if stop() then throw CancellationException("The find of the secret files stopped.")
          val dir = todo.pop()
          // A writable root has no skipped name, whatever its name is.
          val skipped = Option(dir.getFileName).exists(name => SecretIndex.Skipped.contains(name.toString)) &&
            !profile.writable.exists(dir.equals)
          if skipped || profile.controls.exists(_.covers(dir)) || profile.isHidden(dir) then ()
          else if secret(dir) && !profile.writable.exists(_.equals(dir)) then found.addOne(dir): Unit
          else if !next.contains(dir) then
            read.incrementAndGet(): Unit
            look(dir, start) match
              case SecretIndex.Seen.Open(listed) =>
                next.update(dir, listed)
                listed.entries.foreach { entry =>
                  if entry.folder then todo.push(entry.path): Unit
                  else if secret(entry.path) then found.addOne(entry.path): Unit
                }
              case SecretIndex.Seen.Closed => if !profile.writable.exists(_.equals(dir)) then found.addOne(dir): Unit
              case SecretIndex.Seen.Gone   => ()
        kept = next.toMap
        found.result().flatMap(path => Vector(path, Layout.real(path))).distinct
      finally walking.set(false)
    }

  private def secret(path: Path): Boolean =
    Option(path.getFileName).map(_.toString).exists(Layout.isSecretName(_, names))

  /** The kept listing of `dir` when it is still true, else a new listing. */
  private def look(dir: Path, start: Instant): SecretIndex.Seen =
    SecretIndex.stamp(dir) match
      case Left(seen) => seen
      case Right(now) => kept.get(dir) match
          case Some(old) if old.reusable && old.stamp == now => SecretIndex.Seen.Open(old)
          case _                                             =>
            count += 1
            SecretIndex.list(dir, now, start, settle, secret)

object SecretIndex:

  /** The names of the folders that a walk does not enter below a writable root (5.4). A `.git` folder holds many
    * objects, and a backend that closes no name keeps it read-only. `node_modules` and `target` hold the files of the
    * build tools, and in a large scope they hold most of the folders. So on a backend that cannot close a name, a file
    * with a secret name in such a folder stays readable to a command.
    */
  val Skipped: Set[String] = Set(".git", "node_modules", "target")

  /** The progress of a find: the folders that it read, and the folders that the find before it read, if any. */
  final case class Progress(folders: Long, total: Option[Long]) derives CanEqual

  /** The attributes of a folder that show a change of its entries. */
  private final case class Stamp(folder: Boolean, modified: FileTime, changed: Option[FileTime], key: Option[AnyRef])
      derives CanEqual

  /** One entry of a folder. `folder` is false for a symbolic link. */
  private final case class Item(path: Path, folder: Boolean)

  /** The entries of a folder, and its stamp before the listing. A listing is reusable when the index read each entry
    * and the folder did not change in the last `settle` before the listing.
    */
  private final case class Listing(stamp: Stamp, entries: Vector[Item], reusable: Boolean)

  /** What the index sees of a folder. */
  private enum Seen:
    case Gone // not there, or not a folder: nothing to walk and nothing to hide
    case Closed // the index cannot read it
    case Open(listing: Listing) // the entries

  /** The stamp of the folder `path`, with no link followed, or what the index sees when it has no stamp. */
  private def stamp(path: Path): Either[Seen, Stamp] =
    Try(unixStamp(path)).recover {
      // A file system with no unix view: no change time.
      case _: UnsupportedOperationException | _: IllegalArgumentException =>
        val a = JFiles.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        Stamp(a.isDirectory, a.lastModifiedTime, None, Option(a.fileKey))
    } match
      case Success(stamp) if stamp.folder  => Right(stamp)
      case Success(_)                      => Left(Seen.Gone)
      case Failure(_: NoSuchFileException) => Left(Seen.Gone)
      case Failure(_)                      => Left(Seen.Closed)

  private def unixStamp(path: Path): Stamp =
    val attributes =
      JFiles.readAttributes(path, "unix:isDirectory,lastModifiedTime,ctime,fileKey", LinkOption.NOFOLLOW_LINKS)
    def time(name: String): Option[FileTime] =
      attributes.get(name) match
        case time: FileTime => Some(time)
        case _              => None
    val folder = attributes.get("isDirectory") match
      case flag: java.lang.Boolean => flag.booleanValue
      case _                       => false
    val modified = time("lastModifiedTime").getOrElse(throw UnsupportedOperationException("no lastModifiedTime"))
    Stamp(folder, modified, time("ctime"), Option(attributes.get("fileKey")))

  /** A new listing of `dir`. The listing keeps only the folders and the entries with a secret name (`secret`), because
    * the walk needs no other entry. A folder is closed when the index cannot open it, cannot read each name, or cannot
    * read the attributes of an entry that is still there.
    */
  private def list(
      dir   : Path,
      now   : Stamp,
      start : Instant,
      settle: FiniteDuration,
      secret: Path => Boolean
  ): Seen =
    Try(JFiles.newDirectoryStream(dir)) match
      case Failure(_: NoSuchFileException) => Seen.Gone
      case Failure(_)                      => Seen.Closed
      case Success(stream)                 =>
        try
          val entries  = Vector.newBuilder[Item]
          var complete = true
          var closed   = false
          try
            stream.iterator.asScala.foreach { path =>
              Try(JFiles.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)) match
                case Success(attributes) => if attributes.isDirectory || secret(path) then
                    entries.addOne(Item(path, attributes.isDirectory)): Unit
                case Failure(_: NoSuchFileException) => complete = false // the entry is gone: list the folder again
                case Failure(_)                      => closed = true
            }
          catch
            case _: DirectoryIteratorException => closed = true
          if closed then Seen.Closed
          else
            val last    = now.changed.getOrElse(now.modified).toInstant
            val settled = !last.isAfter(start.minusNanos(settle.toNanos))
            Seen.Open(Listing(now, entries.result(), complete && settled))
        finally stream.close()
