package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Access, Mode}

import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.nio.file.{DirectoryIteratorException, Files as JFiles, LinkOption, NoSuchFileException, Path}
import java.time.Instant
import java.util.Locale
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

  /** True when a child can reach `host` at the port 80 or 443. */
  def canConnect(host: String): Boolean = canRun && net.allows(host)

  def canConnect(host: String, port: Int): Boolean = canRun && net.allows(host, port)

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
    * the target of each one that is a symbolic link, and each folder that the walk cannot read. The walk does not
    * follow a link, and it does not enter a read-only part such as .git, a hidden part, or a folder that it found. A
    * new index does the walk, so the walk and `SecretIndex` cannot give different files.
    */
  def secretFiles(profile: Profile, names: Set[String]): Vector[Path] = SecretIndex(names).find(profile)

/** The secret files of the writable roots of a profile, with a cache of the folders (CODE_HARNESS_DESIGN.md, 5.4).
  *
  * `find` gives the files of `Profiles.secretFiles`: each file and folder with a secret name of `names`, and the real
  * target of each one that is a symbolic link. The walk does not enter a read-only part such as .git, a hidden part, or
  * a folder with a secret name, and it does not follow a link. It also gives each folder that it cannot read, apart
  * from a writable root: the command can change the mode of its own folder, and then it could read a secret file that
  * the walk did not see. So bubblewrap covers the folder, and Seatbelt denies it. A folder that is gone is skipped.
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

  /** The number of folders that the index listed since its start. A find lists only the folders that changed. */
  def listings: Long = synchronized(count)

  def find(profile: Profile): Vector[Path] =
    synchronized {
      val start = Instant.now()
      val found = Vector.newBuilder[Path]
      val next  = mutable.Map.empty[Path, SecretIndex.Listing]
      // A stack and not a recursion, because a command can make a deep tree of folders.
      val todo = mutable.Stack.from(profile.writable.filter(JFiles.isDirectory(_)))
      while todo.nonEmpty do
        val dir = todo.pop()
        if profile.readOnly.exists(dir.startsWith) || profile.isHidden(dir) then ()
        else if secret(dir) && !profile.writable.exists(_.equals(dir)) then found.addOne(dir): Unit
        else if !next.contains(dir) then
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
