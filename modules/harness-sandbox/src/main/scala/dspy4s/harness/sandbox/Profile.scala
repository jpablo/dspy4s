package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Access, Mode}

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files as JFiles, Path, SimpleFileVisitor}
import java.util.Locale

/** The places of the host that a profile names, apart from the scope (CODE_HARNESS_DESIGN.md, 5.4).
  *
  * Each path is absolute and normalized. Seatbelt compares the real path of a file, so a path that exists must also be
  * real: `Layout.forSession` resolves the symbolic links.
  */
final case class Layout(
    temp       : Path,         // the private temporary directory of the session
    journals   : Path,         // the folder of the journals: closed to each profile (5.4)
    secrets    : Vector[Path], // the secret paths, for example ~/.ssh (5.6)
    secretNames: Set[String]   // the names of secret files: see `Profiles.forCommand`
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
      "Library/Keychains"
    ).map(home.resolve)

  /** `.env` names `.env`, `.env.local` and `.env.production`. */
  val secretNames: Set[String] = Set(".env")

  /** The layout of a session: a new private temporary directory, and the secrets of the home folder `home`. For each
    * secret path that is a symbolic link, the layout holds the link and its target.
    */
  def forSession(home: Path, journals: Path): Layout =
    val temp = JFiles.createTempDirectory("dspy4s-session-").toRealPath()
    Layout(temp, real(journals), secretsUnder(real(home)).flatMap(p => Vector(p, real(p)).distinct), secretNames)

  /** The real path of `path` when it exists, else the absolute and normalized path. */
  def real(path: Path): Path =
    val absolute = path.toAbsolutePath.normalize
    if JFiles.exists(absolute) then absolute.toRealPath() else absolute

/** The hosts that the proxy lets through (5.4). The match ignores the case. A pattern `*.d` matches each subdomain of
  * `d` and not `d`, and another pattern matches only the same host.
  *
  * A proposal for the review: the design names the host list of the `guarded` handler and gives no type.
  */
final case class HostList(patterns: Set[String]) derives CanEqual:

  def allows(host: String): Boolean =
    val name = HostList.normal(host)
    name.nonEmpty && patterns.exists { pattern =>
      val p = HostList.normal(pattern)
      if p.startsWith("*.") then name.endsWith(p.drop(1)) && name.length > p.length - 1 else name == p
    }

object HostList:
  val empty: HostList = HostList(Set.empty)

  private def normal(host: String): String = host.toLowerCase(Locale.ROOT).stripSuffix(".")

/** The network of a child process (5.4). */
enum Net derives CanEqual:

  /** No network. */
  case Closed

  /** Only the proxy of the session, which lets a request through when its host is on the list. */
  case Proxy(hosts: HostList)

  def allows(host: String): Boolean =
    this match
      case Closed         => false
      case Proxy(allowed) => allowed.allows(host)

/** What a child process can do (5.4). The observations `canRun`, `canRead`, `canWrite` and `canConnect` are the meaning
  * of a profile: a backend must allow at most what they allow. Each path is absolute and normalized.
  */
final case class Profile(
    canRun  : Boolean,      // a child process can start
    writable: Vector[Path], // a child can write under each of these roots
    readOnly: Vector[
      Path
    ], // the parts of the writable roots that stay read-only, for example .git (CODE_HARNESS.md F7)
    hidden: Vector[Path], // a child can neither read nor write under these: the secrets, the journals, the secret files
    net   : Net
):

  def isHidden(path: Path): Boolean = hidden.exists(path.startsWith)

  def canRead(path: Path): Boolean = canRun && !isHidden(path)

  def canWrite(path: Path): Boolean =
    canRun && writable.exists(path.startsWith) && !readOnly.exists(path.startsWith) && !isHidden(path)

  def canConnect(host: String): Boolean = canRun && net.allows(host)

  /** The same profile, with `paths` hidden too. It allows less: nothing under `paths`, and the rest as before. */
  def hide(paths: Vector[Path]): Profile = copy(hidden = (hidden ++ paths).distinct)

/** The profiles of the modes. */
object Profiles:

  /** The profile of the table of 5.4, for a session with the mode `mode` and the scope `scope`. A child process needs
    * `Shell^` and `Files^`, because `exec` takes both. The network needs `Network^` too.
    */
  def forMode(mode: Mode, scope: Path, layout: Layout, hosts: HostList): Profile =
    val run = mode.shell == Access.Full && mode.files == Access.Full
    Profile(
      canRun = run,
      writable = if run then Vector(scope, layout.temp) else Vector.empty,
      readOnly = Vector(scope.resolve(".git")),
      hidden = layout.secrets :+ layout.journals,
      net = if run && mode.network == Access.Full then Net.Proxy(hosts) else Net.Closed
    )

  /** The profile of one command: the profile of the mode, and each file with a secret name that exists in a writable
    * root when the command starts (5.4). The command cannot read, write, link or move these files. A command can make a
    * new file with a secret name: it holds only data of the command, and the next command cannot read it.
    */
  def forCommand(mode: Mode, scope: Path, layout: Layout, hosts: HostList): Profile =
    val profile = forMode(mode, scope, layout, hosts)
    profile.hide(secretFiles(profile, layout.secretNames))

  /** The files and folders in the writable roots of `profile` with a name of `names`, or with the name and a dot, and
    * the target of each one that is a symbolic link. The walk does not follow a link, and it does not enter a read-only
    * part such as .git, a hidden part, or a folder that it found.
    */
  def secretFiles(profile: Profile, names: Set[String]): Vector[Path] =
    val found                       = Vector.newBuilder[Path]
    def secret(path: Path): Boolean =
      Option(path.getFileName).map(_.toString).exists(n => names.exists(name => n == name || n.startsWith(name + ".")))
    val visitor = new SimpleFileVisitor[Path]:
      override def preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult =
        if profile.readOnly.exists(dir.startsWith) || profile.isHidden(dir) then FileVisitResult.SKIP_SUBTREE
        else if secret(dir) && !profile.writable.exists(_.equals(dir)) then
          found.addOne(dir): Unit
          FileVisitResult.SKIP_SUBTREE
        else FileVisitResult.CONTINUE

      override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
        if secret(file) then found.addOne(file): Unit
        FileVisitResult.CONTINUE

      // A folder that the walk cannot read is closed to the command too.
      override def visitFileFailed(file: Path, error: IOException): FileVisitResult = FileVisitResult.CONTINUE
    profile.writable.filter(JFiles.isDirectory(_)).foreach(root => JFiles.walkFileTree(root, visitor): Unit)
    found.result().flatMap(path => Vector(path, Layout.real(path))).distinct
