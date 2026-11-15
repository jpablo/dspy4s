package dspy4s.harness.sandbox

import dspy4s.harness.guest.{Access, Mode}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.file.attribute.{FileTime, PosixFilePermissions}
import java.nio.file.{Files as JFiles, Path}
import java.time.Instant
import java.util.concurrent.CancellationException
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The laws of the profile (5.4). The profile is pure, so the paths need not exist. */
class ProfileLaws extends ScalaCheckSuite:

  private val scope    = Path.of("/w/scope")
  private val temp     = Path.of("/w/tmp")
  private val journals = Path.of("/w/journals")
  private val secrets  = Vector(Path.of("/w/home/.ssh"), Path.of("/w/home/.aws"))
  private val layout   = Layout(temp, journals, secrets, Layout.secretNames)
  private val hosts    = HostList(Set("example.com", "*.example.org"))

  private val access: Gen[Access] = Gen.oneOf(Access.values.toSeq)

  private val anyModes: Gen[Mode] =
    for
      files    <- access
      shell    <- access
      network  <- access
      llm      <- access
      sessions <- access
      children <- access
      store    <- access
    yield Mode(files, shell, network, llm, sessions, children, store)

  /** Only one mode in nine can start a process, so half of the modes here can. */
  private val modes: Gen[Mode] = Gen.oneOf(anyModes, anyModes.map(_.copy(files = Access.Full, shell = Access.Full)))

  /** Paths in each region of the layout, with a secret name or a .git part in some of them. */
  private val paths: Gen[Path] =
    for
      region <- Gen.oneOf("/w/scope", "/w/scope/.git", "/w/tmp", "/w/journals", "/w/home/.ssh", "/w/home", "/w", "/etc")
      rest   <- Gen.oneOf("", "a", ".env", ".env.local", "b/.env", "x/y", ".git/hooks/pre-commit", ".envrc", "my.env")
    yield Path.of(region).resolve(rest).normalize

  private val git = scope.resolve(".git")

  /** The control paths of 5.4 for a backend that closes names, written again for the test: the paths of the scope that
    * a command must not write. A rule on a name reads each part of the path, as the regex of Seatbelt does. The list of
    * another backend reads the file system, so its laws use real folders.
    */
  private def controlled(path: Path): Boolean =
    val parts = path.iterator.asScala.map(_.toString).toSet
    val tools = Vector(
      ".vscode/settings.json",
      ".vscode/tasks.json",
      ".idea",
      ".claude/settings.json",
      ".claude/settings.local.json"
    ).map(scope.resolve)
    val ofGit = path.equals(git) ||
      under(path, Vector("hooks", "config", "config.worktree", "commondir").map(git.resolve)*) ||
      (under(path, git.resolve("modules")) && Set("hooks", "config", "config.worktree", "commondir").exists(parts)) ||
      (under(path, git.resolve("worktrees")) && Set("commondir", "gitdir", "config.worktree").exists(parts)) ||
      (under(path, scope) && !under(path, git) && parts.contains(".git"))
    ofGit || under(path, tools*)

  /** Paths of the scope and of the temporary folder: the parts of git, the files of the tools, and others. */
  private val gitPaths: Gen[Path] =
    for
      region <- Gen.oneOf(
                  "/w/scope",
                  "/w/scope/.git",
                  "/w/scope/.git/modules/s",
                  "/w/scope/.git/worktrees/w",
                  "/w/scope/sub",
                  "/w/tmp",
                  "/w/tmp/r"
                )
      rest <- Gen.oneOf(
                "",
                ".git",
                ".git/config",
                "config",
                "hooks/pre-commit",
                "index",
                "HEAD",
                "refs/heads/main",
                "objects/ab/cd",
                "commondir",
                "gitdir",
                "config.worktree",
                "config.lock",
                ".vscode/settings.json",
                ".vscode/launch.json",
                ".idea/workspace.xml",
                ".claude/settings.json",
                ".claude/commands/a.md",
                ".gitignore",
                ".github/w"
              )
    yield Path.of(region).resolve(rest).normalize

  private val hostNames: Gen[String] =
    Gen.oneOf("example.com", "EXAMPLE.com", "a.example.org", "example.org", "other.net", "example.com.", "")

  private val patterns: Gen[Set[String]] =
    Gen.containerOf[Set, String](Gen.oneOf("example.com", "*.example.com", "other.net", "*.net", "EXAMPLE.org"))

  /** A change of the files of a scope, from a seed: a new file, a deletion, a move, or a new folder with a secret name.
    * The names hold secret names and others, at three depths.
    */
  private val changes: Gen[Path => Unit] =
    val folders                         = Vector("", "d1", "d1/d2", "d3")
    val names                           = Vector(".env", ".env.local", "a.txt", ".envrc", "my.env")
    def files(root: Path): Vector[Path] =
      Using.resource(JFiles.walk(root))(_.iterator.asScala.filter(JFiles.isRegularFile(_)).toVector.sorted)
    for
      kind   <- Gen.choose(0, 3)
      folder <- Gen.oneOf(folders)
      name   <- Gen.oneOf(names)
      pick   <- Gen.choose(0, 100)
    yield (root: Path) =>
      // A change that cannot apply does nothing: a file where a folder is, or a folder where a file is.
      val dir = JFiles.createDirectories(root.resolve(folder))
      kind match
        case 0 => if !JFiles.isDirectory(dir.resolve(name)) then JFiles.writeString(dir.resolve(name), "x"): Unit
        case 1 => files(root).lift(pick % (files(root).size max 1)).foreach(JFiles.delete)
        case 2 => files(root).lift(pick % (files(root).size max 1)).foreach { from =>
            val to = dir.resolve(name)
            if !JFiles.exists(to) then JFiles.move(from, to): Unit
          }
        case _ => if !JFiles.isRegularFile(dir.resolve(".env")) then
            JFiles.writeString(JFiles.createDirectories(dir.resolve(".env/inner")).resolve("k"), "x"): Unit

  /** Gives each folder that changed in the last minute an old time of its own, as after a wait. A folder that did not
    * change keeps its time, so the index can keep its listing.
    */
  private def age(root: Path, step: Int): Unit =
    val now = Instant.now()
    Using.resource(JFiles.walk(root))(_.iterator.asScala.filter(JFiles.isDirectory(_)).toVector).foreach { dir =>
      if JFiles.getLastModifiedTime(dir).toInstant.isAfter(now.minusSeconds(60)) then
        JFiles.setLastModifiedTime(dir, FileTime.from(now.minusSeconds(3600 - step.toLong))): Unit
    }

  /** The profile of a mode with a backend that closes names, so the profile reads no file system. */
  private def profile(mode: Mode): Profile = Profiles.forMode(mode, scope, layout, hosts, closesNames = true)

  private def under(path: Path, roots: Path*): Boolean = roots.exists(path.startsWith)

  property("Monotone: if m1 <= m2, the profile of m2 allows what the profile of m1 allows") {
    forAll(modes, modes, paths, hostNames) { (a, b, path, host) =>
      val (low, high) = (profile(a.meet(b)), profile(a))
      assert(!low.canRun || high.canRun)
      assert(!low.canRead(path) || high.canRead(path), s"read $path")
      assert(!low.canWrite(path) || high.canWrite(path), s"write $path")
      assert(!low.canConnect(host) || high.canConnect(host), s"connect $host")
    }
  }

  property("Monotone: a profile that hides more paths allows less, and allows nothing under them") {
    forAll(modes, Gen.containerOf[Vector, Path](paths), paths, hostNames) { (mode, more, path, host) =>
      val (wide, narrow) = (profile(mode), profile(mode).hide(more))
      assert(!narrow.canRead(path) || wide.canRead(path), s"read $path")
      assert(!narrow.canWrite(path) || wide.canWrite(path), s"write $path")
      assertEquals(narrow.canRun, wide.canRun)
      assertEquals(narrow.canConnect(host), wide.canConnect(host))
      if under(path, more*) then assert(!narrow.canRead(path) && !narrow.canWrite(path), s"$path is not hidden")
    }
  }

  property("Authority: a child process needs Shell^ and Files^") {
    forAll(modes) { mode =>
      assertEquals(profile(mode).canRun, mode.shell == Access.Full && mode.files == Access.Full)
    }
  }

  property(
    "Authority: a write is only under the scope or the temporary directory, never in a control path or a secret"
  ) {
    forAll(modes, paths) { (mode, path) =>
      if profile(mode).canWrite(path) then
        assert(mode.files == Access.Full)
        assert(under(path, scope, temp), s"$path is outside the scope")
        assert(!controlled(path), s"$path is a control path")
        assert(!under(path, (secrets :+ journals)*), s"$path is secret")
    }
  }

  property("Authority: a host is only with Network^ and on the list") {
    forAll(modes, hostNames) { (mode, host) =>
      if profile(mode).canConnect(host) then
        assert(mode.network == Access.Full)
        assert(hosts.allows(host))
    }
  }

  property("Authority: in no mode can a child read a secret or the journals") {
    forAll(modes, paths) { (mode, path) =>
      if under(path, (secrets :+ journals)*) then
        assert(!profile(mode).canRead(path), s"$path is readable")
    }
  }

  test("Authority: local mode writes the scope and reads the other files, with no network") {
    val local = profile(Mode.local)
    assert(local.canWrite(scope.resolve("src/A.scala")))
    assert(local.canWrite(temp.resolve("x")))
    assert(!local.canWrite(Path.of("/etc/hosts")))
    assert(local.canRead(Path.of("/etc/hosts")))
    assert(local.canRead(scope.resolve(".git/config")))
    assert(!local.canWrite(scope.resolve(".git/config")))
    assert(!local.canRead(journals.resolve("j")) && !local.canWrite(journals.resolve("j")))
    assert(!local.canRead(secrets.head.resolve("id_rsa")))
    assert(!local.canConnect("example.com"))
  }

  test("Authority: full mode adds the hosts of the list, and the other modes of 5.2 start no process") {
    assert(profile(Mode.full).canConnect("example.com"))
    assert(profile(Mode.full).canConnect("a.example.org"))
    assert(!profile(Mode.full).canConnect("other.net"))
    Vector(Mode.readOnly, Mode.controller).foreach(mode => assert(!profile(mode).canRun, mode))
    assert(profile(Mode.local.meet(Mode.closed)).canRun, "a child of a local session")
  }

  test("Authority: the layout of a session hides a secret folder and the target of its symbolic link") {
    val place = Place()
    try
      val home      = place.base.resolve("home")
      val elsewhere = JFiles.createDirectories(place.base.resolve("elsewhere"))
      JFiles.createSymbolicLink(home.resolve(".aws"), elsewhere): Unit
      val layout = Layout.forSession(home, place.journals)
      try
        val local = Profiles.forMode(Mode.local, place.scope, layout, HostList.empty)
        assert(JFiles.isDirectory(layout.temp))
        assert(local.canWrite(layout.temp.resolve("x")))
        assert(!local.canRead(home.resolve(".ssh/id_fake")))
        assert(!local.canRead(home.resolve(".aws/credentials")))
        assert(!local.canRead(elsewhere.resolve("credentials")))
      finally JFiles.deleteIfExists(layout.temp): Unit
    finally place.close()
  }

  test(
    "Authority: on a backend that cannot close a name, the profile of a shell hides each file with a secret name that " +
      "exists in a writable root at its start"
  ) {
    val place = Place()
    try
      val s = place.scope
      Vector("app/.env.local", "conf/.env/key", ".envrc", "my.env", ".git/.env").foreach { name =>
        val file = s.resolve(name)
        JFiles.createDirectories(file.getParent): Unit
        JFiles.writeString(file, "x"): Unit
      }
      JFiles.writeString(place.temp.resolve(".env.t"), "x"): Unit
      JFiles.createSymbolicLink(s.resolve(".env.link"), place.outside.resolve("r.txt")): Unit
      val shell = Profiles.forShell(Mode.local, s, place.layout, HostList.empty, closesNames = false)
      // The files with a secret name, a folder with one and the files in it, and the target of a symbolic link.
      Vector(
        s.resolve(".env"),
        place.odd,
        s.resolve("app/.env.local"),
        s.resolve("conf/.env/key"),
        place.temp.resolve(".env.t"),
        s.resolve(".env.link"),
        place.outside.resolve("r.txt")
      ).foreach(path => assert(!shell.canRead(path) && !shell.canWrite(path), s"$path is not hidden"))
      // Other names, and .git, which is read-only and which the walk skips.
      Vector(s.resolve(".envrc"), s.resolve("my.env"), s.resolve(".git/.env"))
        .foreach(path => assert(shell.canRead(path), s"$path is hidden"))
      // A file with a secret name that is made after the start is not hidden until the next activation.
      assert(shell.canWrite(s.resolve(".env.new")) && shell.canWrite(s.resolve("app/.env.production")))
      assertEquals(shell.foundNames, place.layout.secretNames)
      // A mode with no child process has no writable root, so nothing to walk.
      assertEquals(
        Profiles.forShell(Mode.readOnly, s, place.layout, HostList.empty, closesNames = false).hidden,
        Profiles.forMode(Mode.readOnly, s, place.layout, HostList.empty).hidden
      )
      // A backend that closes a name needs no walk.
      assertEquals(
        Profiles.forShell(Mode.local, s, place.layout, HostList.empty, closesNames = true),
        Profiles.forMode(Mode.local, s, place.layout, HostList.empty, closesNames = true)
      )
    finally place.close()
  }

  test("Authority: the secret paths cover the credential files of common tools") {
    val home  = Path.of("/w/home")
    val found = Layout.secretsUnder(home)
    Vector(
      ".codex/auth.json",
      ".claude/.credentials.json",
      ".m2/settings.xml",
      ".gradle/gradle.properties",
      ".sbt/.credentials",
      ".ivy2/.credentials",
      ".cargo/credentials",
      ".cargo/credentials.toml",
      ".local/share/keyrings/login.keyring",
      ".config/op/config",
      ".config/git/credentials",
      ".pgpass",
      ".vault-token",
      ".terraform.d/credentials.tfrc.json"
    ).foreach(name => assert(found.exists(home.resolve(name).startsWith), name))
  }

  property("Index: after each change of the files, the index gives the secret files of a new walk") {
    forAll(Gen.listOfN(12, changes)) { steps =>
      val place = Place()
      try
        val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
        val names   = place.layout.secretNames
        val index   = SecretIndex(names)
        steps.zipWithIndex.foreach { (change, i) =>
          change(place.scope)
          age(place.scope, i)
          assertEquals(index.find(profile).toSet, Profiles.secretFiles(profile, names).toSet, s"step $i")
        }
      finally place.close()
    }
  }

  test("Index: a folder that changed in the last two seconds is listed again, also with the same time") {
    // A file time on Linux can be coarse, so two changes in one tick can give one time, as git knows.
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      val index   = SecretIndex(place.layout.secretNames)
      val dir     = JFiles.createDirectories(place.scope.resolve("d"))
      val time    = JFiles.getLastModifiedTime(dir)
      assert(!index.find(profile).contains(dir.resolve(".env")))
      JFiles.writeString(dir.resolve(".env"), "FAKE"): Unit
      JFiles.setLastModifiedTime(dir, time): Unit
      assert(index.find(profile).contains(dir.resolve(".env")))
    finally place.close()
  }

  property("Index: with no settle time, the index keeps the folders, and it gives the secret files of a new walk") {
    // With the default settle time, each folder of a new place changed less than two seconds before, so the index
    // lists each one again. With none, the index keeps each folder that did not change.
    forAll(Gen.listOfN(12, changes)) { steps =>
      val place = Place()
      try
        val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
        val names   = place.layout.secretNames
        val index   = SecretIndex(names, settle = Duration.Zero)
        steps.zipWithIndex.foreach { (change, i) =>
          change(place.scope)
          age(place.scope, i)
          assertEquals(index.find(profile).toSet, Profiles.secretFiles(profile, names).toSet, s"step $i")
        }
      finally place.close()
    }
  }

  test("Index: a folder that did not change is listed once, and a folder that changed in the last two seconds again") {
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      JFiles.createDirectories(place.scope.resolve("d/e")): Unit
      val kept = SecretIndex(place.layout.secretNames, settle = Duration.Zero)
      kept.find(profile): Unit
      val first = kept.listings
      kept.find(profile): Unit
      assertEquals(kept.listings, first)
      val recent = SecretIndex(place.layout.secretNames)
      recent.find(profile): Unit
      recent.find(profile): Unit
      assertEquals(recent.listings, 2 * first)
    finally place.close()
  }

  test("Index: a find that stops throws and keeps nothing of its walk, so a later find lists each folder again") {
    // A part of the list of secret files would let a command read a secret file, so a stopped find gives no list.
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      (1 to 20).foreach(i => JFiles.createDirectories(place.scope.resolve(s"d$i/e")))
      val full = SecretIndex(place.layout.secretNames, settle = Duration.Zero)
      full.find(profile): Unit
      val index = SecretIndex(place.layout.secretNames, settle = Duration.Zero)
      val asked = java.util.concurrent.atomic.AtomicInteger(0)
      intercept[CancellationException](index.find(profile, () => asked.incrementAndGet() > 10)): Unit
      val stopped = index.listings
      assert(stopped > 0 && stopped < full.listings, s"the stopped find listed $stopped of ${full.listings} folders")
      assertEquals(index.find(profile).toSet, Profiles.secretFiles(profile, place.layout.secretNames).toSet)
      assertEquals(index.listings - stopped, full.listings)
    finally place.close()
  }

  test("Index: progress counts the folders of a find while it runs, and the next find has a total") {
    // A command waits for a find that runs, and a client shows why (CODE_HARNESS_DESIGN.md, 10.6).
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      (1 to 20).foreach(i => JFiles.createDirectories(place.scope.resolve(s"d$i/e")))
      val index = SecretIndex(place.layout.secretNames, settle = Duration.Zero)
      assertEquals(index.progress, None)
      // Another thread reads the progress during the find, so the read must not wait for the lock of the find.
      val other = java.util.concurrent.atomic.AtomicReference[Option[SecretIndex.Progress]]()
      val first = Vector.newBuilder[Option[SecretIndex.Progress]]
      index.find(
        profile,
        () =>
          first.addOne(index.progress)
          if other.get == null then
            val read = java.util.concurrent.CompletableFuture.supplyAsync(() => index.progress)
            other.set(read.get(2, java.util.concurrent.TimeUnit.SECONDS))
          false
      ): Unit
      val folders = index.listings
      assertEquals(index.progress, None)
      assert(Option(other.get).exists(_.isDefined), "the other thread got no progress")
      val counts = first.result()
      assert(counts.forall(_.exists(_.total.isEmpty)), counts.toString)
      val seen = counts.flatten.map(_.folders)
      assertEquals(seen, seen.sorted)
      assert(seen.last > seen.head, seen.toString)
      val second = Vector.newBuilder[Option[SecretIndex.Progress]]
      index.find(
        profile,
        () =>
          second.addOne(index.progress); false
      ): Unit
      val again = second.result()
      assert(again.nonEmpty && again.forall(_.exists(_.total.contains(folders))), s"$folders: $again")
      assertEquals(index.progress, None)
    finally place.close()
  }

  test("Index: a folder whose last-modified time a command sets back is listed again") {
    // The change time shows the change: a command cannot set it.
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      val index   = SecretIndex(place.layout.secretNames, settle = Duration.Zero)
      val dir     = JFiles.createDirectories(place.scope.resolve("d"))
      val time    = JFiles.getLastModifiedTime(dir)
      assert(!index.find(profile).contains(dir.resolve(".env")))
      JFiles.writeString(dir.resolve(".env"), "FAKE"): Unit
      JFiles.setLastModifiedTime(dir, time): Unit
      assert(index.find(profile).contains(dir.resolve(".env")))
    finally place.close()
  }

  test("Index: a folder with a secret name is hidden, also when the index cannot read it") {
    val place = Place()
    try
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      val closed  = JFiles.createDirectories(place.scope.resolve("a/.env.closed"))
      JFiles.writeString(closed.resolve("k"), "FAKE"): Unit
      JFiles.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------")): Unit
      try assert(Profiles.secretFiles(profile, place.layout.secretNames).contains(closed))
      finally JFiles.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------")): Unit
    finally place.close()
  }

  test("Index: the walk does not enter .git, node_modules or target below a writable root, and it walks such a root") {
    // These folders hold most of the folders of a large scope (5.4, 2026-11-15).
    val place = Place()
    try
      val s       = place.scope
      val skipped = Vector("node_modules/p/.env", "target/.env.local", "app/node_modules/.env", "app/target/x/.env")
      val kept    = Vector("targets/.env", "my_target/.env", "app/node_modules.d/.env")
      (skipped ++ kept ++ Vector("sub/.git/.env")).map(s.resolve).foreach { file =>
        JFiles.createDirectories(file.getParent): Unit
        JFiles.writeString(file, "FAKE"): Unit
      }
      val profile = Profiles.forMode(Mode.local, s, place.layout, HostList.empty)
      val found   = Profiles.secretFiles(profile, place.layout.secretNames).toSet
      (skipped :+ "sub/.git/.env").map(s.resolve).foreach(path => assert(!found.contains(path), s"$path: $found"))
      (kept :+ ".env").map(s.resolve).foreach(path => assert(found.contains(path), s"$path: $found"))
      // A writable root with such a name is walked.
      val root  = JFiles.createDirectories(place.base.resolve("target"))
      val named = Profile(
        canRun = true,
        writable = Vector(root),
        controls = Vector.empty,
        hidden = Vector.empty,
        net = Net.Closed
      )
      JFiles.writeString(root.resolve(".env"), "FAKE"): Unit
      assertEquals(Profiles.secretFiles(named, place.layout.secretNames), Vector(root.resolve(".env")))
    finally place.close()
  }

  test("Index: a folder that the index cannot read is hidden as a whole") {
    // The command can chmod its own folder, and then it could read a secret file that the walk did not see.
    val place  = Place()
    val locked = JFiles.createDirectories(place.scope.resolve("locked"))
    try
      JFiles.writeString(locked.resolve(".env"), "FAKE"): Unit
      JFiles.setPosixFilePermissions(locked, java.util.Set.of()): Unit
      val profile = Profiles.forMode(Mode.local, place.scope, place.layout, HostList.empty)
      assert(SecretIndex(place.layout.secretNames).find(profile).contains(locked))
      assert(!SecretIndex(place.layout.secretNames).find(profile).contains(place.scope))
    finally
      JFiles.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")): Unit
      place.close()
  }

  test("Hosts: a pattern with no port allows only the ports 80 and 443, and host:port or host:* allows more") {
    assert(HostList(Set("example.com")).allows("example.com", 443))
    assert(HostList(Set("example.com")).allows("example.com", 80))
    assert(!HostList(Set("example.com")).allows("example.com", 22))
    assert(HostList(Set("example.com:8080")).allows("example.com", 8080))
    assert(!HostList(Set("example.com:8080")).allows("example.com", 443))
    assert(HostList(Set("example.com:*")).allows("example.com", 22))
    assert(HostList(Set("*.example.com:*")).allows("a.example.com", 5432))
    assert(!HostList(Set("*.example.com:*")).allows("example.com", 443))
  }

  test("Hosts: a pattern names the same host, with no case and no last dot") {
    val list = HostList(Set("Example.com"))
    assert(list.allows("example.COM"))
    assert(list.allows("example.com."))
    assert(!list.allows("a.example.com"))
    assert(!list.allows("example.co"))
  }

  test("Hosts: *.d matches each subdomain of d, and not d") {
    val list = HostList(Set("*.example.com"))
    assert(list.allows("a.example.com"))
    assert(list.allows("b.a.example.com"))
    assert(!list.allows("example.com"))
    assert(!list.allows("aexample.com"))
    assert(!list.allows(".example.com"))
  }

  property("Hosts: an empty list allows no host, and a longer list allows more") {
    forAll(patterns, patterns, hostNames) { (a, b, host) =>
      assert(!HostList.empty.allows(host))
      assert(!HostList(a).allows(host) || HostList(a ++ b).allows(host))
    }
  }

  // ── The secret names (5.4) ──

  test("Secret names: a profile of a backend that closes names closes each path with a part that is a secret name") {
    // Seatbelt closes these names in each folder with no walk. bubblewrap cannot close a name.
    val closing = Profiles.forMode(Mode.local, scope, layout, hosts, closesNames = true)
    val open    = Profiles.forMode(Mode.local, scope, layout, hosts, closesNames = false)
    assertEquals(closing.hiddenNames, Layout.secretNames)
    assertEquals(open.hiddenNames, Set.empty[String])
    val closed = Vector(".env", "deep/er/.env.local", ".env.d/x", ".env.").map(scope.resolve)
    val kept   = Vector(".envrc", "notes.env.txt", "env", "a/b.env").map(scope.resolve)
    closed.foreach { path =>
      assert(closing.isHidden(path) && !closing.canRead(path) && !closing.canWrite(path), path.toString)
      assert(open.canRead(path) && open.canWrite(path), path.toString)
    }
    kept.foreach(path => assert(closing.canRead(path) && closing.canWrite(path), path.toString))
    assert(closing.isHidden(temp.resolve(".env")), "the temporary folder")
  }

  test(
    "Secret names: the words say that a command and a file call cannot read a secret name, only when the backend closes it"
  ) {
    val closing = Profiles.words(Profiles.forMode(Mode.local, scope, layout, hosts, closesNames = true), scope, layout)
    val open    = Profiles.words(Profiles.forMode(Mode.local, scope, layout, hosts, closesNames = false), scope, layout)
    val names   = "a file or a folder whose name is .env or starts with \".env.\" (for example .env.local)"
    val both    = closing.indexOf("A command and a file call can neither read nor write")
    assert(both >= 0 && closing.indexOf(names) > both, closing)
    assert(!closing.contains("A file call can neither"), closing)
    assert(open.contains(s"A file call can neither read nor write $names."), open)
    assert(!open.contains("at the open of the session") && !closing.contains("at the open of the session"), open)
  }

  test(
    "Secret names: on a backend that cannot close a name, the words of the shell say that a command cannot reach such " +
      "a file that was there at the open of the session, and can reach one that is made later"
  ) {
    // The walk at the start of the shell finds no file here, because the scope does not exist. The words do not
    // depend on what it found.
    val shell = Profiles.forShell(Mode.local, scope, layout, hosts, closesNames = false)
    val words = Profiles.words(shell, scope, layout)
    val names = "a file or a folder whose name is .env or starts with \".env.\" (for example .env.local)"
    val file  = words.indexOf(s"A file call can neither read nor write $names.")
    val start = words.indexOf(
      "A command can neither read nor write such a file or folder when it was there at the open of the session."
    )
    val later = words.indexOf(
      "A command can read and write such a file or folder that is made after the open, until the session opens again."
    )
    assert(file >= 0 && start > file && later > start, words)
    // Seatbelt closes the names in each folder, so its words give no rule of a walk.
    val closing = Profiles.words(Profiles.forShell(Mode.local, scope, layout, hosts, closesNames = true), scope, layout)
    assert(!closing.contains("at the open of the session"), closing)
  }

  property("Words: the words of a profile name what it can write, its control paths, its closed paths and its net") {
    forAll(modes, Gen.oneOf(true, false)) { (mode, closes) =>
      val p     = Profiles.forMode(mode, scope, layout, hosts, closes)
      val words = Profiles.words(p, scope, layout)
      if !p.canRun then assertEquals(words, "This session runs no command.")
      else
        assertEquals(words.contains("the folder of the session"), p.canWrite(scope.resolve("a")), words)
        assertEquals(words.contains("TMPDIR"), p.canWrite(temp.resolve("a")), words)
        p.controls.foreach(part => assert(words.contains(part.label), s"${part.label}: $words"))
        p.controls.filter(_.note.nonEmpty).foreach(part => assert(words.contains(part.note), s"${part.note}: $words"))
        assert(Vector(".ssh", ".aws", "journals", ".env.local").forall(words.contains), words)
        assertEquals(words.contains("no network"), !p.canConnect("example.com"), words)
        if p.canConnect("example.com") then assert(hosts.patterns.forall(words.contains), words)
    }
  }

  // ── The control paths (5.4, choice 19) ──

  property("Control paths: a command writes a path of the scope exactly when the path is not a control path") {
    forAll(gitPaths) { path =>
      val p = profile(Mode.local)
      if under(path, scope) then assertEquals(p.canWrite(path), !controlled(path), path.toString)
      else assertEquals(p.canWrite(path), under(path, temp), path.toString)
    }
  }

  property("Control paths: the control paths do not depend on the mode") {
    forAll(modes, modes, Gen.oneOf(true, false)) { (a, b, closes) =>
      assertEquals(
        Profiles.forMode(a, scope, layout, hosts, closes).controls,
        Profiles.forMode(b, scope, layout, hosts, closes).controls
      )
    }
  }

  test("Control paths: with a backend that closes names, git changes its index and refs, not its settings or hooks") {
    val p        = Profiles.forMode(Mode.local, scope, layout, hosts, closesNames = true)
    val writable = Vector(
      ".git/index",
      ".git/index.lock",
      ".git/HEAD",
      ".git/refs/heads/main",
      ".git/objects/ab/cd",
      ".git/config.lock",
      ".git/modules/s/index",
      ".git/worktrees/w/HEAD",
      ".gitignore",
      ".github/w",
      ".vscode/launch.json",
      ".mcp.json",
      ".claude/commands/a.md"
    )
    val closed = Vector(
      ".git",
      ".git/config",
      ".git/config.worktree",
      ".git/commondir",
      ".git/hooks",
      ".git/hooks/pre-commit",
      ".git/modules/s/config",
      ".git/modules/s/hooks/post-checkout",
      ".git/modules/s/modules/t/config",
      ".git/modules/s/commondir",
      ".git/worktrees/w/gitdir",
      ".git/worktrees/w/commondir",
      ".git/worktrees/w/config.worktree",
      "sub/.git",
      "sub/.git/config",
      "sub/deep/.git/HEAD",
      ".vscode/settings.json",
      ".vscode/tasks.json",
      ".idea",
      ".idea/workspace.xml",
      ".claude/settings.json",
      ".claude/settings.local.json"
    )
    writable.map(scope.resolve).foreach(path => assert(p.canWrite(path), s"$path is not writable"))
    closed.map(scope.resolve).foreach(path => assert(!p.canWrite(path), s"$path is writable"))
  }

  test(
    "Control paths: with a backend that cannot close a name, all of .git and each control path that exists stay read-only"
  ) {
    // A mount covers only a path that exists, and it cannot close a name. So the profile of such a backend closes the
    // control paths that exist, and its observations stay true: the gap of Linux (5.4).
    val place = Place()
    try
      val s = place.scope
      JFiles.createDirectories(s.resolve(".vscode")): Unit
      JFiles.writeString(s.resolve(".vscode/settings.json"), "{}"): Unit
      JFiles.createDirectories(s.resolve(".husky")): Unit
      val p = Profiles.forMode(Mode.local, s, place.layout.copy(hooks = Vector(s.resolve(".husky"))), hosts)
      Vector(".git", ".git/index", ".git/refs/heads/main", ".git/config", ".vscode/settings.json", ".husky/pre-commit")
        .map(s.resolve).foreach(path => assert(!p.canWrite(path), s"$path is writable"))
      Vector(".idea/workspace.xml", ".claude/settings.json", ".vscode/tasks.json", "sub/.git/config").map(s.resolve)
        .foreach(path => assert(p.canWrite(path), s"$path is not writable"))
      JFiles.delete(s.resolve(".git"))
      val none = Profiles.forMode(Mode.local, s, place.layout, hosts)
      assert(none.canWrite(s.resolve(".git/config")), "a .git that does not exist")
      assert(!none.controls.exists(_.label == ".git"), none.controls)
    finally place.close()
  }

  test("Control paths: the class holds only the scope, so a command can make a repository in the temporary folder") {
    Vector(true, false).foreach { closes =>
      val p = Profiles.forMode(Mode.local, scope, layout, hosts, closes)
      Vector(".git", ".git/config", ".git/hooks/pre-commit", "r/.git/config", ".vscode/settings.json").map(temp.resolve)
        .foreach(path => assert(p.canWrite(path), s"$path, closes = $closes"))
    }
  }

  test("Control paths: the folder of core.hooksPath is a control path when it is in the scope") {
    val husky = scope.resolve(".husky")
    val hooks = layout.copy(hooks = Vector(husky, Path.of("/w/home/hooks")))
    val p     = Profiles.forMode(Mode.local, scope, hooks, hosts, closesNames = true)
    assert(!p.canWrite(husky.resolve("pre-commit")))
    assert(p.canWrite(scope.resolve(".husky-old/pre-commit")))
    assertEquals(p.controls.count(_.label == ".husky"), 1, p.controls)
    // A folder outside the scope is outside the writable roots, so it needs no control path.
    assert(!p.controls.exists(_.label.contains("/w/home")), p.controls)
  }

  test("Control paths: hooksFolder reads core.hooksPath with git, relative to the top of the work tree") {
    assume(Host.runs(Vector("git", "--version")), "git is not on this host")
    val base                     = JFiles.createTempDirectory("dspy4s-hooks-").toRealPath()
    def git(args: String*): Unit = Support.run(Vector("git", "-C", base.toString) ++ args, base): Unit
    try
      assertEquals(Layout.hooksFolder(base), None, "no repository")
      git("init", "-q")
      assertEquals(Layout.hooksFolder(base), None, "no setting")
      git("config", "core.hooksPath", ".husky")
      assertEquals(Layout.hooksFolder(base), Some(base.resolve(".husky")))
      JFiles.createDirectories(base.resolve("sub")): Unit
      assertEquals(Layout.hooksFolder(base.resolve("sub")), Some(base.resolve(".husky")), "from a folder of the tree")
      git("config", "core.hooksPath", "/w/other/hooks")
      assertEquals(Layout.hooksFolder(base), Some(Path.of("/w/other/hooks")))
    finally
      val paths = JFiles.walk(base)
      try paths.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => JFiles.deleteIfExists(p): Unit)
      finally paths.close()
  }

  test("Words: the words give the note of git, so the model knows which git commands fail") {
    val closing = Profiles.words(Profiles.forMode(Mode.local, scope, layout, hosts, closesNames = true), scope, layout)
    assert(closing.contains(".git/config") && closing.contains("print an error about .git/config"), closing)
    assert(closing.contains("git init"), closing)
    assert(!closing.contains("all of .git"), closing)
    Vector(".vscode/settings.json", ".idea", ".claude/settings.json").foreach(label => assert(closing.contains(label)))
    // A backend that cannot close a name: the place has a .git, and no file of a tool.
    val place = Place()
    try
      val open =
        Profiles.words(Profiles.forMode(Mode.local, place.scope, place.layout, hosts), place.scope, place.layout)
      assert(open.contains("all of .git stays read-only") && open.contains("commit"), open)
      assert(!open.contains(".vscode/settings.json"), open)
    finally place.close()
  }
