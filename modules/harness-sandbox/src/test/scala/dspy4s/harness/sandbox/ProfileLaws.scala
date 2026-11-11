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

  private def profile(mode: Mode): Profile = Profiles.forMode(mode, scope, layout, hosts)

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

  property("Authority: a write is only under the scope or the temporary directory, never in .git or a secret") {
    forAll(modes, paths) { (mode, path) =>
      if profile(mode).canWrite(path) then
        assert(mode.files == Access.Full)
        assert(under(path, scope, temp), s"$path is outside the scope")
        assert(!under(path, scope.resolve(".git")), s"$path is in .git")
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

  test("Authority: the profile of a command hides each file with a secret name that exists in a writable root") {
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
      val command = Profiles.forCommand(Mode.local, s, place.layout, HostList.empty)
      // The files with a secret name, a folder with one and the files in it, and the target of a symbolic link.
      Vector(
        s.resolve(".env"),
        place.odd,
        s.resolve("app/.env.local"),
        s.resolve("conf/.env/key"),
        place.temp.resolve(".env.t"),
        s.resolve(".env.link"),
        place.outside.resolve("r.txt")
      ).foreach(path => assert(!command.canRead(path) && !command.canWrite(path), s"$path is not hidden"))
      // Other names, and .git, which is read-only and which the walk skips.
      Vector(s.resolve(".envrc"), s.resolve("my.env"), s.resolve(".git/.env"))
        .foreach(path => assert(command.canRead(path), s"$path is hidden"))
      // A new file with a secret name holds only data of the command.
      assert(command.canWrite(s.resolve(".env.new")) && command.canWrite(s.resolve("app/.env.production")))
      // A mode with no child process has no writable root, so nothing to walk.
      assertEquals(
        Profiles.forCommand(Mode.readOnly, s, place.layout, HostList.empty).hidden,
        Profiles.forMode(Mode.readOnly, s, place.layout, HostList.empty).hidden
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
